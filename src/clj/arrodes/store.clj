(ns arrodes.store
  "Transactional SQLite persistence for sessions, histories, queues, operations, and events."
  (:refer-clojure :exclude [uuid?])
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [arrodes.session :as session-model]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.sql Connection DriverManager PreparedStatement ResultSet)
           (java.nio.channels FileChannel FileLock OverlappingFileLockException)
           (java.nio.file FileAlreadyExistsException Files StandardCopyOption StandardOpenOption OpenOption LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.util UUID Base64)
           (java.util.concurrent.locks ReentrantLock)))

(defrecord Store [^Connection connection ^ReentrantLock lock closed? path artifact-dir memory? opened-at
                  ^FileChannel owner-channel ^FileLock owner-lock owner-path
                  ^FileChannel artifact-channel ^FileLock artifact-lock])

(def ^:private schema-version 5)
(def ^:private application-id 0x4152524f)
(def ^:private entry-kinds
  #{:message :config :compaction :branch-summary :custom :custom-context :label :evaluation})
(def ^:private statuses #{:idle :running :failed :interrupted})
(def ^:private operation-kinds #{:run :continue :compact :evaluate :invoke})
(def ^:private operation-statuses
  #{:queued :running :cancelling :completed :failed :cancelled :interrupted})
(def ^:private max-transfer-artifact-bytes (* 256 1024 1024))
(def ^:private max-agent-content-bytes (* 256 1024))
(def ^:private max-agent-completion-bytes (* 16 1024 1024))
(def ^:private max-agent-preview 1200)
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

(defn- single-link! [path]
  (when (Files/exists (util/path path)
                      (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (let [links (try
                  (long (Files/getAttribute (util/path path) "unix:nlink"
                                            (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                  (catch UnsupportedOperationException _ nil))]
      (value/check! (or (nil? links) (= 1 links)) :insecure-database
                    "Store files cannot have hard-linked aliases"
                    {:path (str path) :links links}))))

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
    (single-link! target)
    (doseq [sidecar [(util/path (str path "-wal")) (util/path (str path "-shm"))]]
      (value/check! (not (Files/isSymbolicLink sidecar)) :insecure-database
                   "SQLite sidecar cannot be a symbolic link" {:path (str sidecar)})
      (single-link! sidecar))
    target))

(defn- tighten-store-files! [store]
  (when-let [path (:path store)]
    (doseq [candidate (map util/path [path (str path "-wal") (str path "-shm")])]
      (value/check! (not (Files/isSymbolicLink candidate)) :insecure-database
                   "SQLite storage file cannot be a symbolic link" {:path (str candidate)})
      (single-link! candidate)
      (when (Files/isRegularFile candidate
                                 (into-array java.nio.file.LinkOption
                                             [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
        (util/private-file! candidate)))))


(defn- safe-storage-path! [path]
  (let [candidate (.toAbsolutePath (util/path path))]
    (loop [parts (iterator-seq (.iterator candidate))
           parent (.getRoot candidate)]
      (when-let [part (first parts)]
        (value/check! (not= ".." (str part)) :insecure-database
                      "Storage path refuses traversal" {:path (str candidate)})
        (let [current (.resolve parent ^java.nio.file.Path part)
              symlink? (Files/isSymbolicLink current)
              trusted-parent? (when symlink?
                                (let [real-parent (.toRealPath parent
                                                               (make-array LinkOption 0))
                                      permissions (Files/getPosixFilePermissions
                                                   real-parent (make-array LinkOption 0))]
                                  (and (= "root" (str (Files/getOwner
                                                       real-parent (make-array LinkOption 0))))
                                       (not (contains? permissions
                                                       java.nio.file.attribute.PosixFilePermission/GROUP_WRITE))
                                       (not (contains? permissions
                                                       java.nio.file.attribute.PosixFilePermission/OTHERS_WRITE)))))]
          (value/check! (or (not symlink?) trusted-parent?) :insecure-database
                        "Storage path refuses untrusted symbolic links"
                        {:path (str current)})
          (recur (next parts) current))))))

(defn- artifact-content-plan [artifact-dir]
  (when artifact-dir
    (safe-storage-path! artifact-dir)
    (let [root (util/path artifact-dir)]
      (when (Files/exists root (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
        (value/check! (Files/isDirectory root
                                         (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                      :insecure-directory "Artifact storage is not a directory"
                      {:path (str root)})
        (with-open [walk (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
          (let [paths (vec (iterator-seq (.iterator walk)))
                content (transient [])]
            (doseq [path paths]
              (safe-storage-path! path)
              (let [relative (.relativize root path)
                    segments (mapv str (iterator-seq (.iterator relative)))]
                (cond
                  (Files/isDirectory path
                                     (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                  (value/check! (or (= path root)
                                    (and (= 1 (count segments))
                                         (re-matches #"[0-9a-f]{2}" (first segments))))
                                :artifact-owner-unknown
                                "Unmarked artifact directory contains unknown content"
                                {:path (str path)})

                  (Files/isRegularFile path
                                       (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                  (when-not (and (= 1 (count segments))
                                 (= ".lock" (first segments)))
                    (value/check! (and (= 2 (count segments))
                                       (re-matches #"[0-9a-f]{2}" (first segments))
                                       (re-matches #"[0-9a-f]{64}" (second segments))
                                       (= (first segments) (subs (second segments) 0 2)))
                                  :artifact-owner-unknown
                                  "Unmarked artifact directory contains unknown content"
                                  {:path (str path)})
                    (single-link! path)
                    (conj! content path))

                  :else
                  (value/fail! :insecure-directory
                               "Artifact storage contains an unsafe filesystem entry"
                               {:path (str path)}))))
            {:files (persistent! content)}))))))


(defn- release-artifact-owner! [{:keys [artifact-channel artifact-lock]}]
  (when artifact-lock
    (try
      (when (.isValid ^FileLock artifact-lock)
        (.release ^FileLock artifact-lock))
      (finally
        (.close ^FileChannel artifact-channel)))))

(defn- acquire-artifact-owner! [artifact-dir db-path]
  (safe-storage-path! artifact-dir)
  (private-dir! artifact-dir)
  (let [root (util/path artifact-dir)
        lock-path (.resolve root ".lock")
        marker (.resolve root ".arrodes-owner")]
    (create-owner-file! lock-path)
    (value/check! (and (not (Files/isSymbolicLink lock-path))
                       (Files/isRegularFile lock-path
                                            (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                  :insecure-directory "Artifact ownership lock is unsafe"
                  {:path (str lock-path)})
    (single-link! lock-path)
    (let [channel (FileChannel/open lock-path
                                    (into-array OpenOption
                                                [StandardOpenOption/WRITE
                                                 LinkOption/NOFOLLOW_LINKS]))]
      (try
        (let [lock (try (.tryLock channel)
                        (catch OverlappingFileLockException _ nil))]
          (value/check! lock :artifact-store-in-use
                        "Artifact storage is owned by another open store"
                        {:path artifact-dir})
          (try
            (if (Files/exists marker (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
              (do
                (value/check! (and (not (Files/isSymbolicLink marker))
                                   (Files/isRegularFile marker
                                                        (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                              :insecure-directory "Artifact owner marker is unsafe"
                              {:path (str marker)})
                (single-link! marker)
                (value/check! (<= (Files/size marker) 16384)
                              :artifact-owner-conflict "Artifact ownership marker is invalid"
                              {:path (str marker)})
                (value/check! (= {:database db-path} (decode (Files/readString marker)))
                              :artifact-owner-conflict
                              "Artifact storage belongs to another database"
                              {:path artifact-dir}))
              (let [owned-hashes
                    (when (Files/exists (util/path db-path)
                                        (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                      (with-open [connection (DriverManager/getConnection
                                              (str "jdbc:sqlite:" (.toUri (util/path db-path)) "?mode=ro"))]
                        (when (scalar connection
                                      "SELECT 1 FROM sqlite_master WHERE type='table' AND name='artifacts'"
                                      [])
                          (set (query-sql connection "SELECT sha256 FROM artifacts"
                                          [] #(.getString ^ResultSet % "sha256"))))))
                    plan (artifact-content-plan artifact-dir)]
                (value/check! (every? #(contains? owned-hashes
                                                  (str (.getFileName ^java.nio.file.Path %)))
                                      (:files plan))
                              :artifact-owner-unknown
                              "Unmarked artifact files are not recorded by this database"
                              {:path artifact-dir})
                (let [temp (Files/createTempFile root ".owner-" ".tmp"
                                                 (make-array FileAttribute 0))]
                  (try
                    (Files/write temp (.getBytes (encode {:database db-path}) "UTF-8")
                                 (into-array OpenOption [StandardOpenOption/WRITE]))
                    (util/private-file! temp)
                    (Files/move temp marker (make-array StandardCopyOption 0))
                    (finally (Files/deleteIfExists temp))))))
            (util/private-file! lock-path)
            {:artifact-channel channel :artifact-lock lock}
            (catch Throwable error
              (.release ^FileLock lock)
              (throw error))))
        (catch Throwable error
          (.close channel)
          (throw error))))))


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
                              (set (query-sql connection
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

(defn- column-shape? [connection expected tables]
  (and (set/subset? (set (keys expected)) tables)
       (every? (fn [[table columns]]
                 (= columns (set (query-sql connection (str "PRAGMA table_info(" table ")")
                                            [] #(.getString ^ResultSet % "name")))))
               expected)))

(defn- check-schema! [^Connection connection]
  (let [current (long (or (scalar connection "PRAGMA user_version" []) 0))
        marker (long (or (scalar connection "PRAGMA application_id" []) 0))
        tables (set (query-sql connection
                               "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                               [] #(.getString ^ResultSet % "name")))
        fresh? (and (zero? current) (zero? marker)
                    (zero? (long (scalar connection
                                             "SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
                                             []))))
        recognized? (or (= marker application-id)
                        (and (zero? marker) (arrodes-owned? connection tables)))
        base? (column-shape? connection historical-columns tables)
        agents? (column-shape? connection agent-columns tables)]
    (when-not fresh?
      (value/check! recognized? :unrecognized-store
                    "Unrecognized SQLite store; the database was left unchanged"
                    {:found current :application-id marker})
      (value/check! (contains? #{3 4 5} current) :unsupported-store-format
                    "Unsupported store version; use a compatible Arrodes build or restore a backup"
                    {:found current :required schema-version})
      (value/check! (and base?
                         (if (= current 5) agents?
                             (or (= tables (set (keys historical-columns)))
                                 (and (= current 4) agents?))))
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
      (doseq [row (query-sql connection
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
        (value/check! (and (uuid? (:sid row)) (uuid? (:root row))
                           (uuid? (:context row)) (not (neg? (:depth row)))
                           (contains? #{0 1} (:paused row))
                           (contains? #{0 1} (:stopped row))
                           (if (:parent row)
                             (and (uuid? (:parent row)) (uuid? (:parent-context row))
                                  (pos? (:depth row)))
                             (and (= (:sid row) (:root row))
                                  (zero? (:depth row))
                                  (nil? (:parent-context row)))))
                      :unsupported-store-format "Malformed agent routing record"
                      {:session-id (:sid row)}))
      (value/check!
       (contains? (set (query-sql connection "PRAGMA table_info(agent_deliveries)"
                                  [] #(.getString ^ResultSet % "name"))) "operation_id")
       :unsupported-store-format "Current store is missing agent delivery operation linkage"
       {:found current :required schema-version})
      (doseq [row (query-sql connection
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
           "delivered" (and (uuid? (:entry-id row))
                            (uuid? (:operation-id row))
                            (= (:recipient-id row) (:entry-session row) (:operation-session row))
                            (contains? #{"run" "continue"} (:operation-kind row)))
           ("pending" "superseded") (and (nil? (:entry-id row))
                                        (nil? (:operation-id row)))
           false)
         :unsupported-store-format "Malformed agent delivery link"
         {:status (:status row) :recipient-id (:recipient-id row)})))
    (if fresh? :fresh current)))

(def ^:private agent-table-statements
  ["CREATE TABLE agent_routes (session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE, root_id TEXT NOT NULL REFERENCES sessions(id), parent_session_id TEXT REFERENCES sessions(id), name TEXT NOT NULL, context_id TEXT NOT NULL, parent_context_id TEXT, depth INTEGER NOT NULL, paused INTEGER NOT NULL DEFAULT 0, stopped INTEGER NOT NULL DEFAULT 0, origin TEXT NOT NULL, UNIQUE(root_id,name))"
   "CREATE INDEX agent_routes_parent ON agent_routes(parent_session_id)"
   "CREATE TABLE agent_submissions (source_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, submission_id TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, receipt TEXT NOT NULL, PRIMARY KEY(source_id,submission_id))"
   "CREATE TABLE agent_messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, root_id TEXT NOT NULL, sender_id TEXT, kind TEXT NOT NULL, content TEXT NOT NULL, source_operation_id TEXT, created_at INTEGER NOT NULL, wake INTEGER NOT NULL DEFAULT 0, UNIQUE(source_operation_id,kind))"
   "CREATE TABLE agent_deliveries (message_id TEXT NOT NULL REFERENCES agent_messages(id) ON DELETE CASCADE, recipient_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, context_id TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', entry_id TEXT, operation_id TEXT REFERENCES operations(id), PRIMARY KEY(message_id,recipient_id))"
   "CREATE INDEX agent_deliveries_recipient ON agent_deliveries(recipient_id,status,message_id)"])

(defn- initialize-schema! [^Connection connection]
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
           agent-table-statements))
        (execute-command! connection (str "PRAGMA user_version = " schema-version))
        (execute-command! connection (str "PRAGMA application_id = " application-id))
        (.commit connection)
        (catch Throwable error (.rollback connection) (throw error))
        (finally (.setAutoCommit connection old-auto))))))

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


(defn- check-legacy-reset-marker! [db-path]
  (let [marker (str db-path ".reset")]
    (safe-storage-path! marker)
    (when (Files/exists (util/path marker) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (single-link! marker)
      (value/fail! :incomplete-legacy-reset
                   "Interrupted legacy reset; no further cleanup was attempted. Inspect the database and artifacts, restore from a backup if needed, and move the .reset marker aside only after recovery."
                   {:path marker}))))

(defn- backup-store! [^Connection connection db-path version]
  ;; VACUUM INTO reads a consistent SQLite snapshot, including committed WAL frames.
  (let [parent (.getParent (util/path db-path))
        temp (Files/createTempFile parent ".arrodes-backup-" ".tmp"
                                   (into-array FileAttribute
                                               [(PosixFilePermissions/asFileAttribute
                                                 (PosixFilePermissions/fromString "rw-------"))]))
        backup (util/path (str db-path ".schema" version "-" (util/id) ".backup"))]
    (try
      (execute-command! connection (str "VACUUM INTO '" (str/replace (str temp) "'" "''") "'"))
      (util/private-file! temp)
      (with-open [channel (FileChannel/open temp (into-array OpenOption [StandardOpenOption/WRITE]))]
        (.force channel true))
      (Files/move temp backup (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
      (with-open [channel (FileChannel/open parent (into-array OpenOption [StandardOpenOption/READ]))]
        (.force channel true))
      (str backup)
      (finally (Files/deleteIfExists temp)))))

(defn- upgrade-schema! [^Connection connection]
  (let [old-auto (.getAutoCommit connection)
        tables (set (query-sql connection
                               "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                               [] #(.getString ^ResultSet % "name")))]
    (try
      (.setAutoCommit connection false)
      (when-not (contains? tables "agent_routes")
        (execute-script! connection agent-table-statements)
        ;; Historical forks and clones are independent sessions, not active teams.
        (execute-command! connection
                          "INSERT INTO agent_routes(session_id,root_id,parent_session_id,name,context_id,parent_context_id,depth,paused,stopped,origin) SELECT id,id,NULL,'Main',id,NULL,0,0,0,'{}' FROM sessions"))
      (execute-command! connection (str "PRAGMA user_version = " schema-version))
      (execute-command! connection (str "PRAGMA application_id = " application-id))
      (check-schema! connection)
      (.commit connection)
      (catch Throwable error
        (.rollback connection)
        (throw error))
      (finally (.setAutoCommit connection old-auto)))))

(defn- open-current-connection! [memory? db-path artifact-dir artifact-owner*]
  (let [url (if memory? "jdbc:sqlite::memory:" (str "jdbc:sqlite:" db-path))
        version (when db-path
                  (with-open [reader (DriverManager/getConnection
                                     (str "jdbc:sqlite:" (.toUri (util/path db-path)) "?mode=ro"))]
                    (let [version (check-schema! reader)]
                      (when (#{3 4} version)
                        (value/check! (= "ok" (scalar reader "PRAGMA integrity_check" []))
                                      :unsupported-store-format
                                      "SQLite integrity check failed; restore the database from a backup"
                                      {:path db-path}))
                      version)))]
    (when artifact-dir
      (reset! artifact-owner*
              (acquire-artifact-owner! artifact-dir db-path)))
    (let [connection (DriverManager/getConnection url)]
      (try
        (let [checked (check-schema! connection)
              version (or version checked)]
          (value/check! (= checked version) :unsupported-store-format
                        "Store changed during validation; retry after closing other database writers"
                        {:path db-path})
          (when (and db-path (#{3 4} version))
            (execute-command! connection "PRAGMA synchronous = FULL")
            (backup-store! connection db-path version)
            (upgrade-schema! connection))
          connection)
        (catch Throwable error
          (.close connection)
          (throw error))))))

(defn open!
  "Opens an independent memory store or exclusively owns a canonical file store."
  [{:keys [path memory? artifact-dir]}]
  (Class/forName "org.sqlite.JDBC")
  (let [memory? (or memory? (nil? path))
        db-path (when-not memory?
                  (safe-storage-path! path)
                  (canonical-database-path path))
        owner (when db-path (acquire-owner! db-path))
        artifact-owner* (atom nil)]
    (try
      (let [artifact-dir (when-not memory?
                           (when artifact-dir (safe-storage-path! artifact-dir))
                           (util/canonical-path
                            (or artifact-dir (str db-path ".artifacts"))))
            _ (when db-path (check-legacy-reset-marker! db-path))
            _ (when db-path (prepare-database-file! db-path))
            connection (open-current-connection! memory? db-path artifact-dir artifact-owner*)
            lock (ReentrantLock.)
            closed? (atom false)
            store (map->Store
                   (merge {:connection connection
                           :lock lock
                           :closed? closed?
                           :path db-path
                           :artifact-dir artifact-dir
                           :memory? memory?
                           :opened-at (util/now)}
                          owner @artifact-owner*))]
        (try
          (check-schema! connection)
          (execute-command! connection "PRAGMA foreign_keys = ON")
          (execute-command! connection "PRAGMA busy_timeout = 5000")
          (if memory?
            (execute-command! connection "PRAGMA journal_mode = MEMORY")
            (do (execute-command! connection "PRAGMA journal_mode = WAL")
                (execute-command! connection "PRAGMA synchronous = FULL")))
          (tighten-store-files! store)
          (initialize-schema! connection)
          (transact! store expire-live-results!)
          (tighten-store-files! store)
          (when db-path (util/private-file! db-path))
          (when artifact-dir (private-dir! artifact-dir))
          store
          (catch Throwable error
            (try (.close connection) (catch Throwable _))
            (throw error))))
      (catch Throwable error
        (try (release-artifact-owner! @artifact-owner*)
             (finally (release-owner! owner)))
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
                  (try (release-artifact-owner! store)
                       (finally (release-owner! store))))))))
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
       (let [sql (str "SELECT s.*, (SELECT e.created_at FROM entries e "
                      "WHERE e.session_id = s.id AND e.kind = 'message' ORDER BY e.seq DESC LIMIT 1) AS last_message_at "
                      "FROM sessions s " (when cwd "WHERE s.cwd = ? ")
                      "ORDER BY s.updated_at DESC, s.id")]
         (query-sql connection sql (if cwd [(util/canonical-path cwd)] [])
                    (fn [^ResultSet rs]
                      (assoc (public-session (session-row rs))
                             :last-message-at (some-> (.getObject rs "last_message_at") long)))))))))

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
      (when kept
        (require-uuid! kept :first-kept-entry-id)
        (value/check! (contains? ancestors kept) :invalid-entry
                     "Compaction retained entry must be on its preceding active path"
                     {:first-kept-entry-id kept}))
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

(defn- route-row [^ResultSet rs]
  {:session-id (.getString rs "session_id")
   :root-id (.getString rs "root_id")
   :parent-session-id (.getString rs "parent_session_id")
   :name (.getString rs "name")
   :context-id (.getString rs "context_id")
   :parent-context-id (.getString rs "parent_context_id")
   :depth (.getInt rs "depth")
   :paused? (pos? (.getInt rs "paused"))
   :stopped? (pos? (.getInt rs "stopped"))
   :origin (decode (.getString rs "origin"))})

(defn- route [connection sid]
  (or (first (query-sql connection "SELECT * FROM agent_routes WHERE session_id=?" [sid] route-row))
      (let [snapshot (require-session connection sid)]
        {:session-id sid :root-id sid :parent-session-id nil :name "Main"
         :context-id sid :parent-context-id nil :depth 0 :paused? false
         :stopped? false :origin {} :session-name (:name snapshot)})))

(defn- public-route [row]
  (select-keys row [:session-id :root-id :parent-session-id :name :context-id
                    :parent-context-id :depth :paused? :stopped?]))

(defn- ensure-root-route! [connection sid]
  (when-not (scalar connection "SELECT 1 FROM agent_routes WHERE session_id=?" [sid])
    (execute-sql! connection
                  "INSERT INTO agent_routes(session_id,root_id,parent_session_id,name,context_id,parent_context_id,depth,paused,stopped,origin) VALUES(?,?,?,?,?,?,?,0,0,?)"
                  [sid sid nil "Main" sid nil 0 (encode {})]))
  (route connection sid))

(defn- routing-event! [connection root sid type data]
  (insert-event! connection root
                 {:type type :data (merge {:root-id root :session-id sid} data)}))

(defn- checked-agent-content
  ([content] (checked-agent-content content max-agent-content-bytes))
  ([content limit]
   (let [serialized (encode content)]
     (value/check! (<= (count (.getBytes ^String serialized "UTF-8")) limit)
                   :agent-content-too-large "Agent content exceeds the durable size limit"
                   {:limit limit})
     serialized)))

(defn- canonical-assistant-result [result]
  (when (= :assistant (:message/role result))
    (let [content (:message/content result)
          content (if (vector? content)
                    (mapv #(if (map? %)
                             (dissoc % :part/provider-data)
                             %) content)
                    content)
          metadata (select-keys (:message/provider-data result)
                                [:response/provider :response/model :response/usage
                                 :response/cost :response/finish-reason])]
      (cond-> {:message/role :assistant :message/content content}
        (seq metadata) (assoc :message/provider-data metadata)))))

(defn- completion-content [operation child-name]
  (checked-agent-content {:session-id (:session-id operation)
                          :name child-name
                          :operation-id (:id operation) :status (:status operation)
                          :result (canonical-assistant-result (:result operation))
                          :error (select-keys (:error operation)
                                              [:code :message :error/code])}
                         max-agent-completion-bytes))

(defn- completion! [connection operation]
  (when (contains? #{:completed :failed :cancelled :interrupted} (:status operation))
    (when-let [child (first (query-sql connection
                                      "SELECT * FROM agent_routes WHERE session_id=? AND parent_session_id IS NOT NULL"
                                      [(:session-id operation)] route-row))]
      (let [parent (:parent-session-id child)
            parent-route (route connection parent)
            content (completion-content operation (:name child))]
        (when (and (= (:parent-context-id child) (:context-id parent-route))
                   (not (scalar connection
                                "SELECT 1 FROM agent_messages WHERE source_operation_id=? AND kind='completion'"
                                [(:id operation)])))
          (let [id (util/id)]
            (execute-sql! connection
                          "INSERT INTO agent_messages(id,root_id,sender_id,kind,content,source_operation_id,created_at,wake) VALUES(?,?,?,?,?,?,?,1)"
                          [id (:root-id child) (:session-id operation) "completion" content
                           (:id operation) (util/now)])
            (execute-sql! connection
                          "INSERT INTO agent_deliveries(message_id,recipient_id,context_id,status) VALUES(?,?,?,'pending')"
                          [id parent (:context-id parent-route)])
            [(routing-event! connection (:root-id child) parent :agent/message
                             {:message-id id :from (:session-id operation) :kind :completion})]))))))

(defn- supersede-deliveries! [connection sid]
  (execute-sql! connection
                "UPDATE agent_deliveries SET status='superseded' WHERE recipient_id=? AND status='pending'"
                [sid]))

(def job-terminal-statuses #{:completed :failed :cancelled :interrupted})

(defn- job-row [^ResultSet rs]
  (assoc (decode (.getString rs "record")) :delivered? (pos? (.getInt rs "delivered"))))

(defn job [store sid id]
  (store-read store
    (fn [connection]
      (require-session connection sid)
      (require-uuid! id :job-id)
      (or (first (query-sql connection "SELECT * FROM jobs WHERE session_id=? AND id=?" [sid id] job-row))
          (value/fail! :job-not-found "Job does not exist in this session" {:job-id id :session-id sid})))))

(defn jobs
  ([store sid] (jobs store sid {}))
  ([store sid {:keys [limit before] :or {limit 100}}]
   (value/check! (and (integer? limit) (<= 1 limit 500)) :invalid-limit "Job limit must be 1..500" {})
   (store-read store
     (fn [connection]
       (require-session connection sid)
       (if before
         (let [cursor (job store sid before)]
           (query-sql connection
             "SELECT * FROM jobs WHERE session_id=? AND (created_at<? OR (created_at=? AND id>?)) ORDER BY created_at DESC,id LIMIT ?"
             [sid (:created-at cursor) (:created-at cursor) before limit] job-row))
         (query-sql connection "SELECT * FROM jobs WHERE session_id=? ORDER BY created_at DESC,id LIMIT ?"
                    [sid limit] job-row))))))

(defn active-jobs [store sid]
  (store-read store
    (fn [connection]
      (require-session connection sid)
      (query-sql connection "SELECT * FROM jobs WHERE session_id=? AND status IN ('queued','running','cancelling') ORDER BY created_at DESC,id"
                 [sid] job-row))))

(defn create-job! [store record]
  (transact! store
    (fn [connection]
      (require-session connection (:session-id record))
      (require-uuid! (:id record) :job-id)
      (value/check! (= :queued (:status record)) :invalid-job "New jobs must be queued" {})
      (execute-sql! connection "INSERT INTO jobs(id,session_id,status,created_at,record) VALUES(?,?,?,?,?)"
                    [(:id record) (:session-id record) "queued" (:created-at record) (encode record)])
      {:job record :events [(insert-event! connection (:session-id record)
                             {:type :job/changed :data {:job record}})]})))

(defn transition-job! [store sid id expected changes]
  (transact! store
    (fn [connection]
      (let [prior (job store sid id)]
        (if-not (contains? expected (:status prior))
          {:job prior :events []}
          (let [next-status (:status changes)
                allowed (case (:status prior)
                          :queued #{:running :cancelled :failed :interrupted}
                          :running #{:cancelling :completed :failed :interrupted}
                          :cancelling #{:cancelled :completed :failed :interrupted}
                          #{})
                _ (value/check! (contains? allowed next-status) :invalid-job-transition
                                "Invalid job transition" {:from (:status prior) :to next-status})
                next (assoc (merge prior changes) :revision (inc (or (:revision prior) 0)))]
            (execute-sql! connection "UPDATE jobs SET status=?,record=? WHERE session_id=? AND id=?"
                          [(name next-status) (encode next) sid id])
            {:job next :events [(insert-event! connection sid {:type :job/changed :data {:job next}})]}))))))

(defn pending-job-results [store sid]
  (store-read store
    #(query-sql % "SELECT * FROM jobs WHERE session_id=? AND delivered=0 AND status IN ('completed','failed','cancelled','interrupted') ORDER BY created_at,id LIMIT 20"
                [sid] job-row)))

(defn acknowledge-jobs! [store sid ids]
  (transact! store
    (fn [connection]
      (doseq [id ids]
        (job store sid id)
        (execute-sql! connection "UPDATE jobs SET delivered=1 WHERE session_id=? AND id=?" [sid id])))))

(defn acknowledge-all-jobs! [store sid]
  (transact! store
    (fn [connection]
      (require-session connection sid)
      (execute-sql! connection "UPDATE jobs SET delivered=1 WHERE session_id=?" [sid]))))

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
              _ (doseq [id (:job-deliver command)]
                  (execute-sql! connection "UPDATE jobs SET delivered=1 WHERE session_id=? AND id=?" [sid id]))
              operation (when-let [raw (:operation command)]
                          (let [prior (when-let [oid (:id raw)] (find-operation connection oid))]
                            (upsert-operation! connection (normalize-operation sid prior raw))))
              _ (update-session! connection updated)
              branch? (or (:agent-branch? command)
                          (and (contains? (or (:session command) {}) :head)
                               (not= (:head current) (:head changes))
                               (not (seq committed))))
              _ (when branch?
                  (ensure-root-route! connection sid)
                  (execute-sql! connection "UPDATE agent_routes SET context_id=? WHERE session_id=?"
                                [(util/id) sid])
                  (supersede-deliveries! connection sid))
              entry-events (mapv #(insert-event! connection sid
                                                {:type :entry/committed :data {:entry %}})
                                 committed)
              events (into entry-events
                           (concat (map #(insert-event! connection sid %)
                                        (or (:events command) []))
                                   (when branch?
                                     [(routing-event! connection (:root-id (route connection sid))
                                                      sid :agent/changed {:reason :branch})])))
              completion-events (when operation (completion! connection operation))]
          {:session (public-session updated)
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
    (commit! store sid {:expected-revision (if (contains? changes :expected-revision)
                                             (:expected-revision changes)
                                             (:revision snapshot))
                        :entries [{:kind :config :data config}]
                        :session session-changes
                        :events (cond-> [{:type :session/configured :data {:config config}}]
                                  (contains? changes :name)
                                  (conj {:type :session/named :data {:name (:name changes) :source :user}}))})))

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
                        :agent-branch? true
                        :entries boundary
                        :session session-changes
                        :events [{:type :session/branched :data {:entry-id leaf}}]})))

(defn- remap-labels [labels id-map]
  (into []
        (keep (fn [label]
                (when-let [entry-id (get id-map (:entry-id label))]
                  (assoc label :entry-id entry-id))))
        labels))

(defn- remap-entry-refs [{:keys [kind data] :as entry} id-map compaction-id-map]
  (case kind
    :compaction
    (let [kept (get compaction-id-map (:first-kept-entry-id data))]
      (if (and kept (not= kept (:id entry)))
        (assoc-in entry [:data :first-kept-entry-id] kept)
        (update entry :data dissoc :first-kept-entry-id)))

    :branch-summary
    (if-let [from-id (get id-map (:from-id data))]
      (assoc-in entry [:data :from-id] from-id)
      (update entry :data dissoc :from-id))

    :label
    (assoc-in entry [:data :entry-id] (get id-map (:entry-id data)))

    entry))

(defn- canonical-result-descriptor [{:keys [kind data]}]
  (case kind
    :message (:message/result data)
    :custom-context (:message/result data)
    :evaluation (get-in data [:result :result])
    :custom (get-in data [:result :result])
    nil))

(defn- referenced-result-ids [entries]
  (into #{}
        (keep (fn [entry]
                (let [descriptor (canonical-result-descriptor entry)]
                  (when (and (map? descriptor) (integer? (:id descriptor)))
                    (:id descriptor)))))
        entries))

(defn- remap-entry-result-refs [entry imported-results]
  (let [descriptor (canonical-result-descriptor entry)
        replacement (when (and (map? descriptor) (integer? (:id descriptor)))
                      (get imported-results (:id descriptor)))]
    (if-not replacement
      entry
      (case (:kind entry)
        :message (assoc-in entry [:data :message/result] replacement)
        :custom-context (assoc-in entry [:data :message/result] replacement)
        :evaluation (assoc-in entry [:data :result :result] replacement)
        :custom (assoc-in entry [:data :result :result] replacement)
        entry))))

(defn- artifact-descriptor? [value]
  (and (map? value)
       (uuid? (:id value))
       (string? (:session-id value))
       (string? (:sha256 value))
       (integer? (:bytes value))
       (contains? #{:text :edn :binary} (:kind value))))

(defn- result-artifact-ids [descriptor]
  (cond-> #{}
    (and (= :artifact (:kind descriptor)) (uuid? (:artifact-id descriptor)))
    (conj (:artifact-id descriptor))

    (artifact-descriptor? (get-in descriptor [:details :artifact]))
    (conj (get-in descriptor [:details :artifact :id]))))

(defn- referenced-artifact-ids [descriptors]
  (reduce into #{} (map result-artifact-ids descriptors)))

(defn- remap-result-details [details artifact-by-old]
  (if-let [replacement (let [artifact (:artifact details)]
                         (when (artifact-descriptor? artifact)
                           (get artifact-by-old (:id artifact))))]
    (assoc details :artifact replacement)
    details))

(defn- remap-entry-artifact-refs [entry artifact-by-old]
  (let [details (get-in entry [:data :result :details])]
    (if (and (contains? #{:evaluation :custom} (:kind entry)) (map? details))
      (assoc-in entry [:data :result :details]
                (remap-result-details details artifact-by-old))
      entry)))

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
        artifact-ids (referenced-artifact-ids source-results)
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
                                           :details (remap-result-details
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
  (let [copy-path
        (loop [remaining source-path
               included #{}
               result []]
          (if-let [entry (first remaining)]
            (if (and (= :label (:kind entry))
                     (not (contains? included (get-in entry [:data :entry-id]))))
              (recur (next remaining) included result)
              (recur (next remaining) (conj included (:id entry)) (conj result entry)))
            result))
        new-sid (or (:id opts) (util/id))
        _ (require-uuid! new-sid :session-id)
        id-map (into {} (map (fn [entry] [(:id entry) (util/id)]) copy-path))
        compaction-id-map
        (loop [remaining (rseq source-path)
               following nil
               result id-map]
          (if-let [entry (first remaining)]
            (if-let [copied-id (get id-map (:id entry))]
              (recur (next remaining) copied-id result)
              (recur (next remaining) following (assoc result (:id entry) following)))
            result))
        now (util/now)
        config (session-model/effective-config (::base-config source-row) source-path)
        copied-source-head (get id-map (:id (last copy-path)))
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
                        :metadata (dissoc (or (:metadata opts) (:metadata source-row))
                                          :agent/origin)
                        :labels (remap-labels (:labels source-row) id-map)
                        :parent-id (:id source-row)
                        :fork-entry (:source-leaf opts)}))
                     (assoc :head final-head))]
    (value/check! (nil? (find-session connection new-sid)) :session-exists
                 "Session ID already exists" {:session-id new-sid})
    (insert-session! connection snapshot (::base-config source-row))
    (let [value-records (copy-value-records! store connection (:id source-row)
                                             new-sid copy-path now)
          imported-results (:results value-records)
          copied-source
          (mapv (fn [index entry]
                  (-> entry
                      (assoc :id (get id-map (:id entry))
                             :session-id new-sid
                             :parent-id (when (pos? index)
                                          (get id-map (:id (nth copy-path (dec index))))))
                      (remap-entry-refs id-map compaction-id-map)
                      (remap-entry-result-refs imported-results)
                      (remap-entry-artifact-refs (:artifacts value-records))))
                (range) copy-path)
          start-seq (inc (long (reduce max 0 (map :seq copy-path))))
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
      (value/check! (nil? (scalar connection
                                  "SELECT 1 FROM agent_routes WHERE parent_session_id=? LIMIT 1"
                                  [sid])) :agent-descendants-exist
                    "Delete linked child sessions first" {:session-id sid})
      (execute-sql! connection "DELETE FROM agent_deliveries WHERE recipient_id=?" [sid])
      (execute-sql! connection "DELETE FROM agent_submissions WHERE source_id=?" [sid])
      (execute-sql! connection "DELETE FROM agent_routes WHERE session_id=?" [sid])
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
            events (volatile! (transient []))]
        (doseq [record (query-sql connection "SELECT * FROM jobs WHERE status IN ('queued','running','cancelling')" [] job-row)]
          (let [record (assoc record :status :interrupted :finished-at now :revision (inc (or (:revision record) 0))
                                    :error {:code "interrupted" :message "Runtime stopped before execution settled. Effects were not replayed."})]
            (execute-sql! connection "UPDATE jobs SET status='interrupted',record=? WHERE id=?" [(encode record) (:id record)])
            (vswap! events conj! (insert-event! connection (:session-id record)
                                  {:type :job/changed :data {:job record}}))))
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
              (let [interrupted (assoc op :status :interrupted :finished-at now
                                      :error {:code "interrupted"
                                              :message "Process restarted before the operation completed"})]
                (execute-sql! connection "UPDATE operations SET status='interrupted',finished_at=?,error=? WHERE id=?"
                              [now (encode (:error interrupted)) (:id op)])
                (vswap! events conj! (insert-event! connection sid
                                                    {:operation-id (:id op)
                                                     :type :operation/interrupted
                                                     :data {:reason :restart}
                                                     :time now}))
                (doseq [event (completion! connection interrupted)]
                  (vswap! events conj! event))))
            (update-session! connection (assoc snapshot :head @final-head :status :interrupted
                                               :revision (inc (:revision snapshot)) :updated-at now))))
        (execute-sql! connection "UPDATE agent_routes SET paused=1" [])
        (doseq [op (query-sql connection
                              "SELECT o.* FROM operations o JOIN agent_routes r ON r.session_id=o.session_id WHERE r.parent_session_id IS NOT NULL AND o.status IN ('completed','failed','cancelled','interrupted')"
                              [] operation-row)]
          (doseq [event (completion! connection op)]
            (vswap! events conj! event)))
        (persistent! @events)))))

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
    (doseq [label (:labels snapshot)]
      (value/check! (and (map? label) (contains? id-set (:entry-id label)))
                   :invalid-import "Session label references a missing entry"
                   {:entry-id (:entry-id label)}))
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
          (when kept
            (value/check! (contains? ancestors kept) :invalid-import
                         "Compaction retained entry must be on its preceding active path"
                         {:entry-id (:id entry) :first-kept-entry-id kept}))
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
          artifact-referenced (referenced-artifact-ids results)
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


(defn- copy-graph! [^Connection connection source-row source-entries opts]
  (let [source-entries (vec (sort-by :seq source-entries))
        new-sid (or (:id opts) (util/id))
        _ (require-uuid! new-sid :session-id)
        id-map (into {} (map (fn [entry] [(:id entry) (util/id)]) source-entries))
        now (util/now)
        base-config (session-model/normalize-config (::base-config source-row))
        copied-source-head (get id-map (:head source-row))
        raw-boundary (tool-boundary-entries
                      (session-model/active-path source-entries (:head source-row)))
        final-head (or (:id (last raw-boundary)) copied-source-head)
        snapshot (-> (session-model/new-snapshot
                      {:id new-sid
                       :name (or (:name opts) (:name source-row))
                       :cwd (or (:cwd opts) (:cwd source-row))
                       :config (session-model/normalize-config (:config source-row))
                       :status :idle
                       :created-at now
                       :metadata (dissoc (or (:metadata opts) (:metadata source-row))
                                         :agent/origin)
                       :labels (remap-labels (:labels source-row) id-map)})
                     (assoc :head final-head))
        copied-source (mapv (fn [entry]
                              (-> entry
                                  (assoc :id (get id-map (:id entry))
                                         :session-id new-sid
                                         :parent-id (get id-map (:parent-id entry)))
                                  (remap-entry-refs id-map id-map)
                                  (remap-entry-result-refs (:imported-results opts))
                                  (remap-entry-artifact-refs (:imported-artifacts opts))))
                            source-entries)
        start-seq (inc (long (reduce max 0 (map :seq source-entries))))
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
                                          :details (remap-result-details
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

(defn- route-ancestors [connection sid]
  (loop [current (route connection sid), visited #{}, rows []]
    (value/check! (not (contains? visited (:session-id current))) :invalid-agent-route
                  "Agent ancestry contains a cycle" {:session-id sid})
    (let [rows (conj rows current)]
      (if-let [parent (:parent-session-id current)]
        (recur (route connection parent) (conj visited (:session-id current)) rows)
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
  (store-read store #(public-route (route % sid))))

(defn- agent-descendants* [connection sid]
  (require-session connection sid)
  (loop [front (conj clojure.lang.PersistentQueue/EMPTY sid), seen #{}, result []]
    (if-let [current (peek front)]
      (if (contains? seen current)
        (recur (pop front) seen result)
        (let [children (query-sql connection
                                  "SELECT session_id FROM agent_routes WHERE parent_session_id=? ORDER BY name,session_id"
                                  [current] #(.getString ^ResultSet % "session_id"))]
          (recur (into (pop front) children) (conj seen current) (conj result current))))
      result)))

(defn agent-descendants [store sid]
  (store-read store #(agent-descendants* % sid)))

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
  (store-read store
    (fn [connection]
      (let [root (:root-id (route connection sid))
            target-id (:target-id opts)
            _ (when target-id
                (value/check! (= root (:root-id (route connection target-id)))
                              :agent-target-forbidden "Cannot inspect a foreign team" {}))
            [limit offset] (compact-agent-page opts)
            total (if target-id 1
                      (max 1 (long (or (scalar connection
                                               "SELECT COUNT(*) FROM agent_routes WHERE root_id=?"
                                               [root]) 0))))
            rows (query-sql
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
                    (let [config (decode (.getString rs "config"))]
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
  (store-read store
    (fn [connection]
      (let [root (:root-id (route connection sid))
            limit (page-limit (:limit opts) 100)
            offset (or (:offset opts) 0)]
        (value/check! (and (integer? offset) (not (neg? offset))) :invalid-arguments
                      "Page offset must be non-negative" {:offset offset})
        (mapv (fn [id]
                (let [state (route connection id)]
                  (merge (public-route state)
                         {:session (public-session (require-session connection id))
                          :operation (first (query-sql connection
                                                        "SELECT * FROM operations WHERE session_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1"
                                                        [id] operation-row))
                          :pending-count (long (or (scalar connection
                                                           "SELECT COUNT(*) FROM agent_deliveries WHERE recipient_id=? AND status='pending' AND context_id=?"
                                                           [id (:context-id state)]) 0))})))
              (if (scalar connection "SELECT 1 FROM agent_routes WHERE session_id=?" [root])
                (query-sql connection
                           "SELECT session_id FROM agent_routes WHERE root_id=? ORDER BY depth,session_id LIMIT ? OFFSET ?"
                           [root limit offset] #(.getString ^ResultSet % "session_id"))
                (if (zero? offset) [root] [])))))))

(defn- submission-row [connection sid submission-id]
  (first (query-sql connection
                    "SELECT kind,payload,receipt FROM agent_submissions WHERE source_id=? AND submission_id=?"
                    [sid submission-id]
                    (fn [^ResultSet rs] {:kind (keyword (.getString rs "kind"))
                                         :payload (decode (.getString rs "payload"))
                                         :receipt (decode (.getString rs "receipt"))}))))

(defn agent-submission [store source-sid submission-id]
  (require-uuid! submission-id :submission-id)
  (store-read store
    (fn [connection]
      (require-session connection source-sid)
      (:receipt (submission-row connection source-sid submission-id)))))

(defn- save-submission! [connection sid submission-id kind payload receipt]
  (execute-sql! connection
                "INSERT INTO agent_submissions(source_id,submission_id,kind,payload,receipt) VALUES(?,?,?,?,?)"
                [sid submission-id (name kind) (encode payload) (encode receipt)]))

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
    (require-uuid! submission-id :submission-id)
    (value/check! (and (string? name) (not (str/blank? name)) (<= (count name) 100))
                  :invalid-agent-name "Agent name must contain 1..100 characters" {})
    (value/check! (and (string? task) (not (str/blank? task))) :invalid-agent-task
                  "Agent task must be nonblank text" {})
    (value/check! (or (nil? context) (string? context)) :invalid-agent-context
                  "Agent context must be text" {})
    (checked-agent-content {:task task :context context})
    (value/check! (or (nil? origin) (map? origin)) :invalid-agent-origin
                  "Agent origin must be a map" {})
    (transact! store
      (fn [connection]
        (let [parent (route connection parent-sid)
              existing (existing-submission! connection parent-sid submission-id :spawn payload)]
          (if existing
            (let [handle (:receipt existing)]
              {:session (public-session (require-session connection (:session-id handle)))
               :operation (find-operation connection (:operation-id handle))
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
                  _ (ensure-root-route! connection root)
                  _ (value/check! (< (long (or (scalar connection
                                                       "SELECT COUNT(*) FROM agent_routes WHERE root_id=?"
                                                       [root]) 0)) limit)
                                  :agent-limit "Agent team limit reached" {:limit limit})
                  _ (value/check! (nil? (scalar connection
                                                "SELECT 1 FROM agent_routes WHERE root_id=? AND name=?"
                                                [root name])) :agent-name-exists
                                  "Agent name already exists in this team" {:name name})
                  _ (require-uuid! id :session-id)
                  _ (require-uuid! operation-id :operation-id)
                  _ (value/check! (nil? (find-session connection id)) :session-exists
                                  "Session ID already exists" {:session-id id})
                  _ (value/check! (nil? (find-operation connection operation-id))
                                  :operation-exists "Operation ID already exists" {:operation-id operation-id})
                  snapshot (session-model/new-snapshot
                            (prepare-session-options {:id id :name name :cwd cwd
                                                      :config config
                                                      :metadata {:agent/origin origin
                                                                 :title/source :user}}))
                  _ (insert-session! connection snapshot (:config snapshot))
                  context-id (util/id)
                  _ (execute-sql! connection
                                  "INSERT INTO agent_routes(session_id,root_id,parent_session_id,name,context_id,parent_context_id,depth,paused,stopped,origin) VALUES(?,?,?,?,?,?,?,0,0,?)"
                                  [id root parent-sid name context-id (:context-id parent) depth
                                   (encode (or origin {}))])
                  initial (str (when (seq context) (str "Context:\n" context "\n\n"))
                               task)
                  now (util/now)
                  entry {:id (util/id) :session-id id :parent-id nil :seq 1 :kind :message
                         :data {:message/role :user :message/content initial
                                :message/agent {:kind :task :from parent-sid}}
                         :created-at now}
                  _ (execute-sql! connection
                                  "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                                  [(:id entry) id nil 1 "message" (encode (:data entry)) now])
                  snapshot (assoc snapshot :head (:id entry) :revision 1 :updated-at now)
                  _ (update-session! connection snapshot)
                  operation (upsert-operation! connection
                                               (normalize-operation id nil {:id operation-id
                                                                             :kind :run :status :queued}))
                  handle {:session-id id :operation-id operation-id :submission-id submission-id}
                  _ (save-submission! connection parent-sid submission-id :spawn payload handle)
                  events [(insert-event! connection id {:type :entry/committed :data {:entry entry}})
                          (routing-event! connection root id :agent/changed
                                          {:reason :created :operation-id operation-id})]]
              {:session (public-session snapshot) :operation operation :handle handle
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
      (query-sql connection
                 "SELECT * FROM agent_routes WHERE root_id=? AND session_id<>? ORDER BY depth,session_id"
                 [root (:session-id source)] route-row)
      (let [id (cond
                 (= :parent target) (:parent-session-id source)
                 (and (string? target) (uuid? target)) target
                 (string? target) (scalar connection
                                          "SELECT session_id FROM agent_routes WHERE root_id=? AND name=?"
                                          [root target])
                 :else nil)]
        (value/check! id :agent-target-not-found "Agent target does not exist"
                      {:target target})
        (let [resolved (route connection id)]
          (value/check! (= root (:root-id resolved)) :agent-target-forbidden
                        "Agent target belongs to another team" {:target target})
          [resolved])))))

(defn agent-target-id [store viewer-sid target]
  (store-read store
    (fn [connection]
      (let [viewer (route connection viewer-sid)]
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
          result (first (query-sql connection
                                   "SELECT descriptor FROM results WHERE session_id=? AND id=?"
                                   [session-id id] #(decode (.getString ^ResultSet % "descriptor"))))]
      (value/check! (and result (= :inline (:kind result)) (:available? result))
                    :agent-result-unavailable "Only durable inline results can cross sessions"
                    {:session-id session-id :result-id id})
      {:value (:value result) :source-result {:session-id session-id :id id}})
    content))

(defn send-agent-message! [store source-sid target content opts]
  (let [{:keys [submission-id wake? kind]} opts
        _ (require-uuid! submission-id :submission-id)
        _ (value/check! (or (nil? wake?) (instance? Boolean wake?)) :invalid-arguments
                        "Wake policy must be boolean" {})
        kind (or kind :peer)
        _ (value/check! (contains? #{:peer :human} kind) :invalid-agent-message
                        "Message kind must be peer or human" {:kind kind})
        payload {:target target :content content :kind kind :wake? (boolean wake?)}]
    (checked-agent-content content)
    (transact! store
      (fn [connection]
        (let [source (route connection source-sid)
              existing (existing-submission! connection source-sid submission-id :send payload)]
          (if existing
            {:receipt (:receipt existing) :events []}
            (let [source (active-route! connection source-sid)
                  targets (resolve-agent-target connection source target)
                  _ (value/check! (seq targets) :agent-target-not-found
                                  "No agents are available for this message" {:target target})
                  _ (doseq [{:keys [session-id]} targets] (active-route! connection session-id))
                  content (portable-message-content connection source content)
                  serialized (checked-agent-content content)
                  id (util/id)
                  now (util/now)
                  recipients (mapv :session-id targets)
                  receipt {:id id :submission-id submission-id :from source-sid
                           :recipients recipients :status :accepted}
                  _ (execute-sql! connection
                                  "INSERT INTO agent_messages(id,root_id,sender_id,kind,content,source_operation_id,created_at,wake) VALUES(?,?,?,?,?,?,?,?)"
                                  [id (:root-id source) source-sid (name kind) serialized nil now
                                   (if wake? 1 0)])
                  _ (doseq [recipient targets]
                      (execute-sql! connection
                                    "INSERT INTO agent_deliveries(message_id,recipient_id,context_id,status) VALUES(?,?,?,'pending')"
                                    [id (:session-id recipient) (:context-id recipient)]))
                  _ (save-submission! connection source-sid submission-id :send payload receipt)
                  events (mapv #(routing-event! connection (:root-id source) %
                                                :agent/message {:message-id id :from source-sid :kind kind})
                               recipients)]
              {:receipt receipt :events events})))))))

(defn- message-row [^ResultSet rs]
  {:id (.getString rs "id")
   :seq (.getLong rs "seq")
   :root-id (.getString rs "root_id")
   :from (.getString rs "sender_id")
   :kind (keyword (.getString rs "kind"))
   :content (decode (.getString rs "content"))
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
  (require-uuid! message-id :message-id)
  (store-read store
    (fn [connection]
      (let [viewer (route connection viewer-sid)
            [limit offset] (if (:internal? opts)
                             (let [limit (page-limit (:limit opts) 8)
                                   offset (or (:offset opts) 0)]
                               (value/check! (and (integer? offset) (not (neg? offset)))
                                             :invalid-arguments "Page offset must be non-negative" {})
                               [limit offset])
                             (compact-agent-page opts))
            message (first
                     (query-sql
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
                          (:detailed? opts) (assoc :content (decode (.getString rs "content")))))))
            _ (value/check! message :agent-message-not-found
                            "Agent message is not available" {:message-id message-id})
            counts (first (query-sql connection
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
            deliveries (query-sql
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
  (store-read store
    (fn [connection]
      (let [viewer (route connection viewer-sid)
            sid (or (:session-id opts) viewer-sid)
            selected (route connection sid)
            _ (value/check! (= (:root-id viewer) (:root-id selected))
                            :agent-target-forbidden "Cannot inspect a foreign team" {})
            before (or (:before opts) Long/MAX_VALUE)
            limit (page-limit (:limit opts) 50)
            _ (value/check! (and (integer? before) (pos? before)) :invalid-arguments
                            "Message cursor must be a positive sequence" {})]
        (mapv (fn [message]
                (assoc (select-keys message [:id :seq :from :kind :content :created-at :operation-id])
                       :recipients (query-sql connection
                                              "SELECT recipient_id FROM agent_deliveries WHERE message_id=? ORDER BY recipient_id"
                                              [(:id message)] #(.getString ^ResultSet % "recipient_id"))
                       :deliveries (query-sql connection
                                              "SELECT * FROM agent_deliveries WHERE message_id=? ORDER BY recipient_id"
                                              [(:id message)] delivery-row)))
              (query-sql connection
                         (str "SELECT m.* FROM agent_messages m WHERE m.seq<? AND m.root_id=? "
                              "AND (m.sender_id=? OR EXISTS (SELECT 1 FROM agent_deliveries d "
                              "WHERE d.message_id=m.id AND d.recipient_id=?)) ORDER BY m.seq DESC LIMIT ?")
                         [before (:root-id viewer) sid sid limit] message-row))))))

(defn- pending-deliveries [connection sid limit]
  (query-sql connection
             (str "SELECT m.* FROM agent_messages m JOIN agent_deliveries d ON d.message_id=m.id "
                  "WHERE d.recipient_id=? AND d.status='pending' ORDER BY m.seq LIMIT ?")
             [sid limit] message-row))

(defn pending-agent-messages? [store sid]
  (store-read store
    (fn [connection]
      (let [state (route connection sid)]
        (boolean
         (and (try (active-route! connection sid) true
                   (catch clojure.lang.ExceptionInfo _ false))
              (scalar connection
                      "SELECT 1 FROM agent_deliveries WHERE recipient_id=? AND status='pending' AND context_id=? LIMIT 1"
                      [sid (:context-id state)])))))))

(defn- eligible-wake-route [connection sid]
  (let [state (route connection sid)
        valid? (try (active-route! connection sid) true
                    (catch clojure.lang.ExceptionInfo _ false))]
    (when (and valid? (not (:paused? state))
               (scalar connection
                       (str "SELECT 1 FROM agent_deliveries d JOIN agent_messages m ON m.id=d.message_id "
                            "WHERE d.recipient_id=? AND d.context_id=? AND d.status='pending' "
                            "AND (m.kind='completion' OR m.wake=1 OR ?=1) LIMIT 1")
                       [sid (:context-id state) (if (:parent-session-id state) 1 0)]))
      state)))

(defn agent-wake? [store sid]
  (store-read store #(boolean (eligible-wake-route % sid))))

(defn agent-wake-roots [store]
  (store-read store
    (fn [connection]
      (let [candidates (query-sql
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
     (if (<= (count text) max-agent-preview)
       text
       (str (subs text 0 max-agent-preview)
            "\n[Preview shortened; read the recipient-owned result for full content.]")))))

(defn deliver-agent-messages! [store sid operation-id]
  (require-uuid! operation-id :operation-id)
  (transact! store
    (fn [connection]
      (let [operation (or (find-operation connection operation-id)
                          (value/fail! :operation-not-found
                                       "Delivery operation does not exist" {:operation-id operation-id}))
            _ (value/check! (= sid (:session-id operation)) :operation-forbidden
                            "Delivery operation belongs to another session" {:operation-id operation-id})
            _ (value/check! (and (contains? #{:run :continue} (:kind operation))
                                 (= :running (:status operation)))
                            :invalid-agent-operation
                            "Agent messages require a running run or continue operation"
                            {:operation-id operation-id})
            state (route connection sid)
            active-state (try (active-route! connection sid) :active
                              (catch clojure.lang.ExceptionInfo error
                                (case (:error/code (ex-data error))
                                  "agent-stopped" :stopped
                                  "agent-stale-context" :stale
                                  (throw error))))
            messages (pending-deliveries connection sid 100)
            snapshot (require-session connection sid)
            seq* (volatile! (long (or (scalar connection
                                             "SELECT MAX(seq) FROM entries WHERE session_id=?"
                                             [sid]) 0)))
            result* (volatile! (long (or (scalar connection
                                                "SELECT MAX(id) FROM results WHERE session_id=?"
                                                [sid]) 0)))
            head* (volatile! (:head snapshot))
            entries (volatile! [])
            delivered (volatile! [])
            events (volatile! [])]
        (doseq [message messages]
          (let [scope-current? (= (:context-id state)
                                  (scalar connection
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
                (execute-sql! connection
                              "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                              [sid result-id "inline" (encode descriptor) (:created-at entry)])
                (execute-sql! connection
                              "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                              [(:id entry) sid @head* (:seq entry) "message"
                               (encode (:data entry)) (:created-at entry)])
                (execute-sql! connection
                              "UPDATE agent_deliveries SET status='delivered',entry_id=?,operation_id=? WHERE message_id=? AND recipient_id=?"
                              [(:id entry) operation-id (:id message) sid])
                (vreset! head* (:id entry))
                (vswap! entries conj entry)
                (vswap! delivered conj (:id message))
                (vswap! events conj (insert-event! connection sid
                                                    {:type :entry/committed :data {:entry entry}}))
                (vswap! events conj (routing-event! connection (:root-id state) sid
                                                    :agent/message
                                                    {:message-id (:id message)
                                                     :status :delivered})))
              (when (or (= :stale active-state) (not scope-current?))
                (execute-sql! connection
                              "UPDATE agent_deliveries SET status='superseded' WHERE message_id=? AND recipient_id=?"
                              [(:id message) sid])
                (vswap! events conj (routing-event! connection (:root-id state) sid
                                                    :agent/message
                                                    {:message-id (:id message)
                                                     :status :superseded}))))))
        (when (seq @entries)
          (update-session! connection (assoc snapshot :head @head*
                                             :revision (inc (:revision snapshot))
                                             :updated-at (util/now))))
        {:entries @entries :events @events :delivered @delivered}))))

(defn set-agent-paused! [store sid paused?]
  (value/check! (instance? Boolean paused?) :invalid-arguments
                "Pause state must be boolean" {})
  (transact! store
    (fn [connection]
      (let [state (ensure-root-route! connection sid)]
        (execute-sql! connection "UPDATE agent_routes SET paused=? WHERE session_id=?"
                      [(if paused? 1 0) sid])
        {:state (public-route (route connection sid))
         :events [(routing-event! connection (:root-id state) sid
                                  :agent/changed {:reason :pause :paused? paused?})]}))))

(defn set-agent-stopped! [store sid stopped?]
  (value/check! (instance? Boolean stopped?) :invalid-arguments
                "Stop state must be boolean" {})
  (transact! store
    (fn [connection]
      (let [state (ensure-root-route! connection sid)
            members (agent-descendants* connection sid)]
        (execute-sql! connection "UPDATE agent_routes SET stopped=? WHERE session_id=?"
                      [(if stopped? 1 0) sid])
        {:state (public-route (route connection sid))
         :events (mapv #(routing-event! connection (:root-id state) % :agent/changed
                                        {:reason :stop :stopped? stopped?})
                       members)}))))

(defn agent-result [store viewer-sid target-sid operation-id]
  (require-uuid! operation-id :operation-id)
  (store-read store
    (fn [connection]
      (let [viewer (route connection viewer-sid)
            target (route connection target-sid)
            _ (value/check! (= (:root-id viewer) (:root-id target))
                            :agent-target-forbidden "Agent belongs to another team" {})
            op (or (find-operation connection operation-id)
                   (value/fail! :operation-not-found "Operation does not exist"
                                {:operation-id operation-id}))]
        (value/check! (= (:session-id op) target-sid) :operation-forbidden
                      "Operation does not belong to target agent"
                      {:operation-id operation-id})
        (assoc (select-keys op [:session-id :status :result :error :finished-at :created-at])
               :operation-id operation-id)))))

(defn latest-event-seq [store]
  (store-read store #(long (or (scalar % "SELECT MAX(seq) FROM events" []) 0))))
