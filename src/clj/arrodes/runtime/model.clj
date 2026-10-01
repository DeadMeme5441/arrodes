(ns arrodes.runtime.model
  "Provider continuation, ordered REPL calls, and safe context summarization."
  (:require [clojure.string :as str]
            [arrodes.agents :as agents]
            [arrodes.artifacts :as artifacts]
            [arrodes.capabilities :as capabilities]
            [arrodes.provider :as provider]
            [arrodes.provider-repl :as repl-wire]
            [arrodes.resources :as resources]
            [arrodes.run :as run]
            [arrodes.runtime.control :as control]
            [arrodes.runtime.handles :as handles]
            [arrodes.runtime.preparation :as preparation]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.db :as db]
            [arrodes.store.jobs :as store-jobs]
            [arrodes.platform :as util]
            [arrodes.value :as value]))

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
                         (control/transient-event! runtime sid (:operation-id slot)
                                           :provider-event event callback)))
            outcome (try
                      {:response
                       (provider/complete! (:provider (handles/handle! runtime sid)) request
                                           {:provider (:provider config)
                                            :on-event on-event :cancelled? cancelled?})}
                      (catch Throwable error {:error error}))]
        (if-let [error (:error outcome)]
          (if (and (< attempt max-retries) (not @visible?)
                   (not (run/context-overflow? error))
                   (run/retryable-error? error) (not (cancelled?)))
            (do
              (control/transient-event! runtime sid (:operation-id slot) :provider-retry
                                {:attempt (inc attempt) :error (value/error-map error)} callback)
              (Thread/sleep (long (min 2000 (* 200 (bit-shift-left 1 attempt)))))
              (recur (inc attempt)))
            (throw (ex-info (ex-message error)
                            (assoc (ex-data error) :provider/output-started? @visible?) error)))
          (:response outcome))))))

(defn- runtime-context [runtime sid registry manager config]
  (let [project (resources/context manager)
        instructions (:instructions config)
        developer (->> [repl-wire/instructions project instructions]
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
  (control/set-phase! slot :provider)
  (provider-complete! runtime sid slot config request callback))

(defn- commit-assistant! [runtime sid slot assistant response]
  (locking (control/session-lock runtime sid)
    (util/check-cancelled! (:cancelled slot))
    (let [result (control/commit! runtime sid
                          {::command/entries [{:kind :message :data assistant}]
                           ::command/events [{:operation-id (:operation-id slot)
                                     :type :message/assistant
                                     :data {:message assistant
                                            :usage (:response/usage response)}}]})]
      (swap! (:usage slot) run/add-usage (:response/usage response))
      (:session result))))

(defn- evaluate-calls! [runtime sid slot registry calls config callback]
  (control/set-phase! slot :evaluating)
  ;; Evaluations share bindings and therefore run in response order. Concurrency
  ;; inside an evaluation is Clojure composition, not a second host scheduler.
  (doseq [call calls]
    (util/check-cancelled! (:cancelled slot))
    (let [{:keys [id source]} (repl-wire/evaluation call)
          result (capabilities/evaluate!
                  registry source
                  {:id id :cancelled? (:cancelled slot) :on-event callback
                   :on-progress #(control/transient-event! runtime sid (:operation-id slot)
                                                   :tool-progress % callback)
                   :context {:runtime runtime :session-id sid
                             :operation-id (:operation-id slot)
                             :tool-selection (:tools config)}})]
      (locking (control/session-lock runtime sid)
        (control/commit! runtime sid
                 {::command/entries [{:kind :message :data (repl-wire/result-message result)}]})))))

(defn- deliver-intents! [runtime sid slot phase]
  (locking (control/session-lock runtime sid)
    (util/check-cancelled! (:cancelled slot))
    (let [items (run/select-intents (store/pending (:store runtime) sid) phase)
          peer-entries (agents/deliver! (:agents runtime) sid (:operation-id slot))]
      (if (seq items)
        (control/commit! runtime sid
                 {::command/entries (run/intent-entries items)
                  ::command/queue-deliver (mapv :id items)
                  ::command/events [{:operation-id (:operation-id slot)
                            :type :queue/delivered
                            :data {:ids (mapv :id items) :phase phase}}]})
        (when (and (= phase :turn-boundary) (not (seq peer-entries)))
          (reset! (:accepting-input? slot) false)
          (control/set-phase! slot :settling)))
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
        (control/publish-phase! runtime sid slot :compacting callback)
        (try
          (let [hook-context {:runtime runtime :session-id sid
                              :operation-id (:operation-id slot)
                              :session (store/session (:store runtime) sid)
                              :compaction? true}
                request (-> (capabilities/apply-hooks
                             registry :transform-request hook-context
                             (run/summary-request config (:summary-entries plan) instructions))
                            (session-cache (str sid ":compaction")))
                _ (value/check! (map? request) :invalid-hook-result
                                "transform-request hooks must return a request map" {})
                response (provider-complete! runtime sid slot config request callback
                                             {:publish-stream? false})
                summary (run/response-text response)]
            (value/check! (not (str/blank? summary)) :empty-compaction
                          "Provider returned an empty compaction summary" {})
            (value/check! (not= :length (:response/finish-reason response)) :compaction-truncated
                          "Compaction summary was truncated by the provider" {})
            (locking (control/session-lock runtime sid)
              (util/check-cancelled! (:cancelled slot))
              (control/commit! runtime sid
                       {::command/entries [{:kind :compaction
                                   :data {:summary summary
                                          :first-kept-entry-id (:first-kept-entry-id plan)
                                          :usage (:response/usage response)
                                          :cost (:response/cost response)}}]
                        ::command/events [{:operation-id (:operation-id slot)
                                  :type :session/compacted
                                  :data {:automatic? automatic?
                                         :first-kept-entry-id (:first-kept-entry-id plan)
                                         :usage (:response/usage response)
                                         :cost (:response/cost response)}}]}))
            {:summary summary :first-kept-entry-id (:first-kept-entry-id plan)
             :usage (:response/usage response)})
          (finally (control/publish-phase! runtime sid slot previous-phase callback)))))))

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
                                            callback)}
              (catch Throwable retry-error
                (if (run/context-overflow? retry-error)
                  (throw (context-recovery-error "The provider still rejects the context after one compaction retry." retry-error))
                  (throw retry-error))))))
        (throw error))
      attempt)))

(defn- configured-model [runtime sid config]
  (provider/model (:provider (handles/handle! runtime sid))
                  (:provider config)
                  (:model config)))

(defn- maybe-auto-compact! [runtime sid slot registry config callback usage]
  (when (run/auto-compact? config (configured-model runtime sid config)
                           usage)
    (boolean (compact-current! runtime sid slot registry config nil callback true))))

(defn- deliver-job-results! [runtime sid]
  (locking (control/session-lock runtime sid)
    (db/store-read (:store runtime)
      (fn [_]
        (let [records (store-jobs/pending-job-results (:store runtime) sid)
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
            (control/commit! runtime sid {::command/entries entries ::command/job-deliver (mapv :id records)})))))))

(defn run-loop! [runtime sid slot prompt opts]
  (let [{registry ::preparation/registry manager ::preparation/manager
         config ::preparation/config hook-context ::preparation/hook-context
         initial-intents ::preparation/initial-intents}
        (preparation/validate!
         (preparation/prepare-run! runtime sid slot prompt (:config opts)))
        config (preparation/provider-config runtime sid (run/config-with-intents config initial-intents))
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
            _ (maybe-auto-compact!
               runtime sid slot registry config callback (run/latest-usage path))
            {:keys [response]} (complete-with-context-recovery! runtime sid slot registry manager config callback)
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
                  next-config (preparation/provider-config runtime sid
                                               (run/config-with-intents config intents))]
              (recur (inc step) next-config)))
          (let [{:keys [intents peer?]} (deliver-intents! runtime sid slot :turn-boundary)]
            (if (or (seq intents) peer?)
              (let [next-config (preparation/provider-config runtime sid
                                                 (run/config-with-intents config intents))]
                (recur (inc step) next-config))
              (let [final (capabilities/apply-hooks registry :after-run hook-context assistant)]
                (value/check! (map? final) :invalid-hook-result
                              "after-run hooks must return an assistant message" {})
                final))))))))

(defn compact-operation! [runtime sid slot opts]
  (let [{registry ::preparation/registry config ::preparation/config}
        (preparation/validate! (preparation/prepare-run! runtime sid slot nil (:config opts)))]
    (compact-current! runtime sid slot registry config (:instructions opts)
                      (:on-event opts) false)))

(defn summarize-branch! [runtime sid slot leaf abandoned source opts]
     (let [{registry ::preparation/registry config ::preparation/config}
        (preparation/validate! (preparation/prepare-run! runtime sid slot nil (:config opts)))
           hook-context {:runtime runtime :session-id sid
                         :operation-id (:operation-id slot)
                         :session (store/session (:store runtime) sid)
                         :branch-summary? true}
           request (-> (capabilities/apply-hooks
                        registry :transform-request hook-context
                        (run/summary-request config abandoned (:instructions opts)))
                       (session-cache (str sid ":branch-summary")))
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
       (locking (control/session-lock runtime sid)
         (util/check-cancelled! (:cancelled slot))
         (reset! (:cancellable? slot) false)
         (let [branched (store/branch! (:store runtime) sid leaf
                                       (dissoc opts :expected-revision :summarize?))
               _ (control/emit-events! runtime (:events branched))]
           (control/commit! runtime sid
                    {::command/entries [{:kind :branch-summary
                                :data {:summary summary
                                       :from-id (get-in source [:snapshot :head])
                                       :usage (:response/usage response)
                                       :cost (:response/cost response)}}]
                     ::command/events [{:operation-id (:operation-id slot)
                               :type :session/branch-summarized
                               :data {:from-id (get-in source [:snapshot :head])
                                      :entry-id leaf
                                      :usage (:response/usage response)
                                      :cost (:response/cost response)}}]})))
       {:summary summary :usage (:response/usage response)}))
