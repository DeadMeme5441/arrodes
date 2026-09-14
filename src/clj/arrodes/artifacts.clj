(ns arrodes.artifacts
  "Immutable artifacts and durable result descriptors backed by arrodes.store."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [arrodes.store :as store]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files StandardCopyOption LinkOption OpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.sql Connection PreparedStatement ResultSet)
           (java.util Base64 UUID)))

(def ^:private artifact-kinds #{:text :edn :binary})
(def ^:private result-kinds #{:inline :artifact :live})
(def ^:private default-page-size 40000)
(def ^:private bytes-class (Class/forName "[B"))
(def ^:private max-inline-nodes 100000)

(defn- encode [value]
  (binding [*print-length* nil *print-level* nil *print-dup* false]
    (pr-str value)))

(defn- decode [value]
  (binding [*read-eval* false]
    (edn/read-string value)))

(defn- set-params! [^PreparedStatement statement params]
  (doseq [[index value] (map-indexed vector params)]
    (cond
      (nil? value) (.setObject statement (inc index) nil)
      (instance? bytes-class value) (.setBytes statement (inc index) value)
      :else (.setObject statement (inc index) value)))
  statement)

(defn- execute! [^Connection connection sql params]
  (with-open [statement (set-params! (.prepareStatement connection sql) params)]
    (.executeUpdate statement)))

(defn- query [^Connection connection sql params row-fn]
  (with-open [statement (set-params! (.prepareStatement connection sql) params)
              result (.executeQuery statement)]
    (loop [rows []]
      (if (.next result)
        (recur (conj rows (row-fn result)))
        rows))))

(defn- scalar [^Connection connection sql params]
  (first (query connection sql params #(.getObject ^ResultSet % 1))))

(defn- authorize-session! [^Connection connection sid]
  (value/check! (and (string? sid)
                    (try (UUID/fromString sid) true (catch IllegalArgumentException _ false)))
               :invalid-id "Expected a UUID session ID" {:session-id sid})
  (value/check! (some? (scalar connection "SELECT 1 FROM sessions WHERE id=?" [sid]))
               :session-not-found "Session does not exist" {:session-id sid}))


(defn- content-bytes [content]
  (cond
    (bytes? content) content
    (string? content) (.getBytes ^String content StandardCharsets/UTF_8)
    :else (value/fail! :invalid-artifact-content "Artifact content must be a string or byte array"
                      {:class (some-> content class .getName)})))

(defn- infer-kind [content requested]
  (let [kind (or requested (if (bytes? content) :binary :text))
        kind (if (string? kind) (keyword kind) kind)]
    (value/check! (contains? artifact-kinds kind) :invalid-artifact-kind
                 "Artifact kind must be :text, :edn, or :binary" {:kind kind})
    (when (not= (= :binary kind) (bytes? content))
      (value/fail! :invalid-artifact-content
                  "Binary artifacts require bytes; text and EDN artifacts require strings"
                  {:kind kind}))
    kind))

(defn- artifact-path [store sha]
  (when-let [root (:artifact-dir store)]
    (.resolve (.resolve (util/path root) (subs sha 0 2)) sha)))

(defn- private-dir! [path]
  (let [existed? (Files/exists path (make-array LinkOption 0))]
    (Files/createDirectories path (make-array FileAttribute 0))
    (value/check! (not (Files/isSymbolicLink path)) :insecure-directory
                 "Artifact directory cannot be a symbolic link" {:path (str path)})
    (try
      (if existed?
        (let [mode (PosixFilePermissions/toString
                    (Files/getPosixFilePermissions path (make-array LinkOption 0)))]
          (value/check! (= "------" (subs mode 3)) :insecure-directory
                       "Existing artifact directory grants group or other access"
                       {:path (str path)}))
        (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rwx------")))
      (catch UnsupportedOperationException _ nil))))

(defn- immutable-write! [store sha bytes]
  (let [target (artifact-path store sha)
        parent (.getParent target)]
    (private-dir! parent)
    (value/check! (not (Files/isSymbolicLink target)) :artifact-corrupt
                 "Artifact content path cannot be a symbolic link" {:sha256 sha})
    (if (Files/exists target (make-array LinkOption 0))
      target
      (let [temp (Files/createTempFile parent ".artifact-" ".tmp" (make-array FileAttribute 0))]
        (try
          (Files/write temp bytes (make-array OpenOption 0))
          (try
            (Files/setPosixFilePermissions temp (PosixFilePermissions/fromString "rw-------"))
            (catch UnsupportedOperationException _ nil))
          (try
            (Files/move temp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
            (catch java.nio.file.FileAlreadyExistsException _ target))
          (finally (Files/deleteIfExists temp)))))
    (value/check! (= sha (util/sha256 (Files/readAllBytes target))) :artifact-corrupt
                 "Content-addressed artifact file is corrupt" {:sha256 sha})
    (util/private-file! target)
    target))

(defn- artifact-row [^ResultSet rs]
  (cond-> {:id (.getString rs "id")
           :session-id (.getString rs "session_id")
           :sha256 (.getString rs "sha256")
           :bytes (.getLong rs "bytes")
           :kind (keyword (.getString rs "kind"))
           :available? (pos? (.getInt rs "available"))
           :created-at (.getLong rs "created_at")}
    (.getString rs "name") (assoc :name (.getString rs "name"))))

(defn- find-artifact [^Connection connection id]
  (first (query connection "SELECT * FROM artifacts WHERE id=?" [id] artifact-row)))

(defn- artifact-content-present? [store connection descriptor]
  (and (:available? descriptor)
       (if (:memory? store)
         (some? (scalar connection "SELECT content FROM artifacts WHERE id=? AND session_id=?"
                        [(:id descriptor) (:session-id descriptor)]))
         (let [path (artifact-path store (:sha256 descriptor))]
           (and (not (Files/isSymbolicLink path))
                (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                (= (:bytes descriptor) (Files/size path)))))))

(defn- actual-availability [store descriptor]
  (assoc descriptor :available?
         (store/store-read store #(artifact-content-present? store % descriptor))))

(defn put!
  "Stores immutable content and returns its session-authorized descriptor."
  [store sid content opts]
  (let [kind (infer-kind content (:kind opts))
        bytes (content-bytes content)
        sha (util/sha256 bytes)
        id (util/id)
        now (util/now)
        artifact-name (:name opts)
        _ (when artifact-name
            (value/check! (and (string? artifact-name) (not (str/blank? artifact-name))) :invalid-artifact-name
                         "Artifact name must be a non-blank string" {}))
        _ (store/session store sid)
        _ (when-not (:memory? store) (immutable-write! store sha bytes))
        descriptor (cond-> {:id id :session-id sid :sha256 sha :bytes (alength ^bytes bytes)
                            :kind kind :available? true :created-at now}
                     artifact-name (assoc :name artifact-name))]
    (store/transact! store
      (fn [connection]
        (authorize-session! connection sid)
        (execute! connection
                  "INSERT INTO artifacts(id,session_id,sha256,bytes,kind,available,created_at,name,content) VALUES(?,?,?,?,?,?,?,?,?)"
                  [id sid sha (alength ^bytes bytes) (name kind) 1 now artifact-name
                   (when (:memory? store) bytes)])
        descriptor))))

(defn get-artifact
  "Returns metadata for an artifact after enforcing owning-session authorization."
  [store sid id]
  (value/check! (and (string? id)
                    (try (UUID/fromString id) true (catch IllegalArgumentException _ false)))
               :invalid-id "Expected a UUID artifact ID" {:artifact-id id})
  (let [descriptor
        (store/store-read store
          (fn [connection]
            (authorize-session! connection sid)
            (let [found (find-artifact connection id)]
              (value/check! found :artifact-not-found "Artifact does not exist" {:artifact-id id})
              (value/check! (= sid (:session-id found)) :artifact-forbidden
                           "Artifact belongs to another session" {:artifact-id id :session-id sid})
              found)))]
    (actual-availability store descriptor)))

(defn list-artifacts [store sid]
  (let [descriptors
        (store/store-read store
          (fn [connection]
            (authorize-session! connection sid)
            (query connection "SELECT * FROM artifacts WHERE session_id=? ORDER BY created_at,id"
                   [sid] artifact-row)))]
    (mapv #(actual-availability store %) descriptors)))

(defn- artifact-bytes [store descriptor]
  (if (:memory? store)
    (store/store-read store
      (fn [connection]
        (first (query connection "SELECT content FROM artifacts WHERE id=? AND session_id=?"
                      [(:id descriptor) (:session-id descriptor)]
                      #(.getBytes ^ResultSet % "content")))))
    (let [path (artifact-path store (:sha256 descriptor))]
      (when (and (not (Files/isSymbolicLink path))
                 (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
        (Files/readAllBytes path)))))
(defn- page-bounds [length opts]
  (let [raw-offset (or (:offset opts) 1)
        raw-limit (or (:limit opts) default-page-size)]
    (value/check! (and (integer? raw-offset) (pos? raw-offset)
                      (<= raw-offset Long/MAX_VALUE))
                 :invalid-offset "Artifact offset is 1-based and must be a positive integer"
                 {:offset raw-offset})
    (value/check! (and (integer? raw-limit) (pos? raw-limit)
                      (<= raw-limit Long/MAX_VALUE))
                 :invalid-limit "Artifact page limit must be a positive integer"
                 {:limit raw-limit})
    (let [offset (long raw-offset)
          limit (long raw-limit)
          start (min length (dec offset))
          end (+ start (min limit (- length start)))]
      {:offset offset :start start :end end
       :next-offset (when (< end length) (inc end))
       :truncated? (< end length)})))

(defn read!
  "Reads a bounded artifact page. Text offsets are character-based; binary offsets are bytes."
  [store sid id opts]
  (let [descriptor (get-artifact store sid id)]
    (value/check! (:available? descriptor) :artifact-unavailable
                 "Artifact content is unavailable" {:artifact-id id :session-id sid})
    (let [bytes (artifact-bytes store descriptor)]
      (value/check! bytes :artifact-unavailable "Artifact content is unavailable"
                   {:artifact-id id :session-id sid})
      (value/check! (and (= (:bytes descriptor) (alength ^bytes bytes))
                        (= (:sha256 descriptor) (util/sha256 bytes)))
                   :artifact-corrupt "Artifact content failed integrity verification"
                   {:artifact-id id :session-id sid})
      (if (= :binary (:kind descriptor))
        (let [{:keys [offset start end next-offset truncated?]} (page-bounds (alength ^bytes bytes) opts)
              piece (java.util.Arrays/copyOfRange ^bytes bytes (int start) (int end))]
          {:artifact descriptor
           :content (.encodeToString (Base64/getEncoder) piece)
           :encoding :base64
           :offset offset
           :next-offset next-offset
           :truncated? truncated?})
        (let [text (String. ^bytes bytes StandardCharsets/UTF_8)
              {:keys [offset start end next-offset truncated?]} (page-bounds (count text) opts)]
          {:artifact descriptor
           :content (subs text start end)
           :offset offset
           :next-offset next-offset
           :truncated? truncated?})))))

(defn- bounded-edn-shape? [value]
  (let [remaining (volatile! max-inline-nodes)]
    (letfn [(walk [item depth]
              (and (< depth 32)
                   (pos? @remaining)
                   (do
                     (vswap! remaining dec)
                     (cond
                       (or (nil? item) (string? item) (number? item) (keyword? item)
                           (symbol? item) (char? item) (instance? Boolean item)
                           (instance? UUID item) (instance? java.util.Date item)) true
                       (vector? item) (and (<= (count item) max-inline-nodes)
                                           (every? #(walk % (inc depth)) item))
                       (instance? clojure.lang.PersistentList item)
                       (and (<= (count item) max-inline-nodes)
                            (every? #(walk % (inc depth)) item))
                       (and (list? item) (empty? item)) true
                       (set? item) (and (<= (count item) max-inline-nodes)
                                        (every? #(walk % (inc depth)) item))
                       (map? item) (and (<= (count item) max-inline-nodes)
                                        (every? (fn [[key nested]]
                                                  (and (walk key (inc depth))
                                                       (walk nested (inc depth))))
                                                item))
                       :else false))))]
      (boolean (walk value 0)))))

(defn- serializable! [value field]
  (value/check! (bounded-edn-shape? value) :non-serializable-result
               "Result data is not bounded durable EDN"
               {:field field :class (some-> value class .getName)})
  (try
    (let [encoded (encode value)]
      (decode encoded)
      value)
    (catch Throwable error
      (value/fail! :non-serializable-result "Result data is not durable EDN"
                  {:field field :class (some-> value class .getName)
                   :cause (ex-message error)}))))

(defn- normalize-result [store connection sid descriptor]
  (let [kind (if (string? (:kind descriptor)) (keyword (:kind descriptor)) (:kind descriptor))]
    (value/check! (contains? result-kinds kind) :invalid-result-kind
                 "Result kind must be :inline, :artifact, or :live" {:kind kind})
    (value/check! (string? (or (:content descriptor) "")) :invalid-result
                 "Result content must be a string" {:field :content})
    (value/check! (map? (or (:details descriptor) {})) :invalid-result
                 "Result details must be a map" {:field :details})
    (serializable! (or (:details descriptor) {}) :details)
    (when (= :inline kind)
      (value/check! (contains? descriptor :value) :invalid-result
                   "Inline results must contain a value" {:field :value})
      (serializable! (:value descriptor) :value))
    (when (= :artifact kind)
      (let [artifact-id (:artifact-id descriptor)
            found (find-artifact connection artifact-id)]
        (value/check! found :artifact-not-found "Result artifact does not exist" {:artifact-id artifact-id})
        (value/check! (= sid (:session-id found)) :artifact-forbidden
                     "Result artifact belongs to another session" {:artifact-id artifact-id :session-id sid})
        (value/check! (artifact-content-present? store connection found) :artifact-unavailable
                     "Result artifact content is unavailable" {:artifact-id artifact-id :session-id sid})))
    (cond-> {:kind kind
             :content (or (:content descriptor) "")
             :details (or (:details descriptor) {})
             :available? (if (contains? descriptor :available?) (boolean (:available? descriptor)) true)}
      (= :inline kind) (assoc :value (:value descriptor))
      (= :artifact kind) (assoc :artifact-id (:artifact-id descriptor)))))

(defn put-result!
  "Persists a normalized result and assigns the next positive per-session integer ID."
  [store sid descriptor]
  (store/transact! store
    (fn [connection]
      (authorize-session! connection sid)
      (let [normalized (normalize-result store connection sid descriptor)
            id (inc (long (or (scalar connection "SELECT MAX(id) FROM results WHERE session_id=?" [sid]) 0)))
            result (assoc normalized :id id :session-id sid)
            now (util/now)]
        (execute! connection "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                  [sid id (name (:kind result)) (encode result) now])
        result))))

(defn- require-result-id! [id]
  (value/check! (and (integer? id) (pos? id) (<= id Long/MAX_VALUE))
               :invalid-result-id
               "Result ID must be a positive 64-bit integer" {:result-id id})
  (long id))

(defn- with-actual-result-availability [store connection descriptor]
  (assoc descriptor :available?
         (boolean
          (and (:available? descriptor)
               (case (:kind descriptor)
                 :artifact
                 (when-let [artifact (find-artifact connection (:artifact-id descriptor))]
                   (and (= (:session-id descriptor) (:session-id artifact))
                        (artifact-content-present? store connection artifact)))
                 :live true
                 :inline true
                 false)))))

(defn result [store sid id]
  (store/store-read store
    (fn [connection]
      (authorize-session! connection sid)
      (let [id (require-result-id! id)
            value (first (query connection "SELECT descriptor FROM results WHERE session_id=? AND id=?"
                                [sid id] #(.getString ^ResultSet % "descriptor")))]
        (if value
          (with-actual-result-availability store connection (decode value))
          (value/fail! :result-not-found "Result does not exist"
                      {:session-id sid :result-id id}))))))

(defn results [store sid]
  (store/store-read store
    (fn [connection]
      (authorize-session! connection sid)
      (mapv #(with-actual-result-availability store connection (decode %))
            (query connection "SELECT descriptor FROM results WHERE session_id=? ORDER BY id"
                   [sid] #(.getString ^ResultSet % "descriptor"))))))

(defn release-live-results!
  "Marks a session's live-only result descriptors unavailable when its live registry closes."
  [store sid]
  (store/transact! store
    (fn [connection]
      (authorize-session! connection sid)
      (let [rows (query connection
                        "SELECT id,descriptor FROM results WHERE session_id=? AND kind='live'"
                        [sid]
                        (fn [^ResultSet rs]
                          {:id (.getLong rs "id")
                           :descriptor (decode (.getString rs "descriptor"))}))
            available (filterv #(get-in % [:descriptor :available?]) rows)]
        (doseq [{:keys [id descriptor]} available]
          (execute! connection
                    "UPDATE results SET descriptor=? WHERE session_id=? AND id=?"
                    [(encode (assoc descriptor :available? false)) sid id]))
        (count available)))))

(defn read-result!
  "Pages the printable content of a result descriptor with 1-based character offsets."
  [store sid id opts]
  (let [descriptor (result store sid id)
        content (:content descriptor)
        {:keys [offset start end next-offset truncated?]} (page-bounds (count content) opts)]
    {:result descriptor
     :content (subs content start end)
     :offset offset
     :next-offset next-offset
     :truncated? truncated?}))
