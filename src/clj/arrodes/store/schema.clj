(ns arrodes.store.schema
  "SQLite format recognition, initialization, backup, and transactional upgrades."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [arrodes.context-tree :as tree]
            [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.records :as records]
            [arrodes.store.sql :as sql])
  (:import (java.sql Connection ResultSet)
           (java.nio.channels FileChannel)
           (java.nio.file Files StandardCopyOption StandardOpenOption OpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(def ^:private schema-version 6)
(def ^:private application-id 0x4152524f)
(defn- execute-script! [^Connection connection statements]
  (with-open [statement (.createStatement connection)]
    (doseq [sql statements] (.executeUpdate statement sql))))

(def ^:private arrodes-table-signature
  {"sessions" #{"base_config" "fork_entry" "labels"}
   "entries" #{"session_id" "parent_id" "data"}
   "queue" #{"session_id" "options"}
   "operations" #{"session_id" "result" "error"}
   "events" #{"session_id" "operation_id" "data"}
   "artifacts" #{"session_id" "sha256" "content"}
   "results" #{"session_id" "descriptor"}
   "jobs" #{"session_id" "record"}})

(defn- arrodes-owned? [connection tables]
  (and (set/subset? (set (keys arrodes-table-signature)) tables)
       (every? (fn [[table columns]]
                 (set/subset? columns
                              (set (sql/query-sql connection
                                              (str "PRAGMA table_info(" table ")")
                                              [] #(.getString ^ResultSet % "name")))))
               arrodes-table-signature)))

(def ^:private historical-columns
  {"sessions" #{"id" "name" "cwd" "head" "revision" "base_config" "config" "status" "created_at" "updated_at" "metadata" "labels" "parent_id" "fork_entry"}
   "entries" #{"id" "session_id" "parent_id" "seq" "kind" "data" "created_at"}
   "queue" #{"id" "session_id" "seq" "kind" "content" "options" "created_at"}
   "operations" #{"id" "session_id" "kind" "status" "created_at" "finished_at" "result" "error"}
   "events" #{"seq" "id" "session_id" "operation_id" "type" "data" "time"}
   "artifacts" #{"id" "session_id" "sha256" "bytes" "kind" "available" "created_at" "name" "content"}
   "results" #{"session_id" "id" "kind" "descriptor" "created_at"}
   "jobs" #{"id" "session_id" "status" "created_at" "delivered" "record"}})

(def ^:private agent-columns
  {"agent_routes" #{"session_id" "root_id" "parent_session_id" "name" "context_id" "parent_context_id" "depth" "paused" "stopped" "origin"}
   "agent_submissions" #{"source_id" "submission_id" "kind" "payload" "receipt"}
   "agent_messages" #{"seq" "id" "root_id" "sender_id" "kind" "content" "source_operation_id" "created_at" "wake"}
   "agent_deliveries" #{"message_id" "recipient_id" "context_id" "status" "entry_id" "operation_id"}})

(def ^:private context-columns
  {"context_nodes" #{"session_id" "id" "start" "count" "first_entry_id"
                     "last_entry_id" "left_id" "right_id" "text" "bytes" "metadata"}})

(def ^:private context-node-fields
  #{:id :start :count :first-entry-id :last-entry-id :left-id :right-id
    :text :bytes :provider :model :usage :cost :created-at})

(def context-node-metadata-fields #{:provider :model :usage :cost :created-at})

(defn context-node-metadata [^ResultSet rs]
  (let [metadata (try (codec/decode (.getString rs "metadata"))
                      (catch RuntimeException error
                        (value/fail! :unsupported-store-format "Malformed context node metadata"
                                     {:cause (ex-message error)})))]
    (value/check! (and (map? metadata) (every? context-node-metadata-fields (keys metadata)))
                  :unsupported-store-format "Invalid context node metadata" {})
    metadata))

(defn context-node-row [^ResultSet rs]
  (merge (cond-> {:id (.getString rs "id")
                 :start (.getLong rs "start")
                 :count (.getLong rs "count")
                 :first-entry-id (.getString rs "first_entry_id")
                 :last-entry-id (.getString rs "last_entry_id")
                 :text (.getString rs "text")
                 :bytes (.getLong rs "bytes")}
           (.getString rs "left_id") (assoc :left-id (.getString rs "left_id"))
           (.getString rs "right_id") (assoc :right-id (.getString rs "right_id")))
         (context-node-metadata rs)))

(defn find-context-node [connection sid id]
  (first (sql/query-sql connection
                       "SELECT * FROM context_nodes WHERE session_id=? AND id=?"
                       [sid id] context-node-row)))

(defn- context-entry [connection sid id]
  (or (first (sql/query-sql connection "SELECT * FROM entries WHERE session_id=? AND id=?"
                           [sid id] records/entry-row))
      (value/fail! :context-source-forbidden "Context source is not part of this session"
                   {:session-id sid :entry-id id})))

(defn- preceding-context-source [connection sid entry]
  (loop [id (:parent-id entry), seen #{(:id entry)}]
    (when id
      (value/check! (not (contains? seen id)) :invalid-context-branch
                    "Context source ancestry contains a cycle" {:entry-id id})
      (let [parent (context-entry connection sid id)]
        (if (tree/source-entry? parent)
          parent
          (recur (:parent-id parent) (conj seen id)))))))

(defn- context-source-position [connection sid entry]
  ;; Prior validated leaves make append validation proportional to new ancestry,
  ;; rather than reading the session's entire (possibly branched) history per put.
  (loop [id (:parent-id entry), sources 0, seen #{(:id entry)}]
    (if-not id
      sources
      (do
        (value/check! (not (contains? seen id)) :invalid-context-branch
                      "Context source ancestry contains a cycle" {:entry-id id})
        (let [parent (context-entry connection sid id)
              source? (tree/source-entry? parent)
              prior (when source?
                      (sql/scalar connection
                                  "SELECT start FROM context_nodes WHERE session_id=? AND last_entry_id=? AND count=1"
                                  [sid id]))]
          (if prior
            (+ sources 1 prior)
            (recur (:parent-id parent) (if source? (inc sources) sources)
                   (conj seen id))))))))

(defn validate-context-node!
  "Validates one completed node using exact, session-scoped source and child lookups."
  [connection sid {:keys [id start count first-entry-id last-entry-id text bytes] :as node}]
  (value/check! (and (map? node)
                     (every? context-node-fields (keys node))
                     (integer? start) (<= 0 start Long/MAX_VALUE)
                     (integer? count) (<= 1 count Long/MAX_VALUE)
                     (zero? (bit-and (long count) (dec (long count))))
                     (zero? (mod start count))
                     (<= count (- Long/MAX_VALUE start))
                     (codec/uuid? first-entry-id) (codec/uuid? last-entry-id)
                     (= id (tree/node-id first-entry-id last-entry-id count))
                     (string? text) (integer? bytes) (= bytes (tree/utf8-bytes text))
                     (if (= count 1)
                       (and (= first-entry-id last-entry-id)
                            (not (contains? node :left-id)) (not (contains? node :right-id)))
                       (and (string? (:left-id node)) (string? (:right-id node))
                            (not= (:left-id node) (:right-id node))))
                     (or (not (contains? node :provider))
                         (keyword? (:provider node))
                         (and (string? (:provider node)) (not (str/blank? (:provider node)))))
                     (or (not (contains? node :model))
                         (and (string? (:model node)) (not (str/blank? (:model node)))))
                     (or (not (contains? node :usage)) (map? (:usage node)))
                     (or (not (contains? node :cost)) (map? (:cost node)))
                     (or (not (contains? node :created-at))
                         (and (integer? (:created-at node))
                              (<= 0 (:created-at node) Long/MAX_VALUE))))
                :invalid-context-node "Invalid completed context node" {:node-id id})
  (let [first-entry (context-entry connection sid first-entry-id)
        last-entry (if (= first-entry-id last-entry-id)
                     first-entry (context-entry connection sid last-entry-id))]
    (value/check! (and (tree/source-entry? first-entry) (tree/source-entry? last-entry))
                  :invalid-context-node "Context nodes must cover indexable source entries"
                  {:node-id id})
    (if (= count 1)
      (value/check! (= start (context-source-position connection sid first-entry))
                    :invalid-context-branch "Context leaf has the wrong branch position"
                    {:node-id id :start start})
      (let [left (find-context-node connection sid (:left-id node))
            right (find-context-node connection sid (:right-id node))]
        (value/check! (and left right) :invalid-context-node
                      "Context parent requires completed children in this session" {:node-id id})
        (value/check! (= (select-keys node [:id :start :count :first-entry-id :last-entry-id
                                           :left-id :right-id :text :bytes])
                         (tree/parent-node left right text))
                      :invalid-context-node "Context parent does not match its children"
                      {:node-id id})
        (value/check! (= (:last-entry-id left)
                         (:id (preceding-context-source
                               connection sid (context-entry connection sid (:first-entry-id right)))))
                      :invalid-context-branch "Context children are not adjacent on one branch"
                      {:node-id id}))))
  node)

(defn- column-shape? [connection expected tables]
  (and (set/subset? (set (keys expected)) tables)
       (every? (fn [[table columns]]
                 (= columns (set (sql/query-sql connection (str "PRAGMA table_info(" table ")")
                                            [] #(.getString ^ResultSet % "name")))))
               expected)))

(defn- context-layout? [connection]
  (let [columns (sql/query-sql connection "PRAGMA table_info(context_nodes)" []
                               (fn [^ResultSet rs]
                                 {:name (.getString rs "name") :type (.getString rs "type")
                                  :required? (pos? (.getInt rs "notnull")) :pk (.getInt rs "pk")}))
        foreign-keys (sql/query-sql connection "PRAGMA foreign_key_list(context_nodes)" []
                                    (fn [^ResultSet rs]
                                      {:id (.getInt rs "id") :seq (.getInt rs "seq")
                                       :table (.getString rs "table") :from (.getString rs "from")
                                       :to (.getString rs "to") :delete (.getString rs "on_delete")}))
        links (set (map (fn [rows]
                          (let [rows (sort-by :seq rows)]
                            [(:table (first rows)) (mapv :from rows) (mapv :to rows)
                             (:delete (first rows))]))
                        (vals (group-by :id foreign-keys))))]
    (and (every? (fn [{:keys [name type required? pk]}]
                   (and (= type (if (contains? #{"start" "count" "bytes"} name) "INTEGER" "TEXT"))
                        (= required? (not (contains? #{"left_id" "right_id"} name)))
                        (= pk (get {"session_id" 1 "id" 2} name 0))))
                 columns)
         (= links #{["sessions" ["session_id"] ["id"] "CASCADE"]
                    ["entries" ["first_entry_id"] ["id"] "NO ACTION"]
                    ["entries" ["last_entry_id"] ["id"] "NO ACTION"]
                    ["context_nodes" ["session_id" "left_id"] ["session_id" "id"] "NO ACTION"]
                    ["context_nodes" ["session_id" "right_id"] ["session_id" "id"] "NO ACTION"]})
         (empty? (sql/query-sql connection "PRAGMA foreign_key_check(context_nodes)" []
                                #(.getString ^ResultSet % "table"))))))

(defn check-schema! [^Connection connection]
  (let [current (long (or (sql/scalar connection "PRAGMA user_version" []) 0))
        marker (long (or (sql/scalar connection "PRAGMA application_id" []) 0))
        tables (set (sql/query-sql connection
                               "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                               [] #(.getString ^ResultSet % "name")))
        fresh? (and (zero? current) (zero? marker)
                    (zero? (long (sql/scalar connection
                                             "SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
                                             []))))
        recognized? (or (= marker application-id)
                        (and (zero? marker) (arrodes-owned? connection tables)))
        base? (column-shape? connection historical-columns tables)
        agents? (column-shape? connection agent-columns tables)
        context? (column-shape? connection context-columns tables)
        base-tables (set (keys historical-columns))
        agent-tables (set/union base-tables (set (keys agent-columns)))
        current-tables (set/union agent-tables (set (keys context-columns)))]
    (when-not fresh?
      (value/check! recognized? :unrecognized-store
                    "Unrecognized SQLite store; the database was left unchanged"
                    {:found current :application-id marker})
      (value/check! (contains? #{3 4 5 6} current) :unsupported-store-format
                    "Unsupported store version; use a compatible Arrodes build or restore a backup"
                    {:found current :required schema-version})
      (value/check! (and base?
                         (case current
                           3 (= tables base-tables)
                           4 (or (= tables base-tables) (and agents? (= tables agent-tables)))
                           5 (and agents? (= tables agent-tables))
                           6 (and agents? context? (= tables current-tables))))
                    :unsupported-store-format
                    "Store tables do not match a supported schema; restore from a backup"
                    {:found current :required schema-version}))
    (when agents?
      (let [required #{"sessions" "entries" "queue" "operations" "events"
                       "artifacts" "results" "jobs" "agent_routes"
                       "agent_submissions" "agent_messages" "agent_deliveries"}]
        (value/check! (set/subset? required tables) :unsupported-store-format
                      "Current store is missing required tables"
                      {:found current :required schema-version
                       :missing (vec (sort (set/difference required tables)))}))
      (doseq [row (sql/query-sql connection
                             "SELECT session_id,root_id,parent_session_id,context_id,parent_context_id,depth,paused,stopped FROM agent_routes"
                             [] (fn [^ResultSet rs]
                                  {:sid (.getString rs "session_id")
                                   :root (.getString rs "root_id")
                                   :parent (.getString rs "parent_session_id")
                                   :context (.getString rs "context_id")
                                   :parent-context (.getString rs "parent_context_id")
                                   :depth (.getInt rs "depth")
                                   :paused (.getInt rs "paused")
                                   :stopped (.getInt rs "stopped")}))]
        (value/check! (and (codec/uuid? (:sid row)) (codec/uuid? (:root row))
                           (codec/uuid? (:context row)) (not (neg? (:depth row)))
                           (contains? #{0 1} (:paused row))
                           (contains? #{0 1} (:stopped row))
                           (if (:parent row)
                             (and (codec/uuid? (:parent row)) (codec/uuid? (:parent-context row))
                                  (pos? (:depth row)))
                             (and (= (:sid row) (:root row))
                                  (zero? (:depth row))
                                  (nil? (:parent-context row)))))
                      :unsupported-store-format "Malformed agent routing record"
                      {:session-id (:sid row)}))
      (value/check!
       (contains? (set (sql/query-sql connection "PRAGMA table_info(agent_deliveries)"
                                  [] #(.getString ^ResultSet % "name"))) "operation_id")
       :unsupported-store-format "Current store is missing agent delivery operation linkage"
       {:found current :required schema-version})
      (doseq [row (sql/query-sql connection
                             (str "SELECT d.status,d.entry_id,d.operation_id,d.recipient_id,"
                                  "e.session_id AS entry_session,o.session_id AS operation_session,"
                                  "o.kind AS operation_kind FROM agent_deliveries d "
                                  "LEFT JOIN entries e ON e.id=d.entry_id "
                                  "LEFT JOIN operations o ON o.id=d.operation_id")
                             [] (fn [^ResultSet rs]
                                  {:status (.getString rs "status")
                                   :entry-id (.getString rs "entry_id")
                                   :operation-id (.getString rs "operation_id")
                                   :recipient-id (.getString rs "recipient_id")
                                   :entry-session (.getString rs "entry_session")
                                   :operation-session (.getString rs "operation_session")
                                   :operation-kind (.getString rs "operation_kind")}))]
        (value/check!
         (case (:status row)
           "delivered" (and (codec/uuid? (:entry-id row))
                            (codec/uuid? (:operation-id row))
                            (= (:recipient-id row) (:entry-session row) (:operation-session row))
                            (contains? #{"run" "continue"} (:operation-kind row)))
           ("pending" "superseded") (and (nil? (:entry-id row))
                                        (nil? (:operation-id row)))
           false)
         :unsupported-store-format "Malformed agent delivery link"
         {:status (:status row) :recipient-id (:recipient-id row)})))
    (when (and (not fresh?) (= current schema-version))
      (value/check! (context-layout? connection) :unsupported-store-format
                    "Context node table is missing ownership or integrity constraints"
                    {:found current :required schema-version})
      (doseq [{:keys [sid node]}
              (sql/query-sql connection "SELECT * FROM context_nodes ORDER BY count,start,id"
                             [] (fn [^ResultSet rs]
                                  {:sid (.getString rs "session_id")
                                   :node (context-node-row rs)}))]
        (try
          (records/require-session connection sid)
          (validate-context-node! connection sid node)
          (codec/encode (select-keys node context-node-metadata-fields))
          (catch clojure.lang.ExceptionInfo error
            (value/fail! :unsupported-store-format "Malformed persisted context node"
                         {:session-id sid :node-id (:id node) :cause (ex-message error)})))))
    (if fresh? :fresh current)))

(def ^:private agent-table-statements
  ["CREATE TABLE agent_routes (session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE, root_id TEXT NOT NULL REFERENCES sessions(id), parent_session_id TEXT REFERENCES sessions(id), name TEXT NOT NULL, context_id TEXT NOT NULL, parent_context_id TEXT, depth INTEGER NOT NULL, paused INTEGER NOT NULL DEFAULT 0, stopped INTEGER NOT NULL DEFAULT 0, origin TEXT NOT NULL, UNIQUE(root_id,name))"
   "CREATE INDEX agent_routes_parent ON agent_routes(parent_session_id)"
   "CREATE TABLE agent_submissions (source_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, submission_id TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, receipt TEXT NOT NULL, PRIMARY KEY(source_id,submission_id))"
   "CREATE TABLE agent_messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, root_id TEXT NOT NULL, sender_id TEXT, kind TEXT NOT NULL, content TEXT NOT NULL, source_operation_id TEXT, created_at INTEGER NOT NULL, wake INTEGER NOT NULL DEFAULT 0, UNIQUE(source_operation_id,kind))"
   "CREATE TABLE agent_deliveries (message_id TEXT NOT NULL REFERENCES agent_messages(id) ON DELETE CASCADE, recipient_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, context_id TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', entry_id TEXT, operation_id TEXT REFERENCES operations(id), PRIMARY KEY(message_id,recipient_id))"
   "CREATE INDEX agent_deliveries_recipient ON agent_deliveries(recipient_id,status,message_id)"])

(def ^:private context-table-statements
  ["CREATE TABLE context_nodes (session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id TEXT NOT NULL, start INTEGER NOT NULL, count INTEGER NOT NULL, first_entry_id TEXT NOT NULL REFERENCES entries(id), last_entry_id TEXT NOT NULL REFERENCES entries(id), left_id TEXT, right_id TEXT, text TEXT NOT NULL, bytes INTEGER NOT NULL, metadata TEXT NOT NULL, PRIMARY KEY(session_id,id), FOREIGN KEY(session_id,left_id) REFERENCES context_nodes(session_id,id), FOREIGN KEY(session_id,right_id) REFERENCES context_nodes(session_id,id))"
   "CREATE INDEX context_nodes_source ON context_nodes(session_id,last_entry_id,count)"])

(defn initialize-schema! [^Connection connection]
  (when (= :fresh (check-schema! connection))
    (let [old-auto (.getAutoCommit connection)]
      (try
        (.setAutoCommit connection false)
        (execute-script! connection
          (concat
           ["CREATE TABLE sessions (id TEXT PRIMARY KEY, name TEXT NOT NULL, cwd TEXT NOT NULL, head TEXT, revision INTEGER NOT NULL, base_config TEXT NOT NULL, config TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, metadata TEXT NOT NULL, labels TEXT NOT NULL, parent_id TEXT, fork_entry TEXT)"
            "CREATE TABLE entries (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, parent_id TEXT, seq INTEGER NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(session_id, seq), FOREIGN KEY(parent_id) REFERENCES entries(id))"
            "CREATE INDEX entries_session_parent ON entries(session_id, parent_id)"
            "CREATE TABLE queue (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, seq INTEGER NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, options TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(session_id, seq))"
            "CREATE INDEX queue_session_seq ON queue(session_id, seq)"
            "CREATE TABLE operations (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, kind TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, finished_at INTEGER, result TEXT, error TEXT)"
            "CREATE INDEX operations_session_created ON operations(session_id, created_at)"
            "CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, session_id TEXT, operation_id TEXT, type TEXT NOT NULL, data TEXT NOT NULL, time INTEGER NOT NULL)"
            "CREATE INDEX events_session_seq ON events(session_id, seq)"
            "CREATE TABLE artifacts (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, sha256 TEXT NOT NULL, bytes INTEGER NOT NULL, kind TEXT NOT NULL, available INTEGER NOT NULL, created_at INTEGER NOT NULL, name TEXT, content BLOB, UNIQUE(session_id, id))"
            "CREATE INDEX artifacts_session_created ON artifacts(session_id, created_at)"
            "CREATE TABLE results (session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id INTEGER NOT NULL, kind TEXT NOT NULL, descriptor TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(session_id, id))"
            "CREATE TABLE jobs (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, status TEXT NOT NULL, created_at INTEGER NOT NULL, delivered INTEGER NOT NULL DEFAULT 0, record TEXT NOT NULL)"
            "CREATE INDEX jobs_session_created ON jobs(session_id, created_at DESC, id)"]
           agent-table-statements
           context-table-statements))
        (sql/execute-command! connection (str "PRAGMA user_version = " schema-version))
        (sql/execute-command! connection (str "PRAGMA application_id = " application-id))
        (.commit connection)
        (catch Throwable error (.rollback connection) (throw error))
        (finally (.setAutoCommit connection old-auto))))))

(defn expire-live-results! [^Connection connection]
  (doseq [{:keys [session-id id descriptor]}
          (sql/query-sql connection
                     "SELECT session_id,id,descriptor FROM results WHERE kind='live'"
                     []
                     (fn [^ResultSet rs]
                       {:session-id (.getString rs "session_id")
                        :id (.getLong rs "id")
                        :descriptor (codec/decode (.getString rs "descriptor"))}))]
    (sql/execute-sql! connection
                  "UPDATE results SET descriptor=? WHERE session_id=? AND id=?"
                  [(codec/encode (assoc descriptor :available? false)) session-id id])))


(defn backup-store! [^Connection connection db-path version]
  ;; VACUUM INTO reads a consistent SQLite snapshot, including committed WAL frames.
  (let [parent (.getParent (util/path db-path))
        temp (Files/createTempFile parent ".arrodes-backup-" ".tmp"
                                   (into-array FileAttribute
                                               [(PosixFilePermissions/asFileAttribute
                                                 (PosixFilePermissions/fromString "rw-------"))]))
        backup (util/path (str db-path ".schema" version "-" (util/id) ".backup"))]
    (try
      (sql/execute-command! connection (str "VACUUM INTO '" (str/replace (str temp) "'" "''") "'"))
      (util/private-file! temp)
      (with-open [channel (FileChannel/open temp (into-array OpenOption [StandardOpenOption/WRITE]))]
        (.force channel true))
      (Files/move temp backup (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
      (with-open [channel (FileChannel/open parent (into-array OpenOption [StandardOpenOption/READ]))]
        (.force channel true))
      (str backup)
      (finally (Files/deleteIfExists temp)))))

(defn upgrade-schema! [^Connection connection]
  (let [old-auto (.getAutoCommit connection)
        tables (set (sql/query-sql connection
                               "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                               [] #(.getString ^ResultSet % "name")))]
    (try
      (.setAutoCommit connection false)
      (when-not (contains? tables "agent_routes")
        (execute-script! connection agent-table-statements)
        ;; Historical forks and clones are independent sessions, not active teams.
        (sql/execute-command! connection
                          "INSERT INTO agent_routes(session_id,root_id,parent_session_id,name,context_id,parent_context_id,depth,paused,stopped,origin) SELECT id,id,NULL,'Main',id,NULL,0,0,0,'{}' FROM sessions"))
      (execute-script! connection context-table-statements)
      (sql/execute-command! connection (str "PRAGMA user_version = " schema-version))
      (sql/execute-command! connection (str "PRAGMA application_id = " application-id))
      (check-schema! connection)
      (.commit connection)
      (catch Throwable error
        (.rollback connection)
        (throw error))
      (finally (.setAutoCommit connection old-auto)))))
