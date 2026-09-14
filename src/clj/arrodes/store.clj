(ns arrodes.store
  "Transactional SQLite persistence for sessions, histories, queues, operations, and events."
  (:refer-clojure :exclude [uuid?])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [arrodes.session :as session-model]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.sql Connection DriverManager PreparedStatement ResultSet Statement)
           (java.nio.channels FileChannel FileLock OverlappingFileLockException)
           (java.nio.file FileAlreadyExistsException Files StandardCopyOption StandardOpenOption OpenOption LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.util UUID Base64)
           (java.util.concurrent.locks ReentrantLock)))

(defrecord Store [^Connection connection ^ReentrantLock lock closed? path artifact-dir memory? opened-at
                  ^FileChannel owner-channel ^FileLock owner-lock owner-path])

(def ^:private schema-version 1)
(def ^:private entry-kinds
  #{:message :config :compaction :branch-summary :custom :custom-context :label :evaluation})
(def ^:private statuses #{:idle :running :failed :interrupted})
(def ^:private operation-kinds #{:run :continue :compact :evaluate :invoke})
(def ^:private operation-statuses
  #{:queued :running :cancelling :completed :failed :cancelled :interrupted})
(def ^:private max-transfer-artifact-bytes (* 256 1024 1024))
(def ^:private max-durable-nodes 100000)

(defn- bounded-edn-shape? [value]
  (let [remaining (volatile! max-durable-nodes)]
    (letfn [(walk [item depth]
              (and (< depth 32)
                   (pos? @remaining)
                   (do
                     (vswap! remaining dec)
                     (cond
                       (or (nil? item) (string? item) (number? item) (keyword? item)
                           (symbol? item) (char? item) (instance? Boolean item)
                           (instance? UUID item) (instance? java.util.Date item)) true
                       (vector? item) (and (<= (count item) max-durable-nodes)
                                           (every? #(walk % (inc depth)) item))
                       (instance? clojure.lang.PersistentList item)
                       (and (<= (count item) max-durable-nodes)
                            (every? #(walk % (inc depth)) item))
                       (and (list? item) (empty? item)) true
                       (set? item) (and (<= (count item) max-durable-nodes)
                                        (every? #(walk % (inc depth)) item))
                       (map? item) (and (<= (count item) max-durable-nodes)
                                        (every? (fn [[key nested]]
                                                  (and (walk key (inc depth))
                                                       (walk nested (inc depth))))
                                                item))
                       :else false))))]
      (boolean (walk value 0)))))


(defn- encode [value]
  (value/check! (bounded-edn-shape? value) :non-durable-value
               "Value is not bounded durable EDN"
               {:class (some-> value class .getName)})
  (let [serialized (binding [*print-length* nil *print-level* nil *print-dup* false]
                     (pr-str value))]
    (try
      (binding [*read-eval* false] (edn/read-string serialized))
      serialized
      (catch Throwable error
        (value/fail! :non-durable-value "Value cannot be persisted as EDN"
                    {:class (some-> value class .getName) :cause (ex-message error)})))))

(defn- decode [value]
  (when-not (nil? value)
    (binding [*read-eval* false]
      (edn/read-string value))))

(def ^:private bytes-class (Class/forName "[B"))

(defn- uuid? [value]
  (and (string? value)
       (try (UUID/fromString value) true (catch IllegalArgumentException _ false))))

(defn- require-uuid! [value field]
  (value/check! (uuid? value) :invalid-id "Expected a UUID string" {:field field :value value})
  value)

(defn- set-params! [^PreparedStatement statement params]
  (doseq [[index value] (map-indexed vector params)]
    (cond
      (nil? value) (.setObject statement (inc index) nil)
      (instance? bytes-class value) (.setBytes statement (inc index) value)
      :else (.setObject statement (inc index) value)))
  statement)

(defn- execute-sql! [^Connection connection sql params]
  (with-open [statement (set-params! (.prepareStatement connection sql) params)]
    (.executeUpdate statement)))

(defn- execute-command! [^Connection connection sql]
  (with-open [statement (.createStatement connection)]
    (.execute statement sql)))

(defn- query-sql [^Connection connection sql params row-fn]
  (with-open [statement (set-params! (.prepareStatement connection sql) params)
              result (.executeQuery statement)]
    (loop [rows []]
      (if (.next result)
        (recur (conj rows (row-fn result)))
        rows))))

(defn- scalar [^Connection connection sql params]
  (first (query-sql connection sql params #(.getObject ^ResultSet % 1))))

(defn- ensure-open! [store]
  (value/check! (and store (not @(:closed? store))) :store-closed "Store is closed" {}))

(defn store-read
  "Runs an internal database read while serializing access to the store connection."
  [store f]
  (let [^ReentrantLock lock (:lock store)]
    (.lock lock)
    (try
      (ensure-open! store)
      (f (:connection store))
      (finally (.unlock lock)))))

(declare tighten-store-files!)

(defn transact!
  "Runs an internal database mutation in one explicit SQLite transaction."
  [store f]
  (let [^ReentrantLock lock (:lock store)]
    (.lock lock)
    (try
      (ensure-open! store)
      (let [^Connection connection (:connection store)
            old-auto (.getAutoCommit connection)]
        (try
          (.setAutoCommit connection false)
          (let [value (f connection)]
            (tighten-store-files! store)
            (.commit connection)
            value)
          (catch Throwable error
            (try (.rollback connection) (catch Throwable _))
            (throw error))
          (finally (.setAutoCommit connection old-auto))))
      (finally (.unlock lock)))))

(defn- private-dir! [path]
  (let [target (util/path path)
        existed? (Files/exists target (make-array java.nio.file.LinkOption 0))]
    (Files/createDirectories target (make-array FileAttribute 0))
    (value/check! (not (Files/isSymbolicLink target)) :insecure-directory
                 "Private data directory cannot be a symbolic link" {:path (str target)})
    (try
      (if existed?
        (let [mode (PosixFilePermissions/toString (Files/getPosixFilePermissions target (make-array java.nio.file.LinkOption 0)))]
          (value/check! (= "------" (subs mode 3)) :insecure-directory
                       "Existing artifact directory grants group or other access" {:path (str target)}))
        (Files/setPosixFilePermissions target (PosixFilePermissions/fromString "rwx------")))
      (catch UnsupportedOperationException _ nil))
    (str path)))

(defn- artifact-storage-path [store sha]
  (when-let [root (:artifact-dir store)]
    (.resolve (.resolve (util/path root) (subs sha 0 2)) sha)))

(defn- write-content-addressed! [store sha bytes]
  (let [target (artifact-storage-path store sha)
        parent (.getParent target)]
    (private-dir! parent)
    (value/check! (not (Files/isSymbolicLink target)) :artifact-corrupt
                 "Artifact content path cannot be a symbolic link" {:sha256 sha})
    (when-not (Files/exists target (make-array LinkOption 0))
      (let [temp (Files/createTempFile parent ".import-" ".tmp" (make-array FileAttribute 0))]
        (try
          (Files/write temp bytes (make-array OpenOption 0))
          (util/private-file! temp)
          (try
            (Files/move temp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
            (catch java.nio.file.FileAlreadyExistsException _ target))
          (finally (Files/deleteIfExists temp)))))
    (value/check! (= sha (util/sha256 (Files/readAllBytes target))) :artifact-corrupt
                 "Content-addressed artifact file is corrupt" {:sha256 sha})
    (util/private-file! target)
    target))

(defn- persisted-artifact-bytes [store ^Connection connection descriptor]
  (if (:memory? store)
    (first (query-sql connection "SELECT content FROM artifacts WHERE id=? AND session_id=?"
                      [(:id descriptor) (:session-id descriptor)]
                      #(.getBytes ^ResultSet % "content")))
    (let [path (artifact-storage-path store (:sha256 descriptor))]
      (when (and (not (Files/isSymbolicLink path))
                 (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
        (Files/readAllBytes path)))))

(defn- ensure-parent! [path]
  (when-let [parent (.getParent (util/path path))]
    (Files/createDirectories parent (make-array FileAttribute 0))))

(defn- canonical-database-path [path]
  (let [target (.normalize (.toAbsolutePath (util/path path)))
        parent (.getParent target)
        filename (.getFileName target)]
    (value/check! (and parent filename) :invalid-store-path
                 "Session database path must name a file" {:path (str target)})
    (ensure-parent! target)
    (str (.resolve (.toRealPath parent (make-array LinkOption 0)) filename))))

(defn- create-owner-file! [target]
  (when-not (Files/exists target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (try
      (Files/createFile
       target
       (into-array FileAttribute
                   [(PosixFilePermissions/asFileAttribute
                     (PosixFilePermissions/fromString "rw-------"))]))
      (catch FileAlreadyExistsException _ nil)
      (catch UnsupportedOperationException _
        (try
          (Files/createFile target (make-array FileAttribute 0))
          (catch FileAlreadyExistsException _ nil))))))

(defn- acquire-owner! [db-path]
  (let [target (util/path (str db-path ".lock"))]
    (create-owner-file! target)
    (value/check! (not (Files/isSymbolicLink target)) :insecure-database
                 "Store ownership file cannot be a symbolic link" {:path (str target)})
    (let [channel (FileChannel/open
                   target
                   (into-array OpenOption
                               [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
      (try
        (value/check! (Files/isRegularFile target
                                          (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                     :insecure-database "Store ownership path must be a regular file"
                     {:path (str target)})
        (let [owner-lock (try
                           (.tryLock channel)
                           (catch OverlappingFileLockException _ nil))]
          (value/check! owner-lock :store-in-use
                       "Session store is already open in another runtime"
                       {:path db-path})
          (try
            (util/private-file! target)
            {:owner-channel channel :owner-lock owner-lock :owner-path (str target)}
            (catch Throwable error
              (try (.release ^FileLock owner-lock) (catch Throwable _))
              (throw error))))
        (catch Throwable error
          (try (.close channel) (catch Throwable _))
          (throw error))))))

(defn- release-owner! [{:keys [owner-channel owner-lock]}]
  (when owner-lock
    (try
      (when (.isValid ^FileLock owner-lock)
        (.release ^FileLock owner-lock))
      (finally
        (when owner-channel
          (.close ^FileChannel owner-channel))))))

(defn- prepare-database-file! [path]
  (let [target (util/path path)]
    (ensure-parent! path)
    (when-not (Files/exists target (make-array LinkOption 0))
      (Files/createFile target (make-array FileAttribute 0)))
    (value/check! (not (Files/isSymbolicLink target)) :insecure-database
                 "Session database cannot be a symbolic link" {:path (str target)})
    (value/check! (Files/isRegularFile target
                                      (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                 :insecure-database "Session database path must be a regular file"
                 {:path (str target)})
    (doseq [sidecar [(util/path (str path "-wal")) (util/path (str path "-shm"))]]
      (value/check! (not (Files/isSymbolicLink sidecar)) :insecure-database
                   "SQLite sidecar cannot be a symbolic link" {:path (str sidecar)}))
    (util/private-file! path)))

(defn- tighten-store-files! [store]
  (when-let [path (:path store)]
    (doseq [candidate (map util/path [path (str path "-wal") (str path "-shm")])]
      (value/check! (not (Files/isSymbolicLink candidate)) :insecure-database
                   "SQLite storage file cannot be a symbolic link" {:path (str candidate)})
      (when (Files/isRegularFile candidate
                                 (into-array java.nio.file.LinkOption
                                             [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
        (util/private-file! candidate)))))

(defn- execute-script! [^Connection connection statements]
  (with-open [statement (.createStatement connection)]
    (doseq [sql statements] (.executeUpdate statement sql))))

(defn- migrate! [^Connection connection]
  (let [current (long (or (scalar connection "PRAGMA user_version" []) 0))]
    (value/check! (<= current schema-version) :schema-too-new
                 "Store schema was created by a newer Arrodes version"
                 {:found current :supported schema-version})
    (when (< current 1)
      (let [old-auto (.getAutoCommit connection)]
        (try
          (.setAutoCommit connection false)
          (execute-script!
           connection
           ["CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, name TEXT NOT NULL, cwd TEXT NOT NULL, head TEXT, revision INTEGER NOT NULL, base_config TEXT NOT NULL, config TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, metadata TEXT NOT NULL, labels TEXT NOT NULL, parent_id TEXT, fork_entry TEXT)"
            "CREATE TABLE IF NOT EXISTS entries (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, parent_id TEXT, seq INTEGER NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(session_id, seq), FOREIGN KEY(parent_id) REFERENCES entries(id))"
            "CREATE INDEX IF NOT EXISTS entries_session_parent ON entries(session_id, parent_id)"
            "CREATE TABLE IF NOT EXISTS queue (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, seq INTEGER NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, options TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(session_id, seq))"
            "CREATE INDEX IF NOT EXISTS queue_session_seq ON queue(session_id, seq)"
            "CREATE TABLE IF NOT EXISTS operations (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, kind TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, finished_at INTEGER, result TEXT, error TEXT)"
            "CREATE INDEX IF NOT EXISTS operations_session_created ON operations(session_id, created_at)"
            "CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, session_id TEXT, operation_id TEXT, type TEXT NOT NULL, data TEXT NOT NULL, time INTEGER NOT NULL)"
            "CREATE INDEX IF NOT EXISTS events_session_seq ON events(session_id, seq)"
            "CREATE TABLE IF NOT EXISTS artifacts (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, sha256 TEXT NOT NULL, bytes INTEGER NOT NULL, kind TEXT NOT NULL, available INTEGER NOT NULL, created_at INTEGER NOT NULL, name TEXT, content BLOB, UNIQUE(session_id, id))"
            "CREATE INDEX IF NOT EXISTS artifacts_session_created ON artifacts(session_id, created_at)"
            "CREATE TABLE IF NOT EXISTS results (session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id INTEGER NOT NULL, kind TEXT NOT NULL, descriptor TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(session_id, id))"])
          (execute-command! connection "PRAGMA user_version = 1")
          (.commit connection)
          (catch Throwable error
            (try (.rollback connection) (catch Throwable _))
            (throw error))
          (finally (.setAutoCommit connection old-auto)))))))

(defn- expire-live-results! [^Connection connection]
  (doseq [{:keys [session-id id descriptor]}
          (query-sql connection
                     "SELECT session_id,id,descriptor FROM results WHERE kind='live'"
                     []
                     (fn [^ResultSet rs]
                       {:session-id (.getString rs "session_id")
                        :id (.getLong rs "id")
                        :descriptor (decode (.getString rs "descriptor"))}))]
    (execute-sql! connection
                  "UPDATE results SET descriptor=? WHERE session_id=? AND id=?"
                  [(encode (assoc descriptor :available? false)) session-id id])))


(defn open!
  "Opens an independent memory store or exclusively owns a canonical file store."
  [{:keys [path memory? artifact-dir]}]
  (Class/forName "org.sqlite.JDBC")
  (let [memory? (or memory? (nil? path))
        db-path (when-not memory? (canonical-database-path path))
        owner (when db-path (acquire-owner! db-path))]
    (try
      (when db-path (prepare-database-file! db-path))
      (let [url (if memory? "jdbc:sqlite::memory:" (str "jdbc:sqlite:" db-path))
            connection (DriverManager/getConnection url)
            lock (ReentrantLock.)
            closed? (atom false)
            artifact-dir (when-not memory?
                           (util/canonical-path
                            (or artifact-dir (str db-path ".artifacts"))))
            store (map->Store
                   (merge {:connection connection
                           :lock lock
                           :closed? closed?
                           :path db-path
                           :artifact-dir artifact-dir
                           :memory? memory?
                           :opened-at (util/now)}
                          owner))]
        (try
          (execute-command! connection "PRAGMA foreign_keys = ON")
          (execute-command! connection "PRAGMA busy_timeout = 5000")
          (if memory?
            (execute-command! connection "PRAGMA journal_mode = MEMORY")
            (do (execute-command! connection "PRAGMA journal_mode = WAL")
                (execute-command! connection "PRAGMA synchronous = FULL")))
          (tighten-store-files! store)
          (migrate! connection)
          (transact! store expire-live-results!)
          (tighten-store-files! store)
          (when db-path (util/private-file! db-path))
          (when artifact-dir (private-dir! artifact-dir))
          store
          (catch Throwable error
            (try (.close connection) (catch Throwable _))
            (throw error))))
      (catch Throwable error
        (try (release-owner! owner) (catch Throwable _))
        (throw error)))))

(defn close!
  "Closes a store once; repeated calls are harmless."
  [store]
  (when store
    (let [^ReentrantLock lock (:lock store)]
      (.lock lock)
      (try
        (when (compare-and-set! (:closed? store) false true)
          (try
            (tighten-store-files! store)
            (finally
              (try
                (.close ^Connection (:connection store))
                (finally
                  (release-owner! store))))))
        nil
        (finally (.unlock lock))))))

(defn- session-row [^ResultSet rs]
  (cond-> {:id (.getString rs "id")
           :name (.getString rs "name")
           :cwd (.getString rs "cwd")
           :head (.getString rs "head")
           :revision (.getLong rs "revision")
           :config (decode (.getString rs "config"))
           :status (keyword (.getString rs "status"))
           :created-at (.getLong rs "created_at")
           :updated-at (.getLong rs "updated_at")
           :metadata (decode (.getString rs "metadata"))
           :labels (decode (.getString rs "labels"))
           ::base-config (decode (.getString rs "base_config"))}
    (.getString rs "parent_id") (assoc :parent-id (.getString rs "parent_id"))
    (.getString rs "fork_entry") (assoc :fork-entry (.getString rs "fork_entry"))))

(defn- public-session [row] (dissoc row ::base-config))

(defn- find-session [^Connection connection sid]
  (first (query-sql connection "SELECT * FROM sessions WHERE id = ?" [sid] session-row)))

(defn- require-session [^Connection connection sid]
  (require-uuid! sid :session-id)
  (or (find-session connection sid)
      (value/fail! :session-not-found "Session does not exist" {:session-id sid})))

(defn session [store sid]
  (store-read store #(public-session (require-session % sid))))

(defn list-sessions
  ([store] (list-sessions store {}))
  ([store {:keys [cwd]}]
   (store-read store
     (fn [connection]
       (mapv public-session
             (if cwd
               (query-sql connection "SELECT * FROM sessions WHERE cwd = ? ORDER BY updated_at DESC, id" [(util/canonical-path cwd)] session-row)
               (query-sql connection "SELECT * FROM sessions ORDER BY updated_at DESC, id" [] session-row)))))))

(defn- insert-session! [^Connection connection snapshot base-config]
  (execute-sql! connection
                "INSERT INTO sessions(id,name,cwd,head,revision,base_config,config,status,created_at,updated_at,metadata,labels,parent_id,fork_entry) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                [(:id snapshot) (:name snapshot) (:cwd snapshot) (:head snapshot) (:revision snapshot)
                 (encode base-config) (encode (:config snapshot)) (name (:status snapshot))
                 (:created-at snapshot) (:updated-at snapshot) (encode (:metadata snapshot))
                 (encode (:labels snapshot)) (:parent-id snapshot) (:fork-entry snapshot)]))

(defn- prepare-session-options
  [opts]
  (let [created-at (or (:created-at opts) (util/now))]
    (-> opts
        (assoc :id (or (:id opts) (util/id))
               :cwd (util/canonical-path (or (:cwd opts) "."))
               :created-at created-at
               :updated-at (or (:updated-at opts) created-at)))))

(defn create-session! [store opts]
  (let [snapshot (session-model/new-snapshot (prepare-session-options opts))]
    (require-uuid! (:id snapshot) :session-id)
    (value/check! (contains? statuses (:status snapshot)) :invalid-session "Invalid session status" {:status (:status snapshot)})
    (transact! store
      (fn [connection]
        (value/check! (nil? (find-session connection (:id snapshot))) :session-exists
                     "Session ID already exists" {:session-id (:id snapshot)})
        (insert-session! connection snapshot (:config snapshot))
        snapshot))))

(defn- entry-row [^ResultSet rs]
  {:id (.getString rs "id")
   :session-id (.getString rs "session_id")
   :parent-id (.getString rs "parent_id")
   :seq (.getLong rs "seq")
   :kind (keyword (.getString rs "kind"))
   :data (decode (.getString rs "data"))
   :created-at (.getLong rs "created_at")})

(defn- all-entries [^Connection connection sid]
  (query-sql connection "SELECT * FROM entries WHERE session_id = ? ORDER BY seq" [sid] entry-row))

(defn entries [store sid]
  (store-read store (fn [connection] (require-session connection sid) (all-entries connection sid))))

(defn active-path
  ([store sid]
   (store-read store
     (fn [connection]
       (let [snapshot (require-session connection sid)]
         (session-model/active-path (all-entries connection sid) (:head snapshot))))))
  ([store sid leaf]
   (store-read store
     (fn [connection]
       (require-session connection sid)
       (session-model/active-path (all-entries connection sid) leaf)))))

(defn context-messages [store sid]
  (session-model/context-messages (active-path store sid)))

(defn- entry-exists? [^Connection connection sid eid]
  (boolean (scalar connection "SELECT 1 FROM entries WHERE session_id = ? AND id = ?" [sid eid])))

(defn- validate-entry! [^Connection connection sid {:keys [id parent-id kind data created-at]}]
  (require-uuid! id :entry-id)
  (value/check! (contains? entry-kinds kind) :invalid-entry "Invalid entry kind" {:kind kind})
  (value/check! (map? data) :invalid-entry "Entry data must be a map" {:kind kind})
  (value/check! (and (integer? created-at) (not (neg? created-at))) :invalid-entry
               "Entry creation time must be a non-negative integer" {:created-at created-at})
  (when parent-id
    (require-uuid! parent-id :parent-id)
    (value/check! (entry-exists? connection sid parent-id) :invalid-branch
                 "Entry parent is not part of this session" {:session-id sid :parent-id parent-id}))
  (case kind
    :config
    (value/check! (= data (session-model/normalize-config data)) :invalid-entry
                 "Configuration entries must contain a complete normalized configuration" {})

    :compaction
    (let [kept (:first-kept-entry-id data)
          ancestors (set (map :id (session-model/active-path
                                   (all-entries connection sid) parent-id)))]
      (require-uuid! kept :first-kept-entry-id)
      (value/check! (contains? ancestors kept) :invalid-entry
                   "Compaction retained entry must be on its preceding active path"
                   {:first-kept-entry-id kept})
      (value/check! (string? (:summary data)) :invalid-entry
                   "Compaction summary must be a string" {})
      (value/check! (map? (or (:usage data) {})) :invalid-entry
                   "Compaction usage must be a map" {}))

    :branch-summary
    (do
      (value/check! (string? (:summary data)) :invalid-entry
                   "Branch summary must be a string" {})
      (when-let [from-id (:from-id data)]
        (require-uuid! from-id :from-id)
        (value/check! (entry-exists? connection sid from-id) :invalid-entry
                     "Branch summary source is not part of this session"
                     {:from-id from-id})))

    :label
    (let [entry-id (:entry-id data)]
      (require-uuid! entry-id :entry-id)
      (value/check! (entry-exists? connection sid entry-id) :invalid-entry
                   "Label target is not part of this session" {:entry-id entry-id}))

    nil))

(defn- operation-row [^ResultSet rs]
  (cond-> {:id (.getString rs "id")
           :session-id (.getString rs "session_id")
           :kind (keyword (.getString rs "kind"))
           :status (keyword (.getString rs "status"))
           :created-at (.getLong rs "created_at")}
    (not= 0 (.getLong rs "finished_at")) (assoc :finished-at (.getLong rs "finished_at"))
    (.getString rs "result") (assoc :result (decode (.getString rs "result")))
    (.getString rs "error") (assoc :error (decode (.getString rs "error")))))

(defn- find-operation [^Connection connection oid]
  (first (query-sql connection "SELECT * FROM operations WHERE id = ?" [oid] operation-row)))

(def ^:private operation-transitions
  {:queued #{:queued :running :cancelling :completed :failed :cancelled :interrupted}
   :running #{:running :cancelling :completed :failed :cancelled :interrupted}
   :cancelling #{:cancelling :completed :failed :cancelled :interrupted}
   :completed #{:completed}
   :failed #{:failed}
   :cancelled #{:cancelled}
   :interrupted #{:interrupted}})

(defn- normalize-operation [sid prior operation]
  (let [allowed #{:id :session-id :kind :status :created-at :finished-at :result :error}
        unknown (seq (remove allowed (keys operation)))]
    (value/check! (nil? unknown) :invalid-operation
                 "Operation contains unsupported fields" {:fields (vec unknown)})
    (when prior
      (value/check! (= sid (:session-id prior)) :operation-forbidden
                   "Operation belongs to another session" {:operation-id (:id prior)})
      (doseq [field [:session-id :kind :created-at]]
        (when (contains? operation field)
          (value/check! (= (get prior field) (get operation field)) :invalid-operation
                       "Operation identity fields are immutable"
                       {:operation-id (:id prior) :field field}))))
    (let [operation (if prior
                      (merge prior operation)
                      (merge {:id (util/id) :session-id sid :status :queued
                              :created-at (util/now)}
                             operation))]
      (require-uuid! (:id operation) :operation-id)
      (value/check! (= sid (:session-id operation)) :invalid-operation
                   "Operation belongs to another session" {:operation-id (:id operation)})
      (value/check! (contains? operation-kinds (:kind operation)) :invalid-operation
                   "Invalid operation kind" {:kind (:kind operation)})
      (value/check! (contains? operation-statuses (:status operation)) :invalid-operation
                   "Invalid operation status" {:status (:status operation)})
      (value/check! (and (integer? (:created-at operation)) (not (neg? (:created-at operation))))
                   :invalid-operation "Operation creation time must be a non-negative integer" {})
      (when (contains? operation :finished-at)
        (value/check! (and (integer? (:finished-at operation))
                          (not (neg? (:finished-at operation))))
                     :invalid-operation "Operation finish time must be a non-negative integer" {}))
      (when prior
        (value/check! (contains? (get operation-transitions (:status prior) #{})
                                (:status operation))
                     :invalid-operation-transition "Operation status cannot move backward"
                     {:operation-id (:id operation)
                      :from (:status prior) :to (:status operation)}))
      operation)))

(defn- upsert-operation! [^Connection connection operation]
  (execute-sql! connection
                "INSERT INTO operations(id,session_id,kind,status,created_at,finished_at,result,error) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET kind=excluded.kind,status=excluded.status,finished_at=excluded.finished_at,result=excluded.result,error=excluded.error"
                [(:id operation) (:session-id operation) (name (:kind operation)) (name (:status operation))
                 (:created-at operation) (:finished-at operation)
                 (when (contains? operation :result) (encode (:result operation)))
                 (when (contains? operation :error) (encode (:error operation)))])
  operation)

(defn- insert-event! [^Connection connection sid event]
  (let [id (or (:id event) (util/id))
        time (or (:time event) (util/now))
        data (or (:data event) {})]
    (require-uuid! id :event-id)
    (value/check! (keyword? (:type event)) :invalid-event
                 "Event type must be a keyword" {:type (:type event)})
    (value/check! (map? data) :invalid-event "Event data must be a map" {})
    (value/check! (and (integer? time) (not (neg? time))) :invalid-event
                 "Event time must be a non-negative integer" {:time time})
    (when-let [oid (:operation-id event)]
      (require-uuid! oid :operation-id)
      (let [operation (find-operation connection oid)]
        (value/check! operation :operation-not-found
                     "Event operation does not exist" {:operation-id oid})
        (value/check! (= sid (:session-id operation)) :operation-forbidden
                     "Event operation belongs to another session" {:operation-id oid})))
    (execute-sql! connection
                  "INSERT INTO events(id,session_id,operation_id,type,data,time) VALUES(?,?,?,?,?,?)"
                  [id sid (:operation-id event) (encode (:type event)) (encode data) time])
    {:id id
     :seq (long (scalar connection "SELECT last_insert_rowid()" []))
     :session-id sid
     :operation-id (:operation-id event)
     :type (:type event)
     :data data
     :time time}))

(defn- update-session! [^Connection connection snapshot]
  (execute-sql! connection
                "UPDATE sessions SET name=?,cwd=?,head=?,revision=?,config=?,status=?,updated_at=?,metadata=?,labels=? WHERE id=?"
                [(:name snapshot) (:cwd snapshot) (:head snapshot) (:revision snapshot)
                 (encode (:config snapshot)) (name (:status snapshot)) (:updated-at snapshot)
                 (encode (:metadata snapshot)) (encode (:labels snapshot)) (:id snapshot)]))

(defn- normalize-session-changes [snapshot changes]
  (let [allowed #{:name :cwd :head :config :status :metadata :labels}
        unknown (seq (remove allowed (keys changes)))]
    (value/check! (nil? unknown) :invalid-session-change "Unsupported session field" {:fields (vec unknown)})
    (let [result (merge snapshot changes)
          result (cond-> result
                   (contains? changes :cwd) (update :cwd util/canonical-path)
                   (contains? changes :config) (update :config session-model/normalize-config)
                   (contains? changes :labels) (update :labels vec))]
      (value/check! (and (string? (:name result)) (not (str/blank? (:name result)))) :invalid-session "Session name must not be blank" {})
      (value/check! (contains? statuses (:status result)) :invalid-session "Invalid session status" {:status (:status result)})
      (value/check! (map? (:metadata result)) :invalid-session "Session metadata must be a map" {})
      result)))

(defn commit!
  "Atomically applies entries, session projection, queues, operation, and durable events."
  [store sid command]
  (transact! store
    (fn [connection]
      (let [current (require-session connection sid)
            expected (:expected-revision command)]
        (when (some? expected)
          (value/check! (integer? expected) :invalid-revision
                       "Expected revision must be an integer" {:expected expected})
          (value/check! (= (long expected) (:revision current)) :stale-revision
                       "Session revision has changed"
                       {:session-id sid :expected expected :actual (:revision current)}))
        (let [start-seq (long (or (scalar connection "SELECT MAX(seq) FROM entries WHERE session_id = ?" [sid]) 0))
              raw-entries (vec (or (:entries command) []))
              committed
              (loop [remaining raw-entries, parent (:head current), seq (inc start-seq), result []]
                (if-let [raw (first remaining)]
                  (let [entry (merge {:id (util/id)
                                      :session-id sid
                                      :parent-id parent
                                      :created-at (util/now)} raw {:seq seq :session-id sid})]
                    (validate-entry! connection sid entry)
                    (value/check! (nil? (scalar connection "SELECT 1 FROM entries WHERE id = ?" [(:id entry)]))
                                 :entry-exists "Entry ID already exists" {:entry-id (:id entry)})
                    (execute-sql! connection
                                  "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                                  [(:id entry) sid (:parent-id entry) seq (name (:kind entry)) (encode (:data entry)) (:created-at entry)])
                    (recur (next remaining) (:id entry) (inc seq) (conj result entry)))
                  result))
              implicit-head (if (seq committed) (:id (last committed)) (:head current))
              changes (assoc (or (:session command) {}) :head
                             (if (contains? (or (:session command) {}) :head)
                               (get-in command [:session :head]) implicit-head))
              _ (when-let [head (:head changes)]
                  (require-uuid! head :head)
                  (value/check! (entry-exists? connection sid head) :invalid-branch
                               "Selected head is not part of this session" {:entry-id head}))
              project-config? (or (some #(= :config (:kind %)) committed)
                                  (contains? (or (:session command) {}) :head))
              projected-config
              (when project-config?
                (session-model/effective-config
                 (::base-config current)
                 (session-model/active-path (all-entries connection sid) (:head changes))))
              changes (if (and project-config?
                               (not (contains? (or (:session command) {}) :config)))
                        (assoc changes :config projected-config)
                        changes)
              now (util/now)
              updated (-> (normalize-session-changes current changes)
                          (assoc :revision (inc (:revision current)) :updated-at now))
              _ (doseq [id (or (:queue-deliver command) [])]
                  (require-uuid! id :queue-id)
                  (value/check! (= 1 (execute-sql! connection "DELETE FROM queue WHERE id = ? AND session_id = ?" [id sid]))
                               :queue-item-not-found "Queue item does not exist" {:queue-id id :session-id sid}))
              queue-seq (volatile! (long (or (scalar connection "SELECT MAX(seq) FROM queue WHERE session_id = ?" [sid]) 0)))
              _ (doseq [raw (or (:queue-enqueue command) [])]
                  (let [item (merge {:id (util/id) :options {} :created-at now} raw)]
                    (require-uuid! (:id item) :queue-id)
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
                    (execute-sql! connection
                                  "INSERT INTO queue(id,session_id,seq,kind,content,options,created_at) VALUES(?,?,?,?,?,?,?)"
                                  [(:id item) sid @queue-seq (name (:kind item)) (encode (:content item))
                                   (encode (or (:options item) {})) (:created-at item)])))
              operation (when-let [raw (:operation command)]
                          (let [prior (when-let [oid (:id raw)] (find-operation connection oid))]
                            (upsert-operation! connection (normalize-operation sid prior raw))))
              _ (update-session! connection updated)
              entry-events
              (mapv #(insert-event! connection sid
                                    {:type :entry/committed :data {:entry %}})
                    committed)
              events
              (into entry-events
                    (map #(insert-event! connection sid %)
                         (or (:events command) [])))]
          {:session (public-session updated)
           :entries committed
           :events events
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
                          (assoc :metadata (merge (:metadata snapshot) (:metadata changes))))]
    (commit! store sid {:expected-revision (if (contains? changes :expected-revision)
                                             (:expected-revision changes)
                                             (:revision snapshot))
                        :entries [{:kind :config :data config}]
                        :session session-changes
                        :events [{:type :session/configured :data {:config config}}]})))

(declare pending-tool-calls tool-boundary-entries)

(defn branch!
  "Selects a prior entry and appends explicit errors for tool calls cut by the boundary."
  [store sid leaf opts]
  (let [selection (store-read store
                    (fn [connection]
                      (let [row (require-session connection sid)
                            path (session-model/active-path (all-entries connection sid) leaf)]
                        {:revision (:revision row)
                         :path path
                         :config (session-model/effective-config (::base-config row) path)})))
        boundary (tool-boundary-entries (:path selection))
        boundary (if (seq boundary)
                   (assoc-in boundary [0 :parent-id] leaf)
                   boundary)
        session-changes (cond-> {:config (:config selection) :status :idle}
                          (empty? boundary) (assoc :head leaf))]
    (commit! store sid {:expected-revision (or (:expected-revision opts) (:revision selection))
                        :entries boundary
                        :session session-changes
                        :events [{:type :session/branched :data {:entry-id leaf}}]})))

(defn- remap-entry-refs [data id-map]
  (let [reference-keys #{:first-kept-entry-id :from-id :entry-id :fork-entry}]
    (letfn [(walk [value]
              (cond
                (map? value)
                (reduce-kv
                 (fn [result key nested]
                   (if (and (contains? reference-keys key) (string? nested))
                     (if-let [replacement (get id-map nested)]
                       (assoc result key replacement)
                       (if (= :from-id key)
                         result
                         (assoc result key nested)))
                     (assoc result key (walk nested))))
                 {} value)
                (vector? value) (mapv walk value)
                (set? value) (set (map walk value))
                (sequential? value) (mapv walk value)
                :else value))]
      (walk data))))

(declare remap-entry-result-refs)

(defn- referenced-result-ids [entries]
  (letfn [(walk [value]
            (cond
              (map? value)
              (reduce-kv (fn [ids key item]
                           (let [ids (if (and (contains? #{:result :message/result} key)
                                              (map? item) (integer? (:id item)))
                                       (conj ids (:id item))
                                       ids)]
                             (into ids (walk item))))
                         #{} value)
              (sequential? value) (reduce into #{} (map walk value))
              (set? value) (reduce into #{} (map walk value))
              :else #{}))]
    (reduce into #{} (map #(walk (:data %)) entries))))

(defn- artifact-descriptor? [value]
  (and (map? value)
       (uuid? (:id value))
       (string? (:session-id value))
       (string? (:sha256 value))
       (integer? (:bytes value))
       (contains? #{:text :edn :binary} (:kind value))))

(defn- referenced-artifact-ids [value]
  (letfn [(walk [item]
            (cond
              (map? item)
              (reduce-kv
               (fn [ids key nested]
                 (let [ids (if (and (= :artifact-id key) (uuid? nested))
                             (conj ids nested)
                             ids)]
                   (into ids (walk nested))))
               (if (artifact-descriptor? item) #{(:id item)} #{})
               item)
              (sequential? item) (reduce into #{} (map walk item))
              (set? item) (reduce into #{} (map walk item))
              :else #{}))]
    (walk value)))

(defn- remap-artifact-refs [value artifact-by-old]
  (letfn [(walk [item]
            (cond
              (map? item)
              (if-let [replacement (when (artifact-descriptor? item)
                                     (get artifact-by-old (:id item)))]
                replacement
                (into {}
                      (map (fn [[key nested]]
                             [key (if (and (= :artifact-id key)
                                           (contains? artifact-by-old nested))
                                    (:id (get artifact-by-old nested))
                                    (walk nested))]))
                      item))
              (vector? item) (mapv walk item)
              (set? item) (set (map walk item))
              (sequential? item) (mapv walk item)
              :else item))]
    (walk value)))

(defn- copy-value-records! [store ^Connection connection old-sid new-sid entries now]
  (let [wanted (referenced-result-ids entries)
        source-results (->> (query-sql connection
                                       "SELECT descriptor FROM results WHERE session_id=? ORDER BY id"
                                       [old-sid] #(decode (.getString ^ResultSet % "descriptor")))
                            (filterv #(contains? wanted (:id %))))
        found (set (map :id source-results))
        _ (value/check! (= wanted found) :result-not-found
                       "Copied history references missing durable results"
                       {:result-ids (vec (sort (set/difference wanted found)))})
        artifact-ids (set/union (referenced-artifact-ids entries)
                                (referenced-artifact-ids source-results))
        source-artifacts
        (mapv (fn [artifact-id]
                (let [row (first (query-sql connection "SELECT * FROM artifacts WHERE session_id=? AND id=?"
                                            [old-sid artifact-id]
                                            (fn [^ResultSet rs]
                                              (cond-> {:id (.getString rs "id")
                                                       :session-id (.getString rs "session_id")
                                                       :sha256 (.getString rs "sha256")
                                                       :bytes (.getLong rs "bytes")
                                                       :kind (keyword (.getString rs "kind"))
                                                       :available? (pos? (.getInt rs "available"))
                                                       :created-at (.getLong rs "created_at")}
                                                (.getString rs "name") (assoc :name (.getString rs "name"))))))]
                  (value/check! row :artifact-not-found
                               "Copied history references a missing artifact"
                               {:artifact-id artifact-id})
                  row))
              artifact-ids)
        artifact-id-map (into {} (map (fn [descriptor] [(:id descriptor) (util/id)]) source-artifacts))
        imported-artifacts
        (mapv (fn [descriptor]
                (let [source-bytes (when (:available? descriptor)
                                     (persisted-artifact-bytes store connection descriptor))
                      available? (and source-bytes
                                      (= (:bytes descriptor) (alength ^bytes source-bytes))
                                      (= (:sha256 descriptor) (util/sha256 source-bytes)))
                      copied (cond-> {:id (get artifact-id-map (:id descriptor))
                                      :session-id new-sid
                                      :sha256 (:sha256 descriptor)
                                      :bytes (:bytes descriptor)
                                      :kind (:kind descriptor)
                                      :available? (boolean available?)
                                      :created-at now}
                               (:name descriptor) (assoc :name (:name descriptor)))]
                  {:descriptor copied
                   :bytes (when (:memory? store) source-bytes)}))
              source-artifacts)
        artifact-by-old (into {} (map (fn [old copied] [(:id old) (:descriptor copied)])
                                      source-artifacts imported-artifacts))
        result-id-map (into {} (map-indexed (fn [index descriptor] [(:id descriptor) (inc index)])
                                            source-results))
        imported-results
        (into {}
              (map (fn [descriptor]
                     (let [artifact (get artifact-by-old (:artifact-id descriptor))
                           copied (cond-> {:id (get result-id-map (:id descriptor))
                                           :session-id new-sid
                                           :kind (:kind descriptor)
                                           :content (or (:content descriptor) "")
                                           :details (remap-artifact-refs
                                                     (or (:details descriptor) {})
                                                     artifact-by-old)
                                           :available? (case (:kind descriptor)
                                                         :live false
                                                         :artifact (and (:available? descriptor)
                                                                        (:available? artifact))
                                                         (boolean (:available? descriptor)))}
                                    (= :inline (:kind descriptor)) (assoc :value (:value descriptor))
                                    (= :artifact (:kind descriptor)) (assoc :artifact-id (:id artifact)))]
                       [(:id descriptor) copied])))
              source-results)]
    (doseq [{:keys [descriptor bytes]} imported-artifacts]
      (execute-sql! connection
                    "INSERT INTO artifacts(id,session_id,sha256,bytes,kind,available,created_at,name,content) VALUES(?,?,?,?,?,?,?,?,?)"
                    [(:id descriptor) new-sid (:sha256 descriptor) (:bytes descriptor)
                     (name (:kind descriptor)) (if (:available? descriptor) 1 0)
                     now (:name descriptor) (when (:memory? store) bytes)]))
    (doseq [descriptor (sort-by :id (vals imported-results))]
      (execute-sql! connection
                    "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                    [new-sid (:id descriptor) (name (:kind descriptor)) (encode descriptor) now]))
    {:results imported-results :artifacts artifact-by-old}))

(defn- copy-path! [store ^Connection connection source-row source-path opts]
  (let [new-sid (or (:id opts) (util/id))
        _ (require-uuid! new-sid :session-id)
        id-map (into {} (map (fn [entry] [(:id entry) (util/id)]) source-path))
        now (util/now)
        config (session-model/effective-config (::base-config source-row) source-path)
        copied-source-head (get id-map (:id (last source-path)))
        raw-boundary (tool-boundary-entries source-path)
        final-head (or (:id (last raw-boundary)) copied-source-head)
        snapshot (-> (session-model/new-snapshot
                      (prepare-session-options
                       {:id new-sid
                        :name (or (:name opts) (str (:name source-row) " copy"))
                        :cwd (or (:cwd opts) (:cwd source-row))
                        :config config
                        :status :idle
                        :created-at now
                        :metadata (or (:metadata opts) (:metadata source-row))
                        :labels (remap-entry-refs (:labels source-row) id-map)
                        :parent-id (:id source-row)
                        :fork-entry (:source-leaf opts)}))
                     (assoc :head final-head))]
    (value/check! (nil? (find-session connection new-sid)) :session-exists
                 "Session ID already exists" {:session-id new-sid})
    (insert-session! connection snapshot (::base-config source-row))
    (let [value-records (copy-value-records! store connection (:id source-row)
                                             new-sid source-path now)
          imported-results (:results value-records)
          imported-artifacts (:artifacts value-records)
          copied-source
          (mapv (fn [entry]
                  (-> entry
                      (assoc :id (get id-map (:id entry))
                             :session-id new-sid
                             :parent-id (get id-map (:parent-id entry))
                             :data (-> (:data entry)
                                       (remap-entry-refs id-map)
                                       (remap-entry-result-refs imported-results)
                                       (remap-artifact-refs imported-artifacts)))))
                source-path)
          start-seq (inc (long (reduce max 0 (map :seq source-path))))
          boundary
          (loop [remaining raw-boundary
                 parent copied-source-head
                 seq start-seq
                 result []]
            (if-let [raw (first remaining)]
              (let [entry (assoc raw :session-id new-sid :parent-id parent
                                 :seq seq :created-at now)]
                (recur (next remaining) (:id entry) (inc seq) (conj result entry)))
              result))
          copied (into copied-source boundary)]
      (doseq [entry copied]
        (execute-sql! connection
                      "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                      [(:id entry) new-sid (:parent-id entry) (:seq entry) (name (:kind entry))
                       (encode (:data entry)) (:created-at entry)])))
    snapshot))

(defn fork!
  "Copies a selected root-to-entry path into a fresh session with remapped entry references."
  [store sid opts]
  (transact! store
    (fn [connection]
      (let [source (require-session connection sid)
            expected (:expected-revision opts)
            _ (when (some? expected)
                (value/check! (and (integer? expected)
                                  (= (long expected) (:revision source)))
                             :stale-revision "Session revision has changed"
                             {:session-id sid :expected expected
                              :actual (:revision source)}))
            requested (or (:entry-id opts) (:head source))
            _ (value/check! requested :invalid-fork "Cannot fork an empty session" {:session-id sid})
            target (case (or (:position opts) :at)
                     :at requested
                     :before (let [entry (first (query-sql connection "SELECT * FROM entries WHERE session_id=? AND id=?" [sid requested] entry-row))]
                               (value/check! entry :entry-not-found "Fork entry does not exist" {:entry-id requested})
                               (:parent-id entry))
                     (value/fail! :invalid-fork "Fork position must be :before or :at" {:position (:position opts)}))
            path (if target (session-model/active-path (all-entries connection sid) target) [])]
        (copy-path! store connection source path (assoc opts :source-leaf requested))))))

(defn clone!
  "Copies the full active path into a fresh session."
  [store sid opts]
  (transact! store
    (fn [connection]
      (let [source (require-session connection sid)
            expected (:expected-revision opts)
            _ (when (some? expected)
                (value/check! (and (integer? expected)
                                  (= (long expected) (:revision source)))
                             :stale-revision "Session revision has changed"
                             {:session-id sid :expected expected
                              :actual (:revision source)}))
            path (session-model/active-path (all-entries connection sid) (:head source))]
        (copy-path! store connection source path (assoc opts :source-leaf (:head source)))))))

(defn delete-session! [store sid]
  (transact! store
    (fn [connection]
      (require-session connection sid)
      (execute-sql! connection "DELETE FROM sessions WHERE id = ?" [sid])
      {:deleted sid})))

(defn- queue-row [^ResultSet rs]
  {:id (.getString rs "id")
   :session-id (.getString rs "session_id")
   :seq (.getLong rs "seq")
   :kind (keyword (.getString rs "kind"))
   :content (decode (.getString rs "content"))
   :options (decode (.getString rs "options"))
   :created-at (.getLong rs "created_at")})

(defn- require-queue-item [^Connection connection sid qid]
  (require-uuid! qid :queue-id)
  (or (first (query-sql connection
                        "SELECT * FROM queue WHERE id = ? AND session_id = ?"
                        [qid sid] queue-row))
      (value/fail! :queue-item-not-found
                   "Queue item does not exist or was already delivered"
                   {:queue-id qid :session-id sid})))

(defn update-queue!
  "Atomically updates one pending item's content without changing its identity or position."
  [store sid qid content]
  (transact! store
    (fn [connection]
      (let [current (require-session connection sid)
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
            _ (value/check! (= 1 (execute-sql! connection
                                               "UPDATE queue SET content = ? WHERE id = ? AND session_id = ?"
                                               [(encode content) qid sid]))
                            :queue-item-not-found
                            "Queue item does not exist or was already delivered"
                            {:queue-id qid :session-id sid})
            _ (update-session! connection updated-session)
            event (insert-event! connection sid
                                 {:type :queue/updated
                                  :data {:item updated-item}
                                  :time now})]
        {:session (public-session updated-session)
         :item updated-item
         :events [event]}))))

(defn drop-queue!
  "Atomically removes one pending item and returns the exact removed value."
  [store sid qid]
  (transact! store
    (fn [connection]
      (let [current (require-session connection sid)
            item (require-queue-item connection sid qid)
            now (util/now)
            updated-session (assoc current
                                   :revision (inc (:revision current))
                                   :updated-at now)
            _ (value/check! (= 1 (execute-sql! connection
                                               "DELETE FROM queue WHERE id = ? AND session_id = ?"
                                               [qid sid]))
                            :queue-item-not-found
                            "Queue item does not exist or was already delivered"
                            {:queue-id qid :session-id sid})
            _ (update-session! connection updated-session)
            event (insert-event! connection sid
                                 {:type :queue/removed
                                  :data {:id qid}
                                  :time now})]
        {:session (public-session updated-session)
         :removed item
         :events [event]}))))

(defn pending [store sid]
  (store-read store
    (fn [connection]
      (require-session connection sid)
      (query-sql connection "SELECT * FROM queue WHERE session_id = ? ORDER BY seq" [sid] queue-row))))

(defn operation [store oid]
  (store-read store
    (fn [connection]
      (require-uuid! oid :operation-id)
      (or (find-operation connection oid)
          (value/fail! :operation-not-found "Operation does not exist" {:operation-id oid})))))

(defn operations
  ([store] (operations store {}))
  ([store {:keys [session-id]}]
   (store-read store
     (fn [connection]
       (when session-id (require-session connection session-id))
       (if session-id
         (query-sql connection "SELECT * FROM operations WHERE session_id = ? ORDER BY created_at DESC, id" [session-id] operation-row)
         (query-sql connection "SELECT * FROM operations ORDER BY created_at DESC, id" [] operation-row))))))

(defn- event-row [^ResultSet rs]
  (cond-> {:id (.getString rs "id")
           :seq (.getLong rs "seq")
           :type (decode (.getString rs "type"))
           :data (decode (.getString rs "data"))
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
    (store-read store
      (fn [connection]
        (when session-id (require-session connection session-id))
        (if session-id
          (query-sql connection "SELECT * FROM events WHERE seq > ? AND session_id = ? ORDER BY seq LIMIT ?"
                     [(long after) session-id limit] event-row)
          (query-sql connection "SELECT * FROM events WHERE seq > ? ORDER BY seq LIMIT ?"
                     [(long after) limit] event-row))))))

(defn- pending-tool-calls [path]
  (let [{:keys [order calls]}
        (reduce
         (fn [{:keys [order calls] :as state} entry]
           (if-not (= :message (:kind entry))
             state
             (let [message (:data entry)]
               (case (:message/role message)
                 :assistant
                 (reduce
                  (fn [{:keys [order calls]} call]
                    (let [id (:tool-call/id call)]
                      (if (nil? id)
                        {:order order :calls calls}
                        {:order (if (contains? calls id) order (conj order id))
                         :calls (assoc calls id call)})))
                  state (or (:message/tool-calls message) []))

                 :tool
                 (let [id (:message/tool-call-id message)]
                   {:order (vec (remove #{id} order))
                    :calls (dissoc calls id)})

                 state))))
         {:order [] :calls {}} path)]
    (mapv (fn [id] [id (get calls id)])
          (filter #(contains? calls %) order))))

(def ^:private branch-boundary-message
  (str "Tool result is unavailable at this branch boundary. "
       "Selecting history did not undo external effects, and this tool call was not replayed."))

(defn- tool-boundary-entries [path]
  (mapv
   (fn [[_ call]]
     {:id (util/id)
      :kind :message
      :data {:message/role :tool
             :message/tool-call-id (:tool-call/id call)
             :message/name (:tool-call/name call)
             :message/content branch-boundary-message}})
   (pending-tool-calls path)))

(defn recover!
  "Marks unfinished operations interrupted and closes pending tool calls without replaying effects."
  [store]
  (transact! store
    (fn [connection]
      (let [active (query-sql connection
                              "SELECT * FROM operations WHERE status IN ('queued','running','cancelling') ORDER BY created_at"
                              [] operation-row)
            by-session (group-by :session-id active)
            now (util/now)
            events (transient [])]
        (doseq [[sid ops] by-session]
          (let [snapshot (require-session connection sid)
                all (all-entries connection sid)
                path (session-model/active-path all (:head snapshot))
                pending-calls (pending-tool-calls path)
                start-seq (long (or (scalar connection "SELECT MAX(seq) FROM entries WHERE session_id=?" [sid]) 0))
                result-seq (volatile! (long (or (scalar connection "SELECT MAX(id) FROM results WHERE session_id=?" [sid]) 0)))
                final-head (volatile! (:head snapshot))]
            (doseq [[index [_ call]] (map-indexed vector pending-calls)]
              (let [result-id (vswap! result-seq inc)
                    result {:id result-id
                            :session-id sid
                            :kind :inline
                            :value nil
                            :content "Tool execution interrupted"
                            :details {:error/code "interrupted" :replayed? false}
                            :available? true}
                    entry {:id (util/id)
                           :session-id sid
                           :parent-id @final-head
                           :seq (+ start-seq index 1)
                           :kind :message
                           :data {:message/role :tool
                                  :message/tool-call-id (:tool-call/id call)
                                  :message/name (:tool-call/name call)
                                  :message/content "Tool execution was interrupted; the external effect was not replayed."
                                  :message/result result}
                           :created-at now}]
                (execute-sql! connection
                              "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                              [sid result-id "inline" (encode result) now])
                (execute-sql! connection
                              "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                              [(:id entry) sid (:parent-id entry) (:seq entry) "message" (encode (:data entry)) now])
                (vreset! final-head (:id entry))))
            (doseq [op ops]
              (execute-sql! connection "UPDATE operations SET status='interrupted',finished_at=?,error=? WHERE id=?"
                            [now (encode {:code "interrupted" :message "Process restarted before the operation completed"}) (:id op)])
              (conj! events (insert-event! connection sid
                                           {:operation-id (:id op)
                                            :type :operation/interrupted
                                            :data {:reason :restart}
                                            :time now})))
            (update-session! connection (assoc snapshot :head @final-head :status :interrupted
                                               :revision (inc (:revision snapshot)) :updated-at now))))
        (persistent! events)))))

(defn- export-artifact-row [store connection ^ResultSet rs]
  (let [descriptor (cond-> {:id (.getString rs "id")
                            :session-id (.getString rs "session_id")
                            :sha256 (.getString rs "sha256")
                            :bytes (.getLong rs "bytes")
                            :kind (keyword (.getString rs "kind"))
                            :available? (pos? (.getInt rs "available"))
                            :created-at (.getLong rs "created_at")}
                     (.getString rs "name") (assoc :name (.getString rs "name")))
        bytes (when (:available? descriptor)
                (if (:memory? store)
                  (.getBytes rs "content")
                  (persisted-artifact-bytes store connection descriptor)))
        intact? (and bytes
                     (= (:bytes descriptor) (alength ^bytes bytes))
                     (= (:sha256 descriptor) (util/sha256 bytes)))
        descriptor (assoc descriptor :available? (boolean intact?))]
    (cond-> {:descriptor descriptor}
      intact? (assoc :encoding :base64
                     :content (.encodeToString (Base64/getEncoder) bytes)))))

(defn- export-artifacts [store connection sid]
  (let [records (query-sql connection
                          "SELECT * FROM artifacts WHERE session_id=? ORDER BY created_at,id"
                          [sid] #(export-artifact-row store connection %))
        total (reduce + 0 (keep #(when (:content %) (get-in % [:descriptor :bytes])) records))]
    (value/check! (<= total max-transfer-artifact-bytes) :session-export-too-large
                 "Session artifacts exceed the bounded export transfer size"
                 {:bytes total :limit max-transfer-artifact-bytes})
    records))

(defn- export-results [connection sid artifact-records]
  (let [artifact-availability
        (into {} (map (fn [{:keys [descriptor]}]
                        [(:id descriptor) (:available? descriptor)])
                      artifact-records))]
    (mapv (fn [descriptor]
            (case (:kind descriptor)
              :live (assoc descriptor :available? false)
              :artifact
              (do
                (value/check! (contains? artifact-availability (:artifact-id descriptor))
                             :artifact-not-found
                             "Result references an artifact missing from the session"
                             {:result-id (:id descriptor)
                              :artifact-id (:artifact-id descriptor)})
                (assoc descriptor :available?
                       (boolean (and (:available? descriptor)
                                     (get artifact-availability (:artifact-id descriptor))))))
              descriptor))
          (query-sql connection "SELECT descriptor FROM results WHERE session_id=? ORDER BY id"
                     [sid] #(decode (.getString ^ResultSet % "descriptor"))))))

(defn export-session [store sid]
  (store-read store
    (fn [connection]
      (let [snapshot (require-session connection sid)
            artifacts (export-artifacts store connection sid)]
        {:format "arrodes-session"
         :version 1
         :session (public-session snapshot)
         :base-config (::base-config snapshot)
         :entries (all-entries connection sid)
         :results (export-results connection sid artifacts)
         :artifacts artifacts}))))
(defn- decode-transfer-bytes [record]
  (when (contains? record :content)
    (value/check! (contains? #{:base64 "base64"} (:encoding record)) :invalid-import
                 "Artifact transfer encoding must be base64" {})
    (try
      (.decode (Base64/getDecoder) ^String (:content record))
      (catch Throwable error
        (value/fail! :invalid-import "Artifact contains invalid base64"
                    {:cause (ex-message error)})))))

(defn- validate-import-artifacts! [records source-sid]
  (value/check! (vector? records) :invalid-import "Export artifacts must be a vector" {})
  (let [ids (mapv #(get-in % [:descriptor :id]) records)
        _ (value/check! (= (count ids) (count (set ids))) :invalid-import
                       "Export contains duplicate artifact IDs" {})
        validated
        (mapv (fn [record]
                (value/check! (map? record) :invalid-import
                             "Artifact transfer record must be a map" {})
                (let [descriptor (:descriptor record)
                      _ (value/check! (map? descriptor) :invalid-import
                                     "Artifact descriptor must be a map" {})
                      bytes (decode-transfer-bytes record)]
                  (require-uuid! (:id descriptor) :artifact-id)
                  (value/check! (= source-sid (:session-id descriptor)) :invalid-import
                               "Artifact belongs to another exported session" {:artifact-id (:id descriptor)})
                  (value/check! (contains? #{:text :edn :binary} (:kind descriptor)) :invalid-import
                               "Artifact has an invalid kind" {:artifact-id (:id descriptor)})
                  (value/check! (boolean? (:available? descriptor)) :invalid-import
                               "Artifact availability must be boolean" {:artifact-id (:id descriptor)})
                  (value/check! (= (:available? descriptor) (boolean bytes)) :invalid-import
                               "Artifact content and availability disagree" {:artifact-id (:id descriptor)})
                  (value/check! (or (contains? record :content)
                                   (not (contains? record :encoding)))
                               :invalid-import "Unavailable artifact cannot declare an encoding"
                               {:artifact-id (:id descriptor)})
                  (value/check! (and (string? (:sha256 descriptor))
                                    (boolean (re-matches #"[0-9a-f]{64}" (:sha256 descriptor))))
                               :invalid-import "Artifact digest is invalid" {:artifact-id (:id descriptor)})
                  (value/check! (and (integer? (:bytes descriptor)) (not (neg? (:bytes descriptor))))
                               :invalid-import "Artifact byte count is invalid" {:artifact-id (:id descriptor)})
                  (value/check! (and (integer? (:created-at descriptor))
                                    (not (neg? (:created-at descriptor))))
                               :invalid-import "Artifact creation time is invalid"
                               {:artifact-id (:id descriptor)})
                  (when (contains? descriptor :name)
                    (value/check! (and (string? (:name descriptor))
                                      (not (str/blank? (:name descriptor))))
                                 :invalid-import "Artifact name is invalid"
                                 {:artifact-id (:id descriptor)}))
                  (when bytes
                    (value/check! (and (= (:bytes descriptor) (alength ^bytes bytes))
                                      (= (:sha256 descriptor) (util/sha256 bytes)))
                                 :invalid-import "Artifact content does not match its digest"
                                 {:artifact-id (:id descriptor)}))
                  {:descriptor descriptor :bytes bytes}))
              records)
        total (reduce + 0 (keep #(some-> % :bytes alength) validated))]
    (value/check! (<= total max-transfer-artifact-bytes) :session-import-too-large
                 "Session artifacts exceed the bounded import transfer size"
                 {:bytes total :limit max-transfer-artifact-bytes})
    validated))

(defn- validate-import-results! [descriptors source-sid artifact-availability]
  (value/check! (vector? descriptors) :invalid-import "Export results must be a vector" {})
  (let [ids (mapv :id descriptors)]
    (value/check! (= (count ids) (count (set ids))) :invalid-import
                 "Export contains duplicate result IDs" {})
    (doseq [descriptor descriptors]
      (value/check! (map? descriptor) :invalid-import "Result descriptor must be a map" {})
      (value/check! (and (integer? (:id descriptor)) (pos? (:id descriptor))) :invalid-import
                   "Result ID must be positive" {:result-id (:id descriptor)})
      (value/check! (= source-sid (:session-id descriptor)) :invalid-import
                   "Result belongs to another exported session" {:result-id (:id descriptor)})
      (value/check! (contains? #{:inline :artifact :live} (:kind descriptor)) :invalid-import
                   "Result has an invalid kind" {:result-id (:id descriptor)})
      (value/check! (boolean? (:available? descriptor)) :invalid-import
                   "Result availability must be boolean" {:result-id (:id descriptor)})
      (value/check! (string? (or (:content descriptor) "")) :invalid-import
                   "Result content must be text" {:result-id (:id descriptor)})
      (value/check! (map? (or (:details descriptor) {})) :invalid-import
                   "Result details must be a map" {:result-id (:id descriptor)})
      (case (:kind descriptor)
        :inline
        (value/check! (contains? descriptor :value) :invalid-import
                     "Inline result is missing its durable value" {:result-id (:id descriptor)})

        :artifact
        (do
          (value/check! (contains? artifact-availability (:artifact-id descriptor)) :invalid-import
                       "Result references a missing artifact"
                       {:result-id (:id descriptor) :artifact-id (:artifact-id descriptor)})
          (value/check! (or (not (:available? descriptor))
                           (get artifact-availability (:artifact-id descriptor)))
                       :invalid-import "Available result references an unavailable artifact"
                       {:result-id (:id descriptor) :artifact-id (:artifact-id descriptor)}))

        :live
        (value/check! (false? (:available? descriptor)) :invalid-import
                     "Portable live results must be explicitly unavailable"
                     {:result-id (:id descriptor)}))
      (encode descriptor))
    descriptors))


(defn- validate-import! [packet]
  (value/check! (map? packet) :invalid-import "Session export must be a map" {})
  (value/check! (= "arrodes-session" (:format packet)) :invalid-import
               "Unsupported session export format" {:format (:format packet)})
  (value/check! (= 1 (:version packet)) :invalid-import
               "Unsupported session export version" {:version (:version packet)})
  (value/check! (vector? (:entries packet)) :invalid-import
               "Export entries must be a vector" {})
  (value/check! (contains? packet :base-config) :invalid-import
               "Export is missing its base configuration" {})
  (let [snapshot (:session packet)
        base-config (session-model/normalize-config (:base-config packet))
        entries (:entries packet)
        ids (mapv :id entries)
        id-set (set ids)
        by-id (into {} (map (juxt :id identity)) entries)]
    (value/check! (map? (:base-config packet)) :invalid-import
                 "Export base configuration must be a map" {})
    (value/check! (= (:base-config packet) base-config) :invalid-import
                 "Export base configuration is not normalized" {})
    (value/check! (map? snapshot) :invalid-import "Export session must be a map" {})
    (value/check! (and (string? (:name snapshot)) (not (str/blank? (:name snapshot))))
                 :invalid-import "Export session name is invalid" {})
    (value/check! (and (string? (:cwd snapshot)) (not (str/blank? (:cwd snapshot))))
                 :invalid-import "Export session cwd is invalid" {})
    (value/check! (= (:config snapshot)
                    (session-model/normalize-config (:config snapshot)))
                 :invalid-import "Export session configuration is not normalized" {})
    (value/check! (contains? statuses (:status snapshot)) :invalid-import
                 "Export session status is invalid" {:status (:status snapshot)})
    (value/check! (and (integer? (:revision snapshot)) (not (neg? (:revision snapshot))))
                 :invalid-import "Export session revision is invalid" {})
    (value/check! (map? (:metadata snapshot)) :invalid-import
                 "Export session metadata must be a map" {})
    (value/check! (vector? (:labels snapshot)) :invalid-import
                 "Export session labels must be a vector" {})
    (require-uuid! (:id snapshot) :session-id)
    (value/check! (= (count ids) (count id-set)) :invalid-import
                 "Export contains duplicate entry IDs" {})
    (value/check! (= (count entries) (count (set (map :seq entries)))) :invalid-import
                 "Export contains duplicate entry sequence numbers" {})
    (doseq [entry entries]
      (value/check! (map? entry) :invalid-import "Export entry must be a map" {})
      (require-uuid! (:id entry) :entry-id)
      (value/check! (= (:id snapshot) (:session-id entry)) :invalid-import
                   "Entry belongs to another session" {:entry-id (:id entry)})
      (value/check! (contains? entry-kinds (:kind entry)) :invalid-import
                   "Export contains an invalid entry kind"
                   {:entry-id (:id entry) :kind (:kind entry)})
      (value/check! (or (nil? (:parent-id entry)) (contains? id-set (:parent-id entry)))
                   :invalid-import "Entry parent is missing"
                   {:entry-id (:id entry) :parent-id (:parent-id entry)})
      (value/check! (and (integer? (:seq entry)) (pos? (:seq entry))) :invalid-import
                   "Entry sequence must be a positive integer"
                   {:entry-id (:id entry) :seq (:seq entry)})
      (value/check! (and (integer? (:created-at entry)) (not (neg? (:created-at entry))))
                   :invalid-import "Entry creation time is invalid" {:entry-id (:id entry)})
      (when-let [parent (:parent-id entry)]
        (let [parent-seq (:seq (get by-id parent))]
          (value/check! (and (integer? parent-seq) (< parent-seq (:seq entry))) :invalid-import
                       "Entry parent must precede its child"
                       {:entry-id (:id entry) :parent-id parent})))
      (value/check! (map? (:data entry)) :invalid-import
                   "Entry data must be a map" {:entry-id (:id entry)})
      (case (:kind entry)
        :config
        (value/check! (= (:data entry)
                        (session-model/normalize-config (:data entry)))
                     :invalid-import "Configuration entry is not normalized"
                     {:entry-id (:id entry)})

        :compaction
        (let [kept (get-in entry [:data :first-kept-entry-id])
              ancestors (set (map :id (session-model/active-path
                                       entries (:parent-id entry))))]
          (value/check! (contains? ancestors kept) :invalid-import
                       "Compaction retained entry must be on its preceding active path"
                       {:entry-id (:id entry) :first-kept-entry-id kept})
          (value/check! (string? (get-in entry [:data :summary])) :invalid-import
                       "Compaction summary must be text" {:entry-id (:id entry)})
          (value/check! (map? (or (get-in entry [:data :usage]) {})) :invalid-import
                       "Compaction usage must be a map" {:entry-id (:id entry)}))

        :branch-summary
        (do
          (value/check! (string? (get-in entry [:data :summary])) :invalid-import
                       "Branch summary must be text" {:entry-id (:id entry)})
          (when-let [from-id (get-in entry [:data :from-id])]
            (value/check! (contains? id-set from-id) :invalid-import
                         "Branch summary references a missing entry"
                         {:entry-id (:id entry) :from-id from-id})))

        :label
        (value/check! (contains? id-set (get-in entry [:data :entry-id])) :invalid-import
                     "Label references a missing entry" {:entry-id (:id entry)})

        nil))
    (when-let [head (:head snapshot)]
      (value/check! (contains? id-set head) :invalid-import
                   "Session head is missing" {:head head}))
    (doseq [entry entries]
      (session-model/active-path entries (:id entry)))
    (let [artifacts (validate-import-artifacts! (:artifacts packet) (:id snapshot))
          artifact-availability
          (into {} (map (fn [{:keys [descriptor]}]
                          [(:id descriptor) (:available? descriptor)])
                        artifacts))
          results (validate-import-results! (:results packet) (:id snapshot)
                                            artifact-availability)
          referenced (referenced-result-ids entries)
          present (set (map :id results))
          artifact-referenced (referenced-artifact-ids [entries results])
          artifact-present (set (keys artifact-availability))]
      (value/check! (set/subset? referenced present) :invalid-import
                   "History references results missing from the export"
                   {:result-ids (vec (sort (set/difference referenced present)))})
      (value/check! (set/subset? artifact-referenced artifact-present) :invalid-import
                   "History or results reference artifacts missing from the export"
                   {:artifact-ids
                    (vec (sort (set/difference artifact-referenced artifact-present)))})
      {:snapshot snapshot :base-config base-config :entries entries
       :artifacts artifacts :results results})))

(defn- remap-entry-result-refs [data imported-results]
  (letfn [(walk [value]
            (cond
              (map? value)
              (into {}
                    (map (fn [[key item]]
                           [key (if (and (contains? #{:result :message/result} key)
                                         (map? item)
                                         (integer? (:id item))
                                         (contains? imported-results (:id item)))
                                  (get imported-results (:id item))
                                  (walk item))]))
                    value)
              (vector? value) (mapv walk value)
              (set? value) (set (map walk value))
              (sequential? value) (mapv walk value)
              :else value))]
    (walk data)))

(defn- copy-graph! [^Connection connection source-row source-entries opts]
  (let [source-entries (vec (sort-by :seq source-entries))
        new-sid (or (:id opts) (util/id))
        _ (require-uuid! new-sid :session-id)
        id-map (into {} (map (fn [entry] [(:id entry) (util/id)]) source-entries))
        now (util/now)
        base-config (session-model/normalize-config (::base-config source-row))
        snapshot (session-model/new-snapshot
                  {:id new-sid
                   :name (or (:name opts) (:name source-row))
                   :cwd (or (:cwd opts) (:cwd source-row))
                   :config (session-model/normalize-config (:config source-row))
                   :status :idle
                   :created-at now
                   :metadata (or (:metadata opts) (:metadata source-row))
                   :labels (remap-entry-refs (:labels source-row) id-map)})
        snapshot (assoc snapshot :head (get id-map (:head source-row)))
        copied (mapv (fn [entry]
                       (-> entry
                           (assoc :id (get id-map (:id entry))
                                  :session-id new-sid
                                  :parent-id (get id-map (:parent-id entry))
                                  :data (-> (:data entry)
                                            (remap-entry-refs id-map)
                                            (remap-entry-result-refs (:imported-results opts))
                                            (remap-artifact-refs (:imported-artifacts opts))))))
                     source-entries)]
    (value/check! (nil? (find-session connection new-sid)) :session-exists
                 "Session ID already exists" {:session-id new-sid})
    (insert-session! connection snapshot base-config)
    (doseq [entry copied]
      (execute-sql! connection
                    "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                    [(:id entry) new-sid (:parent-id entry) (:seq entry) (name (:kind entry))
                     (encode (:data entry)) (:created-at entry)]))
    snapshot))

(defn import-session!
  "Validates a complete export and imports history, results, and artifacts under fresh identities."
  [store packet opts]
  (let [{source :snapshot source-base-config :base-config source-entries :entries
         source-artifacts :artifacts source-results :results} (validate-import! packet)
        new-sid (or (:id opts) (util/id))
        _ (require-uuid! new-sid :session-id)
        now (util/now)
        artifact-id-map (into {} (map (fn [{:keys [descriptor]}]
                                        [(:id descriptor) (util/id)])
                                      source-artifacts))
        imported-artifacts
        (mapv (fn [{:keys [descriptor bytes]}]
                {:descriptor
                 (cond-> {:id (get artifact-id-map (:id descriptor))
                          :session-id new-sid
                          :sha256 (:sha256 descriptor)
                          :bytes (:bytes descriptor)
                          :kind (:kind descriptor)
                          :available? (boolean bytes)
                          :created-at now}
                   (:name descriptor) (assoc :name (:name descriptor)))
                 :bytes bytes})
              source-artifacts)
        artifact-by-old-id (into {}
                                 (map (fn [source-record imported-record]
                                        [(get-in source-record [:descriptor :id])
                                         (:descriptor imported-record)])
                                      source-artifacts imported-artifacts))
        result-id-map (into {} (map-indexed (fn [index descriptor]
                                              [(:id descriptor) (inc index)])
                                            (sort-by :id source-results)))
        imported-results
        (into {}
              (map (fn [descriptor]
                     (let [kind (:kind descriptor)
                           artifact (when (= :artifact kind)
                                      (get artifact-by-old-id (:artifact-id descriptor)))
                           result (cond-> {:id (get result-id-map (:id descriptor))
                                          :session-id new-sid
                                          :kind kind
                                          :content (or (:content descriptor) "")
                                          :details (remap-artifact-refs
                                                    (or (:details descriptor) {})
                                                    artifact-by-old-id)
                                          :available? (case kind
                                                        :live false
                                                        :artifact (and (:available? descriptor)
                                                                       (:available? artifact))
                                                        (boolean (:available? descriptor)))}
                                    (= :inline kind) (assoc :value (:value descriptor))
                                    (= :artifact kind) (assoc :artifact-id (:id artifact)))]
                       [(:id descriptor) result])))
              source-results)]
    (transact! store
      (fn [connection]
        (let [source-row (assoc source ::base-config source-base-config)
              imported-opts (merge {:id new-sid
                                    :name (:name source)
                                    :cwd (:cwd source)
                                    :metadata (assoc (merge (or (:metadata source) {})
                                                            (or (:metadata opts) {}))
                                                     :imported-from (:id source))
                                    :labels (:labels source)
                                    :imported-results imported-results
                                    :imported-artifacts artifact-by-old-id}
                                   (select-keys opts [:name :cwd]))
              snapshot (copy-graph! connection source-row source-entries imported-opts)]
          (doseq [{:keys [descriptor bytes]} imported-artifacts]
            (when (and bytes (not (:memory? store)))
              (write-content-addressed! store (:sha256 descriptor) bytes))
            (execute-sql! connection
                          "INSERT INTO artifacts(id,session_id,sha256,bytes,kind,available,created_at,name,content) VALUES(?,?,?,?,?,?,?,?,?)"
                          [(:id descriptor) new-sid (:sha256 descriptor) (:bytes descriptor)
                           (name (:kind descriptor)) (if (:available? descriptor) 1 0)
                           (:created-at descriptor) (:name descriptor)
                           (when (:memory? store) bytes)]))
          (doseq [descriptor (sort-by :id (vals imported-results))]
            (execute-sql! connection
                          "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                          [new-sid (:id descriptor) (name (:kind descriptor))
                           (encode descriptor) now]))
          snapshot)))))
