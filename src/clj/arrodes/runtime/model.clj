(ns arrodes.runtime.model
  "Provider continuation, ordered REPL calls, and safe context summarization."
  (:require [clojure.string :as str]
            [arrodes.agents :as agents]
            [arrodes.artifacts :as artifacts]
            [arrodes.capabilities :as capabilities]
            [arrodes.context-tree :as tree]
            [arrodes.provider :as provider]
            [arrodes.provider-repl :as repl-wire]
            [arrodes.resources :as resources]
            [arrodes.run :as run]
            [arrodes.session :as session]
            [arrodes.runtime.control :as control]
            [arrodes.runtime.handles :as handles]
            [arrodes.runtime.preparation :as preparation]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.db :as db]
            [arrodes.store.context-tree :as context]
            [arrodes.store.jobs :as store-jobs]
            [arrodes.platform :as util]
            [arrodes.store.records :as records]
            [arrodes.store.sql :as sql]
            [arrodes.summaries :as summaries]
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

(defn- background-context! [runtime sid slot]
  (let [config (:config (store/session (:store runtime) sid))]
    (when (and (= :open @(:lifecycle runtime)) (tree/enabled? config)
               (not (contains? @(:resetting runtime) sid)))
      (summaries/request! (:summaries runtime) sid config {:cancelled? (:cancelled slot)}))))

(defn- finish-context-boundary! [runtime sid]
  (when-not (tree/enabled? (:config (store/session (:store runtime) sid)))
    (summaries/cancel-session! (:summaries runtime) sid)
    (value/check! (summaries/await-session!
                   (:summaries runtime) sid
                   (long (or (:close-timeout-ms (:initial-settings runtime)) 10000)))
                  :summaries-still-running "History summarization has not exited; evaluator retained."
                  {:session-id sid})
    (swap! (:context-views runtime) dissoc sid)))

(defn- with-context-boundary! [runtime sid f]
  (let [failure (atom nil)]
    (try
      (f)
      (catch Throwable error
        (reset! failure error)
        (throw error))
      (finally
        (try
          (finish-context-boundary! runtime sid)
          (catch Throwable cleanup-error
            (if-let [error @failure]
              (.addSuppressed ^Throwable error cleanup-error)
              (throw cleanup-error))))))))

(defn- developer-text [manager config]
  (->> [repl-wire/instructions
        (when (tree/enabled? config) repl-wire/history-instructions)
        (resources/context manager) (:instructions config)]
       (remove str/blank?)
       (str/join "\n\n")))

(defn- exposed-prefix-end [path]
  ;; A final answer is an observed turn boundary. Everything after it remains
  ;; native on /continue, including an input whose first request failed.
  (inc (or (last (keep-indexed
                  (fn [index entry]
                    (when (and (= :message (:kind entry))
                               (= :assistant (get-in entry [:data :message/role]))
                               (not= :length (get-in entry [:data :message/provider-data :response/finish-reason]))
                               (not (seq (get-in entry [:data :message/tool-calls]))))
                      index))
                  path))
           -1)))

(defn- input-ids [entries]
  (into [] (comp (filter #(= :user (get-in % [:data :message/role])))
                (map :id)) entries))

(defn- new-working-context [runtime sid manager config prepared]
  (when (tree/enabled? config)
    (let [path (::preparation/prior-path prepared)
          boundary (or (::preparation/history-boundary prepared) (exposed-prefix-end path))
          suffix (into (subvec path boundary) (::preparation/committed-entries prepared))]
      (atom {:historical-entries (tree/source-entries (subvec path 0 boundary))
             :suffix-entries suffix :cursor (::preparation/cursor prepared)
             :head (or (:id (peek suffix)) (:id (peek path)))
             :path-ids (set (map :id (concat path (::preparation/committed-entries prepared))))
             :view-budget (:summary-view-bytes (tree/settings config))
             :prior-view-ids (get-in (store/session (:store runtime) sid)
                                     [:metadata :context/view-node-ids])
             :input-entry-ids (input-ids suffix) :developer (developer-text manager config)
             :ready nil :last-usage nil}))))

(defn- sync-working! [runtime sid working]
  (when working
    ;; Foreground ownership excludes branch movement. Read only newly committed
    ;; canonical entries, not the entire source graph at every provider step.
    (let [entries (db/store-read (:store runtime)
                    #(sql/query-sql % "SELECT * FROM entries WHERE session_id=? AND seq>? ORDER BY seq"
                                    [sid (:cursor @working)] records/entry-row))]
      (when (seq entries)
        (swap! working
               (fn [context]
                 (-> context
                     (update :suffix-entries into entries)
                     (update :input-entry-ids into (input-ids entries))
                     (update :path-ids into (map :id entries))
                     (assoc :cursor (:seq (peek entries)) :head (:id (peek entries))))))))
    @working))

(defn- publish-working-view! [runtime sid operation-id ready]
  (swap! (:context-views runtime) assoc sid
         {:nodes (mapv #(select-keys % [:id :start :count :first-entry-id :last-entry-id :text :bytes])
                       (:view ready))
          :bytes (:bytes ready) :budget (:budget (meta (:view ready)))
          :source-count (count (:entries ready)) :ready? true
          :fits? (:fits? (meta (:view ready))) :operation-id operation-id}))


(defn- ensure-working! [runtime sid slot config callback working]
  ;; A cancelled worker may still own its provider when a new explicit run
  ;; starts. Resume only after its actual exit, never by replacing the worker.
  (when (and working (nil? (:ready @working)))
    (let [previous-phase @(:phase slot)]
      (control/publish-phase! runtime sid slot :preparing-context callback)
      (try
        (let [ready
              (try
                (summaries/ensure-ready! (:summaries runtime) sid
                                         (:historical-entries @working)
                                         (assoc-in config [:settings :summary-view-bytes] (:view-budget @working))
                                         {:cancelled? (:cancelled slot)})
                (catch clojure.lang.ExceptionInfo error
                  (if (and (= "summary-cancelling" (:error/code (ex-data error)))
                           (not @(:cancelled slot)))
                    (do
                      (value/check!
                       (summaries/await-session! (:summaries runtime) sid
                                                 (:summary-timeout-ms (tree/settings config)))
                       :summaries-still-running "Previous history worker has not exited" {:session-id sid})
                      (util/check-cancelled! (:cancelled slot))
                      (summaries/ensure-ready! (:summaries runtime) sid
                                               (:historical-entries @working)
                                               (assoc-in config [:settings :summary-view-bytes] (:view-budget @working))
                                               {:cancelled? (:cancelled slot)}))
                    (throw error))))
              view (tree/restore-frontier (:view ready) (:entries ready) (:nodes ready)
                                          (:prior-view-ids @working) (:view-budget @working))
              ready (if (identical? view (:view ready))
                      ready
                      (let [text (tree/render-view view)]
                        (assoc ready :view view :text text :bytes (tree/utf8-bytes text))))]
          (swap! working assoc :ready ready :last-usage nil)
          (publish-working-view! runtime sid (:operation-id slot) ready))
        (finally
          (control/publish-phase! runtime sid slot
                                  (if (= :starting previous-phase) :provider previous-phase)
                                  callback))))))

(defn- native-suffix-messages [entries]
  ;; Tree history owns the prefix; legacy markers describe the canonical linear
  ;; projection, not this suffix. Keep their original records untouched.
  (session/context-messages (filterv #(not= :compaction (:kind %)) entries)))

(defn- runtime-context [runtime sid registry manager config working]
  (let [developer (if working (:developer @working) (developer-text manager config))
        messages (if working
                   (let [{:keys [ready suffix-entries]} @working]
                     (cond-> []
                       (seq (:entries ready))
                       (conj {:message/role :user :message/content (:text ready)})
                       true (into (native-suffix-messages suffix-entries))))
                   (store/context-messages (:store runtime) sid))
        messages (if (str/blank? developer)
                   (vec messages)
                   (into [{:message/role :developer :message/content developer}] messages))
        hook-context {:runtime runtime :session-id sid
                      :session (store/session (:store runtime) sid)}
        transformed (capabilities/apply-hooks registry :transform-context hook-context messages)]
    (value/check! (vector? transformed) :invalid-hook-result
                  "transform-context hooks must return a message vector" {})
    transformed))

(defn- session-cache [request sid]
  (update request :request/cache
          #(assoc (merge {:enabled? true} (or % {})) :scope-id sid)))

(defn- prepare-completion-request [runtime sid slot registry manager config working]
  (let [messages (runtime-context runtime sid registry manager config working)
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
      (:session result)))
  (background-context! runtime sid slot))

(defn- evaluate-calls! [runtime sid slot registry calls config callback working]
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
                   :context (cond-> {:runtime runtime :session-id sid
                                     :operation-id (:operation-id slot)
                                     :tool-selection (:tools config)}
                              working (assoc :history/context-entry-ids (:input-entry-ids @working)
                                             :history/head (:head @working)))})]
      (locking (control/session-lock runtime sid)
        (control/commit! runtime sid
                 {::command/entries [{:kind :message :data (repl-wire/result-message result)}]}))
      (sync-working! runtime sid working)
      (background-context! runtime sid slot))))

(defn- session-context-state [runtime sid]
  (locking (control/session-lock runtime sid)
    {:session (store/session (:store runtime) sid)
     :setting-revisions (get (some-> (:context-setting-revisions runtime) deref) sid {})}))

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
      {:intents items :peer? (boolean (seq peer-entries))
       :context-state (session-context-state runtime sid)})))

(defn- compact-linear! [runtime sid slot registry config instructions callback automatic?]
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

(defn- projection-bytes [context]
  (tree/utf8-bytes (pr-str (into [{:message/role :user :message/content (get-in context [:ready :text])}]
                                (repl-wire/messages (native-suffix-messages (:suffix-entries context)))))))

(defn- compact-tree! [runtime sid slot config callback automatic? working]
  (ensure-working! runtime sid slot config callback working)
  (let [before @working
        {:keys [entries view bytes]} (:ready before)
        ;; Ancestors may finish during this turn's native tool exchange. Refresh
        ;; only completed cache data; the frozen sources and suffix stay intact.
        nodes (context/nodes (:store runtime) sid)
        budget (max 1 (quot bytes 2))
        fitted (tree/fit-view view nodes (count entries) budget)
        text (tree/render-view fitted)
        coarser (assoc (:ready before) :nodes nodes :view fitted :text text :bytes (tree/utf8-bytes text))
        ;; Every native suffix entry belongs to the unfinished outer turn.
        ;; Steering is another input, not a safe completion/compaction boundary.
        candidate (when (< (:bytes coarser) bytes)
                    (assoc before :ready coarser))]
    (if (and candidate (< (projection-bytes candidate) (projection-bytes before)))
      (let [view (tree/fit-view (get-in candidate [:ready :view])
                                (get-in candidate [:ready :nodes])
                                (count (:historical-entries candidate)) (:view-budget before))
            ready (assoc (:ready candidate) :view view)
            candidate (assoc candidate :ready ready :last-usage nil)]
        (util/check-cancelled! (:cancelled slot))
        (reset! working candidate)
        (publish-working-view! runtime sid (:operation-id slot) ready)
        (locking (control/session-lock runtime sid)
          (let [snapshot (store/session (:store runtime) sid)]
            (control/commit!
             runtime sid
             {::command/session {:metadata (assoc (:metadata snapshot)
                                                  :context/view-node-ids (mapv :id view))}
              ::command/events [{:operation-id (:operation-id slot) :type :session/compacted
                                 :data {:automatic? automatic? :context-policy :summary-tree
                                        :bytes-before (projection-bytes before)
                                        :bytes-after (projection-bytes candidate)}}]})))
        {:context-policy :summary-tree :bytes-before (projection-bytes before)
         :bytes-after (projection-bytes candidate)})
      (when-not automatic?
        (value/fail! :nothing-to-compact
                     "The projected request has no smaller safe history view"
                     {:session-id sid})))))

(defn- compact-current! [runtime sid slot registry config instructions callback automatic? working]
  (if working
    (let [previous-phase @(:phase slot)]
      (control/publish-phase! runtime sid slot :compacting callback)
      (try (compact-tree! runtime sid slot config callback automatic? working)
           (finally (control/publish-phase! runtime sid slot previous-phase callback))))
    (compact-linear! runtime sid slot registry config instructions callback automatic?)))

(defn- context-recovery-error [message error]
  (ex-info (str message " Reduce the latest message or attached/tool content, or start a new session. "
                "Completed REPL effects have not been repeated.")
           {:error/code "context-limit-unresolved" :provider (:provider (ex-data error))}
           error))

(defn- complete-with-context-recovery! [runtime sid slot registry manager config callback working]
  (let [request (prepare-completion-request runtime sid slot registry manager config working)
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
                                              nil callback true working)
                            (catch Throwable summary-error
                              (util/check-cancelled! (:cancelled slot))
                              (throw (context-recovery-error "The provider rejected the context and compaction failed." summary-error))))]
            (when-not compacted
              (throw (context-recovery-error "The provider rejected the context, but no earlier turn can be compacted safely." error)))
            (util/check-cancelled! (:cancelled slot))
            (try
              {:response (complete-request! runtime sid slot config
                                            (prepare-completion-request runtime sid slot registry manager config working)
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

(defn- maybe-auto-compact! [runtime sid slot registry config callback usage working]
  (when (run/auto-compact? config (configured-model runtime sid config) usage)
    (boolean (compact-current! runtime sid slot registry config nil callback true working))))

(defn- deliver-job-results! [runtime sid working]
  (locking (control/session-lock runtime sid)
    (db/store-read (:store runtime)
      (fn [_]
        (let [records (store-jobs/pending-job-results (:store runtime) sid)
              path-ids (when (seq records)
                         (if working (:path-ids @working)
                             (set (map :id (store/active-path (:store runtime) sid)))))
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

(defn- turn-config [runtime sid config opts intents]
  (let [state (or (::current-context-state opts) (session-context-state runtime sid))
        stored (select-keys (get-in state [:session :config :settings]) tree/setting-keys)
        prior (::session-context-settings opts)
        revisions (:setting-revisions state)
        retained (into {} (filter (fn [[key _]]
                                   (and (= (get stored key ::absent) (get prior key ::absent))
                                        (= (get revisions key 0)
                                           (get (::session-context-setting-revisions opts) key 0)))))
                       (::context-settings opts))
        generation (update config :settings #(apply dissoc (or % {}) tree/setting-keys))
        fresh (run/effective-config (:session state) generation)
        fresh (update fresh :settings merge retained)]
    (preparation/provider-config runtime sid (run/config-with-intents fresh intents))))

(defn- steering-config [runtime sid config intents]
  (let [next-config (run/config-with-intents config intents)]
    ;; Context policy/settings change only at an outer turn boundary, not in the
    ;; middle of a native tool protocol or while a frozen prefix is in use.
    (preparation/provider-config
     runtime sid
     (update next-config :settings
             #(merge (apply dissoc (or % {}) tree/setting-keys)
                     (select-keys (:settings config) tree/setting-keys))))))

(defn run-loop! [runtime sid slot prompt opts]
  (with-context-boundary!
    runtime sid
    (fn []
      (let [prepared (preparation/validate!
                      (preparation/prepare-run! runtime sid slot prompt (:config opts)))
            {registry ::preparation/registry manager ::preparation/manager
             config ::preparation/config hook-context ::preparation/hook-context
             initial-intents ::preparation/initial-intents} prepared
            config (preparation/provider-config runtime sid (run/config-with-intents config initial-intents))
            max-steps (long (max 1 (min 256 (or (get-in config [:settings :max-steps]) 64))))
            callback (:on-event opts)]
        (loop [step 0 config config
               boundary-opts (assoc opts
                                    ::context-settings (select-keys (:settings config) tree/setting-keys)
                                    ::session-context-settings (::preparation/session-context-settings prepared)
                                    ::session-context-setting-revisions
                                    (::preparation/session-context-setting-revisions prepared))
               working (new-working-context runtime sid manager config prepared)]
          (value/check! (< step max-steps) :step-budget-exhausted
                        "Agent exceeded its configured continuation step budget" {:max-steps max-steps})
          (util/check-cancelled! (:cancelled slot))
          (sync-working! runtime sid working)
          (deliver-job-results! runtime sid working)
          (agents/deliver! (:agents runtime) sid (:operation-id slot))
          (sync-working! runtime sid working)
          (ensure-working! runtime sid slot config callback working)
          (let [usage (if working (:last-usage @working)
                          (run/latest-usage (store/active-path (:store runtime) sid)))
                _ (maybe-auto-compact! runtime sid slot registry config callback usage working)
                {:keys [response]} (complete-with-context-recovery!
                                    runtime sid slot registry manager config callback working)
                assistant (run/validate-assistant! (run/response->assistant response))
                calls (:message/tool-calls assistant)]
            (when working (swap! working assoc :last-usage (:response/usage response)))
            (when working
              (swap! (:context-views runtime) update sid assoc :usage (:response/usage response)))
            (commit-assistant! runtime sid slot assistant response)
            (sync-working! runtime sid working)
            (when (= :length (:response/finish-reason response))
              (value/fail! :output-truncated "Provider stopped because its output limit was reached"
                           {:message assistant}))
            (if (seq calls)
              (do
                (evaluate-calls! runtime sid slot registry calls config callback working)
                (let [{:keys [intents context-state]} (deliver-intents! runtime sid slot :tool-boundary)
                      desired (run/config-with-intents
                               (update config :settings merge (::context-settings boundary-opts)) intents)
                      delivered-settings (into #{} (mapcat #(keys (get-in % [:options :config :settings]))) intents)
                      stored-settings (get-in context-state [:session :config :settings])
                      prior-settings (reduce (fn [prior key]
                                               (if (contains? tree/setting-keys key)
                                                 (assoc prior key (get stored-settings key ::absent))
                                                 prior))
                                             (::session-context-settings boundary-opts) delivered-settings)
                      stored-revisions (:setting-revisions context-state)
                      prior-revisions (reduce (fn [prior key]
                                                (if (contains? tree/setting-keys key)
                                                  (assoc prior key (get stored-revisions key 0))
                                                  prior))
                                              (::session-context-setting-revisions boundary-opts)
                                              delivered-settings)]
                  (sync-working! runtime sid working)
                  (recur (inc step) (steering-config runtime sid config intents)
                         (assoc boundary-opts
                                ::context-settings (select-keys (:settings desired) tree/setting-keys)
                                ::session-context-settings prior-settings
                                ::session-context-setting-revisions prior-revisions)
                         working)))
              (let [{:keys [intents peer? context-state]} (deliver-intents! runtime sid slot :turn-boundary)]
                (finish-context-boundary! runtime sid)
                (if (or (seq intents) peer?)
                  (let [next-config (turn-config runtime sid config
                                               (assoc boundary-opts ::current-context-state context-state) intents)
                        path (store/active-path (:store runtime) sid)
                        next-prepared {::preparation/prior-path path
                                       ::preparation/committed-entries []
                                       ::preparation/cursor (:seq (peek path))}
                        next-opts (assoc boundary-opts
                                         ::context-settings (select-keys (:settings next-config) tree/setting-keys)
                                         ::session-context-settings
                                         (select-keys (get-in context-state [:session :config :settings])
                                                      tree/setting-keys)
                                         ::session-context-setting-revisions
                                         (:setting-revisions context-state))]
                    (recur (inc step) next-config next-opts
                           (new-working-context runtime sid manager next-config next-prepared)))
                  (let [final (capabilities/apply-hooks registry :after-run hook-context assistant)]
                    (value/check! (map? final) :invalid-hook-result
                                  "after-run hooks must return an assistant message" {})
                    (background-context! runtime sid slot)
                    final))))))))))

(defn compact-operation! [runtime sid slot opts]
  (with-context-boundary!
    runtime sid
    (fn []
      (let [prepared (preparation/validate!
                      (preparation/prepare-run! runtime sid slot nil (:config opts)))
            {registry ::preparation/registry manager ::preparation/manager
             config ::preparation/config} prepared
            ;; A manual request must retain the same unfinished outer turn as
            ;; /continue, even when steering added a later user-message boundary.
            working (new-working-context runtime sid manager config prepared)]
        (compact-current! runtime sid slot registry config (:instructions opts)
                          (:on-event opts) false working)))))

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
