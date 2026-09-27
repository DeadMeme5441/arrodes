(ns arrodes.runtime
  "Durable session orchestration, REPL-driven agent continuation, and foreground operations."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [arrodes.capabilities :as capabilities]
            [arrodes.agents :as agents]
            [arrodes.coordination :as coordination]
            [arrodes.jobs :as jobs]
            [arrodes.artifacts :as artifacts]
            [arrodes.provider :as provider]
            [arrodes.provider-repl :as repl-wire]
            [arrodes.resources :as resources]
            [arrodes.run :as run]
            [arrodes.store :as store]
            [arrodes.titles :as titles]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.util.concurrent ExecutorService Executors RejectedExecutionException TimeUnit)))

(def ^:dynamic *resetting-session* nil)

(defn- ensure-open! [runtime]
  (value/check! (= :open @(:lifecycle runtime)) :runtime-closed
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
  (when (seq events)
    (try
      (.execute ^ExecutorService (:publisher runtime)
                ^Runnable
                (fn []
                  (loop []
                    (let [batch (store/events-since (:store runtime)
                                                    {:after @(:published-seq runtime) :limit 1000})]
                      (when (seq batch)
                        (doseq [event batch]
                          (notify-listeners! runtime event)
                          (reset! (:published-seq runtime) (:seq event)))
                        (recur))))))
      (catch RejectedExecutionException _ nil)))
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

(def ^:private ui-kinds
  #{:select :confirm :input :notify :capability
    :renderer :widget :set-widget :render :editor})

(defn set-ui!
  "Installs or removes the single host-interaction callback."
  [runtime callback]
  (value/check! (or (nil? callback) (fn? callback)) :invalid-ui-callback
                "UI callback must be a function or nil" {})
  (reset! (:ui runtime) callback)
  runtime)

(defn ui!
  "Invokes the current host callback or fails rather than inventing an answer."
  [runtime request]
  (value/check! (map? request) :invalid-ui-request
                "UI request must be a map" {})
  (value/check! (contains? ui-kinds (:kind request)) :unsupported-ui-request
                "Unsupported UI request kind" {:kind (:kind request)})
  (if-let [callback @(:ui runtime)]
    (callback request)
    (value/fail! :host-unavailable "No host is available for this interaction"
                 {:kind (:kind request)})))

(defn subscribe!
  "Subscribes to durable and transient runtime events and returns an idempotent unsubscribe function."
  [runtime listener]
  (ensure-open! runtime)
  (value/check! (fn? listener) :invalid-listener "Event listener must be a function" {})
  (let [id (util/id)]
    (swap! (:listeners runtime) assoc id (bound-fn [event] (listener event)))
    (let [removed? (atom false)]
      (fn []
        (when (compare-and-set! removed? false true)
          (swap! (:listeners runtime) dissoc id))
        nil))))

(defn- command-dispatch! [runtime method params]
  (if-let [dispatch (:command! runtime)]
    (dispatch runtime method params)
    (value/fail! :host-unavailable "No command host is installed" {:method method})))

(defn- append-extension-entry! [runtime sid entry]
  (value/check! (map? entry) :invalid-entry "Extension entry must be a map" {})
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
   :ui! (fn [request]
          (ui! runtime
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
                                                       (locking (session-lock runtime sid)
                                                         (first (:events (commit! runtime sid {:events [event]}))))
                                                       (notify-listeners! runtime event)))
                                            :get-session #(store/session (:store runtime) sid)})
            _ (swap! built assoc :registry registry)
            _ (agents/install! (:agents runtime) registry)
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

(defn- handle! [runtime sid]
  (ensure-open! runtime)
  (loop []
    (let [[owner attempt]
          (locking (session-lock runtime sid)
            (ensure-open! runtime)
            (while (and (not= sid *resetting-session*)
                        (contains? @(:resetting runtime) sid))
              (.wait ^Object (session-lock runtime sid) 50)
              (ensure-open! runtime))
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
          (locking (session-lock runtime sid)
            (when-let [handle (:value outcome)]
              (swap! (:handles runtime) assoc sid handle))
            (swap! (:handle-attempts runtime) dissoc sid)
            (deliver (:done attempt) outcome))
          (await-handle-attempt! attempt))
        (if (= :closing (:kind attempt))
          (do (await-handle-attempt! attempt) (recur))
          (await-handle-attempt! attempt))))))

(defn registry
  "Returns the lazily-created session registry; construction has one owner outside the gate."
  [runtime sid]
  (:registry (handle! runtime sid)))

(defn resource-manager [runtime sid]
  (:resources (handle! runtime sid)))

(defn provider-manager [runtime sid]
  (:provider (handle! runtime sid)))

(defn- close-owned-handle! [runtime sid handle]
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

(defn- close-handle! [runtime sid]
  (loop []
    (let [[owner attempt handle]
          (locking (session-lock runtime sid)
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
            (locking (session-lock runtime sid)
              (when (:value outcome)
                (swap! (:handles runtime) dissoc sid)
                (swap! (:failed-handles runtime) dissoc sid))
              (swap! (:handle-attempts runtime) dissoc sid)
              (deliver (:done attempt) outcome))
            (await-handle-attempt! attempt))
          (if (= :loading (:kind attempt))
            (do (await-handle-attempt! attempt) (recur))
            (await-handle-attempt! attempt)))))))

(defn- foreground [runtime sid]
  (get @(:foreground runtime) sid))

(defn- ensure-idle! [runtime sid]
  ;; Release the session monitor while teardown waits for background workers.
  ;; Admission resumes only after the old evaluator is closed or retained intact.
  (while (and (not= sid *resetting-session*) (contains? @(:resetting runtime) sid))
    (.wait ^Object (session-lock runtime sid) 50)
    (ensure-open! runtime))
  (when-let [slot (foreground runtime sid)]
    (value/fail! :session-busy "Session already has a foreground operation"
                 {:session-id sid :operation-id (:operation-id slot)})))

(defn reserve! [runtime oid]
  (locking (:foreground runtime)
    (ensure-open! runtime)
    (swap! (:admission runtime) coordination/reserve oid (:operation-limit runtime)))
  oid)

(defn release! [runtime oid]
  (let [released? (locking (:foreground runtime)
                    (when (= :reserved (get @(:admission runtime) oid))
                      (swap! (:admission runtime) dissoc oid)
                      true))]
    (when released? (agents/settled! (:agents runtime) nil)))
  nil)

(defn- acquire-foreground! [runtime sid kind oid]
  (locking (session-lock runtime sid)
    (store/session (:store runtime) sid)
    (ensure-idle! runtime sid)
    (locking (:foreground runtime)
      (ensure-open! runtime)
      (when-not (contains? @(:admission runtime) oid)
        (swap! (:admission runtime) coordination/reserve oid (:operation-limit runtime)))
      (let [slot {:operation-id oid :kind kind :phase (atom :starting)
                  :cancelled (atom false) :cancellable? (atom true)
                  :accepting-input? (atom true) :thread (atom nil)
                  :done (promise) :finished (promise) :usage (atom {})}]
        (swap! (:admission runtime) coordination/occupy oid)
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

(defn- publish-phase! [runtime sid slot phase callback]
  (set-phase! slot phase)
  (transient-event! runtime sid (:operation-id slot) :operation/phase {:phase phase} callback))

(defn- operation-start! [runtime sid kind]
  (locking (session-lock runtime sid)
    (let [oid (util/id)
          slot (acquire-foreground! runtime sid kind oid)
          operation {:id oid :session-id sid :kind kind :status :running :created-at (util/now)}]
      (try
        (let [result (commit! runtime sid
                              {:session {:status :running}
                               :operation operation
                               :events [{:operation-id oid :type :operation/started
                                         :data {:kind kind}}]})]
          {:slot slot :operation (:operation result)})
        (catch Throwable error
          (swap! (:operations runtime) dissoc oid)
          (release-foreground! runtime sid oid)
          (swap! (:admission runtime) dissoc oid)
          (deliver (:finished slot) true)
          (agents/settled! (:agents runtime) sid)
          (throw error))))))

(defn- durable-invocation [result]
  (dissoc result :value))

(defn- settle-operation! [runtime sid slot requested result error]
  (let [oid (:operation-id slot)
        committed
        (locking (session-lock runtime sid)
          (let [status (coordination/terminal-status
                        requested (or @(:cancelled slot)
                                      (= :cancelling
                                         (:status (store/operation (:store runtime) oid)))))
                operation (cond-> {:id oid :session-id sid :kind (:kind slot)
                                   :status status :finished-at (util/now)}
                            (and (= status :completed) (some? result)) (assoc :result result)
                            error (assoc :error (value/error-map error)))
                session-status (case status
                                 :completed :idle
                                 :cancelled :interrupted
                                 :failed :failed
                                 :interrupted :interrupted
                                 :failed)
                unresolved (when-not (= :completed status)
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
                      unresolved)
                command
                {:entries interrupted-entries
                 :session {:status session-status}
                 :operation operation
                 :events (cond-> [{:operation-id oid
                                   :type (keyword "operation" (name status))
                                   :data (cond-> {}
                                           error (assoc :error (value/error-map error)))}]
                           (seq unresolved)
                           (conj {:operation-id oid :type :tool/interrupted
                                  :data {:call-ids (mapv :tool-call/id unresolved)
                                         :status status}}))}]
            (release-foreground! runtime sid oid)
            (try
              (store/commit! (:store runtime) sid command)
              (catch Throwable settlement-error
                (swap! (:foreground runtime) assoc sid slot)
                (throw settlement-error)))))]
    (emit-events! runtime (:events committed))
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

(defn- provider-complete!
  [runtime sid slot config request callback
   & [{:keys [publish-stream?] :or {publish-stream? true}}]]
  (let [max-retries (long (max 0 (min 5 (or (get-in config [:settings :provider-retries]) 2))))
        cancelled? #(or @(:cancelled slot) (.isInterrupted (Thread/currentThread)))]
    (loop [attempt 0]
      (util/check-cancelled! cancelled?)
      (let [visible? (atom false)
            on-event (fn [event]
                       (when (run/event-visible? event) (reset! visible? true))
                       ;; Internal summaries are continuation state, not replies.
                       ;; Gate at the runtime boundary so neither subscribers nor
                       ;; per-operation callbacks can render their partial text.
                       (when publish-stream?
                         (transient-event! runtime sid (:operation-id slot)
                                           :provider-event event callback)))
            outcome (try
                      {:response
                       (provider/complete! (provider-manager runtime sid) request
                                           {:provider (:provider config)
                                            :on-event on-event :cancelled? cancelled?})}
                      (catch Throwable error {:error error}))]
        (if-let [error (:error outcome)]
          (if (and (< attempt max-retries) (not @visible?)
                   (not (run/context-overflow? error))
                   (run/retryable-error? error) (not (cancelled?)))
            (do
              (transient-event! runtime sid (:operation-id slot) :provider-retry
                                {:attempt (inc attempt) :error (value/error-map error)} callback)
              (Thread/sleep (long (min 2000 (* 200 (bit-shift-left 1 attempt)))))
              (recur (inc attempt)))
            (throw (ex-info (ex-message error)
                            (assoc (ex-data error) :provider/output-started? @visible?) error)))
          (:response outcome))))))

(defn- runtime-context [runtime sid registry manager config]
  (let [project (resources/context manager)
        instructions (:instructions config)
        developer (->> [repl-wire/instructions
                        (str "Current REPL generation: " (:generation registry)
                             ". Namespace: " (:namespace registry) ".")
                        project instructions]
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
    (value/check! (vector? transformed) :invalid-hook-result
                  "transform-context hooks must return a message vector" {})
    transformed))

(defn- session-cache [request sid]
  (update request :request/cache
          #(assoc (merge {:enabled? true} (or % {})) :scope-id sid)))

(defn- prepare-completion-request [runtime sid slot registry manager config]
  (let [messages (runtime-context runtime sid registry manager config)
        _ (capabilities/selected-catalog registry (:tools config))
        request (run/request config (repl-wire/messages messages) [])
        context {:runtime runtime :session-id sid :operation-id (:operation-id slot)
                 :session (store/session (:store runtime) sid)}
        request (-> (capabilities/apply-hooks registry :transform-request context request)
                    (session-cache sid)
                    repl-wire/request)]
    (value/check! (map? request) :invalid-hook-result
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

(defn- evaluate-calls! [runtime sid slot registry calls config callback]
  (set-phase! slot :evaluating)
  ;; Evaluations share bindings and therefore run in response order. Concurrency
  ;; inside an evaluation is Clojure composition, not a second host scheduler.
  (doseq [call calls]
    (util/check-cancelled! (:cancelled slot))
    (let [{:keys [id source]} (repl-wire/evaluation call)
          result (capabilities/evaluate!
                  registry source
                  {:id id :cancelled? (:cancelled slot) :on-event callback
                   :on-progress #(transient-event! runtime sid (:operation-id slot)
                                                   :tool-progress % callback)
                   :context {:runtime runtime :session-id sid
                             :operation-id (:operation-id slot)
                             :tool-selection (:tools config)}})]
      (locking (session-lock runtime sid)
        (commit! runtime sid
                 {:entries [{:kind :message :data (repl-wire/result-message result)}]})))))

(defn- deliver-intents! [runtime sid slot phase]
  (locking (session-lock runtime sid)
    (util/check-cancelled! (:cancelled slot))
    (let [items (run/select-intents (store/pending (:store runtime) sid) phase)
          peer-entries (agents/deliver! (:agents runtime) sid (:operation-id slot))]
      (if (seq items)
        (commit! runtime sid
                 {:entries (run/intent-entries items)
                  :queue-deliver (mapv :id items)
                  :events [{:operation-id (:operation-id slot)
                            :type :queue/delivered
                            :data {:ids (mapv :id items) :phase phase}}]})
        (when (and (= phase :turn-boundary) (not (seq peer-entries)))
          (reset! (:accepting-input? slot) false)
          (set-phase! slot :settling)))
      {:intents items :peer? (boolean (seq peer-entries))})))

(defn- compact-current! [runtime sid slot registry config instructions callback automatic?]
  (let [path (store/active-path (:store runtime) sid)
        plan (run/compaction-plan path config)]
    (when-not plan
      (when-not automatic?
        (value/fail! :nothing-to-compact
                     "The session does not contain a safe compaction boundary" {:session-id sid})))
    (when plan
      (let [previous-phase @(:phase slot)]
        (publish-phase! runtime sid slot :compacting callback)
        (try
          (let [hook-context {:runtime runtime :session-id sid
                              :operation-id (:operation-id slot)
                              :session (store/session (:store runtime) sid)
                              :compaction? true}
                request (-> (capabilities/apply-hooks
                             registry :transform-request hook-context
                             (run/summary-request config (:summary-entries plan) instructions))
                            (session-cache sid))
                _ (value/check! (map? request) :invalid-hook-result
                                "transform-request hooks must return a request map" {})
                response (provider-complete! runtime sid slot config request callback
                                             {:publish-stream? false})
                summary (run/response-text response)]
            (value/check! (not (str/blank? summary)) :empty-compaction
                          "Provider returned an empty compaction summary" {})
            (value/check! (not= :length (:response/finish-reason response)) :compaction-truncated
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
             :usage (:response/usage response)})
          (finally (publish-phase! runtime sid slot previous-phase callback)))))))

(defn- context-recovery-error [message error]
  (ex-info (str message " Reduce the latest message or attached/tool content, or start a new session. "
                "Completed REPL effects have not been repeated.")
           {:error/code "context-limit-unresolved" :provider (:provider (ex-data error))}
           error))

(defn- complete-with-context-recovery! [runtime sid slot registry manager config callback]
  (let [request (prepare-completion-request runtime sid slot registry manager config)
        attempt (try {:response (complete-request! runtime sid slot config request callback)}
                     (catch Throwable error {:error error}))]
    (if-let [error (:error attempt)]
      (if (and (run/context-overflow? error)
               (not (:provider/output-started? (ex-data error))))
        (do
          (util/check-cancelled! (:cancelled slot))
          (when (= false (get-in config [:settings :auto-compact?]))
            (throw (context-recovery-error "The provider rejected the context; automatic compaction is disabled." error)))
          ;; Keep the latest complete user turn, including all its settled REPL
          ;; calls. Re-enter only the provider boundary, never prepare-run!/eval.
          (let [compacted (try
                            (compact-current! runtime sid slot registry
                                              (assoc-in config [:settings :compaction-keep-entries] 1)
                                              nil callback true)
                            (catch Throwable summary-error
                              (util/check-cancelled! (:cancelled slot))
                              (throw (context-recovery-error "The provider rejected the context and compaction failed." summary-error))))]
            (when-not compacted
              (throw (context-recovery-error "The provider rejected the context, but no earlier turn can be compacted safely." error)))
            (util/check-cancelled! (:cancelled slot))
            (try
              {:response (complete-request! runtime sid slot config
                                             (prepare-completion-request runtime sid slot registry manager config)
                                             callback)
               :recovered? true}
              (catch Throwable retry-error
                (if (run/context-overflow? retry-error)
                  (throw (context-recovery-error "The provider still rejects the context after one compaction retry." retry-error))
                  (throw retry-error))))))
        (throw error))
      attempt)))

(defn- configured-model [runtime sid config]
  (provider/model (provider-manager runtime sid)
                  (:provider config)
                  (:model config)))

(defn- maybe-auto-compact! [runtime sid slot registry config callback usage]
  (when (run/auto-compact? config (configured-model runtime sid config)
                           usage)
    (boolean (compact-current! runtime sid slot registry config nil callback true))))

(defn- initial-title [runtime sid entries config]
  (when-not (= false (get-in config [:settings :auto-title?]))
    (when-let [message (some #(when (= :user (get-in % [:data :message/role])) (:data %)) entries)]
      (let [snapshot (store/session (:store runtime) sid)
            source (get-in snapshot [:metadata :title/source])]
        (when (and (= :default source)
                   (not-any? #(= :user (get-in % [:data :message/role])) (store/entries (:store runtime) sid)))
          (let [content (:message/content message)
                text (value/text-content content)
                generation (util/id)]
            {:name (or (run/suggested-session-name content) "Attachment discussion")
             :generation generation
             :text (subs text 0 (min 8000 (count text)))
             :metadata (assoc (:metadata snapshot) :title/source :auto :title/generation generation)}))))))

(defn- start-title! [runtime sid config {:keys [generation text]}]
  (when-not (str/blank? text)
    (titles/start! (:titles runtime) (provider-manager runtime sid) sid config text
      (fn [name details]
        (locking (session-lock runtime sid)
          (when (= :open @(:lifecycle runtime))
            (let [snapshot (store/session (:store runtime) sid)]
              (when (and (= :auto (get-in snapshot [:metadata :title/source]))
                         (= generation (get-in snapshot [:metadata :title/generation])))
                (commit! runtime sid
                         {:session {:name name :metadata (assoc (:metadata snapshot) :title/model details)}
                          :events [{:type :session/named :data {:name name :source :auto}}]})))))))))

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
    (value/check! (map? prepared) :invalid-hook-result
                  "before-run hooks must return {:prompt ... :config ...}" {})
    (let [config (provider-config runtime sid
                                  (run/effective-config
                                   (store/session (:store runtime) sid)
                                   (:config prepared)))
          title (atom nil)
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
                  naming (initial-title runtime sid entries config)
                  _ (reset! title naming)
                  events (cond-> []
                           (seq items)
                           (conj {:operation-id (:operation-id slot)
                                  :type :queue/delivered
                                  :data {:ids (mapv :id items) :phase :start-boundary}})
                           (some? prompt)
                           (conj {:operation-id (:operation-id slot)
                                  :type :message/user :data {}})
                           naming (conj {:type :session/named :data {:name (:name naming) :source :auto}}))]
              (when (or (seq entries) (seq items))
                (commit! runtime sid
                         (cond-> {:entries entries
                          :queue-deliver (mapv :id items)
                          :events events}
                           naming (assoc :session (select-keys naming [:name :metadata])))))
              items))]
      (when @title (start-title! runtime sid config @title))
      {:registry registry :manager manager :config config :hook-context context
       :initial-intents initial-intents})))

(defn- deliver-job-results! [runtime sid]
  (locking (session-lock runtime sid)
    (store/store-read (:store runtime)
      (fn [_]
        (let [records (store/pending-job-results (:store runtime) sid)
              path-ids (set (map :id (store/active-path (:store runtime) sid)))
              applicable (filter #(or (nil? (get-in % [:origin :head]))
                                      (contains? path-ids (get-in % [:origin :head]))) records)
              entries (mapv
                        (fn [record]
                          {:kind :custom-context
                           :data (cond-> {:message/role :user :message/job-id (:id record)
                                          :message/content (str "Background job " (pr-str (:name record))
                                                                " is " (name (:status record)) "."
                                                                (when-let [error (when (not= :cancelled (:status record)) (:error record))]
                                                                  (str " " (:message error))))}
                                   (:result-id record)
                                   (assoc :message/result (artifacts/result (:store runtime) sid (:result-id record))))})
                        applicable)]
          (when (seq records)
            (commit! runtime sid {:entries entries :job-deliver (mapv :id records)})))))))

(defn- run-loop! [runtime sid slot prompt opts]
  (let [{:keys [registry manager config hook-context initial-intents]}
        (prepare-run! runtime sid slot prompt (:config opts))
        config (provider-config runtime sid (run/config-with-intents config initial-intents))
        max-steps (long (max 1 (min 256 (or (get-in config [:settings :max-steps]) 64))))
        callback (:on-event opts)]
    (loop [step 0
           config config]
      (value/check! (< step max-steps) :step-budget-exhausted
                    "Agent exceeded its configured continuation step budget"
                    {:max-steps max-steps})
      (util/check-cancelled! (:cancelled slot))
      (deliver-job-results! runtime sid)
      (agents/deliver! (:agents runtime) sid (:operation-id slot))
      (let [path (store/active-path (:store runtime) sid)
            compacted? (maybe-auto-compact!
                        runtime sid slot registry config callback (run/latest-usage path))
            {:keys [response recovered?]} (complete-with-context-recovery! runtime sid slot registry manager config callback)
            assistant (run/validate-assistant! (run/response->assistant response))
            calls (:message/tool-calls assistant)]
        (commit-assistant! runtime sid slot assistant response)
        (when (= :length (:response/finish-reason response))
          (value/fail! :output-truncated
                       "Provider stopped because its output limit was reached"
                       {:message assistant}))
        (if (seq calls)
          (do
            (evaluate-calls! runtime sid slot registry calls config callback)
            (let [{:keys [intents]} (deliver-intents! runtime sid slot :tool-boundary)
                  next-config (provider-config runtime sid
                                               (run/config-with-intents config intents))]
              (recur (inc step) next-config)))
          (let [{:keys [intents peer?]} (deliver-intents! runtime sid slot :turn-boundary)]
            (if (or (seq intents) peer?)
              (let [next-config (provider-config runtime sid
                                                 (run/config-with-intents config intents))]
                (recur (inc step) next-config))
              (do
                (when-not (or compacted? recovered?)
                  (maybe-auto-compact! runtime sid slot registry config callback
                                       (:response/usage response)))
                (let [final (capabilities/apply-hooks registry :after-run hook-context assistant)]
                  (value/check! (map? final) :invalid-hook-result
                                "after-run hooks must return an assistant message" {})
                  final)))))))))

(defn- compact-operation! [runtime sid slot opts]
  (let [{:keys [registry config]} (prepare-run! runtime sid slot nil (:config opts))]
    (compact-current! runtime sid slot registry config (:instructions opts)
                      (:on-event opts) false)))

(defn- execute-operation! [runtime sid slot work]
  (reset! (:thread slot) (Thread/currentThread))
  (try
    (let [{:keys [result error]}
          (try {:result (let [value (work)]
                          (util/check-cancelled! (:cancelled slot))
                          value)}
               (catch Throwable failure {:error failure}))
          status (if error
                   (if (cancelled-error? slot error) :cancelled :failed)
                   :completed)
          durable-result (if (and (map? result) (contains? result :value))
                           (durable-invocation result) result)
          operation
          (try
            (settle-operation! runtime sid slot status durable-result error)
            (catch Throwable settlement-error
              (swap! (:settlement-failures runtime) assoc sid (:operation-id slot))
              (deliver (:done slot)
                       {:id (:operation-id slot) :session-id sid :kind (:kind slot)
                        :status :running
                        :error {:code "settlement-failed"
                                :message (ex-message settlement-error)}})
              (throw settlement-error)))]
      (deliver (:done slot) operation)
      (when (and error (not= :cancelled (:status operation)))
        (transient-event! runtime sid (:operation-id slot) :operation-error
                          {:error (value/error-map error)} nil))
      (cond
        error (throw error)
        (= :cancelled (:status operation))
        (throw (ex-info "Operation was cancelled before settlement"
                        {:error/code "cancelled" :operation-id (:operation-id slot)}))
        :else result))
    (finally
      (reset! (:thread slot) nil)
      (when-not (contains? @(:settlement-failures runtime) sid)
        (release-foreground! runtime sid (:operation-id slot))
        (swap! (:operations runtime) dissoc (:operation-id slot))
        (swap! (:admission runtime) dissoc (:operation-id slot)))
      (deliver (:finished slot) true)
      (agents/settled! (:agents runtime) sid))))

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
        (release-foreground! runtime sid (:operation-id slot))
        (deliver (:finished slot) true)
        (swap! (:operations runtime) dissoc (:operation-id slot))
        (swap! (:admission runtime) dissoc (:operation-id slot))
        (agents/settled! (:agents runtime) sid)
        (throw error)))))
(defn- root-provider-settings [cwd home initial-settings]
  (let [manager (resources/create! {:cwd cwd :home home
                                    :settings initial-settings :trust false})]
    (try
      (resources/settings manager)
      (finally
        (resources/close! manager)))))


(declare state start! start-continue! cancel-operation! wait! delete! launch-agent!)

(defn open!
  "Opens the durable runtime, repairs interrupted work, and creates its owned executor."
  [{:keys [cwd home data-dir memory? settings trust complete-fn ui! command!]
    :or {cwd "." settings {}}}]
  (let [cwd (util/real-path cwd)
        home (util/home-dir {:home home})
        _ (util/ensure-current-home! home data-dir)
        project (util/open-project! home cwd)
        data-dir (util/canonical-path
                  (or data-dir (util/resolve-path (:directory project) "data")))
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
            limit (long (max 1 (min 128 (or (:operation-limit settings) 32))))
            publisher (Executors/newSingleThreadExecutor)
            _ (swap! opened conj #(.shutdownNow ^ExecutorService publisher))
            executor (Executors/newCachedThreadPool)
            _ (swap! opened conj #(.shutdownNow ^ExecutorService executor))
            agents-holder (atom nil)
            runtime {:store store :provider provider :resources root-resources
                     :cwd cwd :home home :data-dir data-dir :project project
                     :trust trust :initial-settings settings
                     :settings (atom (resources/settings root-resources))
                     :handles (atom {}) :failed-handles (atom {}) :handle-attempts (atom {})
                     :operations (atom {}) :listeners (atom {})
                     :foreground (atom {}) :admission (atom {}) :operation-limit limit
                     :settlement-failures (atom {})
                     :resetting (atom #{}) :session-locks (atom {})
                     :close-lock (Object.) :ui (atom ui!) :command! command!
                     :executor executor :publisher publisher
                     :published-seq (atom (store/latest-event-seq store))
                     :titles (titles/create!)
                     :lifecycle (atom :open) :recovery-events recovery-events}
            jobs-manager (jobs/create! store
                                       (bound-fn [event]
                                         (if (:seq event)
                                           (emit-events! runtime [event])
                                           (notify-listeners! runtime event)))
                                       (fn [sid work] (locking (session-lock runtime sid) (work)))
                                       settings)
            runtime (assoc runtime :jobs jobs-manager)
            callbacks {:with-session (fn [sid f] (locking (session-lock runtime sid) (f)))
                       :emit! (fn [events] (emit-events! runtime events))
                       :state (fn [sid] (state @agents-holder sid))
                       :start! (fn [sid prompt opts] (start! @agents-holder sid prompt opts))
                       :continue! (fn [sid opts] (start-continue! @agents-holder sid opts))
                       :cancel! (fn [oid] (cancel-operation! @agents-holder oid))
                       :wait! (fn [oid timeout-ms] (wait! @agents-holder oid timeout-ms))
                       :registry (fn [sid] (registry @agents-holder sid))
                       :reserve! (fn [oid] (reserve! @agents-holder oid))
                       :release! (fn [oid] (release! @agents-holder oid))
                       :launch! (fn [sid oid opts] (launch-agent! @agents-holder sid oid opts))
                       :cancel-jobs! (fn [sid] (jobs/cancel-session! (:jobs @agents-holder) sid))
                       :await-jobs! (fn [sid timeout-ms]
                                      (jobs/await-session! (:jobs @agents-holder) sid timeout-ms))
                       :delete! (fn [sid] (delete! @agents-holder sid))}
            agent-manager (agents/create! runtime callbacks)
            runtime (assoc runtime :agents agent-manager)]
        (reset! agents-holder runtime)
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
    :max-steps :provider-retries :fallback-model? :auto-title? :title-model :title-provider})

(defn- cwd-session-defaults [runtime cwd]
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

(defn create-session! [runtime opts]
  (ensure-open! runtime)
  (let [cwd (util/real-path (or (:cwd opts) (:cwd runtime)))
        defaults (cwd-session-defaults runtime cwd)
        config (value/deep-merge run/default-config defaults (:config opts))]
    (store/create-session! (:store runtime)
                           (assoc opts :cwd cwd :config config
                                  :metadata (assoc (or (:metadata opts) {}) :title/source
                                                   (if (contains? opts :name) :user :default))))))

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
  (locking (session-lock runtime sid)
    (store/store-read (:store runtime)
      (fn [_]
        (let [snapshot (store/session (:store runtime) sid)
              slot (foreground runtime sid)
              operation (when slot
                          (store/operation (:store runtime) (:operation-id slot)))
              event-seq (latest-event-seq runtime sid)
              registry (get-in @(:handles runtime) [sid :registry])
              selection (get-in snapshot [:config :tools])]
          {:session snapshot
           :phase (if slot @(:phase slot) :idle)
           :operation-id (:operation-id slot)
           :operation operation
           :queues (store/pending (:store runtime) sid)
           :jobs (vec (vals (into {} (map (juxt :id identity))
                                  (concat (jobs/list-jobs (:jobs runtime) sid {})
                                          (store/active-jobs (:store runtime) sid)))))
           :usage (usage runtime sid)
           :event-seq event-seq
           :repl (when registry
                   {:namespace (str (:namespace registry)) :generation (:generation registry)})
           :tools {:selection selection
                   :capabilities (if registry (capabilities/catalog registry) [])}})))))

(defn session-view
  "Returns the durable session projection, authoritative foreground operation,
   active entries, and replay cursor from one session-lock boundary."
  [runtime sid]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (let [snapshot (state runtime sid)]
      {:state snapshot
       :entries (store/active-path (:store runtime) sid)
       :cursor (:event-seq snapshot)})))

(defn configure! [runtime sid changes]
  (ensure-open! runtime)
  (let [registry (get-in @(:handles runtime) [sid :registry])
        requested-tools (get-in changes [:config :tools])]
    (when (and registry requested-tools)
      (capabilities/selected-catalog registry requested-tools))
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
  (value/check! (and (string? label) (not (str/blank? label))) :invalid-label
                "Label must be a non-empty string" {})
  (locking (session-lock runtime sid)
    (let [snapshot (store/session (:store runtime) sid)
          exists? (some #(= entry-id (:id %)) (store/entries (:store runtime) sid))
          labels (conj (vec (remove #(= entry-id (:entry-id %)) (:labels snapshot)))
                       {:entry-id entry-id :label label})]
      (value/check! exists? :entry-not-found "Label target does not exist in this session"
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
           _ (value/check! (map? request) :invalid-hook-result
                           "transform-request hooks must return a request map" {})
           response (provider-complete! runtime sid slot config request (:on-event opts)
                                        {:publish-stream? false})
           summary (run/response-text response)]
       (value/check! (not (str/blank? summary)) :empty-branch-summary
                     "Provider returned an empty branch summary" {})
       (value/check! (not= :length (:response/finish-reason response))
                     :branch-summary-truncated
                     "Branch summary was truncated by the provider" {})
       (locking (session-lock runtime sid)
         (util/check-cancelled! (:cancelled slot))
         (reset! (:cancellable? slot) false)
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
       {:summary summary :usage (:response/usage response)}))))

(defn- with-session-reset! [runtime sid work]
  (locking (session-lock runtime sid)
    (ensure-open! runtime)
    (ensure-idle! runtime sid)
    (swap! (:resetting runtime) conj sid))
  (jobs/block-session! (:jobs runtime) sid)
  (try
    (jobs/cancel-session! (:jobs runtime) sid)
    (value/check! (jobs/await-session! (:jobs runtime) sid
                                     (long (or (:close-timeout-ms (:initial-settings runtime)) 10000)))
                  :jobs-still-running "Background execution has not exited; evaluator retained. Retry after jobs settle."
                  {:session-id sid})
    (binding [*resetting-session* sid] (work))
    (finally
      (jobs/unblock-session! (:jobs runtime) sid)
      (locking (session-lock runtime sid)
        (swap! (:resetting runtime) disj sid)
        (.notifyAll ^Object (session-lock runtime sid))))))

(defn- branch-session! [runtime sid leaf opts]
  (ensure-open! runtime)
  (let [{:keys [source abandoned slot events]}
        (locking (session-lock runtime sid)
          (ensure-idle! runtime sid)
          (let [snapshot (store/session (:store runtime) sid)
                _ (when (contains? opts :expected-revision)
                    (value/check! (= (:expected-revision opts) (:revision snapshot))
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
                {:events (:events branched)}))))]
    (let [result (if slot
                   (summarize-branch! runtime sid slot leaf abandoned source opts)
                   (emit-events! runtime events))]
      (close-handle! runtime sid)
      (store/session (:store runtime) sid))))

(defn branch! [runtime sid leaf opts]
  (with-session-reset! runtime sid
    #(let [result (branch-session! runtime sid leaf opts)]
       (store/acknowledge-all-jobs! (:store runtime) sid)
       result)))

(defn fork! [runtime sid opts]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (store/fork! (:store runtime) sid opts)))

(defn clone! [runtime sid opts]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (store/clone! (:store runtime) sid opts)))

(defn delete! [runtime sid]
  (with-session-reset! runtime sid
    #(do
       (close-handle! runtime sid)
       (locking (session-lock runtime sid)
         (store/delete-session! (:store runtime) sid)))))

(defn import! [runtime packet opts]
  (ensure-open! runtime)
  (store/import-session! (:store runtime) packet
                         (merge {:cwd (:cwd runtime)} opts)))

(defn export! [runtime sid]
  (store/export-session (:store runtime) sid))

(defn reload! [runtime sid]
  (with-session-reset! runtime sid
    #(do
       (close-handle! runtime sid)
       (registry runtime sid)
       {:session-id sid :status :reloaded})))

(defn- begin-blocking! [runtime sid kind work & [opts]]
  (let [{:keys [slot]} (operation-start! runtime sid kind)]
    (when (and (contains? #{:run :continue} kind) (not (:automatic? opts)))
      (agents/resume! (:agents runtime) sid))
    (execute-operation! runtime sid slot #(work slot))))

(defn run!
  ([runtime sid prompt] (run! runtime sid prompt {}))
  ([runtime sid prompt opts]
   (begin-blocking! runtime sid :run
                    #(run-loop! runtime sid % prompt opts) opts)))

(defn continue!
  ([runtime sid] (continue! runtime sid {}))
  ([runtime sid opts]
   (begin-blocking! runtime sid :continue
                    #(run-loop! runtime sid % nil opts) opts)))

(defn compact!
  ([runtime sid] (compact! runtime sid {}))
  ([runtime sid opts]
   (begin-blocking! runtime sid :compact
                    #(compact-operation! runtime sid % opts))))

(defn start!
  ([runtime sid prompt] (start! runtime sid prompt {}))
  ([runtime sid prompt opts]
   (let [{:keys [slot operation]} (operation-start! runtime sid :run)]
     (when-not (:automatic? opts) (agents/resume! (:agents runtime) sid))
     (submit-operation! runtime sid slot #(run-loop! runtime sid slot prompt opts))
     operation)))

(defn start-continue!
  ([runtime sid] (start-continue! runtime sid {}))
  ([runtime sid opts]
   (let [{:keys [slot operation]} (operation-start! runtime sid :continue)]
     (when-not (:automatic? opts) (agents/resume! (:agents runtime) sid))
     (submit-operation! runtime sid slot #(run-loop! runtime sid slot nil opts))
     operation)))

(defn launch-agent! [runtime sid oid opts]
  (let [slot
        (locking (session-lock runtime sid)
          (let [op (store/operation (:store runtime) oid)]
            (value/check! (and (= sid (:session-id op)) (= :queued (:status op)))
                          :operation-not-queued "Child launch requires its queued operation"
                          {:session-id sid :operation-id oid :status (:status op)})
            (let [slot (acquire-foreground! runtime sid :run oid)]
              (try
                (commit! runtime sid {:session {:status :running}
                                      :operation {:id oid :status :running}
                                      :events [{:operation-id oid :type :operation/started
                                                :data {:kind :run}}]})
                slot
                (catch Throwable error
                  (release-foreground! runtime sid oid)
                  (swap! (:operations runtime) dissoc oid)
                  (swap! (:admission runtime) dissoc oid)
                  (deliver (:finished slot) true)
                  (throw error))))))]
    (submit-operation! runtime sid slot #(run-loop! runtime sid slot nil opts))
    (store/operation (:store runtime) oid)))

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
        (value/check! (= oid (:operation-id slot)) :operation-not-active
                      "Operation is not the session's current foreground operation"
                      {:operation-id oid :session-id sid
                       :current-operation-id (:operation-id slot)})
        (value/check! (contains? #{:run :continue} (:kind slot)) :operation-not-steerable
                      "Only running agent operations accept queued input"
                      {:operation-id oid :kind (:kind slot)})
        (value/check! @(:accepting-input? slot) :operation-not-active
                      "Operation has crossed its final input boundary"
                      {:operation-id oid :session-id sid})
        (let [item {:id (util/id) :session-id sid :operation-id oid :kind kind
                    :content (run/prompt-content content) :options (or opts {})
                    :created-at (util/now)}]
          (commit! runtime sid
                   {:queue-enqueue [(select-keys item [:id :kind :content :options :created-at])]
                    :events [{:operation-id oid :type :queue/enqueued
                              :data (select-keys item [:id :kind])}]})
          (agents/settled! (:agents runtime) sid)
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
     (value/fail! :session-not-running "Session has no foreground operation" {:session-id sid}))))

(defn follow-up!
  ([runtime sid content] (follow-up! runtime sid content {}))
  ([runtime sid content opts]
   (if-let [oid (:operation-id (foreground runtime sid))]
     (follow-up-operation! runtime oid content opts)
     (value/fail! :session-not-running "Session has no foreground operation" {:session-id sid}))))

(defn update-queue!
  "Updates one still-pending queue item while preserving its ID and order."
  [runtime sid qid content]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (let [result (store/update-queue! (:store runtime) sid qid
                                      (run/prompt-content content))]
      (emit-events! runtime (:events result))
      (:item result))))

(defn drop-queue!
  "Removes and returns one still-pending queue item."
  [runtime sid qid]
  (ensure-open! runtime)
  (locking (session-lock runtime sid)
    (let [result (store/drop-queue! (:store runtime) sid qid)]
      (emit-events! runtime (:events result))
      (:removed result))))

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
  (let [sid (:session-id (store/operation (:store runtime) oid))
        {:keys [operation thread]}
        (locking (session-lock runtime sid)
          (let [op (store/operation (:store runtime) oid)]
            (if (run/terminal-operation? op)
              {:operation op}
              (let [slot (foreground runtime sid)]
                (value/check! (= oid (:operation-id slot)) :operation-not-active
                              "Operation is not the session's current foreground operation"
                              {:operation-id oid :session-id sid
                               :current-operation-id (:operation-id slot)})
                (if-not @(:cancellable? slot)
                  {:operation op}
                  (let [record (if (= :cancelling (:status op))
                                 op
                                 (:operation
                                  (commit! runtime sid
                                           {:operation {:id oid :status :cancelling}
                                            :events [{:operation-id oid :type :operation/cancelling
                                                      :data {}}]})))]
                    (agents/pause! (:agents runtime) sid)
                    (reset! (:cancelled slot) true)
                    {:operation record :thread @(:thread slot)}))))))]
    (when (and thread (not (identical? thread (Thread/currentThread))))
      (.interrupt ^Thread thread))
    operation))

(defn cancel! [runtime sid]
  (if-let [oid (:operation-id (foreground runtime sid))]
    (cancel-operation! runtime oid)
    (do
      (store/session (:store runtime) sid)
      (agents/pause! (:agents runtime) sid)
      {:session-id sid :operation-id nil :status :idle})))


(defn wait!
  ([runtime oid] (wait! runtime oid nil))
  ([runtime oid timeout-ms]
   (if-let [slot (get @(:operations runtime) oid)]
     (let [finished (if (nil? timeout-ms)
                      @(:finished slot)
                      (deref (:finished slot) (long (max 0 timeout-ms)) ::timeout))]
       (if (= ::timeout finished)
         (store/operation (:store runtime) oid)
         @(:done slot)))
     (store/operation (:store runtime) oid))))

(defn evaluate!
  ([runtime sid source] (evaluate! runtime sid source {}))
  ([runtime sid source opts]
   (begin-blocking!
    runtime sid :evaluate
    (fn [slot]
      (set-phase! slot :evaluating)
      (let [registry (registry runtime sid)
            result (capabilities/evaluate!
                    registry source
                    {:cancelled? (:cancelled slot) :on-event (:on-event opts)
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
                    {:cancelled? (:cancelled slot) :on-event (:on-event opts)
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

(defn- await-operation! [slot deadline]
  (or (realized? (:finished slot))
      (let [remaining (remaining-close-millis deadline)]
        (and (pos? remaining)
             (not= ::timeout (deref (:finished slot) remaining ::timeout))))))

(defn- await-handles! [runtime deadline]
  (doseq [attempt (vals @(:handle-attempts runtime))]
    (let [remaining (remaining-close-millis deadline)]
      (when (pos? remaining)
        (deref (:done attempt) remaining nil))))
  (empty? @(:handle-attempts runtime)))

(defn- reconcile-settlements! [runtime]
  (doseq [[sid oid] @(:settlement-failures runtime)
          :let [slot (get @(:operations runtime) oid)]
          :when (and slot (realized? (:finished slot)))]
    (try
      (let [error (ex-info "Operation driver exited without a durable settlement"
                           {:error/code "settlement-interrupted" :operation-id oid})]
        (settle-operation! runtime sid slot :interrupted nil error)
        (swap! (:settlement-failures runtime) dissoc sid)
        (swap! (:operations runtime) dissoc oid)
        (swap! (:admission runtime) dissoc oid))
      (catch Throwable _ nil)))
  (empty? @(:settlement-failures runtime)))

(defn close!
  "Cancels owned work and closes resources only after every operation driver
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
                      (vec (vals @(:operations runtime))))
              current (Thread/currentThread)
              cancellation-errors (atom [])]
          (doseq [slot slots]
            (try
              (cancel-operation! runtime (:operation-id slot))
              (catch Throwable error
                (swap! cancellation-errors conj (value/error-map error))
                (reset! (:cancelled slot) true)
                (when-let [thread @(:thread slot)]
                  (when-not (identical? thread current)
                    (.interrupt ^Thread thread))))))
            ;; Graceful shutdown prevents an operation that invoked close! from
            ;; interrupting its own caller thread. Explicit cancellation above
            ;; still interrupts every other running driver.
          (let [agent-close (agents/close! (:agents runtime))]
          (jobs/stop! (:jobs runtime))
          (titles/stop! (:titles runtime))
          (.shutdown ^ExecutorService (:executor runtime))
          (let [timeout-ms (long (max 0 (or (:close-timeout-ms (:initial-settings runtime))
                                            10000)))
                deadline (+ (System/nanoTime) (* timeout-ms 1000000))
                self-slots (filterv #(identical? current @(:thread %)) slots)
                _ (doseq [slot slots
                          :when (not (some #(identical? slot %) self-slots))]
                    (await-operation! slot deadline))
                jobs-complete? (jobs/await-session! (:jobs runtime) nil (remaining-close-millis deadline))
                incomplete (filterv #(not (realized? (:finished %))) slots)
                settlements-complete? (reconcile-settlements! runtime)
                handles-ready? (await-handles! runtime deadline)
                foreground-complete? (empty? incomplete)
                executor-terminated?
                (if foreground-complete?
                  (try
                    (and (.awaitTermination ^ExecutorService (:executor runtime)
                                            (remaining-close-millis deadline)
                                            TimeUnit/MILLISECONDS)
                         (titles/await-closed! (:titles runtime) (remaining-close-millis deadline)))
                    (catch InterruptedException _
                      (.interrupt current)
                      false))
                  (.isTerminated ^ExecutorService (:executor runtime)))]
            (if-not (and foreground-complete? executor-terminated? jobs-complete?
                         settlements-complete? handles-ready? (= :closed (:status agent-close)))
              {:status :closing :already-closed? false
               :foreground-complete? foreground-complete?
               :active-operation-ids (mapv :operation-id incomplete)
               :jobs-complete? jobs-complete?
               :executor-terminated? executor-terminated?
               :store-closed? false :handles-closed? false
               :errors
               (into @cancellation-errors
                     (cond-> []
                       (not jobs-complete?)
                       (conj {:code "jobs-timeout" :message "Background jobs have not exited; live state retained"})
                       (not foreground-complete?)
                       (conj {:code "foreground-timeout"
                              :message "Foreground execution did not finish before the close deadline"})
                       (not handles-ready?)
                       (conj {:code "handle-timeout"
                              :message "Session evaluator activation or cleanup is still running"})
                       (not settlements-complete?)
                       (conj {:code "settlement-failed"
                              :message "A foreground outcome could not be durably reconciled"})
                       (not= :closed (:status agent-close))
                       (conj {:code "agents-timeout"
                              :message "Agent wake coordinator has not exited"})
                       (not executor-terminated?)
                       (conj {:code "executor-timeout"
                              :message "Operation executor did not terminate before the close deadline"})))}
              (let [errors (atom @cancellation-errors)
                    handles (mapv (fn [sid]
                                    (try (close-handle! runtime sid)
                                         (catch Throwable error
                                           (swap! errors conj (value/error-map error)) nil)))
                                  (distinct (concat (keys @(:handles runtime))
                                                    (keys @(:failed-handles runtime)))))
                    root (try (resources/close! (:resources runtime))
                              (catch Throwable error
                                (swap! errors conj (value/error-map error)) nil))]
                (if (or (seq @(:handles runtime)) (seq @(:failed-handles runtime))
                        (not= :closed (:status root)))
                  {:status :closing :already-closed? false
                   :foreground-complete? true :executor-terminated? true
                   :store-closed? false :handles-closed? false
                   :handles handles :resources root
                   :errors (into @errors (:errors root))}
                  (do
                    (.shutdown ^ExecutorService (:publisher runtime))
                    (let [published? (try (.awaitTermination ^ExecutorService (:publisher runtime)
                                                              (remaining-close-millis deadline)
                                                              TimeUnit/MILLISECONDS)
                                          (catch InterruptedException _
                                            (.interrupt current)
                                            false))]
                      (if-not published?
                        {:status :closing :store-closed? false :handles-closed? true
                         :foreground-complete? true :executor-terminated? true
                         :errors (conj @errors {:code "publisher-timeout"
                                                :message "Durable event publication has not exited"})}
                        (let [provider (try (provider/close! (:provider runtime))
                                            (catch Throwable error
                                              (swap! errors conj (value/error-map error)) nil))
                              store (try (store/close! (:store runtime))
                                         (catch Throwable error
                                           (swap! errors conj (value/error-map error)) nil))
                              closed? (empty? @errors)]
                          (when closed?
                            (reset! (:listeners runtime) {})
                            (reset! (:lifecycle runtime) :closed))
                          {:status (if closed? :closed :closing) :already-closed? false
                           :foreground-complete? true :executor-terminated? true
                           :handles handles :resources root :provider provider :store store
                           :errors @errors}))))))))))))))
