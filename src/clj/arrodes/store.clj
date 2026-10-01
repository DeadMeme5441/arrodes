(ns arrodes.store
  "Transactional sessions, histories, queues, operations, and events."
  (:require [arrodes.session :as session-model]
            [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.command :as command]
            [arrodes.store.codec :as codec]
            [arrodes.store.db :as db]
            [arrodes.store.records :as records]
            [arrodes.store.routing :as routing]
            [arrodes.store.sql :as sql])
  (:import (java.sql Connection ResultSet)))

(defn session [store sid]
  (db/store-read store #(records/public-session (records/require-session % sid))))

(defn list-sessions
  ([store] (list-sessions store {}))
  ([store {:keys [cwd]}]
   (db/store-read store
     (fn [connection]
       (let [sql (str "SELECT s.*, (SELECT e.created_at FROM entries e "
                      "WHERE e.session_id = s.id AND e.kind = 'message' ORDER BY e.seq DESC LIMIT 1) AS last_message_at "
                      "FROM sessions s " (when cwd "WHERE s.cwd = ? ")
                      "ORDER BY s.updated_at DESC, s.id")]
         (sql/query-sql connection sql (if cwd [(util/canonical-path cwd)] [])
                    (fn [^ResultSet rs]
                      (assoc (records/public-session (records/session-row rs))
                             :last-message-at (some-> (.getObject rs "last_message_at") long)))))))))
(defn create-session! [store opts]
  (let [snapshot (session-model/new-snapshot (records/prepare-session-options opts))]
    (codec/require-uuid! (:id snapshot) :session-id)
    (value/check! (contains? records/statuses (:status snapshot)) :invalid-session "Invalid session status" {:status (:status snapshot)})
    (db/transact! store
      (fn [connection]
        (value/check! (nil? (records/find-session connection (:id snapshot))) :session-exists
                     "Session ID already exists" {:session-id (:id snapshot)})
        (records/insert-session! connection snapshot (:config snapshot))
        snapshot))))

(defn entries [store sid]
  (db/store-read store (fn [connection] (records/require-session connection sid) (records/all-entries connection sid))))

(defn active-path
  ([store sid]
   (db/store-read store
     (fn [connection]
       (let [snapshot (records/require-session connection sid)]
         (session-model/active-path (records/all-entries connection sid) (:head snapshot))))))
  ([store sid leaf]
   (db/store-read store
     (fn [connection]
       (records/require-session connection sid)
       (session-model/active-path (records/all-entries connection sid) leaf)))))

(defn context-messages [store sid]
  (session-model/context-messages (active-path store sid)))

(defn commit!
  "Atomically applies entries, session projection, queues, operation, and durable events."
  [store sid command]
  (command/validate! command)
  (db/transact! store
    (fn [connection]
      (let [current (records/require-session connection sid)
            expected (::command/expected-revision command)]
        (when (some? expected)
          (value/check! (integer? expected) :invalid-revision
                       "Expected revision must be an integer" {:expected expected})
          (value/check! (= (long expected) (:revision current)) :stale-revision
                       "Session revision has changed"
                       {:session-id sid :expected expected :actual (:revision current)}))
        (let [start-seq (long (or (sql/scalar connection "SELECT MAX(seq) FROM entries WHERE session_id = ?" [sid]) 0))
              raw-entries (vec (or (::command/entries command) []))
              committed
              (loop [remaining raw-entries, parent (:head current), seq (inc start-seq), result []]
                (if-let [raw (first remaining)]
                  (let [entry (merge {:id (util/id)
                                      :session-id sid
                                      :parent-id parent
                                      :created-at (util/now)} raw {:seq seq :session-id sid})]
                    (records/validate-entry! connection sid entry)
                    (value/check! (nil? (sql/scalar connection "SELECT 1 FROM entries WHERE id = ?" [(:id entry)]))
                                 :entry-exists "Entry ID already exists" {:entry-id (:id entry)})
                    (sql/execute-sql! connection
                                  "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                                  [(:id entry) sid (:parent-id entry) seq (name (:kind entry)) (codec/encode (:data entry)) (:created-at entry)])
                    (recur (next remaining) (:id entry) (inc seq) (conj result entry)))
                  result))
              implicit-head (if (seq committed) (:id (last committed)) (:head current))
              changes (assoc (or (::command/session command) {}) :head
                             (if (contains? (or (::command/session command) {}) :head)
                               (get-in command [::command/session :head]) implicit-head))
              _ (when-let [head (:head changes)]
                  (codec/require-uuid! head :head)
                  (value/check! (records/entry-exists? connection sid head) :invalid-branch
                               "Selected head is not part of this session" {:entry-id head}))
              project-config? (or (some #(= :config (:kind %)) committed)
                                  (contains? (or (::command/session command) {}) :head))
              projected-config
              (when project-config?
                (session-model/effective-config
                 (:arrodes.store/base-config current)
                 (session-model/active-path (records/all-entries connection sid) (:head changes))))
              changes (if (and project-config?
                               (not (contains? (or (::command/session command) {}) :config)))
                        (assoc changes :config projected-config)
                        changes)
              now (util/now)
              updated (-> (records/normalize-session-changes current changes)
                          (assoc :revision (inc (:revision current)) :updated-at now))
              _ (doseq [id (or (::command/queue-deliver command) [])]
                  (codec/require-uuid! id :queue-id)
                  (value/check! (= 1 (sql/execute-sql! connection "DELETE FROM queue WHERE id = ? AND session_id = ?" [id sid]))
                               :queue-item-not-found "Queue item does not exist" {:queue-id id :session-id sid}))
              queue-seq (volatile! (long (or (sql/scalar connection "SELECT MAX(seq) FROM queue WHERE session_id = ?" [sid]) 0)))
              _ (doseq [raw (or (::command/queue-enqueue command) [])]
                  (let [item (merge {:id (util/id) :options {} :created-at now} raw)]
                    (codec/require-uuid! (:id item) :queue-id)
                    (value/check! (contains? #{:steering :follow-up} (:kind item)) :invalid-queue-item
                                 "Invalid queue item kind" {:kind (:kind item)})
                    (value/check! (or (string? (:content item)) (vector? (:content item))) :invalid-queue-item
                                 "Queue content must be text or canonical parts" {})
                    (value/check! (map? (or (:options item) {})) :invalid-queue-item
                                 "Queue options must be a map" {})
                    (value/check! (and (integer? (:created-at item))
                                      (not (neg? (:created-at item))))
                                 :invalid-queue-item
                                 "Queue creation time must be a non-negative integer" {})
                    (vswap! queue-seq inc)
                    (sql/execute-sql! connection
                                  "INSERT INTO queue(id,session_id,seq,kind,content,options,created_at) VALUES(?,?,?,?,?,?,?)"
                                  [(:id item) sid @queue-seq (name (:kind item)) (codec/encode (:content item))
                                   (codec/encode (or (:options item) {})) (:created-at item)])))
              _ (doseq [id (::command/job-deliver command)]
                  (sql/execute-sql! connection "UPDATE jobs SET delivered=1 WHERE session_id=? AND id=?" [sid id]))
              operation (when-let [raw (::command/operation command)]
                          (let [prior (when-let [oid (:id raw)] (records/find-operation connection oid))]
                            (records/upsert-operation! connection (records/normalize-operation sid prior raw))))
              _ (records/update-session! connection updated)
              branch? (or (::command/agent-branch? command)
                          (and (contains? (or (::command/session command) {}) :head)
                               (not= (:head current) (:head changes))
                               (not (seq committed))))
              _ (when branch?
                  (routing/ensure-root-route! connection sid)
                  (sql/execute-sql! connection "UPDATE agent_routes SET context_id=? WHERE session_id=?"
                                [(util/id) sid])
                  (routing/supersede-deliveries! connection sid))
              entry-events (mapv #(records/insert-event! connection sid
                                                {:type :entry/committed :data {:entry %}})
                                 committed)
              events (into entry-events
                           (concat (map #(records/insert-event! connection sid %)
                                        (or (::command/events command) []))
                                   (when branch?
                                     [(routing/routing-event! connection (:root-id (routing/route connection sid))
                                                      sid :agent/changed {:reason :branch})])))
              completion-events (when operation (routing/completion! connection operation))]
          {:session (records/public-session updated)
           :entries committed
           :events (into events completion-events)
           :operation operation})))))

(defn configure!
  "Merges configuration changes and records the complete effective configuration."
  [store sid changes]
  (let [snapshot (session store sid)
        wrapped? (or (contains? changes :config) (contains? changes :name) (contains? changes :metadata))
        config-changes (if wrapped? (or (:config changes) {}) (dissoc changes :expected-revision))
        config (session-model/normalize-config (value/deep-merge (:config snapshot) config-changes))
        session-changes (cond-> {:config config}
                          (and wrapped? (contains? changes :name)) (assoc :name (:name changes))
                          (and wrapped? (contains? changes :metadata))
                          (assoc :metadata (merge (:metadata snapshot) (:metadata changes))))
        session-changes (cond-> session-changes
                          (and wrapped? (contains? changes :name))
                          (assoc :metadata (assoc (merge (:metadata snapshot) (:metadata changes)) :title/source :user)))]
    (commit! store sid {::command/expected-revision (if (contains? changes :expected-revision)
                                             (:expected-revision changes)
                                             (:revision snapshot))
                        ::command/entries [{:kind :config :data config}]
                        ::command/session session-changes
                        ::command/events (cond-> [{:type :session/configured :data {:config config}}]
                                  (contains? changes :name)
                                  (conj {:type :session/named :data {:name (:name changes) :source :user}}))})))

(defn branch!
  "Selects a prior entry and appends explicit errors for tool calls cut by the boundary."
  [store sid leaf opts]
  (let [selection (db/store-read store
                    (fn [connection]
                      (let [row (records/require-session connection sid)
                            path (session-model/active-path (records/all-entries connection sid) leaf)]
                        {:revision (:revision row)
                         :path path
                         :config (session-model/effective-config (:arrodes.store/base-config row) path)})))
        boundary (records/tool-boundary-entries (:path selection))
        boundary (if (seq boundary)
                   (assoc-in boundary [0 :parent-id] leaf)
                   boundary)
        session-changes (cond-> {:config (:config selection) :status :idle}
                          (empty? boundary) (assoc :head leaf))]
    (commit! store sid {::command/expected-revision (or (:expected-revision opts) (:revision selection))
                        ::command/agent-branch? true
                        ::command/entries boundary
                        ::command/session session-changes
                        ::command/events [{:type :session/branched :data {:entry-id leaf}}]})))
(defn delete-session! [store sid]
  (db/transact! store
    (fn [connection]
      (records/require-session connection sid)
      (value/check! (nil? (sql/scalar connection
                                  "SELECT 1 FROM agent_routes WHERE parent_session_id=? LIMIT 1"
                                  [sid])) :agent-descendants-exist
                    "Delete linked child sessions first" {:session-id sid})
      (sql/execute-sql! connection "DELETE FROM agent_deliveries WHERE recipient_id=?" [sid])
      (sql/execute-sql! connection "DELETE FROM agent_submissions WHERE source_id=?" [sid])
      (sql/execute-sql! connection "DELETE FROM agent_routes WHERE session_id=?" [sid])
      (sql/execute-sql! connection "DELETE FROM sessions WHERE id = ?" [sid])
      {:deleted sid})))

(defn- queue-row [^ResultSet rs]
  {:id (.getString rs "id")
   :session-id (.getString rs "session_id")
   :seq (.getLong rs "seq")
   :kind (keyword (.getString rs "kind"))
   :content (codec/decode (.getString rs "content"))
   :options (codec/decode (.getString rs "options"))
   :created-at (.getLong rs "created_at")})

(defn- require-queue-item [^Connection connection sid qid]
  (codec/require-uuid! qid :queue-id)
  (or (first (sql/query-sql connection
                        "SELECT * FROM queue WHERE id = ? AND session_id = ?"
                        [qid sid] queue-row))
      (value/fail! :queue-item-not-found
                   "Queue item does not exist or was already delivered"
                   {:queue-id qid :session-id sid})))

(defn update-queue!
  "Atomically updates one pending item's content without changing its identity or position."
  [store sid qid content]
  (db/transact! store
    (fn [connection]
      (let [current (records/require-session connection sid)
            item (require-queue-item connection sid qid)
            _ (value/check! (or (string? content)
                                (and (vector? content) (every? map? content)))
                            :invalid-queue-item
                            "Queue content must be text or canonical parts" {})
            updated-item (assoc item :content content)
            now (util/now)
            updated-session (assoc current
                                   :revision (inc (:revision current))
                                   :updated-at now)
            _ (value/check! (= 1 (sql/execute-sql! connection
                                               "UPDATE queue SET content = ? WHERE id = ? AND session_id = ?"
                                               [(codec/encode content) qid sid]))
                            :queue-item-not-found
                            "Queue item does not exist or was already delivered"
                            {:queue-id qid :session-id sid})
            _ (records/update-session! connection updated-session)
            event (records/insert-event! connection sid
                                 {:type :queue/updated
                                  :data {:item updated-item}
                                  :time now})]
        {:session (records/public-session updated-session)
         :item updated-item
         :events [event]}))))

(defn drop-queue!
  "Atomically removes one pending item and returns the exact removed value."
  [store sid qid]
  (db/transact! store
    (fn [connection]
      (let [current (records/require-session connection sid)
            item (require-queue-item connection sid qid)
            now (util/now)
            updated-session (assoc current
                                   :revision (inc (:revision current))
                                   :updated-at now)
            _ (value/check! (= 1 (sql/execute-sql! connection
                                               "DELETE FROM queue WHERE id = ? AND session_id = ?"
                                               [qid sid]))
                            :queue-item-not-found
                            "Queue item does not exist or was already delivered"
                            {:queue-id qid :session-id sid})
            _ (records/update-session! connection updated-session)
            event (records/insert-event! connection sid
                                 {:type :queue/removed
                                  :data {:id qid}
                                  :time now})]
        {:session (records/public-session updated-session)
         :removed item
         :events [event]}))))

(defn pending [store sid]
  (db/store-read store
    (fn [connection]
      (records/require-session connection sid)
      (sql/query-sql connection "SELECT * FROM queue WHERE session_id = ? ORDER BY seq" [sid] queue-row))))

(defn operation [store oid]
  (db/store-read store
    (fn [connection]
      (codec/require-uuid! oid :operation-id)
      (or (records/find-operation connection oid)
          (value/fail! :operation-not-found "Operation does not exist" {:operation-id oid})))))

(defn operations
  ([store] (operations store {}))
  ([store {:keys [session-id]}]
   (db/store-read store
     (fn [connection]
       (when session-id (records/require-session connection session-id))
       (if session-id
         (sql/query-sql connection "SELECT * FROM operations WHERE session_id = ? ORDER BY created_at DESC, id" [session-id] records/operation-row)
         (sql/query-sql connection "SELECT * FROM operations ORDER BY created_at DESC, id" [] records/operation-row))))))

(defn- event-row [^ResultSet rs]
  (cond-> {:id (.getString rs "id")
           :seq (.getLong rs "seq")
           :type (codec/decode (.getString rs "type"))
           :data (codec/decode (.getString rs "data"))
           :time (.getLong rs "time")}
    (.getString rs "session_id") (assoc :session-id (.getString rs "session_id"))
    (.getString rs "operation_id") (assoc :operation-id (.getString rs "operation_id"))))

(defn events-since [store {:keys [after session-id limit] :or {after 0 limit 1000}}]
  (value/check! (and (integer? after) (not (neg? after)) (<= after Long/MAX_VALUE))
               :invalid-event-cursor "Event cursor must be a non-negative 64-bit integer"
               {:after after})
  (value/check! (and (integer? limit) (pos? limit))
               :invalid-limit "Event limit must be a positive integer" {:limit limit})
  (let [limit (long (min 10000 limit))]
    (db/store-read store
      (fn [connection]
        (when session-id (records/require-session connection session-id))
        (if session-id
          (sql/query-sql connection "SELECT * FROM events WHERE seq > ? AND session_id = ? ORDER BY seq LIMIT ?"
                     [(long after) session-id limit] event-row)
          (sql/query-sql connection "SELECT * FROM events WHERE seq > ? ORDER BY seq LIMIT ?"
                     [(long after) limit] event-row))))))

(defn latest-event-seq [store]
  (db/store-read store #(long (or (sql/scalar % "SELECT MAX(seq) FROM events" []) 0))))
