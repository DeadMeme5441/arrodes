(ns arrodes.store.files
  "Exclusive filesystem ownership and safe private artifact storage."
  (:require [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.sql :as sql])
  (:import (java.sql DriverManager ResultSet)
           (java.nio.channels FileChannel FileLock OverlappingFileLockException)
           (java.nio.file FileAlreadyExistsException Files StandardCopyOption StandardOpenOption OpenOption LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(defn private-dir! [path]
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

(defn artifact-storage-path [store sha]
  (when-let [root (:artifact-dir store)]
    (.resolve (.resolve (util/path root) (subs sha 0 2)) sha)))

(defn write-content-addressed! [store sha bytes]
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

(defn- ensure-parent! [path]
  (when-let [parent (.getParent (util/path path))]
    (Files/createDirectories parent (make-array FileAttribute 0))))

(defn canonical-database-path [path]
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

(defn acquire-owner! [db-path]
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

(defn release-owner! [{:keys [owner-channel owner-lock]}]
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

(defn prepare-database-file! [path]
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

(defn tighten-store-files! [store]
  (when-let [path (:path store)]
    (doseq [candidate (map util/path [path (str path "-wal") (str path "-shm")])]
      (value/check! (not (Files/isSymbolicLink candidate)) :insecure-database
                   "SQLite storage file cannot be a symbolic link" {:path (str candidate)})
      (single-link! candidate)
      (when (Files/isRegularFile candidate
                                 (into-array java.nio.file.LinkOption
                                             [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
        (util/private-file! candidate)))))


(defn safe-storage-path! [path]
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


(defn release-artifact-owner! [{:keys [artifact-channel artifact-lock]}]
  (when artifact-lock
    (try
      (when (.isValid ^FileLock artifact-lock)
        (.release ^FileLock artifact-lock))
      (finally
        (.close ^FileChannel artifact-channel)))))

(defn acquire-artifact-owner! [artifact-dir db-path]
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
                (value/check! (= {:database db-path} (codec/decode (Files/readString marker)))
                              :artifact-owner-conflict
                              "Artifact storage belongs to another database"
                              {:path artifact-dir}))
              (let [owned-hashes
                    (when (Files/exists (util/path db-path)
                                        (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                      (with-open [connection (DriverManager/getConnection
                                              (str "jdbc:sqlite:" (.toUri (util/path db-path)) "?mode=ro"))]
                        (when (sql/scalar connection
                                      "SELECT 1 FROM sqlite_master WHERE type='table' AND name='artifacts'"
                                      [])
                          (set (sql/query-sql connection "SELECT sha256 FROM artifacts"
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
                    (Files/write temp (.getBytes (codec/encode {:database db-path}) "UTF-8")
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


(defn check-legacy-reset-marker! [db-path]
  (let [marker (str db-path ".reset")]
    (safe-storage-path! marker)
    (when (Files/exists (util/path marker) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (single-link! marker)
      (value/fail! :incomplete-legacy-reset
                   "Interrupted legacy reset; no further cleanup was attempted. Inspect the database and artifacts, restore from a backup if needed, and move the .reset marker aside only after recovery."
                   {:path marker}))))
