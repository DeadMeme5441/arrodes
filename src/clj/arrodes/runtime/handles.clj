(ns arrodes.runtime.handles
  "Single-owner activation and teardown of live session evaluators and resources."
  (:require [arrodes.capabilities :as capabilities]
            [arrodes.agents :as agents]
            [arrodes.context-tree :as context-tree]
            [arrodes.history :as history]
            [arrodes.jobs :as jobs]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.runtime.control :as control]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.summaries :as summaries]
            [arrodes.platform :as util]
            [arrodes.value :as value]))

(defn- command-dispatch! [runtime method params]
  (if-let [dispatch (:command! runtime)]
    (dispatch runtime method params)
    (value/fail! :host-unavailable "No command host is installed" {:method method})))

(defn- append-extension-entry! [runtime sid entry]
  (value/check! (map? entry) :invalid-entry "Extension entry must be a map" {})
  (let [entry (if (:kind entry) entry {:kind :custom :data entry})]
    (locking (control/session-lock runtime sid)
      (control/commit! runtime sid {::command/entries [entry]
                            ::command/events [{:type :session/entry-appended
                                      :data {:kind (:kind entry)}}]}))))

(defn- handle-context [runtime sid cwd provider-manager]
  {:session-id sid
   :cwd cwd
   :get-session (fn
                  ([] (store/session (:store runtime) sid))
                  ([_] (store/session (:store runtime) sid)))
   :command! (fn [method params] (command-dispatch! runtime method params))
   :append-entry! (fn [entry] (append-extension-entry! runtime sid entry))
   :emit! (fn [event]
            (control/notify-listeners!
             runtime
             (merge {:id nil :session-id sid :time (util/now) :durable? false}
                    event)))
   :ui! (fn [request]
          (control/ui! runtime
               (cond-> (assoc request :session-id sid)
                 (:operation-id capabilities/*invocation-context*)
                 (assoc :operation-id (:operation-id capabilities/*invocation-context*)))))
   :provider provider-manager})

(defn- make-handle [runtime sid]
  (let [snapshot (store/session (:store runtime) sid)
        cwd (:cwd snapshot)
        manager (resources/create! {:cwd cwd :home (:home runtime)
                                    :settings (:initial-settings runtime)
                                    :trust (:trust runtime)})
        built (atom {:resources manager :cwd cwd})]
    (try
      (let [provider-manager (provider/for-session (:provider runtime)
                                                   (resources/settings manager))
            _ (swap! built assoc :provider provider-manager)
            registry (capabilities/create! {:session-id sid :cwd cwd
                                            :store (:store runtime)
                                            :job-manager (:jobs runtime)
                                            :agent-manager (:agents runtime)
                                            :config (:config snapshot)
                                            :emit! (fn [event]
                                                     (if (:durable? event)
                                                       (locking (control/session-lock runtime sid)
                                                         (first (:events (control/commit! runtime sid {::command/events [event]}))))
                                                       (control/notify-listeners! runtime event)))
                                            :get-session #(store/session (:store runtime) sid)})
            _ (swap! built assoc :registry registry)
            _ (agents/install! (:agents runtime) registry)
            _ (history/install! registry)
            activation (resources/activate!
                        manager registry
                        (handle-context runtime sid cwd provider-manager))]
        (binding [*ns* (the-ns (:namespace registry))]
          (alias 'jobs 'arrodes.jobs))
        (capabilities/set-tools! registry (get-in snapshot [:config :tools]))
        (assoc @built :activation activation))
      (catch Throwable error
        (let [resource-report (try (resources/close! manager)
                                   (catch Throwable _ nil))
              registry-report (when-let [registry (:registry @built)]
                                (try (capabilities/close! registry)
                                     (catch Throwable _ nil)))
              provider-report (when-let [provider (:provider @built)]
                                (try (provider/close! provider)
                                     (catch Throwable _ nil)))]
          (when-not (and (= :closed (:status resource-report))
                         (or (nil? (:registry @built))
                             (and (:closed? registry-report)
                                  (empty? (:errors registry-report))))
                         (or (nil? (:provider @built)) (:closed? provider-report)))
            (swap! (:failed-handles runtime) assoc sid @built)))
        (throw error)))))

(defn- await-handle-attempt! [attempt]
  (let [{:keys [error] :as outcome} @(:done attempt)]
    (when error (throw error))
    (:value outcome)))

(defn handle! [runtime sid]
  (control/ensure-open! runtime)
  (loop []
    (let [[owner attempt]
          (locking (control/session-lock runtime sid)
            (control/ensure-open! runtime)
            (while (and (not= sid control/*resetting-session*)
                        (contains? @(:resetting runtime) sid))
              (.wait ^Object (control/session-lock runtime sid) 50)
              (control/ensure-open! runtime))
            (if-let [attempt (get @(:handle-attempts runtime) sid)]
              [false attempt]
              (if-let [handle (get @(:handles runtime) sid)]
                [false {:done (doto (promise) (deliver {:value handle}))}]
                (do
                  (value/check! (not (contains? @(:failed-handles runtime) sid))
                                :cleanup-incomplete
                                "Failed evaluator activation still owns session resources"
                                {:session-id sid})
                  (let [attempt {:kind :loading :done (promise)}]
                    (swap! (:handle-attempts runtime) assoc sid attempt)
                    [true attempt])))))]
      (if owner
        (let [outcome (try {:value (make-handle runtime sid)}
                           (catch Throwable error {:error error}))]
          (locking (control/session-lock runtime sid)
            (when-let [handle (:value outcome)]
              (swap! (:handles runtime) assoc sid handle))
            (swap! (:handle-attempts runtime) dissoc sid)
            (deliver (:done attempt) outcome))
          (await-handle-attempt! attempt))
        (if (= :closing (:kind attempt))
          (do (await-handle-attempt! attempt) (recur))
          (await-handle-attempt! attempt))))))


(defn- close-owned-handle! [runtime sid handle]
  (when-let [manager (:summaries runtime)]
    (summaries/cancel-session! manager sid)
    (value/check! (summaries/await-session! manager sid
                                           (long (or (:close-timeout-ms (:initial-settings runtime)) 10000)))
                  :summaries-still-running "History summarization has not exited; session resources retained."
                  {:session-id sid}))
  (let [resource-report (resources/close! (:resources handle))]
    (value/check! (= :closed (:status resource-report)) :cleanup-incomplete
                  "Session resources did not finish shutting down"
                  {:session-id sid :resources resource-report})
    (let [registry-report (when-let [registry (:registry handle)]
                            (capabilities/close! registry))
          provider-report (when-let [provider (:provider handle)]
                            (provider/close! provider))]
      (value/check! (and (or (nil? registry-report)
                             (and (:closed? registry-report) (empty? (:errors registry-report))))
                         (or (nil? provider-report) (:closed? provider-report)))
                    :cleanup-incomplete "Session handles did not finish shutting down"
                    {:session-id sid :registry registry-report :provider provider-report})
      {:session-id sid :resources resource-report :registry registry-report
       :provider provider-report})))

(defn close-handle! [runtime sid]
  (loop []
    (let [[owner attempt handle]
          (locking (control/session-lock runtime sid)
            (if-let [attempt (get @(:handle-attempts runtime) sid)]
              [false attempt nil]
              (when-let [handle (or (get @(:handles runtime) sid)
                                    (get @(:failed-handles runtime) sid))]
                (let [attempt {:kind :closing :done (promise)}]
                  (swap! (:handle-attempts runtime) assoc sid attempt)
                  [true attempt handle]))))]
      (when attempt
        (if owner
          (let [outcome (try {:value (close-owned-handle! runtime sid handle)}
                             (catch Throwable error {:error error}))]
            (locking (control/session-lock runtime sid)
              (when (:value outcome)
                (swap! (:handles runtime) dissoc sid)
                (swap! (:failed-handles runtime) dissoc sid))
              (swap! (:handle-attempts runtime) dissoc sid)
              (deliver (:done attempt) outcome))
            (await-handle-attempt! attempt))
          (if (= :loading (:kind attempt))
            (do (await-handle-attempt! attempt) (recur))
            (await-handle-attempt! attempt)))))))

(defn root-provider-settings [cwd home initial-settings]
  (let [manager (resources/create! {:cwd cwd :home home
                                    :settings initial-settings :trust false})]
    (try
      (resources/settings manager)
      (finally
        (resources/close! manager)))))

(def ^:private session-default-keys
  #{:provider :model :thinking :tools :instructions :settings})

(def ^:private generation-setting-keys
  (into context-tree/setting-keys
        #{:temperature :top-p :max-output-tokens :stop :response-format
          :cache :provider-options :auto-compact? :compaction-threshold
          :compaction-keep-entries :compaction-max-output-tokens
          :max-steps :provider-retries :fallback-model? :auto-title? :title-model :title-provider}))

(defn cwd-session-defaults [runtime cwd]
  (let [manager (resources/create! {:cwd cwd :home (:home runtime)
                                    :settings (:initial-settings runtime)
                                    :trust (:trust runtime)})]
    (try
      (let [effective (resources/settings manager)
            nested (value/deep-merge
                    (select-keys (:session-defaults effective) session-default-keys)
                    (select-keys (:session effective) session-default-keys)
                    (select-keys (:session-config effective) session-default-keys))
            direct (select-keys effective (disj session-default-keys :settings))
            generation (select-keys effective generation-setting-keys)]
        (cond-> (value/deep-merge direct nested)
          (seq generation) (update :settings #(value/deep-merge generation %))))
      (finally
        (resources/close! manager)))))

(defn await-handles! [runtime deadline]
  (doseq [attempt (vals @(:handle-attempts runtime))]
    (let [remaining (control/remaining-close-millis deadline)]
      (when (pos? remaining)
        (deref (:done attempt) remaining nil))))
  (empty? @(:handle-attempts runtime)))

(defn with-session-reset! [runtime sid work]
  (locking (control/session-lock runtime sid)
    (control/ensure-open! runtime)
    (control/ensure-idle! runtime sid)
    (swap! (:resetting runtime) conj sid))
  (jobs/block-session! (:jobs runtime) sid)
  (try
    (when-let [manager (:summaries runtime)]
      (summaries/cancel-session! manager sid)
      (value/check! (summaries/await-session! manager sid
                                             (long (or (:close-timeout-ms (:initial-settings runtime)) 10000)))
                    :summaries-still-running "History summarization has not exited; evaluator retained."
                    {:session-id sid}))
    (swap! (:context-views runtime) dissoc sid)
    (jobs/cancel-session! (:jobs runtime) sid)
    (value/check! (jobs/await-session! (:jobs runtime) sid
                                     (long (or (:close-timeout-ms (:initial-settings runtime)) 10000)))
                  :jobs-still-running "Background execution has not exited; evaluator retained. Retry after jobs settle."
                  {:session-id sid})
    (binding [control/*resetting-session* sid] (work))
    (finally
      (jobs/unblock-session! (:jobs runtime) sid)
      (locking (control/session-lock runtime sid)
        (swap! (:resetting runtime) disj sid)
        (.notifyAll ^Object (control/session-lock runtime sid))))))
