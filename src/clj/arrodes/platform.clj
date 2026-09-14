(ns arrodes.platform
  "JVM clocks, identifiers, paths, files, hashing, cancellation, and bounded output."
  (:require [arrodes.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.channels FileChannel)
           (java.nio.file AtomicMoveNotSupportedException Files LinkOption OpenOption Path Paths
                          StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security MessageDigest)
           (java.util UUID HexFormat)))

(declare ensure-dir! read-edn sha256 write-edn!)

(defn now [] (System/currentTimeMillis))
(defn id [] (str (UUID/randomUUID)))
(defn path ^Path [value]
  (if (instance? Path value) value (Paths/get (str value) (make-array String 0))))
(defn canonical-path [value]
  (str (.normalize (.toAbsolutePath (path value)))))
(defn resolve-path [cwd value]
  (canonical-path (.resolve (path cwd) (str value))))

(defn real-path [value]
  (str (.toRealPath (path value) (make-array LinkOption 0))))

(defn- git-marker? [directory]
  (let [marker (.resolve ^Path directory ".git")]
    (or (Files/isDirectory marker (make-array LinkOption 0))
        (Files/isRegularFile marker (make-array LinkOption 0)))))

(defn project-root
  "Returns the canonical Git worktree root containing cwd, or canonical cwd outside Git."
  [cwd]
  (let [cwd (.toRealPath (path cwd) (make-array LinkOption 0))]
    (loop [candidate cwd]
      (cond
        (git-marker? candidate) (str candidate)
        (.getParent candidate) (recur (.getParent candidate))
        :else (str cwd)))))

(defn- readable-project-name [root]
  (let [filename (.getFileName (path root))
        value (-> (or (some-> filename str) "root")
                  (str/replace #"[^A-Za-z0-9._-]+" "-")
                  (str/replace #"^[._-]+|[._-]+$" ""))
        value (if (str/blank? value) "project" value)]
    (subs value 0 (min 80 (count value)))))

(defn project-info
  "Pure project discovery. Does not create home or project files."
  [home cwd]
  (let [root (project-root cwd)
        id (str (readable-project-name root) "-" (sha256 root))]
    {:id id
     :root root
     :directory (resolve-path (resolve-path (canonical-path home) "projects") id)}))

(defn project-dir [home cwd]
  (:directory (project-info home cwd)))

(def ^:private project-locks (atom {}))

(defn- project-lock [directory]
  (get (swap! project-locks
              #(if (contains? % directory) % (assoc % directory (Object.))))
       directory))

(defn open-project!
  "Creates a project state directory and records its canonical identity."
  [home cwd]
  (let [{:keys [directory] :as project} (project-info home cwd)
        metadata-path (resolve-path directory "project.edn")]
    (locking (project-lock directory)
      (ensure-dir! directory)
      (let [existing (read-edn metadata-path {})
            now (now)
            metadata (merge {:version 1 :created-at now}
                            (select-keys existing [:created-at])
                            project
                            {:version 1 :last-opened-at now})]
        (write-edn! metadata-path metadata)
        (assoc project :metadata metadata)))))
(defn ensure-dir! [value]
  (Files/createDirectories (path value) (make-array FileAttribute 0))
  (str value))
(defn private-file! [value]
  (try
    (Files/setPosixFilePermissions (path value) (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil))
  (str value))
(defn atomic-write!
  ([file content] (atomic-write! file content {}))
  ([file content {:keys [private?] :or {private? false}}]
   (let [target (.toAbsolutePath (path file))
         parent (.getParent target)
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         temp (Files/createTempFile parent ".arrodes-" ".tmp" (make-array FileAttribute 0))]
     (try
       (when private? (private-file! temp))
       (with-open [out (java.io.FileOutputStream. (.toFile temp))]
         (.write out (.getBytes (str content) java.nio.charset.StandardCharsets/UTF_8))
         (.sync (.getFD out)))
       (Files/move temp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
       (str target)
       (finally (Files/deleteIfExists temp))))))
(defn read-edn
  ([file] (read-edn file nil))
  ([file fallback]
   (if (.isFile (io/file (str file)))
     (with-open [reader (java.io.PushbackReader. (io/reader (str file)))]
       (edn/read {:eof fallback} reader))
     fallback)))
(defn write-edn! [file value]
  (atomic-write! file (str (pr-str value) "\n") {:private? true}))
(defn sha256 [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (if (bytes? value) value (.getBytes (str value) java.nio.charset.StandardCharsets/UTF_8)))))
(defn cancelled? [token]
  (or (.isInterrupted (Thread/currentThread))
      (cond (nil? token) false
            (fn? token) (boolean (token))
            (instance? clojure.lang.IDeref token) (boolean @token)
            :else (boolean token))))
(defn check-cancelled! [token]
  (when (cancelled? token) (value/fail! :cancelled "Operation cancelled" {})))
(defn bounded-writer
  "Capture Writer overloads without allowing printed output to grow past limit."
  [limit]
  (let [buffer (StringBuffer. (int (min 1024 limit)))
        truncated? (atom false)
        append! (fn [value offset length]
                  (locking buffer
                    (let [accepted (int (min (max 0 (- limit (.length buffer))) length))]
                      (when (pos? accepted)
                        (if (string? value)
                          (.append buffer ^String value (int offset) (int (+ offset accepted)))
                          (.append buffer ^chars value (int offset) accepted)))
                      (when (< accepted length) (reset! truncated? true)))))
        writer (proxy [java.io.Writer] []
                 (write
                   ([value]
                    (cond
                      (number? value) (append! (str (char (bit-and (int value) 65535))) 0 1)
                      (string? value) (append! value 0 (count value))
                      :else (append! value 0 (alength ^chars value))))
                   ([value offset length] (append! value offset length)))
                 (flush [])
                 (close [])
                 (toString [] (str buffer)))]
    {:writer writer :buffer buffer :truncated? truncated?}))
(defn bounded-string
  ([value] (bounded-string value 40000))
  ([value limit]
   (let [s (if (string? value) value (binding [*print-length* 200 *print-level* 20] (pr-str value)))]
     (if (> (count s) limit) (str (subs s 0 limit) "\n[Preview truncated]") s))))
(defn home-dir [options]
  (canonical-path (or (:home options) (not-empty (System/getenv "ARRODES_HOME"))
                      (str (System/getProperty "user.home") "/.arrodes"))))

(defn- exists-no-follow? [value]
  (Files/exists (path value) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- legacy-entry [kind source destination directory?]
  (when (exists-no-follow? source)
    {:kind kind
     :source source
     :destination destination
     :status (cond
               (Files/isSymbolicLink (path source)) :unsafe-source
               (if directory?
                 (not (Files/isDirectory (path source)
                                         (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                 (not (Files/isRegularFile (path source)
                                           (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))
               :unsafe-source
               (exists-no-follow? destination) :destination-exists
               :else :ready)}))

(defn legacy-home-status
  "Reports root-level v0 storage that can be explicitly migrated. Does not write."
  [home cwd]
  (let [home (canonical-path home)
        config (resolve-path home "config")
        project-data (resolve-path (project-dir home cwd) "data")
        entries (->> [[:settings (resolve-path home "settings.edn")
                       (resolve-path config "settings.edn") false]
                      [:keybindings (resolve-path home "keybindings.edn")
                       (resolve-path config "keybindings.edn") false]
                      [:trust (resolve-path home "trust.edn")
                       (resolve-path config "trust.edn") false]
                      [:data (resolve-path home "data") project-data true]]
                     (keep #(apply legacy-entry %))
                     (mapv #(if (= :data (:kind %))
                              (assoc % :status :explicit-data-dir-required)
                              %)))
        ledger (read-edn (resolve-path config "migration.edn") nil)]
    {:required? (boolean (seq entries))
     :entries entries
     :last-migration (peek (:runs ledger))
     :migration-history (vec (:runs ledger))}))

(def ^:private migration-locks (atom {}))

(defn- migration-lock [home]
  (get (swap! migration-locks
              #(if (contains? % home) % (assoc % home (Object.))))
       home))

(defn- with-migration-lock [home f]
  (let [config (resolve-path home "config")]
    (ensure-dir! config)
    (locking (migration-lock (real-path config))
      (let [owner (path (resolve-path config ".migration.lock"))]
        (when (Files/isSymbolicLink owner)
          (value/fail! :migration/insecure-lock "Migration lock cannot be a symbolic link"
                       {:path (str owner)}))
        (with-open [channel (FileChannel/open
                             owner
                             (into-array OpenOption [StandardOpenOption/CREATE
                                                     StandardOpenOption/WRITE
                                                     LinkOption/NOFOLLOW_LINKS]))]
          (let [lock (.lock channel)]
            (try
              (f)
              (finally (.release lock)))))))))

(defn- move-path! [source destination]
  (ensure-dir! (str (.getParent (path destination))))
  (try
    (Files/move (path source) (path destination)
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (path source) (path destination)
                  (make-array StandardCopyOption 0))))
  destination)

(defn migrate-legacy-home!
  "Moves legacy global config under HOME/config. Legacy databases must be opened explicitly."
  ([home cwd] (migrate-legacy-home! home cwd {}))
  ([home cwd {:keys [kinds] :or {kinds #{:settings :keybindings :trust}}}]
   (let [home (canonical-path home)]
     (with-migration-lock
       home
       (fn []
         (let [{all-entries :entries :as before} (legacy-home-status home cwd)
               entries (filterv #(contains? kinds (:kind %)) all-entries)
               blocked (filterv #(not= :ready (:status %)) entries)]
           (cond
             (empty? entries) (assoc before :status (if (seq all-entries) :deferred :not-needed)
                                     :migrated [])
             (seq blocked) {:status :blocked :required? true :entries all-entries
                            :conflicts blocked :migrated []}
             :else
             (let [moved (atom [])]
               (try
                 (doseq [{:keys [source destination] :as entry} entries]
                   (move-path! source destination)
                   (swap! moved conj entry))
                 (let [config (resolve-path home "config")
                       record-path (resolve-path config "migration.edn")
                       previous (read-edn record-path {:version 1 :runs []})
                       run {:migrated-at (now)
                            :project-root (project-root cwd)
                            :entries (mapv #(select-keys % [:kind :source :destination]) entries)}
                       record {:version 1 :runs (conj (vec (:runs previous)) run)}
                       after (legacy-home-status home cwd)]
                   (write-edn! record-path record)
                   (assoc after :status :migrated :migrated (:entries run)
                          :last-migration run :migration-history (:runs record)))
                 (catch Throwable error
                   (doseq [{:keys [source destination]} (reverse @moved)]
                     (when (and (exists-no-follow? destination)
                                (not (exists-no-follow? source)))
                       (try (move-path! destination source) (catch Throwable _ nil))))
                   (throw error)))))))))))
