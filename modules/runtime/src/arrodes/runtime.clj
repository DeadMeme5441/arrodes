(ns arrodes.runtime
  "Durable session orchestration, provider/tool continuation, and foreground operations."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [arrodes.capabilities :as capabilities]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.run :as run]
            [arrodes.store :as store]
            [arrodes.util :as util])
  (:import (java.util.concurrent Callable CancellationException ExecutionException ExecutorService
                                 Executors Future RejectedExecutionException TimeUnit)))

(defn- ensure-open! [runtime]
  (util/check! (= :open @(:lifecycle runtime)) :runtime-closed
               "Runtime is not open" {:status @(:lifecycle runtime)}))

(defn- session-lock [runtime sid]
  (or (get @(:session-locks runtime) sid)
      (get (swap! (:session-locks runtime)
                  #(if (contains? % sid) % (assoc % sid (Object.)))) sid)))

(defn- notify-listeners! [runtime event]
  (doseq [[_ listener] @(:listeners runtime)]
    (try (listener event) (catch Throwable _ nil)))
  event)

(defn- emit-events! [runtime events]
  (doseq [event events] (notify-listeners! runtime event))
  events)

(defn- transient-event! [runtime sid oid type data callback]
  (let [event {:id nil :session-id sid :operation-id oid :type type :data data
               :time (util/now) :durable? false}]
    (notify-listeners! runtime event)
    (when callback
      (try (callback event) (catch Throwable _ nil)))
    event))

(defn- commit! [runtime sid command]
  (let [result (store/commit! (:store runtime) sid command)]
    (emit-events! runtime (:events result))
    result))

(def ^:private ui-kinds #{:select :confirm :input :notify :capability})

(defn set-ui!
  "Installs or removes the single host-interaction callback."
  [runtime callback]
  (util/check! (or (nil? callback) (fn? callback)) :invalid-ui-callback
               "UI callback must be a function or nil" {})
  (reset! (:ui runtime) callback)
  runtime)

(defn ui!
  "Invokes the current host callback or fails rather than inventing an answer."
  [runtime request]
  (util/check! (map? request) :invalid-ui-request
               "UI request must be a map" {})
  (util/check! (contains? ui-kinds (:kind request)) :unsupported-ui-request
               "Unsupported UI request kind" {:kind (:kind request)})
  (if-let [callback @(:ui runtime)]
    (callback request)
    (util/fail! :host-unavailable "No host is available for this interaction"
                {:kind (:kind request)})))

(defn subscribe!
  "Subscribes to durable and transient runtime events and returns an idempotent unsubscribe function."
  [runtime listener]
  (ensure-open! runtime)
  (util/check! (fn? listener) :invalid-listener "Event listener must be a function" {})
  (let [id (util/id)]
    (swap! (:listeners runtime) assoc id listener)
    (let [removed? (atom false)]
      (fn []
        (when (compare-and-set! removed? false true)
          (swap! (:listeners runtime) dissoc id))
        nil))))

(defn- command-dispatch! [runtime method params]
  (if-let [dispatch (requiring-resolve 'arrodes.commands/dispatch!)]
    (dispatch runtime method params)
    (util/fail! :command-unavailable "Command dispatcher is unavailable" {:method method})))

(defn- append-extension-entry! [runtime sid entry]
  (util/check! (map? entry) :invalid-entry "Extension entry must be a map" {})
  (let [entry (if (:kind entry) entry {:kind :custom :data entry})]
    (locking (session-lock runtime sid)
      (commit! runtime sid {:entries [entry]
                            :events [{:type :session/entry-appended
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
            (notify-listeners!
             runtime
             (merge {:id nil :session-id sid :time (util/now) :durable? false}
                    event)))
   :ui! (fn [request] (ui! runtime (assoc request :session-id sid)))
   :provider provider-manager})

(defn- make-handle [runtime sid]
  (let [snapshot (store/session (:store runtime) sid)
        cwd (:cwd snapshot)
        manager (resources/create! {:cwd cwd :home (:home runtime)
                                    :settings (:initial-settings runtime)
                                    :trust (:trust runtime)})]
    (try
      (let [provider-manager (provider/for-session (:provider runtime)
                                                   (resources/settings manager))]
        (try
          (let [registry (capabilities/create! {:session-id sid :cwd cwd
                                                :store (:store runtime)
                                                :config (:config snapshot)
                                                :emit! #(notify-listeners! runtime %)
                                                :get-session #(store/session (:store runtime) sid)})]
            (try
              (let [activation (resources/activate!
                                manager registry
                                (handle-context runtime sid cwd provider-manager))]
                {:registry registry :resources manager :provider provider-manager
                 :activation activation :cwd cwd})
              (catch Throwable error
                (capabilities/close! registry)
                (throw error))))
          (catch Throwable error
            (provider/close! provider-manager)
            (throw error))))
      (catch Throwable error
        (resources/close! manager)
        (throw error)))))

(defn registry
  "Returns the lazily-created session registry and activates only that session's cwd resources."
  [runtime sid]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (ensure-open! runtime)
    (locking (:handle-lock runtime)
      (if-let [handle (get @(:handles runtime) sid)]
        (:registry handle)
        (let [handle (make-handle runtime sid)]
          (swap! (:handles runtime) assoc sid handle)
          (:registry handle))))))

(defn resource-manager
  "Returns the resource manager belonging to the session, never the runtime's root cwd manager."
  [runtime sid]
  (registry runtime sid)
  (:resources (get @(:handles runtime) sid)))

(defn provider-manager
  "Returns the provider view isolated to the session's effective cwd settings."
  [runtime sid]
  (registry runtime sid)
  (:provider (get @(:handles runtime) sid)))

(defn- close-handle! [runtime sid]
  (locking (session-lock runtime sid)
    (locking (:handle-lock runtime)
      (when-let [handle (get @(:handles runtime) sid)]
        (swap! (:handles runtime) dissoc sid)
        (let [resource-report (try (resources/close! (:resources handle))
                                   (catch Throwable error {:status :failed :error (util/error-map error)}))
              registry-report (try (capabilities/close! (:registry handle))
                                   (catch Throwable error {:closed? false :error (util/error-map error)}))
              provider-report (try (provider/close! (:provider handle))
                                   (catch Throwable error {:closed? false :error (util/error-map error)}))]
          {:session-id sid :resources resource-report :registry registry-report
           :provider provider-report})))))

(defn- foreground [runtime sid]
  (get @(:foreground runtime) sid))

(defn- ensure-idle! [runtime sid]
  (when-let [slot (foreground runtime sid)]
    (util/fail! :session-busy "Session already has a foreground operation"
                {:session-id sid :operation-id (:operation-id slot)})))

(defn- acquire-foreground! [runtime sid kind oid]
  (locking (session-lock runtime sid)
    (store/session (:store runtime) sid)
    (locking (:foreground runtime)
      ;; close! changes lifecycle and snapshots foreground under this same gate.
      (ensure-open! runtime)
      (ensure-idle! runtime sid)
      (let [slot {:operation-id oid :kind kind :phase (atom :starting)
                  :cancelled (atom false) :thread (atom nil)
                  :done (promise) :finished (promise) :usage (atom {})}]
        (swap! (:foreground runtime) assoc sid slot)
        (swap! (:operations runtime) assoc oid slot)
        slot))))

(defn- release-foreground! [runtime sid oid]
  (locking (session-lock runtime sid)
    (when (= oid (:operation-id (foreground runtime sid)))
      (swap! (:foreground runtime) dissoc sid))))

(defn- set-phase! [slot phase]
  (reset! (:phase slot) phase)
  phase)

(defn- operation-start! [runtime sid kind]
  (let [oid (util/id)
        slot (acquire-foreground! runtime sid kind oid)
        operation {:id oid :session-id sid :kind kind :status :running :created-at (util/now)}]
    (try
      (let [result (locking (session-lock runtime sid)
                     (commit! runtime sid
                              {:session {:status :running}
                               :operation operation
                               :events [{:operation-id oid :type :operation/started
                                         :data {:kind kind}}]}))]
        {:slot slot :operation (:operation result)})
      (catch Throwable error
        (swap! (:operations runtime) dissoc oid)
        (deliver (:finished slot) true)
        (release-foreground! runtime sid oid)
        (throw error)))))

(defn- durable-invocation [result]
  (dissoc result :value))

(defn- settle-operation! [runtime sid slot status result error]
  (let [oid (:operation-id slot)
        now (util/now)
        operation (cond-> {:id oid :session-id sid :kind (:kind slot)
                           :status status :finished-at now}
                    (some? result) (assoc :result result)
                    error (assoc :error (util/error-map error)))
        session-status (case status
                         :completed :idle
                         :cancelled :interrupted
                         :failed :failed
                         :interrupted :interrupted
                         :failed)
        committed
        (locking (session-lock runtime sid)
          (let [unresolved (when-not (= :completed status)
                             (run/unresolved-tool-calls
                              (store/active-path (:store runtime) sid)))
                interrupted-entries
                (mapv (fn [call]
                        (let [content (str "Tool execution did not settle before the operation " (name status)
                                           ". Its external effect may have occurred; inspect state before retrying.")]
                          {:kind :message
                           :data {:message/role :tool
                                  :message/tool-call-id (:tool-call/id call)
                                  :message/name (:tool-call/name call)
                                  :message/content content
                                  :message/result {:kind :inline :content content
                                                   :details {:error/code (name status)}
                                                   :available? true}}}))
                      unresolved)]
            (commit! runtime sid
                     {:entries interrupted-entries
                      :session {:status session-status}
                      :operation operation
                      :events (cond-> [{:operation-id oid
                                        :type (keyword "operation" (name status))
                                        :data (cond-> {}
                                                error (assoc :error (util/error-map error)))}]
                                (seq unresolved)
                                (conj {:operation-id oid :type :tool/interrupted
                                       :data {:call-ids (mapv :tool-call/id unresolved)
                                              :status status}}))})))]
    (:operation committed)))

(defn- cancelled-error? [slot error]
  (or @(:cancelled slot)
      (instance? InterruptedException error)
      (= "cancelled" (:error/code (ex-data error)))))

(defn- provider-config [runtime sid config]
  (let [manager (provider-manager runtime sid)]
    (if (provider/model manager (:provider config) (:model config))
      config
      (if (true? (get-in config [:settings :fallback-model?]))
        (if-let [fallback (first (filter #(= (:provider config) (:provider %))
                                        (provider/catalog manager)))]
          (assoc config :model (:id fallback))
          config)
        config))))

(defn- provider-complete! [runtime sid slot config request callback]
  (let [max-retries (long (max 0 (min 5 (or (get-in config [:settings :provider-retries]) 2))))
        cancelled? #(or @(:cancelled slot) (.isInterrupted (Thread/currentThread)))]
    (loop [attempt 0]
      (util/check-cancelled! cancelled?)
      (let [visible? (atom false)
            on-event (fn [event]
                       (when (run/event-visible? event) (reset! visible? true))
                       (transient-event! runtime sid (:operation-id slot)
                                         :provider-event event callback))
            outcome (try
                      {:response
                       (provider/complete! (provider-manager runtime sid) request
                                           {:provider (:provider config)
                                            :on-event on-event :cancelled? cancelled?})}
                      (catch Throwable error {:error error}))]
        (if-let [error (:error outcome)]
          (if (and (< attempt max-retries) (not @visible?)
                   (run/retryable-error? error) (not (cancelled?)))
            (do
              (transient-event! runtime sid (:operation-id slot) :provider-retry
                                {:attempt (inc attempt) :error (util/error-map error)} callback)
              (Thread/sleep (long (min 2000 (* 200 (bit-shift-left 1 attempt)))))
              (recur (inc attempt)))
            (throw error))
          (:response outcome))))))

(defn- runtime-context [runtime sid registry manager config]
  (let [project (resources/context manager)
        instructions (:instructions config)
        developer (->> [project instructions]
                       (remove str/blank?)
                       (str/join "\n\n"))
        messages (store/context-messages (:store runtime) sid)
        messages (if (str/blank? developer)
                   (vec messages)
                   (into [{:message/role :developer :message/content developer}]
                         messages))
        hook-context {:runtime runtime :session-id sid
                      :session (store/session (:store runtime) sid)}
        transformed (capabilities/apply-hooks registry :transform-context hook-context messages)]
    (util/check! (vector? transformed) :invalid-hook-result
                 "transform-context hooks must return a message vector" {})
    transformed))

(defn- session-cache [request sid]
  (update request :request/cache
          #(assoc (merge {:enabled? true} (or % {})) :scope-id sid)))

(defn- prepare-completion-request [runtime sid slot registry manager config]
  (let [messages (runtime-context runtime sid registry manager config)
        tools (capabilities/definitions registry (:tools config))
        request (run/request config messages tools)
        context {:runtime runtime :session-id sid :operation-id (:operation-id slot)
                 :session (store/session (:store runtime) sid)}
        request (-> (capabilities/apply-hooks registry :transform-request context request)
                    (session-cache sid))]
    (util/check! (map? request) :invalid-hook-result
                 "transform-request hooks must return a request map" {})
    request))

(defn- complete-request! [runtime sid slot config request callback]
  (set-phase! slot :provider)
  (provider-complete! runtime sid slot config request callback))

(defn- commit-assistant! [runtime sid slot assistant response]
  (locking (session-lock runtime sid)
    (util/check-cancelled! (:cancelled slot))
    (let [result (commit! runtime sid
                          {:entries [{:kind :message :data assistant}]
                           :events [{:operation-id (:operation-id slot)
                                     :type :message/assistant
                                     :data {:message assistant
                                            :usage (:response/usage response)}}]})]
      (swap! (:usage slot) run/add-usage (:response/usage response))
      (:session result))))

(defn- parallel-tool-outcomes [slot invoke-call calls]
  (with-open [^ExecutorService executor (Executors/newVirtualThreadPerTaskExecutor)]
    (let [tasks (mapv (fn [call]
                        (let [work (bound-fn [] (invoke-call call))]
                          (.submit executor
                                   ^Callable
                                   (reify Callable
                                     (call [_] (work))))))
                      calls)]
      (mapv
       (fn [^Future task]
         (loop []
           (let [outcome
                 (try
                   (let [result (.get task)]
                     (if (map? result)
                       {:result result}
                       {:failure
                        (ex-info "Parallel capability worker returned an invalid result"
                                 {:error/code "parallel-worker-invalid-result"
                                  :result-type (some-> result class .getName)})}))
                   (catch InterruptedException _
                     (reset! (:cancelled slot) true)
                     (.shutdownNow executor)
                     {:interrupted? true})
                   (catch ExecutionException error {:failure (.getCause error)})
                   (catch CancellationException error {:failure error}))]
             (if (:interrupted? outcome) (recur) outcome))))
       tasks))))

(defn- invoke-tools! [runtime sid slot registry calls callback]
  (set-phase! slot :tools)
  (let [execution (into {} (map (juxt :name :execution)) (capabilities/catalog registry))
        parallel? #(= :parallel (get execution (:tool-call/name %)))
        invoke-call
        (fn [call]
          (util/check-cancelled! (:cancelled slot))
          (let [call {:id (:tool-call/id call) :name (:tool-call/name call)
                      :arguments (:tool-call/arguments call)}]
            (transient-event! runtime sid (:operation-id slot) :tool/started
                              {:call-id (:id call) :name (:name call)} callback)
            (let [result (capabilities/invoke!
                          registry call
                          {:cancelled? (:cancelled slot)
                           :on-progress #(transient-event! runtime sid (:operation-id slot)
                                                          :tool-progress % callback)
                           :context {:runtime runtime :session-id sid
                                     :operation-id (:operation-id slot)}})]
              (transient-event! runtime sid (:operation-id slot) :tool/finished
                                {:call-id (:id result) :name (:name result) :error? (:error? result)}
                                callback)
              result)))
        persist-result
        (fn [result]
          (locking (session-lock runtime sid)
            (commit! runtime sid
                     {:entries [{:kind :message :data (capabilities/result-message result)}]
                      :events [{:operation-id (:operation-id slot) :type :tool/completed
                                :data {:call-id (:id result) :name (:name result)
                                       :error? (:error? result) :result (:result result)}}]}))
          result)]
    (reduce
     (fn [completed group]
       (util/check-cancelled! (:cancelled slot))
       (if (parallel? (first group))
         (reduce
          (fn [completed batch]
            (let [outcomes (parallel-tool-outcomes slot invoke-call batch)
                  results
                  (mapv (fn [call outcome]
                          (persist-result
                           (or (:result outcome)
                               {:id (:tool-call/id call) :name (:tool-call/name call)
                                :value nil :error? true
                                :content "Capability execution was interrupted; inspect state before repeating effects."
                                :details {:uncertain? true :error (util/error-map (:failure outcome))}})))
                        batch outcomes)]
              (when-let [failure (some :failure outcomes)] (throw failure))
              (into completed results)))
          completed (partition-all 8 group))
         (into completed (map #(persist-result (invoke-call %))) group)))
     [] (partition-by parallel? calls))))

(defn- deliver-intents! [runtime sid slot phase]
  (locking (session-lock runtime sid)
    (util/check-cancelled! (:cancelled slot))
    (let [items (run/select-intents (store/pending (:store runtime) sid) phase)]
      (if (seq items)
        (commit! runtime sid
                 {:entries (run/intent-entries items)
                  :queue-deliver (mapv :id items)
                  :events [{:operation-id (:operation-id slot)
                            :type :queue/delivered
                            :data {:ids (mapv :id items) :phase phase}}]})
        (when (= phase :turn-boundary)
          (set-phase! slot :settling)))
      items)))

(defn- compact-current! [runtime sid slot registry config instructions callback automatic?]
  (let [path (store/active-path (:store runtime) sid)
        plan (run/compaction-plan path config)]
    (when-not plan
      (when-not automatic?
        (util/fail! :nothing-to-compact
                    "The session does not contain a safe compaction boundary" {:session-id sid})))
    (when plan
      (set-phase! slot :compacting)
      (let [hook-context {:runtime runtime :session-id sid
                          :operation-id (:operation-id slot)
                          :session (store/session (:store runtime) sid)
                          :compaction? true}
            request (-> (capabilities/apply-hooks
                         registry :transform-request hook-context
                         (run/summary-request config (:summary-entries plan) instructions))
                        (session-cache sid))
            _ (util/check! (map? request) :invalid-hook-result
                           "transform-request hooks must return a request map" {})
            response (provider-complete! runtime sid slot config request callback)
            summary (run/response-text response)]
        (util/check! (not (str/blank? summary)) :empty-compaction
                     "Provider returned an empty compaction summary" {})
        (util/check! (not= :length (:response/finish-reason response)) :compaction-truncated
                     "Compaction summary was truncated by the provider" {})
        (locking (session-lock runtime sid)
          (util/check-cancelled! (:cancelled slot))
          (commit! runtime sid
                   {:entries [{:kind :compaction
                               :data {:summary summary
                                      :first-kept-entry-id (:first-kept-entry-id plan)
                                      :usage (:response/usage response)}}]
                    :events [{:operation-id (:operation-id slot)
                              :type :session/compacted
                              :data {:automatic? automatic?
                                     :first-kept-entry-id (:first-kept-entry-id plan)
                                     :usage (:response/usage response)}}]}))
        {:summary summary :first-kept-entry-id (:first-kept-entry-id plan)
         :usage (:response/usage response)}))))

(defn- configured-model [runtime sid config]
  (provider/model (provider-manager runtime sid)
                  (:provider config)
                  (:model config)))

(defn- maybe-auto-compact! [runtime sid slot registry config callback usage estimated-input-tokens]
  (when (run/auto-compact? config (configured-model runtime sid config)
                           usage estimated-input-tokens)
    (boolean (compact-current! runtime sid slot registry config nil callback true))))

(defn- prepare-run! [runtime sid slot prompt overrides]
  (let [registry (registry runtime sid)
        manager (resource-manager runtime sid)
        config (provider-config runtime sid
                                (run/effective-config
                                 (store/session (:store runtime) sid) overrides))
        context {:runtime runtime :session-id sid :operation-id (:operation-id slot)
                 :session (store/session (:store runtime) sid)}
        input (when (some? prompt)
                (let [expanded (if (string? prompt)
                                 (resources/expand-input manager prompt) prompt)]
                  (capabilities/apply-hooks registry :input context expanded)))
        prepared (capabilities/apply-hooks registry :before-run context
                                           {:prompt input :config config})]
    (util/check! (map? prepared) :invalid-hook-result
                 "before-run hooks must return {:prompt ... :config ...}" {})
    (let [config (provider-config runtime sid
                                  (run/effective-config
                                   (store/session (:store runtime) sid)
                                   (:config prepared)))
          initial-intents
          (locking (session-lock runtime sid)
            (util/check-cancelled! (:cancelled slot))
            (let [items (if (contains? #{:run :continue} (:kind slot))
                          (run/select-intents (store/pending (:store runtime) sid)
                                              :start-boundary)
                          [])
                  entries (cond-> (run/intent-entries items)
                            (some? prompt)
                            (conj {:kind :message
                                   :data (run/user-message (:prompt prepared))}))
                  events (cond-> []
                           (seq items)
                           (conj {:operation-id (:operation-id slot)
                                  :type :queue/delivered
                                  :data {:ids (mapv :id items) :phase :start-boundary}})
                           (some? prompt)
                           (conj {:operation-id (:operation-id slot)
                                  :type :message/user :data {}}))]
              (when (or (seq entries) (seq items))
                (commit! runtime sid
                         {:entries entries
                          :queue-deliver (mapv :id items)
                          :events events}))
              items))]
      {:registry registry :manager manager :config config :hook-context context
       :initial-intents initial-intents})))

(defn- run-loop! [runtime sid slot prompt opts]
  (let [{:keys [registry manager config hook-context initial-intents]}
        (prepare-run! runtime sid slot prompt (:config opts))
        config (provider-config runtime sid (run/config-with-intents config initial-intents))
        max-steps (long (max 1 (min 256 (or (get-in config [:settings :max-steps]) 64))))
        callback (:on-event opts)]
    (loop [step 0
           config config]
      (util/check! (< step max-steps) :step-budget-exhausted
                   "Agent exceeded its configured continuation step budget"
                   {:max-steps max-steps})
      (util/check-cancelled! (:cancelled slot))
      (let [path (store/active-path (:store runtime) sid)
            initial-request (prepare-completion-request runtime sid slot registry manager config)
            compacted? (maybe-auto-compact!
                        runtime sid slot registry config callback
                        (run/latest-usage path)
                        (run/estimate-request-tokens initial-request))
            request (if compacted?
                      (prepare-completion-request runtime sid slot registry manager config)
                      initial-request)
            estimated-input-tokens (run/estimate-request-tokens request)
            response (complete-request! runtime sid slot config request callback)
            assistant (run/validate-assistant! (run/response->assistant response))
            calls (:message/tool-calls assistant)]
        (commit-assistant! runtime sid slot assistant response)
        (when (= :length (:response/finish-reason response))
          (util/fail! :output-truncated
                      "Provider stopped because its output limit was reached"
                      {:message assistant}))
        (if (seq calls)
          (do
            (invoke-tools! runtime sid slot registry calls callback)
            (let [intents (deliver-intents! runtime sid slot :tool-boundary)
                  next-config (provider-config runtime sid
                                               (run/config-with-intents config intents))]
              (recur (inc step) next-config)))
          (let [intents (deliver-intents! runtime sid slot :turn-boundary)]
            (if (seq intents)
              (let [next-config (provider-config runtime sid
                                                 (run/config-with-intents config intents))]
                (recur (inc step) next-config))
              (do
                (when-not compacted?
                  (maybe-auto-compact! runtime sid slot registry config callback
                                       (:response/usage response)
                                       estimated-input-tokens))
                (let [final (capabilities/apply-hooks registry :after-run hook-context assistant)]
                  (util/check! (map? final) :invalid-hook-result
                               "after-run hooks must return an assistant message" {})
                  final)))))))))

(defn- compact-operation! [runtime sid slot opts]
  (let [{:keys [registry config]} (prepare-run! runtime sid slot nil (:config opts))]
    (compact-current! runtime sid slot registry config (:instructions opts)
                      (:on-event opts) false)))

(defn- execute-operation! [runtime sid slot work]
  (reset! (:thread slot) (Thread/currentThread))
  (try
    (let [result (work)
          _ (util/check-cancelled! (:cancelled slot))
          durable-result (if (and (map? result) (contains? result :value))
                           (durable-invocation result) result)
          operation (settle-operation! runtime sid slot :completed durable-result nil)]
      (deliver (:done slot) operation)
      result)
    (catch Throwable error
      (let [status (if (cancelled-error? slot error) :cancelled :failed)
            operation (try (settle-operation! runtime sid slot status nil error)
                           (catch Throwable settlement-error
                             {:id (:operation-id slot) :session-id sid :kind (:kind slot)
                              :status :running
                              :error {:code "settlement-failed"
                                      :message (ex-message settlement-error)}}))]
        (deliver (:done slot) operation)
        (when-not (= status :cancelled)
          (transient-event! runtime sid (:operation-id slot) :operation-error
                            {:error (util/error-map error)} nil))
        (throw error)))
    (finally
      (reset! (:thread slot) nil)
      (swap! (:operations runtime) dissoc (:operation-id slot))
      (deliver (:finished slot) true)
      (release-foreground! runtime sid (:operation-id slot)))))

(defn- submit-operation! [runtime sid slot work]
  (try
    (let [task (bound-fn []
                 (try (execute-operation! runtime sid slot work)
                      (catch Throwable _ nil)))]
      (.submit ^ExecutorService (:executor runtime)
               ^Runnable (reify Runnable
                           (run [_] (task)))))
    (store/operation (:store runtime) (:operation-id slot))
    (catch RejectedExecutionException error
      (let [operation (settle-operation! runtime sid slot :failed nil error)]
        (deliver (:done slot) operation)
        (swap! (:operations runtime) dissoc (:operation-id slot))
        (deliver (:finished slot) true)
        (release-foreground! runtime sid (:operation-id slot))
        (throw error)))))
(defn- root-provider-settings [cwd home initial-settings]
  (let [manager (resources/create! {:cwd cwd :home home
                                    :settings initial-settings :trust false})]
    (try
      (resources/settings manager)
      (finally
        (resources/close! manager)))))


(defn open!
  "Opens the durable runtime, repairs interrupted work, and creates its owned executor."
  [{:keys [cwd home data-dir memory? settings trust complete-fn ui!]
    :or {cwd "." settings {}}}]
  (let [cwd (util/canonical-path cwd)
        home (util/home-dir {:home home})
        data-dir (util/canonical-path (or data-dir (str home "/data")))
        opened (atom [])]
    (try
      (util/ensure-dir! home)
      (when-not memory? (util/ensure-dir! data-dir))
      (let [store (store/open! {:path (when-not memory? (str data-dir "/sessions.sqlite"))
                                :memory? memory?
                                :artifact-dir (when-not memory? (str data-dir "/artifacts"))})
            _ (swap! opened conj #(store/close! store))
            recovery-events (store/recover! store)
            provider-settings (root-provider-settings cwd home settings)
            provider (provider/create! {:home home :settings provider-settings
                                        :complete-fn complete-fn})
            _ (swap! opened conj #(provider/close! provider))
            root-resources (resources/create! {:cwd cwd :home home :settings settings :trust trust})
            _ (swap! opened conj #(resources/close! root-resources))
            threads (long (max 1 (min 16 (or (:operation-threads settings)
                                             (.availableProcessors (Runtime/getRuntime))))))
            runtime {:store store :provider provider :resources root-resources
                     :cwd cwd :home home :trust trust :initial-settings settings
                     :settings (atom (resources/settings root-resources))
                     :handles (atom {}) :operations (atom {}) :listeners (atom {})
                     :foreground (atom {}) :session-locks (atom {}) :handle-lock (Object.)
                     :close-lock (Object.) :ui (atom ui!)
                     :executor (Executors/newFixedThreadPool (int threads))
                     :lifecycle (atom :open) :recovery-events recovery-events}]
        (reset! opened [])
        runtime)
      (catch Throwable error
        (doseq [cleanup (reverse @opened)]
          (try (cleanup) (catch Throwable _ nil)))
        (throw error)))))

(def ^:private session-default-keys
  #{:provider :model :thinking :tools :instructions :settings})

(def ^:private generation-setting-keys
  #{:temperature :top-p :max-output-tokens :stop :response-format
    :cache :provider-options :auto-compact? :compaction-threshold
    :compaction-keep-entries :compaction-max-output-tokens
    :max-steps :provider-retries :fallback-model?})

(defn- cwd-session-defaults [runtime cwd]
  (let [manager (resources/create! {:cwd cwd :home (:home runtime)
                                    :settings (:initial-settings runtime)
                                    :trust (:trust runtime)})]
    (try
      (let [effective (resources/settings manager)
            nested (util/deep-merge
                    (select-keys (:session-defaults effective) session-default-keys)
                    (select-keys (:session effective) session-default-keys)
                    (select-keys (:session-config effective) session-default-keys))
            direct (select-keys effective (disj session-default-keys :settings))
            generation (select-keys effective generation-setting-keys)]
        (cond-> (util/deep-merge direct nested)
          (seq generation) (update :settings #(util/deep-merge generation %))))
      (finally
        (resources/close! manager)))))

(defn create-session! [runtime opts]
  (ensure-open! runtime)
  (let [cwd (util/canonical-path (or (:cwd opts) (:cwd runtime)))
        defaults (cwd-session-defaults runtime cwd)
        config (util/deep-merge run/default-config defaults (:config opts))]
    (store/create-session! (:store runtime)
                           (assoc opts :cwd cwd :config config))))

(defn list-sessions
  ([runtime] (list-sessions runtime {}))
  ([runtime opts] (store/list-sessions (:store runtime) opts)))

(defn session [runtime sid] (store/session (:store runtime) sid))
(defn entries [runtime sid] (store/entries (:store runtime) sid))
(defn active-path [runtime sid] (store/active-path (:store runtime) sid))
(defn pending [runtime sid] (store/pending (:store runtime) sid))
(defn operation [runtime oid] (store/operation (:store runtime) oid))
(defn operations
  ([runtime] (store/operations (:store runtime) {}))
  ([runtime opts] (store/operations (:store runtime) opts)))
(defn events-since [runtime opts] (store/events-since (:store runtime) opts))


(defn- latest-event-seq [runtime sid]
  (loop [after 0]
    (let [page (store/events-since (:store runtime)
                                   {:after after :session-id sid :limit 10000})
          cursor (long (or (:seq (peek page)) after))]
      (if (= 10000 (count page))
        (recur cursor)
        cursor))))
(defn usage [runtime sid]
  (run/usage-from-entries (store/active-path (:store runtime) sid)))

(defn state [runtime sid]
  (let [snapshot (store/session (:store runtime) sid)
        slot (foreground runtime sid)
        event-seq (latest-event-seq runtime sid)
        registry (get-in @(:handles runtime) [sid :registry])
        selection (get-in snapshot [:config :tools])]
    {:session snapshot
     :phase (if slot @(:phase slot) :idle)
     :operation-id (:operation-id slot)
     :queues (store/pending (:store runtime) sid)
     :usage (usage runtime sid)
     :event-seq event-seq
     :tools {:selection selection
             :capabilities (if registry (capabilities/catalog registry) [])}}))

(defn configure! [runtime sid changes]
  (ensure-open! runtime)
  (let [registry (get-in @(:handles runtime) [sid :registry])
        requested-tools (get-in changes [:config :tools])]
    (when (and registry requested-tools)
      (capabilities/definitions registry requested-tools))
    (let [result (locking (session-lock runtime sid)
                   (let [result (store/configure! (:store runtime) sid changes)]
                     (when registry
                       (capabilities/set-tools! registry
                                                (get-in result [:session :config :tools])))
                     result))
          snapshot (:session result)]
      (emit-events! runtime (:events result))
      snapshot)))

(defn label! [runtime sid entry-id label opts]
  (ensure-open! runtime)
  (util/check! (and (string? label) (not (str/blank? label))) :invalid-label
               "Label must be a non-empty string" {})
  (locking (session-lock runtime sid)
    (let [snapshot (store/session (:store runtime) sid)
          exists? (some #(= entry-id (:id %)) (store/entries (:store runtime) sid))
          labels (conj (vec (remove #(= entry-id (:entry-id %)) (:labels snapshot)))
                       {:entry-id entry-id :label label})]
      (util/check! exists? :entry-not-found "Label target does not exist in this session"
                   {:session-id sid :entry-id entry-id})
      (commit! runtime sid
               {:expected-revision (:expected-revision opts)
                :entries [{:kind :label :data {:entry-id entry-id :label label}}]
                :session {:labels labels}
                :events [{:type :session/labeled
                          :data {:entry-id entry-id :label label}}]}))))

(defn- common-prefix-length [left right]
  (loop [index 0]
    (if (and (< index (count left)) (< index (count right))
             (= (:id (nth left index)) (:id (nth right index))))
      (recur (inc index))
      index)))

(defn- summarize-branch! [runtime sid slot leaf abandoned source opts]
  (execute-operation!
   runtime sid slot
   (fn []
     (let [{:keys [registry config]} (prepare-run! runtime sid slot nil (:config opts))
           hook-context {:runtime runtime :session-id sid
                         :operation-id (:operation-id slot)
                         :session (store/session (:store runtime) sid)
                         :branch-summary? true}
           request (-> (capabilities/apply-hooks
                        registry :transform-request hook-context
                        (run/summary-request config abandoned (:instructions opts)))
                       (session-cache sid))
           _ (util/check! (map? request) :invalid-hook-result
                          "transform-request hooks must return a request map" {})
           response (provider-complete! runtime sid slot config request (:on-event opts))
           summary (run/response-text response)]
       (util/check! (not (str/blank? summary)) :empty-branch-summary
                    "Provider returned an empty branch summary" {})
       (util/check! (not= :length (:response/finish-reason response))
                    :branch-summary-truncated
                    "Branch summary was truncated by the provider" {})
       (locking (session-lock runtime sid)
         (let [branched (store/branch! (:store runtime) sid leaf
                                       (dissoc opts :expected-revision :summarize?))
               _ (emit-events! runtime (:events branched))]
           (commit! runtime sid
                    {:entries [{:kind :branch-summary
                                :data {:summary summary
                                       :from-id (get-in source [:snapshot :head])}}]
                     :events [{:operation-id (:operation-id slot)
                               :type :session/branch-summarized
                               :data {:from-id (get-in source [:snapshot :head])
                                      :entry-id leaf
                                      :usage (:response/usage response)}}]})))
       (close-handle! runtime sid)
       {:summary summary :usage (:response/usage response)}))))

(defn branch! [runtime sid leaf opts]
  (ensure-open! runtime)
  (let [{:keys [source abandoned slot events]}
        (locking (session-lock runtime sid)
          (ensure-idle! runtime sid)
          (let [snapshot (store/session (:store runtime) sid)
                _ (when (contains? opts :expected-revision)
                    (util/check! (= (:expected-revision opts) (:revision snapshot))
                                 :stale-revision "Session revision has changed"
                                 {:session-id sid :expected (:expected-revision opts)
                                  :actual (:revision snapshot)}))
                source {:snapshot snapshot
                        :current-path (store/active-path (:store runtime) sid)
                        :target-path (if leaf
                                       (store/active-path (:store runtime) sid leaf)
                                       [])}
                prefix (common-prefix-length (:current-path source) (:target-path source))
                abandoned (subvec (vec (:current-path source)) prefix)]
            (if (and (:summarize? opts) (seq abandoned))
              {:source source :abandoned abandoned
               :slot (:slot (operation-start! runtime sid :compact))}
              (let [branched (store/branch! (:store runtime) sid leaf opts)]
                ;; Session lock precedes handle lock for every destructive handle mutation.
                (close-handle! runtime sid)
                {:events (:events branched)}))))]
    (if slot
      (summarize-branch! runtime sid slot leaf abandoned source opts)
      (emit-events! runtime events))
    (store/session (:store runtime) sid)))

(defn fork! [runtime sid opts]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (store/fork! (:store runtime) sid opts)))

(defn clone! [runtime sid opts]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (store/clone! (:store runtime) sid opts)))

(defn delete! [runtime sid]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (ensure-idle! runtime sid)
    (let [result (store/delete-session! (:store runtime) sid)]
      (close-handle! runtime sid)
      result)))

(defn import! [runtime packet opts]
  (ensure-open! runtime)
  (store/import-session! (:store runtime) packet
                         (merge {:cwd (:cwd runtime)} opts)))

(defn export! [runtime sid]
  (store/export-session (:store runtime) sid))

(defn reload! [runtime sid]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (ensure-idle! runtime sid)
    (close-handle! runtime sid)
    (registry runtime sid)
    {:session-id sid :status :reloaded}))

(defn- begin-blocking! [runtime sid kind work]
  (let [{:keys [slot]} (operation-start! runtime sid kind)]
    (execute-operation! runtime sid slot #(work slot))))

(defn run!
  ([runtime sid prompt] (run! runtime sid prompt {}))
  ([runtime sid prompt opts]
   (begin-blocking! runtime sid :run
                    #(run-loop! runtime sid % prompt opts))))

(defn continue!
  ([runtime sid] (continue! runtime sid {}))
  ([runtime sid opts]
   (begin-blocking! runtime sid :continue
                    #(run-loop! runtime sid % nil opts))))

(defn compact!
  ([runtime sid] (compact! runtime sid {}))
  ([runtime sid opts]
   (begin-blocking! runtime sid :compact
                    #(compact-operation! runtime sid % opts))))

(defn start!
  ([runtime sid prompt] (start! runtime sid prompt {}))
  ([runtime sid prompt opts]
   (let [{:keys [slot operation]} (operation-start! runtime sid :run)]
     (submit-operation! runtime sid slot #(run-loop! runtime sid slot prompt opts))
     operation)))

(defn start-continue!
  ([runtime sid] (start-continue! runtime sid {}))
  ([runtime sid opts]
   (let [{:keys [slot operation]} (operation-start! runtime sid :continue)]
     (submit-operation! runtime sid slot #(run-loop! runtime sid slot nil opts))
     operation)))

(defn start-compact!
  ([runtime sid] (start-compact! runtime sid {}))
  ([runtime sid opts]
   (let [{:keys [slot operation]} (operation-start! runtime sid :compact)]
     (submit-operation! runtime sid slot #(compact-operation! runtime sid slot opts))
     operation)))

(defn- queue-operation! [runtime oid kind content opts]
  (ensure-open! runtime)
  (let [op (store/operation (:store runtime) oid)
        sid (:session-id op)]
    (locking (session-lock runtime sid)
      (let [slot (foreground runtime sid)]
        (util/check! (= oid (:operation-id slot)) :operation-not-active
                     "Operation is not the session's current foreground operation"
                     {:operation-id oid :session-id sid
                      :current-operation-id (:operation-id slot)})
        (util/check! (contains? #{:run :continue} (:kind slot)) :operation-not-steerable
                     "Only running agent operations accept queued input"
                     {:operation-id oid :kind (:kind slot)})
        (util/check! (not= :settling @(:phase slot)) :operation-not-active
                     "Operation has crossed its final input boundary"
                     {:operation-id oid :session-id sid})
        (let [item {:id (util/id) :session-id sid :operation-id oid :kind kind
                    :content (run/prompt-content content) :options (or opts {})
                    :created-at (util/now)}]
          (commit! runtime sid
                   {:queue-enqueue [(select-keys item [:id :kind :content :options :created-at])]
                    :events [{:operation-id oid :type :queue/enqueued
                              :data (select-keys item [:id :kind])}]})
          (assoc item :status :queued))))))

(defn steer-operation!
  ([runtime oid content] (steer-operation! runtime oid content {}))
  ([runtime oid content opts] (queue-operation! runtime oid :steering content opts)))

(defn follow-up-operation!
  ([runtime oid content] (follow-up-operation! runtime oid content {}))
  ([runtime oid content opts] (queue-operation! runtime oid :follow-up content opts)))

(defn steer!
  ([runtime sid content] (steer! runtime sid content {}))
  ([runtime sid content opts]
   (if-let [oid (:operation-id (foreground runtime sid))]
     (steer-operation! runtime oid content opts)
     (util/fail! :session-not-running "Session has no foreground operation" {:session-id sid}))))

(defn follow-up!
  ([runtime sid content] (follow-up! runtime sid content {}))
  ([runtime sid content opts]
   (if-let [oid (:operation-id (foreground runtime sid))]
     (follow-up-operation! runtime oid content opts)
     (util/fail! :session-not-running "Session has no foreground operation" {:session-id sid}))))

(defn clear-queue! [runtime sid]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (let [items (store/pending (:store runtime) sid)]
      (when (seq items)
        (commit! runtime sid
                 {:queue-deliver (mapv :id items)
                  :events [{:operation-id (:operation-id (foreground runtime sid))
                            :type :queue/cleared :data {:ids (mapv :id items)}}]}))
      items)))

(defn cancel-operation! [runtime oid]
  (let [initial (store/operation (:store runtime) oid)
        sid (:session-id initial)]
    (locking (session-lock runtime sid)
      (let [op (store/operation (:store runtime) oid)]
        (if (run/terminal-operation? op)
          op
          (let [slot (foreground runtime sid)]
            (util/check! (= oid (:operation-id slot)) :operation-not-active
                         "Operation is not the session's current foreground operation"
                         {:operation-id oid :session-id sid
                          :current-operation-id (:operation-id slot)})
            (reset! (:cancelled slot) true)
            (let [result (commit! runtime sid
                                  {:operation {:id oid :status :cancelling}
                                   :events [{:operation-id oid :type :operation/cancelling
                                             :data {}}]})]
              (when-let [thread @(:thread slot)]
                (when-not (identical? thread (Thread/currentThread))
                  (.interrupt ^Thread thread)))
              (:operation result))))))))

(defn cancel! [runtime sid]
  (if-let [oid (:operation-id (foreground runtime sid))]
    (cancel-operation! runtime oid)
    (do
      (store/session (:store runtime) sid)
      {:session-id sid :operation-id nil :status :idle})))

(defn wait!
  ([runtime oid] (wait! runtime oid nil))
  ([runtime oid timeout-ms]
   (let [current (store/operation (:store runtime) oid)]
     (if (run/terminal-operation? current)
       current
       (if-let [slot (get @(:operations runtime) oid)]
         (let [value (if (nil? timeout-ms)
                       @(:done slot)
                       (deref (:done slot) (long (max 0 timeout-ms)) ::timeout))]
           (if (= ::timeout value) (store/operation (:store runtime) oid) value))
         (store/operation (:store runtime) oid))))))

(defn evaluate!
  ([runtime sid source] (evaluate! runtime sid source {}))
  ([runtime sid source opts]
   (begin-blocking!
    runtime sid :evaluate
    (fn [slot]
      (set-phase! slot :evaluating)
      (let [registry (registry runtime sid)
            result (capabilities/invoke!
                    registry {:id (util/id) :name "clojure_eval" :arguments {:source source}}
                    {:cancelled? (:cancelled slot)
                     :on-progress #(transient-event! runtime sid (:operation-id slot)
                                                     :tool-progress % (:on-event opts))
                     :context {:runtime runtime :session-id sid
                               :operation-id (:operation-id slot)}})]
        (locking (session-lock runtime sid)
          (util/check-cancelled! (:cancelled slot))
          (commit! runtime sid
                   {:entries [{:kind :evaluation
                               :data {:source source :result (durable-invocation result)}}]
                    :events [{:operation-id (:operation-id slot)
                              :type :session/evaluated
                              :data {:result (:result result) :error? (:error? result)}}]}))
        result)))))

(defn invoke!
  ([runtime sid name arguments] (invoke! runtime sid name arguments {}))
  ([runtime sid name arguments opts]
   (begin-blocking!
    runtime sid :invoke
    (fn [slot]
      (set-phase! slot :invoking)
      (let [registry (registry runtime sid)
            result (capabilities/invoke!
                    registry {:id (util/id) :name name :arguments arguments}
                    {:cancelled? (:cancelled slot)
                     :on-progress #(transient-event! runtime sid (:operation-id slot)
                                                     :tool-progress % (:on-event opts))
                     :context {:runtime runtime :session-id sid
                               :operation-id (:operation-id slot)}})]
        (locking (session-lock runtime sid)
          (util/check-cancelled! (:cancelled slot))
          (commit! runtime sid
                   {:entries [{:kind :custom
                               :data {:type :invocation :name name :arguments arguments
                                      :result (durable-invocation result)}}]
                    :events [{:operation-id (:operation-id slot)
                              :type :capability/invoked
                              :data {:name name :result (:result result)
                                     :error? (:error? result)}}]}))
        result)))))

(defn- remaining-close-millis [deadline]
  (long (max 0 (quot (- deadline (System/nanoTime)) 1000000))))

(defn- await-foreground! [slot deadline]
  (or (realized? (:finished slot))
      (let [remaining (remaining-close-millis deadline)]
        (and (pos? remaining)
             (not= ::timeout (deref (:finished slot) remaining ::timeout))))))

(defn close!
  "Cancels owned work and closes resources only after every foreground driver
   has actually exited. An incomplete close retains every handle and the store
   so a later close attempt can finish safely."
  [runtime]
  (locking (:close-lock runtime)
    (let [prior @(:lifecycle runtime)]
      (if (= :closed prior)
        {:status :closed :already-closed? true :foreground-complete? true
         :executor-terminated? true :errors []}
        (let [slots (locking (:foreground runtime)
                      (compare-and-set! (:lifecycle runtime) :open :closing)
                      (vec (vals @(:foreground runtime))))
              current (Thread/currentThread)
              cancellation-errors (atom [])]
            (doseq [slot slots]
              (try
                (cancel-operation! runtime (:operation-id slot))
                (catch Throwable error
                  (swap! cancellation-errors conj (util/error-map error))
                  (reset! (:cancelled slot) true)
                  (when-let [thread @(:thread slot)]
                    (when-not (identical? thread current)
                      (.interrupt ^Thread thread))))))
            ;; Graceful shutdown prevents an operation that invoked close! from
            ;; interrupting its own caller thread. Explicit cancellation above
            ;; still interrupts every other running driver.
            (.shutdown ^ExecutorService (:executor runtime))
            (let [timeout-ms (long (max 0 (or (:close-timeout-ms (:initial-settings runtime))
                                              10000)))
                  deadline (+ (System/nanoTime) (* timeout-ms 1000000))
                  self-slots (filterv #(identical? current @(:thread %)) slots)
                  _ (doseq [slot slots
                            :when (not (some #(identical? slot %) self-slots))]
                      (await-foreground! slot deadline))
                  incomplete (filterv #(not (realized? (:finished %))) slots)
                  foreground-complete? (empty? incomplete)
                  executor-terminated?
                  (if foreground-complete?
                    (try
                      (.awaitTermination ^ExecutorService (:executor runtime)
                                         (remaining-close-millis deadline)
                                         TimeUnit/MILLISECONDS)
                      (catch InterruptedException _
                        (.interrupt current)
                        false))
                    (.isTerminated ^ExecutorService (:executor runtime)))]
              (if-not (and foreground-complete? executor-terminated?)
                {:status :closing :already-closed? false
                 :foreground-complete? foreground-complete?
                 :active-operation-ids (mapv :operation-id incomplete)
                 :executor-terminated? executor-terminated?
                 :store-closed? false :handles-closed? false
                 :errors
                 (into @cancellation-errors
                       (cond-> []
                         (not foreground-complete?)
                         (conj {:code "foreground-timeout"
                                :message "Foreground execution did not finish before the close deadline"})
                         (not executor-terminated?)
                         (conj {:code "executor-timeout"
                                :message "Operation executor did not terminate before the close deadline"})))}
                (let [errors (atom @cancellation-errors)
                      handles (mapv (fn [sid]
                                      (try (close-handle! runtime sid)
                                           (catch Throwable error
                                             (swap! errors conj (util/error-map error)) nil)))
                                    (keys @(:handles runtime)))
                      root (try (resources/close! (:resources runtime))
                                (catch Throwable error
                                  (swap! errors conj (util/error-map error)) nil))
                      provider (try (provider/close! (:provider runtime))
                                    (catch Throwable error
                                      (swap! errors conj (util/error-map error)) nil))
                      store (try (store/close! (:store runtime))
                                 (catch Throwable error
                                   (swap! errors conj (util/error-map error)) nil))]
                  (reset! (:listeners runtime) {})
                  (reset! (:lifecycle runtime) :closed)
                  {:status :closed :already-closed? false
                   :foreground-complete? true :executor-terminated? true
                   :handles handles :resources root :provider provider :store store
                   :errors @errors}))))))))
