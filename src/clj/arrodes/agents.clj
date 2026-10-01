(ns arrodes.agents
  "Addressable session REPLs. Execution belongs to the runtime; messages belong to the store."
  (:refer-clojure :exclude [list])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [arrodes.artifacts :as artifacts]
            [arrodes.capabilities :as capabilities]
            [arrodes.platform :as util]
            [arrodes.run :as run]
            [arrodes.store :as store]
            [arrodes.store.agents :as store-agents]
            [arrodes.store.command :as command]
            [arrodes.store.db :as store-db]
            [arrodes.value :as value])
  (:import (java.util.concurrent Executors ExecutorService RejectedExecutionException TimeUnit)))

(defn- runtime [manager]
  (let [rt (:runtime manager)] (if (instance? clojure.lang.IDeref rt) @rt rt)))

(defn- call [manager key & args]
  (apply (get (:callbacks manager) key) args))

(defn- ensure-open! [manager]
  (value/check! (and manager (not @(:closed? manager))) :agents-unavailable
                "Agent coordination is closed" {}))

(defn- emit! [manager outcome]
  (when (seq (:events outcome)) (call manager :emit! (:events outcome)))
  outcome)

(defn- signal! [manager]
  (locking (:signal manager)
    (swap! (:version manager) inc)
    (.notifyAll ^Object (:signal manager))))

(defn create!
  "Create only the message/wakeup coordinator. Model workers stay runtime-owned."
  [rt callbacks]
  {:runtime rt :store (:store (if (instance? clojure.lang.IDeref rt) @rt rt))
   :callbacks callbacks :closed? (atom false) :signal (Object.) :version (atom 0)
   :launch-lock (Object.) :waiting (atom {}) :scheduled (atom #{})
   :executor (Executors/newSingleThreadExecutor)})

(defn install! [_manager registry]
  (binding [*ns* (the-ns (:namespace registry))]
    (alias 'agents 'arrodes.agents))
  registry)

(defn- measured-usage [entries]
  (reduce (fn [summary entry]
            (if (= :assistant (get-in entry [:data :message/role]))
              (let [usage (get-in entry [:data :message/provider-data :response/usage])
                    total (run/context-tokens usage)]
                (if (some? total)
                  (-> summary
                      (update :usage run/add-usage usage)
                      (update :usage-total (fnil + 0) total)
                      (update :usage-measured-calls inc))
                  (update summary :usage-unmeasured-calls inc)))
              summary))
          {:usage {} :usage-total nil :usage-measured-calls 0 :usage-unmeasured-calls 0}
          entries))

(defn- off-context? [db state]
  (if-let [parent-id (:parent-session-id state)]
    (let [parent (store-agents/agent-state db parent-id)]
      (or (not= (:parent-context-id state) (:context-id parent))
          (recur db parent)))
    false))

(defn- team-snapshot [manager sid opts]
  (store-db/store-read (:store manager)
    (fn [_]
      {:root-id (:root-id (store-agents/agent-state (:store manager) sid))
       :agents (mapv #(merge % (measured-usage (store/entries (:store manager) (:session-id %)))
                            {:off-context? (off-context? (:store manager) %)})
                     (store-agents/agent-team (:store manager) sid opts))
       :cursor (store/latest-event-seq (:store manager))})))

(defn list-agents
  "Atomic durable roster/cursor, enriched with matching live operation phases."
  [manager sid opts]
  (ensure-open! manager)
  (update (team-snapshot manager sid opts) :agents
    (fn [rows]
      (mapv (fn [row]
              (let [live (call manager :state (:session-id row))
                    op (:operation row)
                    phase (cond
                            (or (nil? op) (run/terminal-operation? op)) :idle
                            (= (:id op) (:operation-id live)) (:phase live)
                            :else :starting)]
                (assoc row :phase phase))) rows))))

(defn- target-id [manager sid target]
  (store-agents/agent-target-id (:store manager) sid
                         (if (map? target) (:session-id target) target)))

(defn inspect-agent [manager sid target]
  (let [id (target-id manager sid target)]
    (or (some #(when (= id (:session-id %)) %) (:agents (list-agents manager sid {:limit 500})))
        (value/fail! :agent-not-found "Agent is no longer available" {:session-id id}))))

(defn- wake-error! [manager sid error]
  (when-not @(:closed? manager)
    (try
      (emit! manager (store-agents/set-agent-paused! (:store manager) sid true))
      (let [root (:root-id (store-agents/agent-state (:store manager) sid))]
        (emit! manager (store/commit! (:store manager) root
                         {::command/events [{:type :agent/error
                                    :data {:session-id sid :root-id root
                                           :error (value/error-map error)}}]})))
      (catch Throwable _ nil))))

(defn- wake-team! [manager root]
  (when-not @(:closed? manager)
    (doseq [row (store-agents/agent-team (:store manager) root {:limit 500})
            :let [sid (:session-id row)]
            :when (and (not @(:closed? manager))
                       (store-agents/agent-wake? (:store manager) sid))]
      (try
        (call manager :with-session sid
          #(when (and (not @(:closed? manager))
                      (store-agents/agent-wake? (:store manager) sid)
                      (nil? (:operation-id (call manager :state sid))))
             (call manager :continue! sid {:automatic? true})))
        (catch Throwable error
          (when-not (contains? #{"session-busy" "operation-limit" "runtime-closed"
                                "agents-unavailable" "session-not-found"}
                              (str (:error/code (ex-data error))))
            (wake-error! manager sid error)))))))

(defn settled!
  "Wake waiters and reconsider durable pending work across all teams after capacity changes."
  [manager _sid]
  (when (and manager (not @(:closed? manager)))
    (signal! manager)
    (doseq [root (store-agents/agent-wake-roots (:store manager))]
      (let [schedule? (locking (:scheduled manager)
                        (when-not (contains? @(:scheduled manager) root)
                          (swap! (:scheduled manager) conj root)
                          true))]
        (when schedule?
          (try
            (.execute ^ExecutorService (:executor manager)
                      ^Runnable
                      (fn []
                        ;; A concurrent arrival/release schedules a later check
                        ;; instead of being lost behind this in-progress check.
                        (locking (:scheduled manager) (swap! (:scheduled manager) disj root))
                        (try (wake-team! manager root)
                             (catch Throwable error (wake-error! manager root error)))))
            (catch RejectedExecutionException _
              (locking (:scheduled manager) (swap! (:scheduled manager) disj root)))))))))

(defn pause! [manager sid]
  (when manager
    (emit! manager (store-agents/set-agent-paused! (:store manager) sid true))
    (signal! manager)))

(defn- resume-session!
  "Unpause an explicit foreground start. Does not compete with it for admission."
  [manager sid]
  (when manager
    (when (:stopped? (store-agents/agent-state (:store manager) sid))
      (emit! manager (store-agents/set-agent-stopped! (:store manager) sid false)))
    (emit! manager (store-agents/set-agent-paused! (:store manager) sid false))
    (signal! manager)))

(defn deliver! [manager sid operation-id]
  (if-not manager
    []
    (let [outcome (call manager :with-session sid
                       #(emit! manager (store-agents/deliver-agent-messages! (:store manager) sid operation-id)))]
      (when (seq (:delivered outcome)) (signal! manager))
      (vec (:entries outcome)))))

(defn start-agent!
  "Admit a fresh session and its first operation, without waiting for its model."
  [manager sid opts]
  (ensure-open! manager)
  (value/check! (and (map? opts)
                     (every? #{:name :task :context :config :submission-id} (keys opts)))
                :invalid-agent-options "start! accepts name, task, context, config and submission-id" {})
  (value/check! (and (string? (:task opts)) (not (str/blank? (:task opts))))
                :invalid-agent-task "An agent task must contain text" {})
  (value/check! (or (nil? (:context opts)) (string? (:context opts)))
                :invalid-agent-context "Agent context must be text" {})
  (value/check! (not (contains? #{"Main" "parent" "all"} (:name opts)))
                :invalid-agent-name "Main, parent and all are reserved addresses" {})
  (locking (:launch-lock manager)
    (util/check-cancelled! (:cancelled? capabilities/*invocation-context*))
    (let [source (store/session (:store manager) sid)
          settings (:initial-settings (runtime manager))
          oid (util/id)
          submission (or (:submission-id opts) (util/id))
          config (run/effective-config source (:config opts))
          prepared (merge opts
                          {:id (util/id) :operation-id oid :submission-id submission
                           :name (or (:name opts) (str "Agent-" (subs submission 0 8)))
                           :cwd (:cwd source) :config config
                           :limit (max 1 (min 128 (or (:agent-limit settings) 32)))
                           :max-depth (max 1 (min 16 (or (:agent-max-depth settings) 4)))
                           :origin (select-keys capabilities/*invocation-context*
                                                [:operation-id :job-id :call-id])})]
      (if (store-agents/agent-submission (:store manager) sid submission)
        (:handle (store-agents/create-agent! (:store manager) sid prepared))
        (do
          (call manager :reserve! oid)
          (let [created (atom nil)]
            (try
              (let [outcome (emit! manager (store-agents/create-agent! (:store manager) sid prepared))
                    handle (:handle outcome)]
                (reset! created handle)
                (if (:existing? outcome)
                  (call manager :release! oid)
                  (call manager :launch! (:session-id handle) (:operation-id handle) {:automatic? true}))
                (signal! manager)
                handle)
              (catch Throwable error
                (call manager :release! oid)
                (if-let [handle @created]
                  (let [operation (store/operation (:store manager) (:operation-id handle))]
                    (when-not (run/terminal-operation? operation)
                      (call manager :with-session (:session-id handle)
                        #(emit! manager
                           (store/commit! (:store manager) (:session-id handle)
                             {::command/session {:status :failed}
                              ::command/operation {:id (:operation-id handle) :status :failed
                                          :finished-at (util/now) :error (value/error-map error)}
                              ::command/events [{:type :operation/failed :operation-id (:operation-id handle)
                                        :data {:error (value/error-map error)}}]}))))
                    (settled! manager (:session-id handle))
                    (assoc handle :status :failed :error (value/error-map error)))
                  (throw error))))))))))

(defn send-message! [manager sid target content opts]
  (ensure-open! manager)
  (util/check-cancelled! (:cancelled? capabilities/*invocation-context*))
  (value/check! (and (map? opts) (every? #{:submission-id :wake? :kind} (keys opts))
                     (or (not (contains? opts :wake?)) (boolean? (:wake? opts)))
                     (contains? #{nil :peer :human} (:kind opts)))
                :invalid-agent-options "Invalid addressed message options" {})
  (let [target (if (map? target) (:session-id target) target)
        outcome (emit! manager
                       (store-agents/send-agent-message! (:store manager) sid target content
                                                  (assoc opts :submission-id (or (:submission-id opts) (util/id)))))]
    (settled! manager sid)
    (:receipt outcome)))

(defn messages-for [manager sid opts]
  (ensure-open! manager)
  (store-agents/agent-messages (:store manager) sid opts))

(declare environment)

(defn submission
  "Inspect a known submission ID after an uncertain response, without resending it."
  ([id] (let [[manager sid] (environment)] (submission manager sid id)))
  ([manager sid id]
   (ensure-open! manager)
   (store-agents/agent-submission (:store manager) sid id)))

(defn operation-result [manager sid target oid]
  (ensure-open! manager)
  (value/check! (and (string? oid) (not (str/blank? oid))) :invalid-agent-operation
                "Select a particular operation; a session's latest answer is not a stable result" {})
  (store-agents/agent-result (:store manager) sid (target-id manager sid target) oid))

(defn- inspection-options! [opts allowed]
  (value/check! (and (map? opts) (every? allowed (keys opts))
                     (or (not (contains? opts :detailed?)) (boolean? (:detailed? opts))))
                :invalid-agent-options "Unsupported inspection options or non-boolean detailed?" {})
  opts)

(defn- page-options! [opts]
  (let [limit (get opts :limit 8) offset (get opts :offset 0)]
    (value/check! (and (integer? limit) (<= 1 limit 20)
                       (integer? offset) (<= 0 offset))
                  :invalid-agent-page "Use limit 1..20 and a non-negative offset" {})
    (assoc opts :limit limit :offset offset)))

(defn- clip [text limit]
  (subs text 0 (min limit (count text))))

(defn- brief-row [db row]
  (reduce (fn [summary key]
            (let [text (some-> (get summary key) str)]
              (if (and text (> (count text) 160))
                (-> summary (dissoc key)
                    (assoc (keyword (str (name key) "-preview")) (clip text 160)
                           :identity-truncated? true))
                summary)))
          (assoc (select-keys row [:session-id :name :parent-session-id :provider :model
                                  :operation-id :paused? :stopped? :pending-count])
                 :status (or (:operation-status row) (:status row) :idle)
                 :off-context? (off-context? db row))
          [:provider :model]))

(defn list-page [manager sid opts]
  (ensure-open! manager)
  (inspection-options! opts #{:limit :offset :detailed?})
  (let [opts (page-options! opts)
        page (store-db/store-read (:store manager)
               (fn [_]
                 (update (store-agents/agent-summaries (:store manager) sid (dissoc opts :detailed?))
                         :agents #(mapv (partial brief-row (:store manager)) %))))]
    (if (:detailed? opts)
      (assoc page :agents (:agents (list-agents manager sid (dissoc opts :detailed?))))
      page)))

(defn inspect-view [manager sid target opts]
  (ensure-open! manager)
  (inspection-options! opts #{:detailed?})
  (if (:detailed? opts)
    (inspect-agent manager sid target)
    (let [id (target-id manager sid target)]
      (store-db/store-read (:store manager)
        (fn [_]
          (let [row (brief-row (:store manager)
                               (first (:agents (store-agents/agent-summaries (:store manager) sid
                                                                     {:target-id id :limit 1}))))
                handle (pr-str (select-keys row [:session-id :operation-id]))]
            (assoc row :next
                   (cond-> {:details (str "(agents/inspect " (pr-str id) " {:detailed? true})")}
                     (:operation-id row)
                     (assoc :result (str "(agents/result " handle ")"))
                     (and (:operation-id row) (not (run/terminal-operation? row)))
                     (assoc :wait (str "(agents/wait {:handles [" handle "] :timeout-ms 1000})"))))))))))

(defn- text-preview [content limit]
  (let [parts (cond (string? content) [content]
                    (sequential? content) (keep #(when (= :text (:part/type %)) (:text %)) content)
                    :else [])
        output (StringBuilder.)]
    (loop [parts (seq parts)]
      (if-let [text (first parts)]
        (let [remaining (- limit (.length output))
              retained (min remaining (count text))]
          (.append output ^String text 0 (int retained))
          (if (< retained (count text))
            {:text (str output) :truncated? true}
            (recur (next parts))))
        {:text (str output) :truncated? false}))))

(defn- brief-error [error]
  (when error
    (let [message (str (:message error)) code (:code error)]
      (cond-> {:message (clip message 240)}
        (or (keyword? code) (string? code)) (assoc :code (if (<= (count (str code)) 80) code (clip (str code) 80)))
        (or (> (count message) 240)
            (and (or (keyword? code) (string? code)) (> (count (str code)) 80)))
        (assoc :truncated? true)))))

(defn result-view [manager sid target oid opts]
  (inspection-options! opts #{:detailed?})
  (let [record (operation-result manager sid target oid)]
    (if (:detailed? opts)
      record
      (let [result (:result record)
            preview (text-preview (if (contains? result :message/content)
                                    (:message/content result) (:content result)) 1200)
            reference (:result result)
            handle (pr-str (select-keys record [:session-id :operation-id]))]
        (cond-> (assoc (select-keys record [:session-id :operation-id :status])
                       :next (cond-> {:details (str "(agents/result " handle " {:detailed? true})")}
                               (not (run/terminal-operation? record))
                               (assoc :wait (str "(agents/wait {:handles [" handle "] :timeout-ms 1000})"))
                               (and (:available? reference) (contains? #{:inline :artifact} (:kind reference)))
                               (assoc :value (str "(agents/value " (pr-str (:session-id record))
                                                  " " (:id reference) ")"))))
          (seq (:text preview)) (assoc :answer (:text preview))
          (:truncated? preview) (assoc :answer-truncated? true)
          (:error record) (assoc :error (brief-error (:error record)))
          (:id reference) (assoc :result-ref
                                 (assoc (select-keys reference [:id :kind :available?])
                                        :session-id (:session-id record))))))))

(defn- small-content? [content]
  (let [remaining (volatile! 400)
        charge! (fn [n] (vswap! remaining - n) (not (neg? @remaining)))]
    (letfn [(small? [x depth]
              (and (< depth 5) (charge! 4)
                   (cond
                     (or (nil? x) (boolean? x)) true
                     (string? x) (charge! (* 6 (count x)))
                     (or (keyword? x) (symbol? x)) (charge! (count (str x)))
                     (integer? x) (and (<= (.bitLength (biginteger x)) 256)
                                       (charge! (count (str x))))
                     (ratio? x) (and (small? (numerator x) (inc depth))
                                     (small? (denominator x) (inc depth)))
                     (instance? java.math.BigDecimal x)
                     (and (<= (.precision ^java.math.BigDecimal x) 80)
                          (<= -80 (.scale ^java.math.BigDecimal x) 80)
                          (charge! 100))
                     (number? x) (charge! 30)
                     (char? x) (charge! 8)
                     (instance? java.util.UUID x) (charge! 40)
                     (instance? java.util.Date x) (charge! 40)
                     (map? x) (and (<= (count x) 8)
                                   (every? (fn [[k v]] (and (small? k (inc depth)) (small? v (inc depth)))) x))
                     (or (vector? x) (set? x) (instance? clojure.lang.PersistentList x))
                     (and (<= (count x) 8) (every? #(small? % (inc depth)) x))
                     :else false)))]
      (small? content 0))))

(defn- message-summary [message]
  (let [content (:content message)
        recipients (:recipients message)
        summary (assoc (select-keys message [:id :seq :from :kind :operation-id])
                       :recipients (vec (take 4 recipients))
                       :recipient-count (count recipients))]
    (cond-> (if (small-content? content)
              (assoc summary :content content)
              (assoc summary :content-omitted? true
                             :preview (if (string? content)
                                        (clip content 240)
                                        {:kind (cond (map? content) :map (vector? content) :vector
                                                     (set? content) :set (list? content) :list
                                                     (number? content) :number :else :value)
                                         :count (when (coll? content) (count content))})))
      (and (string? content) (> (count content) 240)) (assoc :preview-truncated? true)
      (> (count recipients) 4) (assoc :recipients-truncated? true))))

(defn message-page [manager sid opts]
  (inspection-options! opts #{:limit :before :detailed?})
  (let [limit (:limit (page-options! (select-keys opts [:limit])))
        records (messages-for manager sid (assoc (select-keys opts [:before]) :limit (inc limit)))
        page (vec (take limit records))
        more? (> (count records) limit)]
    {:messages (if (:detailed? opts) page (mapv message-summary page))
     :next-before (when more? (:seq (peek page)))}))

(defn- receipt-id [receipt]
  (let [id (if (map? receipt) (:id receipt) receipt)]
    (value/check! (and (string? id) (not (str/blank? id))) :invalid-agent-receipt
                  "Use a send receipt or its message ID" {})
    id))

(defn delivery-state [manager sid receipt opts]
  (ensure-open! manager)
  (inspection-options! opts #{:limit :offset :detailed?})
  (let [id (receipt-id receipt)
        page (store-agents/agent-delivery (:store manager) sid id (page-options! opts))]
    (assoc page
           :deliveries (mapv (fn [delivery]
                               (cond-> delivery
                                 (:operation-id delivery)
                                 (assoc :next {:result (str "(agents/result "
                                                           (pr-str (select-keys delivery [:session-id :operation-id]))
                                                           ")")})))
                             (:deliveries page))
           :next (cond-> {}
                   (not (:detailed? opts))
                   (assoc :details (str "(agents/delivery " (pr-str id) " {:detailed? true})"))
                   (some #(or (= :pending (:status %))
                              (and (:operation-id %)
                                   (not (run/terminal-operation? {:status (:operation-status %)}))))
                         (:deliveries page))
                   (assoc :wait (str "(agents/wait {:receipts [" (pr-str id) "] :timeout-ms 1000})"))
                   (:next-offset page)
                   (assoc :page (str "(agents/delivery " (pr-str id) " "
                                     (pr-str (assoc opts :offset (:next-offset page))) ")"))))))

(defn read-value
  "Read portable retained data, never another session's live object."
  [manager sid target result-id]
  (ensure-open! manager)
  (let [target (target-id manager sid target)
        descriptor (artifacts/result (:store manager) target result-id)]
    (value/check! (:available? descriptor) :result-unavailable "Retained value is unavailable" {})
    (case (:kind descriptor)
      :inline (:value descriptor)
      :artifact (loop [offset 1 parts []]
                  (let [page (artifacts/read! (:store manager) target (:artifact-id descriptor)
                                             {:offset offset :limit 32768})
                        parts (conj parts (:content page))]
                    (if-let [next-offset (:next-offset page)]
                      (recur next-offset parts)
                      (edn/read-string (str/join "" parts)))))
      (value/fail! :live-result-unavailable "Live JVM objects cannot cross session boundaries"
                   {:session-id target :result-id result-id}))))

(defn cancel-agent! [manager sid target oid]
  (ensure-open! manager)
  (let [target (target-id manager sid target)
        live (call manager :state target)
        oid (or oid (:operation-id live))]
    (if oid
      (let [op (store/operation (:store manager) oid)]
        (value/check! (= target (:session-id op)) :operation-not-found
                      "Operation does not belong to the addressed session" {:operation-id oid})
        (if (run/terminal-operation? op)
          op
          (call manager :cancel! oid)))
      (do (pause! manager target)
          {:session-id target :operation-id nil :status :idle :paused? true}))))

(defn resume-agent! [manager sid target]
  (ensure-open! manager)
  (util/check-cancelled! (:cancelled? capabilities/*invocation-context*))
  (let [target (target-id manager sid target)]
    (emit! manager (store-agents/set-agent-stopped! (:store manager) target false))
    (resume-session! manager target)
    (settled! manager target)
    (inspect-agent manager sid target)))

(defn stop-agent! [manager sid target opts]
  (ensure-open! manager)
  (let [target (target-id manager sid target)
        timeout-ms (get opts :timeout-ms 10000)
        _ (value/check! (and (integer? timeout-ms) (<= 0 timeout-ms 300000))
                        :invalid-timeout "Stop timeout must be 0..300000 milliseconds" {})
        ids (locking (:launch-lock manager)
              (emit! manager (store-agents/set-agent-stopped! (:store manager) target true))
              (store-agents/agent-descendants (:store manager) target))
        deadline (+ (System/nanoTime) (* timeout-ms 1000000))
        remaining #(long (max 0 (quot (- deadline (System/nanoTime)) 1000000)))]
    (doseq [id ids]
      (pause! manager id)
      (when-let [oid (:operation-id (call manager :state id))] (call manager :cancel! oid))
      (call manager :cancel-jobs! id))
    (doseq [id ids]
      (when-let [oid (:operation-id (call manager :state id))]
        (when-not (= oid (:operation-id capabilities/*invocation-context*))
          (call manager :wait! oid (remaining)))))
    (let [calling-job-session (when (:job-id capabilities/*invocation-context*)
                                (:session-id capabilities/*invocation-context*))
          job-exits (into {}
                          (map (fn [id]
                                 ;; A job's ancestors cannot settle until this call
                                 ;; returns. Report stopping instead of awaiting them.
                                 [id (and (not= id calling-job-session)
                                          (call manager :await-jobs! id (remaining)))])
                               ids))
          active (filterv #(or (:operation-id (call manager :state %)) (not (get job-exits %))) ids)]
      {:session-id target :status (if (seq active) :stopping :stopped) :active active})))

(defn- cycle? [edges origin targets]
  (loop [pending (seq targets) visited #{}]
    (if-let [id (first pending)]
      (cond
        (= id origin) true
        (contains? visited id) (recur (next pending) visited)
        :else (recur (concat (next pending) (get edges id)) (conj visited id)))
      false)))

(defn- summary-team [manager sid]
  (store-db/store-read (:store manager)
    (fn [_]
      (loop [offset 0 rows []]
        (let [page (store-agents/agent-summaries (:store manager) sid {:offset offset :limit 20})
              rows (into rows (:agents page))]
          (if-let [next-offset (:next-offset page)]
            (recur next-offset rows)
            rows))))))

(defn- watch-operations! [manager wait-id origin handles]
  (when wait-id
    (locking (:waiting manager)
      (let [edges (reduce (fn [graph {:keys [operation-id targets]}]
                            (update graph operation-id (fnil into []) targets))
                          {} (vals (dissoc @(:waiting manager) wait-id)))
            targets (mapv :operation-id handles)]
        (value/check! (not (cycle? edges origin targets)) :agent-wait-cycle
                      "An operation cannot wait for itself or a cycle of waiting operations" {})
        (swap! (:waiting manager) assoc wait-id {:operation-id origin :targets targets})))))

(defn await-agents [manager sid opts]
  (ensure-open! manager)
  (value/check! (and (map? opts) (every? #{:handles :receipts :until :timeout-ms} (keys opts))
                     (not (and (contains? opts :handles) (contains? opts :receipts)))
                     (or (not (contains? opts :until)) (contains? opts :receipts)))
                :invalid-agent-options "wait accepts handles OR receipts, timeout-ms, and until for receipts" {})
  (let [timeout-ms (get opts :timeout-ms 300000)
        _ (value/check! (and (integer? timeout-ms) (<= 0 timeout-ms 300000))
                        :invalid-timeout "Wait timeout must be 0..300000 milliseconds" {})
        receipts? (contains? opts :receipts)
        explicit? (contains? opts :handles)
        until (get opts :until :completed)
        _ (value/check! (contains? #{:delivered :completed} until) :invalid-agent-wait
                        "Receipt waits support until :delivered or :completed" {})
        requested (if receipts? (:receipts opts) (:handles opts))
        _ (value/check! (or (not (or explicit? receipts?))
                           (and (vector? requested) (<= (count requested) 20)))
                        :invalid-agent-handles "Wait accepts a vector of at most 20 handles or receipts" {})
        receipt-ids (when receipts? (vec (distinct (map receipt-id requested))))
        rows (when-not (or explicit? receipts?) (summary-team manager sid))
        handles (cond
                  receipts? []
                  explicit?
                  (mapv (fn [handle]
                          (value/check! (and (map? handle) (:operation-id handle))
                                        :invalid-agent-handle "Wait requires session/operation handles" {})
                          (let [target (target-id manager sid handle)
                                op (store/operation (:store manager) (:operation-id handle))]
                            (value/check! (= target (:session-id op)) :operation-not-found
                                          "Operation belongs to another session" {})
                            {:session-id target :operation-id (:id op)})) requested)
                  :else
                  (vec (keep (fn [row]
                               (when (and (not= sid (:session-id row)) (:operation-id row)
                                          (not (run/terminal-operation? {:status (:operation-status row)})))
                                 (select-keys row [:session-id :operation-id]))) rows)))
        origin (:operation-id capabilities/*invocation-context*)
        wait-id (when origin (util/id))
        deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (try
      (loop []
        (ensure-open! manager)
        (util/check-cancelled! (:cancelled? capabilities/*invocation-context*))
        (let [version @(:version manager)
              receipts (mapv #(store-agents/agent-delivery (:store manager) sid %
                                                    {:internal? true :limit 500}) receipt-ids)
              deliveries (mapcat :deliveries receipts)
              mapped (vec (distinct (keep #(when (:operation-id %)
                                             (select-keys % [:session-id :operation-id])) deliveries)))
              _ (watch-operations! manager wait-id origin
                                    (if receipts? (if (= :completed until) mapped []) handles))
              ready (if receipts?
                      (vec (distinct
                             (keep #(when (and (= :delivered (:status %)) (:operation-id %)
                                                (or (= :delivered until)
                                                    (run/terminal-operation? {:status (:operation-status %)})))
                                      (select-keys % [:session-id :operation-id])) deliveries)))
                      (filterv #(run/terminal-operation? (store/operation (:store manager) (:operation-id %))) handles))
              superseded (vec (for [receipt receipts
                                    delivery (:deliveries receipt)
                                    :when (= :superseded (:status delivery))]
                                {:id (:id receipt) :session-id (:session-id delivery)}))
              reason (cond
                       (some #(= :steering (:kind %)) (store/pending (:store manager) sid)) :steering
                       (store-agents/pending-agent-messages? (:store manager) sid) :message
                       (seq ready) (if (and receipts? (= :delivered until)) :delivered :completed)
                       (seq superseded) :superseded
                       (if (or explicit? receipts?) (empty? requested)
                           (and (empty? handles) (= 1 (count rows)))) :nothing
                       (>= (System/nanoTime) deadline) :timeout)]
          (if reason
            (cond-> {:reason reason :ready (vec (take 20 ready))}
              (> (count ready) 20) (assoc :more-ready? true)
              (seq superseded) (assoc :superseded (vec (take 20 superseded)))
              (> (count superseded) 20) (assoc :more-superseded? true))
            (do
              (locking (:signal manager)
                (when (= version @(:version manager))
                  (.wait ^Object (:signal manager)
                         (long (max 1 (min 100 (quot (- deadline (System/nanoTime)) 1000000)))))))
              (recur)))))
      (finally
        (when wait-id (locking (:waiting manager) (swap! (:waiting manager) dissoc wait-id)))))))

(defn close! [manager]
  (when manager
    (reset! (:closed? manager) true)
    (signal! manager)
    (.shutdownNow ^ExecutorService (:executor manager))
    {:status (if (.awaitTermination ^ExecutorService (:executor manager) 10 TimeUnit/SECONDS)
               :closed :closing)}))

(defn- environment []
  (let [context capabilities/*invocation-context*
        manager (or (get-in context [:registry :agent-manager]) (get-in context [:runtime :agents]))]
    (value/check! manager :agents-unavailable "Agent functions require an active Arrodes REPL invocation" {})
    [manager (:session-id context)]))

(defn start!
  "Launch an independent session REPL. {:task text :name? :context? :config? :submission-id?}; returns a session/operation handle."
  [opts]
  (let [[manager sid] (environment)] (start-agent! manager sid opts)))

(defn send!
  "Accept durable peer input. Target a handle, session ID, team name, :parent or :all. Returns a receipt, not proof of model delivery."
  ([target content] (send! target content {}))
  ([target content opts]
   (value/check! (and (map? opts) (every? #{:submission-id :wake?} (keys opts)))
                 :invalid-agent-options "send! accepts submission-id and boolean wake?" {})
   (let [[manager sid] (environment)] (send-message! manager sid target content opts))))

(defn inspect
  "Compact native identity/status; {:detailed? true} explicitly loads configuration and diagnostics."
  ([target] (inspect target {}))
  ([target opts]
   (let [[manager sid] (environment)] (inspect-view manager sid target opts))))

(defn list
  "A compact page {:agents [...] :total n :next-offset n}. Default 8 rows, max 20; opts offset, limit, detailed?."
  ([] (list {}))
  ([opts] (let [[manager sid] (environment)] (list-page manager sid opts))))

(defn messages
  "A non-consuming bounded page {:messages [...] :next-before seq}. Small content stays native; use delivery with detailed? for one full message."
  ([] (messages {}))
  ([opts] (let [[manager sid] (environment)] (message-page manager sid opts))))

(defn delivery
  "Inspect a send receipt's recipient-to-operation associations without consuming it. Options: offset, limit (default 8, max 20), detailed? for full content."
  ([receipt] (delivery receipt {}))
  ([receipt opts]
   (let [[manager sid] (environment)] (delivery-state manager sid receipt opts))))

(defn wait
  "Wait on handles or send receipts. Receipt :until is :completed (default) or :delivered. Incoming messages/steering still interrupt; ready handles are bounded to 20."
  ([] (wait {}))
  ([opts] (let [[manager sid] (environment)] (await-agents manager sid opts))))

(defn result
  "Compact outcome for a particular operation, with at most 1200 answer characters. Pass {:detailed? true} for the original native outcome."
  ([handle] (result handle (:operation-id handle) {}))
  ([target operation-or-options]
   (if (map? operation-or-options)
     (result target (:operation-id target) operation-or-options)
     (result target operation-or-options {})))
  ([target operation-id opts]
   (let [[manager sid] (environment)] (result-view manager sid target operation-id opts))))

(defn value
  "Read a team's retained durable result by session/handle and session-local result ID. Reject live JVM values."
  [target result-id]
  (let [[manager sid] (environment)] (read-value manager sid target result-id)))

(defn cancel!
  "Cancel the handle's operation, or pause/cancel the addressed session's current operation. Jobs/children keep running."
  [target]
  (let [[manager sid] (environment)] (cancel-agent! manager sid target (:operation-id target))))

(defn stop!
  "Stop an explicit subtree, including its function jobs. Incomplete cleanup remains :stopping."
  ([target] (stop! target {}))
  ([target opts] (let [[manager sid] (environment)] (stop-agent! manager sid target opts))))

(defn resume!
  "Explicitly allow an agent to run again. The two-argument runtime form only unpauses an already admitted user run."
  ([target]
   (let [[manager sid] (environment)] (resume-agent! manager sid target)))
  ([manager sid] (resume-session! manager sid)))
