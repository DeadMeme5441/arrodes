(ns arrodes.store.agents
  "Session-backed team membership, submissions, and message delivery."
  (:require [clojure.string :as str]
            [arrodes.session :as session-model]
            [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.db :as db]
            [arrodes.store.records :as records]
            [arrodes.store.routing :as routing]
            [arrodes.store.sql :as sql])
  (:import (java.sql ResultSet)))

(defn- route-ancestors [connection sid]
  (loop [current (routing/route connection sid), visited #{}, rows []]
    (value/check! (not (contains? visited (:session-id current))) :invalid-agent-route
                  "Agent ancestry contains a cycle" {:session-id sid})
    (let [rows (conj rows current)]
      (if-let [parent (:parent-session-id current)]
        (recur (routing/route connection parent) (conj visited (:session-id current)) rows)
        rows))))

(defn- active-route! [connection sid]
  (let [ancestors (route-ancestors connection sid)]
    (value/check! (not-any? :stopped? ancestors) :agent-stopped
                  "Agent tree is stopped" {:session-id sid})
    (doseq [[child parent] (partition 2 1 ancestors)]
      (value/check! (= (:parent-context-id child) (:context-id parent))
                    :agent-stale-context "Agent belongs to a previous parent context"
                    {:session-id (:session-id child) :parent-session-id (:session-id parent)}))
    (first ancestors)))

(defn agent-state [store sid]
  (db/store-read store #(routing/public-route (routing/route % sid))))

(defn- agent-descendants* [connection sid]
  (records/require-session connection sid)
  (loop [front (conj clojure.lang.PersistentQueue/EMPTY sid), seen #{}, result []]
    (if-let [current (peek front)]
      (if (contains? seen current)
        (recur (pop front) seen result)
        (let [children (sql/query-sql connection
                                  "SELECT session_id FROM agent_routes WHERE parent_session_id=? ORDER BY name,session_id"
                                  [current] #(.getString ^ResultSet % "session_id"))]
          (recur (into (pop front) children) (conj seen current) (conj result current))))
      result)))

(defn agent-descendants [store sid]
  (db/store-read store #(agent-descendants* % sid)))

(defn- page-limit [limit default-limit]
  (let [limit (or limit default-limit)]
    (value/check! (and (integer? limit) (<= 1 limit 500)) :invalid-arguments
                  "Page limit must be between 1 and 500" {:limit limit})
    limit))

(defn- compact-agent-page [opts]
  (let [limit (or (:limit opts) 8)
        offset (or (:offset opts) 0)]
    (value/check! (and (integer? limit) (<= 1 limit 20)) :invalid-arguments
                  "Page limit must be between 1 and 20" {:limit limit})
    (value/check! (and (integer? offset) (not (neg? offset))) :invalid-arguments
                  "Page offset must be non-negative" {:offset offset})
    [limit offset]))

(defn agent-summaries [store sid opts]
  (db/store-read store
    (fn [connection]
      (let [root (:root-id (routing/route connection sid))
            target-id (:target-id opts)
            _ (when target-id
                (value/check! (= root (:root-id (routing/route connection target-id)))
                              :agent-target-forbidden "Cannot inspect a foreign team" {}))
            [limit offset] (compact-agent-page opts)
            total (if target-id 1
                      (max 1 (long (or (sql/scalar connection
                                               "SELECT COUNT(*) FROM agent_routes WHERE root_id=?"
                                               [root]) 0))))
            rows (sql/query-sql
                  connection
                  (str "SELECT s.id AS session_id,COALESCE(r.name,'Main') AS agent_name,"
                       "r.parent_session_id,r.parent_context_id,"
                       "COALESCE(r.context_id,s.id) AS context_id,"
                       "r.paused,r.stopped,s.config,s.status AS session_status,"
                       "o.id AS operation_id,o.status AS operation_status,"
                       "(SELECT COUNT(*) FROM agent_deliveries d "
                       "WHERE d.recipient_id=s.id AND d.status='pending' "
                       "AND d.context_id=COALESCE(r.context_id,s.id)) AS pending_count "
                       "FROM sessions s LEFT JOIN agent_routes r ON r.session_id=s.id "
                       "LEFT JOIN operations o ON o.id=(SELECT latest.id FROM operations latest "
                       "WHERE latest.session_id=s.id ORDER BY latest.created_at DESC,latest.rowid DESC LIMIT 1) "
                       "WHERE (r.root_id=? OR (s.id=? AND r.session_id IS NULL)) "
                       (when target-id "AND s.id=? ")
                       "ORDER BY CASE WHEN s.id=? THEN 0 ELSE 1 END,r.depth,s.id LIMIT ? OFFSET ?")
                  (cond-> [root root] target-id (conj target-id)
                    true (into [root limit offset]))
                  (fn [^ResultSet rs]
                    (let [config (codec/decode (.getString rs "config"))]
                      {:session-id (.getString rs "session_id")
                       :name (.getString rs "agent_name")
                       :parent-session-id (.getString rs "parent_session_id")
                       :context-id (.getString rs "context_id")
                       :parent-context-id (.getString rs "parent_context_id")
                       :paused? (pos? (.getInt rs "paused"))
                       :stopped? (pos? (.getInt rs "stopped"))
                       :provider (:provider config)
                       :model (:model config)
                       :status (keyword (.getString rs "session_status"))
                       :operation-id (.getString rs "operation_id")
                       :operation-status (some-> (.getString rs "operation_status") keyword)
                       :pending-count (.getLong rs "pending_count")})))]
        {:root-id root :agents rows :total total
         :next-offset (when (< (+ offset (count rows)) total) (+ offset (count rows)))}))))

(defn agent-team [store sid opts]
  (db/store-read store
    (fn [connection]
      (let [root (:root-id (routing/route connection sid))
            limit (page-limit (:limit opts) 100)
            offset (or (:offset opts) 0)]
        (value/check! (and (integer? offset) (not (neg? offset))) :invalid-arguments
                      "Page offset must be non-negative" {:offset offset})
        (mapv (fn [id]
                (let [state (routing/route connection id)]
                  (merge (routing/public-route state)
                         {:session (records/public-session (records/require-session connection id))
                          :operation (first (sql/query-sql connection
                                                        "SELECT * FROM operations WHERE session_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1"
                                                        [id] records/operation-row))
                          :pending-count (long (or (sql/scalar connection
                                                           "SELECT COUNT(*) FROM agent_deliveries WHERE recipient_id=? AND status='pending' AND context_id=?"
                                                           [id (:context-id state)]) 0))})))
              (if (sql/scalar connection "SELECT 1 FROM agent_routes WHERE session_id=?" [root])
                (sql/query-sql connection
                           "SELECT session_id FROM agent_routes WHERE root_id=? ORDER BY depth,session_id LIMIT ? OFFSET ?"
                           [root limit offset] #(.getString ^ResultSet % "session_id"))
                (if (zero? offset) [root] [])))))))

(defn- submission-row [connection sid submission-id]
  (first (sql/query-sql connection
                    "SELECT kind,payload,receipt FROM agent_submissions WHERE source_id=? AND submission_id=?"
                    [sid submission-id]
                    (fn [^ResultSet rs] {:kind (keyword (.getString rs "kind"))
                                         :payload (codec/decode (.getString rs "payload"))
                                         :receipt (codec/decode (.getString rs "receipt"))}))))

(defn agent-submission [store source-sid submission-id]
  (codec/require-uuid! submission-id :submission-id)
  (db/store-read store
    (fn [connection]
      (records/require-session connection source-sid)
      (:receipt (submission-row connection source-sid submission-id)))))

(defn- save-submission! [connection sid submission-id kind payload receipt]
  (sql/execute-sql! connection
                "INSERT INTO agent_submissions(source_id,submission_id,kind,payload,receipt) VALUES(?,?,?,?,?)"
                [sid submission-id (name kind) (codec/encode payload) (codec/encode receipt)]))

(defn- existing-submission! [connection sid submission-id kind payload]
  (when-let [saved (submission-row connection sid submission-id)]
    (value/check! (and (= kind (:kind saved)) (= payload (:payload saved)))
                  :agent-submission-conflict
                  "Submission ID was already used with different content"
                  {:session-id sid :submission-id submission-id})
    saved))

(defn create-agent! [store parent-sid opts]
  (let [{:keys [id operation-id submission-id name cwd config task context origin limit max-depth]} opts
        payload (select-keys opts [:name :cwd :config :task :context])]
    (codec/require-uuid! submission-id :submission-id)
    (value/check! (and (string? name) (not (str/blank? name)) (<= (count name) 100))
                  :invalid-agent-name "Agent name must contain 1..100 characters" {})
    (value/check! (and (string? task) (not (str/blank? task))) :invalid-agent-task
                  "Agent task must be nonblank text" {})
    (value/check! (or (nil? context) (string? context)) :invalid-agent-context
                  "Agent context must be text" {})
    (routing/checked-agent-content {:task task :context context})
    (value/check! (or (nil? origin) (map? origin)) :invalid-agent-origin
                  "Agent origin must be a map" {})
    (db/transact! store
      (fn [connection]
        (let [parent (routing/route connection parent-sid)
              existing (existing-submission! connection parent-sid submission-id :spawn payload)]
          (if existing
            (let [handle (:receipt existing)]
              {:session (records/public-session (records/require-session connection (:session-id handle)))
               :operation (records/find-operation connection (:operation-id handle))
               :handle handle :events [] :existing? true})
            (let [parent (active-route! connection parent-sid)
                  root (:root-id parent)
                  depth (inc (:depth parent))
                  limit (or limit 100)
                  max-depth (or max-depth 8)
                  _ (value/check! (and (integer? limit) (pos? limit)
                                       (integer? max-depth) (pos? max-depth))
                                  :invalid-arguments "Agent limits must be positive" {})
                  _ (value/check! (<= depth max-depth) :agent-depth-limit
                                  "Agent nesting limit reached" {:depth depth :max-depth max-depth})
                  _ (routing/ensure-root-route! connection root)
                  _ (value/check! (< (long (or (sql/scalar connection
                                                       "SELECT COUNT(*) FROM agent_routes WHERE root_id=?"
                                                       [root]) 0)) limit)
                                  :agent-limit "Agent team limit reached" {:limit limit})
                  _ (value/check! (nil? (sql/scalar connection
                                                "SELECT 1 FROM agent_routes WHERE root_id=? AND name=?"
                                                [root name])) :agent-name-exists
                                  "Agent name already exists in this team" {:name name})
                  _ (codec/require-uuid! id :session-id)
                  _ (codec/require-uuid! operation-id :operation-id)
                  _ (value/check! (nil? (records/find-session connection id)) :session-exists
                                  "Session ID already exists" {:session-id id})
                  _ (value/check! (nil? (records/find-operation connection operation-id))
                                  :operation-exists "Operation ID already exists" {:operation-id operation-id})
                  snapshot (session-model/new-snapshot
                            (records/prepare-session-options {:id id :name name :cwd cwd
                                                      :config config
                                                      :metadata {:agent/origin origin
                                                                 :title/source :user}}))
                  _ (records/insert-session! connection snapshot (:config snapshot))
                  context-id (util/id)
                  _ (sql/execute-sql! connection
                                  "INSERT INTO agent_routes(session_id,root_id,parent_session_id,name,context_id,parent_context_id,depth,paused,stopped,origin) VALUES(?,?,?,?,?,?,?,0,0,?)"
                                  [id root parent-sid name context-id (:context-id parent) depth
                                   (codec/encode (or origin {}))])
                  initial (str (when (seq context) (str "Context:\n" context "\n\n"))
                               task)
                  now (util/now)
                  entry {:id (util/id) :session-id id :parent-id nil :seq 1 :kind :message
                         :data {:message/role :user :message/content initial
                                :message/agent {:kind :task :from parent-sid}}
                         :created-at now}
                  _ (sql/execute-sql! connection
                                  "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                                  [(:id entry) id nil 1 "message" (codec/encode (:data entry)) now])
                  snapshot (assoc snapshot :head (:id entry) :revision 1 :updated-at now)
                  _ (records/update-session! connection snapshot)
                  operation (records/upsert-operation! connection
                                               (records/normalize-operation id nil {:id operation-id
                                                                             :kind :run :status :queued}))
                  handle {:session-id id :operation-id operation-id :submission-id submission-id}
                  _ (save-submission! connection parent-sid submission-id :spawn payload handle)
                  events [(records/insert-event! connection id {:type :entry/committed :data {:entry entry}})
                          (routing/routing-event! connection root id :agent/changed
                                          {:reason :created :operation-id operation-id})]]
              {:session (records/public-session snapshot) :operation operation :handle handle
               :events events :existing? false})))))))

(defn- resolve-agent-target [connection source target]
  (let [root (:root-id source)
        target (if (string? target)
                 (case target
                   "parent" :parent
                   "all" :all
                   target)
                 target)]
    (if (= target :all)
      (sql/query-sql connection
                 "SELECT * FROM agent_routes WHERE root_id=? AND session_id<>? ORDER BY depth,session_id"
                 [root (:session-id source)] routing/route-row)
      (let [id (cond
                 (= :parent target) (:parent-session-id source)
                 (and (string? target) (codec/uuid? target)) target
                 (string? target) (sql/scalar connection
                                          "SELECT session_id FROM agent_routes WHERE root_id=? AND name=?"
                                          [root target])
                 :else nil)]
        (value/check! id :agent-target-not-found "Agent target does not exist"
                      {:target target})
        (let [resolved (routing/route connection id)]
          (value/check! (= root (:root-id resolved)) :agent-target-forbidden
                        "Agent target belongs to another team" {:target target})
          [resolved])))))

(defn agent-target-id [store viewer-sid target]
  (db/store-read store
    (fn [connection]
      (let [viewer (routing/route connection viewer-sid)]
        (cond
          (or (nil? target) (= target :self) (= target "self"))
          viewer-sid

          (or (= target :all) (= target "all"))
          (value/fail! :agent-target-not-found "Expected a single agent target" {:target target})

          (or (= target :main) (= target "Main"))
          (:root-id viewer)

          :else
          (:session-id (first (resolve-agent-target connection viewer target))))))))

(defn- portable-message-content [connection source content]
  (if (and (map? content) (contains? content :result/ref))
    (let [{:keys [session-id id]} (:result/ref content)
          _ (value/check! (= session-id (:session-id source)) :agent-result-forbidden
                          "Only this session's retained values may be sent" {})
          result (first (sql/query-sql connection
                                   "SELECT descriptor FROM results WHERE session_id=? AND id=?"
                                   [session-id id] #(codec/decode (.getString ^ResultSet % "descriptor"))))]
      (value/check! (and result (= :inline (:kind result)) (:available? result))
                    :agent-result-unavailable "Only durable inline results can cross sessions"
                    {:session-id session-id :result-id id})
      {:value (:value result) :source-result {:session-id session-id :id id}})
    content))

(defn send-agent-message! [store source-sid target content opts]
  (let [{:keys [submission-id wake? kind]} opts
        _ (codec/require-uuid! submission-id :submission-id)
        _ (value/check! (or (nil? wake?) (instance? Boolean wake?)) :invalid-arguments
                        "Wake policy must be boolean" {})
        kind (or kind :peer)
        _ (value/check! (contains? #{:peer :human} kind) :invalid-agent-message
                        "Message kind must be peer or human" {:kind kind})
        payload {:target target :content content :kind kind :wake? (boolean wake?)}]
    (routing/checked-agent-content content)
    (db/transact! store
      (fn [connection]
        (let [source (routing/route connection source-sid)
              existing (existing-submission! connection source-sid submission-id :send payload)]
          (if existing
            {:receipt (:receipt existing) :events []}
            (let [source (active-route! connection source-sid)
                  targets (resolve-agent-target connection source target)
                  _ (value/check! (seq targets) :agent-target-not-found
                                  "No agents are available for this message" {:target target})
                  _ (doseq [{:keys [session-id]} targets] (active-route! connection session-id))
                  content (portable-message-content connection source content)
                  serialized (routing/checked-agent-content content)
                  id (util/id)
                  now (util/now)
                  recipients (mapv :session-id targets)
                  receipt {:id id :submission-id submission-id :from source-sid
                           :recipients recipients :status :accepted}
                  _ (sql/execute-sql! connection
                                  "INSERT INTO agent_messages(id,root_id,sender_id,kind,content,source_operation_id,created_at,wake) VALUES(?,?,?,?,?,?,?,?)"
                                  [id (:root-id source) source-sid (name kind) serialized nil now
                                   (if wake? 1 0)])
                  _ (doseq [recipient targets]
                      (sql/execute-sql! connection
                                    "INSERT INTO agent_deliveries(message_id,recipient_id,context_id,status) VALUES(?,?,?,'pending')"
                                    [id (:session-id recipient) (:context-id recipient)]))
                  _ (save-submission! connection source-sid submission-id :send payload receipt)
                  events (mapv #(routing/routing-event! connection (:root-id source) %
                                                :agent/message {:message-id id :from source-sid :kind kind})
                               recipients)]
              {:receipt receipt :events events})))))))

(defn- message-row [^ResultSet rs]
  {:id (.getString rs "id")
   :seq (.getLong rs "seq")
   :root-id (.getString rs "root_id")
   :from (.getString rs "sender_id")
   :kind (keyword (.getString rs "kind"))
   :content (codec/decode (.getString rs "content"))
   :operation-id (.getString rs "source_operation_id")
   :created-at (.getLong rs "created_at")
   :wake? (pos? (.getInt rs "wake"))})

(defn- delivery-row [^ResultSet rs]
  {:session-id (.getString rs "recipient_id")
   :context-id (.getString rs "context_id")
   :status (keyword (.getString rs "status"))
   :entry-id (.getString rs "entry_id")
   :operation-id (.getString rs "operation_id")})

(defn agent-delivery
  "Receipt page. :internal? permits up to 500 recipients for managed waits; public callers cap at 20."
  [store viewer-sid message-id opts]
  (codec/require-uuid! message-id :message-id)
  (db/store-read store
    (fn [connection]
      (let [viewer (routing/route connection viewer-sid)
            [limit offset] (if (:internal? opts)
                             (let [limit (page-limit (:limit opts) 8)
                                   offset (or (:offset opts) 0)]
                               (value/check! (and (integer? offset) (not (neg? offset)))
                                             :invalid-arguments "Page offset must be non-negative" {})
                               [limit offset])
                             (compact-agent-page opts))
            message (first
                     (sql/query-sql
                      connection
                      (str "SELECT m.sender_id,m.kind"
                           (when (:detailed? opts) ",m.content")
                           " FROM agent_messages m WHERE m.id=? AND m.root_id=? "
                           "AND (m.sender_id=? OR EXISTS (SELECT 1 FROM agent_deliveries d "
                           "WHERE d.message_id=m.id AND d.recipient_id=?))")
                      [message-id (:root-id viewer) viewer-sid viewer-sid]
                      (fn [^ResultSet rs]
                        (cond-> {:from (.getString rs "sender_id")
                                 :kind (keyword (.getString rs "kind"))}
                          (:detailed? opts) (assoc :content (codec/decode (.getString rs "content")))))))
            _ (value/check! message :agent-message-not-found
                            "Agent message is not available" {:message-id message-id})
            counts (first (sql/query-sql connection
                                     (str "SELECT COUNT(*) AS total,"
                                          "SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END) AS pending,"
                                          "SUM(CASE WHEN status='delivered' THEN 1 ELSE 0 END) AS delivered,"
                                          "SUM(CASE WHEN status='superseded' THEN 1 ELSE 0 END) AS superseded "
                                          "FROM agent_deliveries WHERE message_id=?")
                                     [message-id]
                                     (fn [^ResultSet rs]
                                       {:total (.getLong rs "total")
                                        :pending (.getLong rs "pending")
                                        :delivered (.getLong rs "delivered")
                                        :superseded (.getLong rs "superseded")})))
            total (:total counts)
            deliveries (sql/query-sql
                        connection
                        (str "SELECT d.recipient_id,d.status,d.entry_id,d.operation_id,"
                             "o.status AS operation_status FROM agent_deliveries d "
                             "LEFT JOIN operations o ON o.id=d.operation_id "
                             "WHERE d.message_id=? ORDER BY d.recipient_id LIMIT ? OFFSET ?")
                        [message-id limit offset]
                        (fn [^ResultSet rs]
                          {:session-id (.getString rs "recipient_id")
                           :status (keyword (.getString rs "status"))
                           :entry-id (.getString rs "entry_id")
                           :operation-id (.getString rs "operation_id")
                           :operation-status (some-> (.getString rs "operation_status") keyword)}))]
        (merge {:id message-id :from (:from message) :kind (:kind message)
                :status (cond (= total (:delivered counts)) :delivered
                              (= total (:superseded counts)) :superseded
                              (= total (:pending counts)) :pending
                              :else :partial)
                :deliveries deliveries :total-recipients total
                :next-offset (when (< (+ offset (count deliveries)) total)
                               (+ offset (count deliveries)))}
               (select-keys message [:content]))))))

(defn agent-messages [store viewer-sid opts]
  (db/store-read store
    (fn [connection]
      (let [viewer (routing/route connection viewer-sid)
            sid (or (:session-id opts) viewer-sid)
            selected (routing/route connection sid)
            _ (value/check! (= (:root-id viewer) (:root-id selected))
                            :agent-target-forbidden "Cannot inspect a foreign team" {})
            before (or (:before opts) Long/MAX_VALUE)
            limit (page-limit (:limit opts) 50)
            _ (value/check! (and (integer? before) (pos? before)) :invalid-arguments
                            "Message cursor must be a positive sequence" {})]
        (mapv (fn [message]
                (assoc (select-keys message [:id :seq :from :kind :content :created-at :operation-id])
                       :recipients (sql/query-sql connection
                                              "SELECT recipient_id FROM agent_deliveries WHERE message_id=? ORDER BY recipient_id"
                                              [(:id message)] #(.getString ^ResultSet % "recipient_id"))
                       :deliveries (sql/query-sql connection
                                              "SELECT * FROM agent_deliveries WHERE message_id=? ORDER BY recipient_id"
                                              [(:id message)] delivery-row)))
              (sql/query-sql connection
                         (str "SELECT m.* FROM agent_messages m WHERE m.seq<? AND m.root_id=? "
                              "AND (m.sender_id=? OR EXISTS (SELECT 1 FROM agent_deliveries d "
                              "WHERE d.message_id=m.id AND d.recipient_id=?)) ORDER BY m.seq DESC LIMIT ?")
                         [before (:root-id viewer) sid sid limit] message-row))))))

(defn- pending-deliveries [connection sid limit]
  (sql/query-sql connection
             (str "SELECT m.* FROM agent_messages m JOIN agent_deliveries d ON d.message_id=m.id "
                  "WHERE d.recipient_id=? AND d.status='pending' ORDER BY m.seq LIMIT ?")
             [sid limit] message-row))

(defn pending-agent-messages? [store sid]
  (db/store-read store
    (fn [connection]
      (let [state (routing/route connection sid)]
        (boolean
         (and (try (active-route! connection sid) true
                   (catch clojure.lang.ExceptionInfo _ false))
              (sql/scalar connection
                      "SELECT 1 FROM agent_deliveries WHERE recipient_id=? AND status='pending' AND context_id=? LIMIT 1"
                      [sid (:context-id state)])))))))

(defn- eligible-wake-route [connection sid]
  (let [state (routing/route connection sid)
        valid? (try (active-route! connection sid) true
                    (catch clojure.lang.ExceptionInfo _ false))]
    (when (and valid? (not (:paused? state))
               (sql/scalar connection
                       (str "SELECT 1 FROM agent_deliveries d JOIN agent_messages m ON m.id=d.message_id "
                            "WHERE d.recipient_id=? AND d.context_id=? AND d.status='pending' "
                            "AND (m.kind='completion' OR m.wake=1 OR ?=1) LIMIT 1")
                       [sid (:context-id state) (if (:parent-session-id state) 1 0)]))
      state)))

(defn agent-wake? [store sid]
  (db/store-read store #(boolean (eligible-wake-route % sid))))

(defn agent-wake-roots [store]
  (db/store-read store
    (fn [connection]
      (let [candidates (sql/query-sql
                        connection
                        (str "SELECT DISTINCT d.recipient_id FROM agent_deliveries d "
                             "JOIN agent_messages m ON m.id=d.message_id "
                             "WHERE d.status='pending' AND "
                             "(m.kind='completion' OR m.wake=1 OR EXISTS "
                             "(SELECT 1 FROM agent_routes r WHERE r.session_id=d.recipient_id "
                             "AND r.parent_session_id IS NOT NULL))")
                        [] #(.getString ^ResultSet % "recipient_id"))]
        (vec (into (sorted-set)
                   (keep #(some-> (eligible-wake-route connection %) :root-id))
                   candidates))))))

(defn- agent-preview
  ([content] (agent-preview content :peer))
  ([content kind]
   (let [text (if (= :completion kind)
                (let [reply (get-in content [:result :message/content])
                      answer (if (string? reply)
                               reply
                               (str/join "\n" (keep #(when (= :text (:part/type %)) (:text %)) reply)))
                      error (get-in content [:error :message])]
                  (str "Agent " (:name content) " " (name (:status content))
                       (when (seq answer) (str ": " answer))
                       (when (and (not (seq answer)) (seq error)) (str ": " error))))
                (if (string? content) content (pr-str content)))]
     (if (<= (count text) routing/max-agent-preview)
       text
       (str (subs text 0 routing/max-agent-preview)
            "\n[Preview shortened; read the recipient-owned result for full content.]")))))

(defn deliver-agent-messages! [store sid operation-id]
  (codec/require-uuid! operation-id :operation-id)
  (db/transact! store
    (fn [connection]
      (let [operation (or (records/find-operation connection operation-id)
                          (value/fail! :operation-not-found
                                       "Delivery operation does not exist" {:operation-id operation-id}))
            _ (value/check! (= sid (:session-id operation)) :operation-forbidden
                            "Delivery operation belongs to another session" {:operation-id operation-id})
            _ (value/check! (and (contains? #{:run :continue} (:kind operation))
                                 (= :running (:status operation)))
                            :invalid-agent-operation
                            "Agent messages require a running run or continue operation"
                            {:operation-id operation-id})
            state (routing/route connection sid)
            active-state (try (active-route! connection sid) :active
                              (catch clojure.lang.ExceptionInfo error
                                (case (:error/code (ex-data error))
                                  "agent-stopped" :stopped
                                  "agent-stale-context" :stale
                                  (throw error))))
            messages (pending-deliveries connection sid 100)
            snapshot (records/require-session connection sid)
            seq* (volatile! (long (or (sql/scalar connection
                                             "SELECT MAX(seq) FROM entries WHERE session_id=?"
                                             [sid]) 0)))
            result* (volatile! (long (or (sql/scalar connection
                                                "SELECT MAX(id) FROM results WHERE session_id=?"
                                                [sid]) 0)))
            head* (volatile! (:head snapshot))
            entries (volatile! [])
            delivered (volatile! [])
            events (volatile! [])]
        (doseq [message messages]
          (let [scope-current? (= (:context-id state)
                                  (sql/scalar connection
                                          "SELECT context_id FROM agent_deliveries WHERE message_id=? AND recipient_id=?"
                                          [(:id message) sid]))]
            (if (and (= :active active-state) scope-current?)
              (let [result-id (vswap! result* inc)
                    content (:content message)
                    preview (agent-preview content (:kind message))
                    descriptor {:id result-id :session-id sid :kind :inline
                                :value content :content preview
                                :details {:agent/message-id (:id message)
                                          :agent/from (:from message)
                                          :agent/kind (:kind message)}
                                :available? true}
                    entry {:id (util/id) :session-id sid :parent-id @head*
                           :seq (vswap! seq* inc) :kind :message
                           :data {:message/role :user
                                  :message/content preview
                                  :message/agent (cond-> {:id (:id message) :from (:from message)
                                                          :kind (:kind message)}
                                                   (:operation-id message)
                                                   (assoc :operation-id (:operation-id message)))
                                  :message/result descriptor}
                           :created-at (util/now)}]
                (sql/execute-sql! connection
                              "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                              [sid result-id "inline" (codec/encode descriptor) (:created-at entry)])
                (sql/execute-sql! connection
                              "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                              [(:id entry) sid @head* (:seq entry) "message"
                               (codec/encode (:data entry)) (:created-at entry)])
                (sql/execute-sql! connection
                              "UPDATE agent_deliveries SET status='delivered',entry_id=?,operation_id=? WHERE message_id=? AND recipient_id=?"
                              [(:id entry) operation-id (:id message) sid])
                (vreset! head* (:id entry))
                (vswap! entries conj entry)
                (vswap! delivered conj (:id message))
                (vswap! events conj (records/insert-event! connection sid
                                                    {:type :entry/committed :data {:entry entry}}))
                (vswap! events conj (routing/routing-event! connection (:root-id state) sid
                                                    :agent/message
                                                    {:message-id (:id message)
                                                     :status :delivered})))
              (when (or (= :stale active-state) (not scope-current?))
                (sql/execute-sql! connection
                              "UPDATE agent_deliveries SET status='superseded' WHERE message_id=? AND recipient_id=?"
                              [(:id message) sid])
                (vswap! events conj (routing/routing-event! connection (:root-id state) sid
                                                    :agent/message
                                                    {:message-id (:id message)
                                                     :status :superseded}))))))
        (when (seq @entries)
          (records/update-session! connection (assoc snapshot :head @head*
                                             :revision (inc (:revision snapshot))
                                             :updated-at (util/now))))
        {:entries @entries :events @events :delivered @delivered}))))

(defn set-agent-paused! [store sid paused?]
  (value/check! (instance? Boolean paused?) :invalid-arguments
                "Pause state must be boolean" {})
  (db/transact! store
    (fn [connection]
      (let [state (routing/ensure-root-route! connection sid)]
        (sql/execute-sql! connection "UPDATE agent_routes SET paused=? WHERE session_id=?"
                      [(if paused? 1 0) sid])
        {:state (routing/public-route (routing/route connection sid))
         :events [(routing/routing-event! connection (:root-id state) sid
                                  :agent/changed {:reason :pause :paused? paused?})]}))))

(defn set-agent-stopped! [store sid stopped?]
  (value/check! (instance? Boolean stopped?) :invalid-arguments
                "Stop state must be boolean" {})
  (db/transact! store
    (fn [connection]
      (let [state (routing/ensure-root-route! connection sid)
            members (agent-descendants* connection sid)]
        (sql/execute-sql! connection "UPDATE agent_routes SET stopped=? WHERE session_id=?"
                      [(if stopped? 1 0) sid])
        {:state (routing/public-route (routing/route connection sid))
         :events (mapv #(routing/routing-event! connection (:root-id state) % :agent/changed
                                        {:reason :stop :stopped? stopped?})
                       members)}))))

(defn agent-result [store viewer-sid target-sid operation-id]
  (codec/require-uuid! operation-id :operation-id)
  (db/store-read store
    (fn [connection]
      (let [viewer (routing/route connection viewer-sid)
            target (routing/route connection target-sid)
            _ (value/check! (= (:root-id viewer) (:root-id target))
                            :agent-target-forbidden "Agent belongs to another team" {})
            op (or (records/find-operation connection operation-id)
                   (value/fail! :operation-not-found "Operation does not exist"
                                {:operation-id operation-id}))]
        (value/check! (= (:session-id op) target-sid) :operation-forbidden
                      "Operation does not belong to target agent"
                      {:operation-id operation-id})
        (assoc (select-keys op [:session-id :status :result :error :finished-at :created-at])
               :operation-id operation-id)))))
