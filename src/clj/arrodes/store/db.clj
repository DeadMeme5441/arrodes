(ns arrodes.store.db
  "Store connection lifecycle and serialized SQLite transactions."
  (:require [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.files :as files]
            [arrodes.store.schema :as schema]
            [arrodes.store.sql :as sql])
  (:import (java.sql Connection DriverManager)
           (java.nio.channels FileChannel FileLock)
           (java.util.concurrent.locks ReentrantLock)))

(defrecord Store [^Connection connection ^ReentrantLock lock closed? path artifact-dir memory? opened-at
                  ^FileChannel owner-channel ^FileLock owner-lock owner-path
                  ^FileChannel artifact-channel ^FileLock artifact-lock])
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
            (files/tighten-store-files! store)
            (.commit connection)
            value)
          (catch Throwable error
            (try (.rollback connection) (catch Throwable _))
            (throw error))
          (finally (.setAutoCommit connection old-auto))))
      (finally (.unlock lock)))))
(defn- open-current-connection! [memory? db-path artifact-dir artifact-owner*]
  (let [url (if memory? "jdbc:sqlite::memory:" (str "jdbc:sqlite:" db-path))
        version (when db-path
                  (with-open [reader (DriverManager/getConnection
                                     (str "jdbc:sqlite:" (.toUri (util/path db-path)) "?mode=ro"))]
                    (let [version (schema/check-schema! reader)]
                      (when (#{3 4 5} version)
                        (value/check! (= "ok" (sql/scalar reader "PRAGMA integrity_check" []))
                                      :unsupported-store-format
                                      "SQLite integrity check failed; restore the database from a backup"
                                      {:path db-path}))
                      version)))]
    (when artifact-dir
      (reset! artifact-owner*
              (files/acquire-artifact-owner! artifact-dir db-path)))
    (let [connection (DriverManager/getConnection url)]
      (try
        (let [checked (schema/check-schema! connection)
              version (or version checked)]
          (value/check! (= checked version) :unsupported-store-format
                        "Store changed during validation; retry after closing other database writers"
                        {:path db-path})
          (when (and db-path (#{3 4 5} version))
            (sql/execute-command! connection "PRAGMA synchronous = FULL")
            (schema/backup-store! connection db-path version)
            (schema/upgrade-schema! connection))
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
                  (files/safe-storage-path! path)
                  (files/canonical-database-path path))
        owner (when db-path (files/acquire-owner! db-path))
        artifact-owner* (atom nil)]
    (try
      (let [artifact-dir (when-not memory?
                           (when artifact-dir (files/safe-storage-path! artifact-dir))
                           (util/canonical-path
                            (or artifact-dir (str db-path ".artifacts"))))
            _ (when db-path (files/check-legacy-reset-marker! db-path))
            _ (when db-path (files/prepare-database-file! db-path))
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
          (schema/check-schema! connection)
          (sql/execute-command! connection "PRAGMA foreign_keys = ON")
          (sql/execute-command! connection "PRAGMA busy_timeout = 5000")
          (if memory?
            (sql/execute-command! connection "PRAGMA journal_mode = MEMORY")
            (do (sql/execute-command! connection "PRAGMA journal_mode = WAL")
                (sql/execute-command! connection "PRAGMA synchronous = FULL")))
          (files/tighten-store-files! store)
          (schema/initialize-schema! connection)
          (transact! store schema/expire-live-results!)
          (files/tighten-store-files! store)
          (when db-path (util/private-file! db-path))
          (when artifact-dir (files/private-dir! artifact-dir))
          store
          (catch Throwable error
            (try (.close connection) (catch Throwable _))
            (throw error))))
      (catch Throwable error
        (try (files/release-artifact-owner! @artifact-owner*)
             (finally (files/release-owner! owner)))
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
            (files/tighten-store-files! store)
            (finally
              (try
                (.close ^Connection (:connection store))
                (finally
                  (try (files/release-artifact-owner! store)
                       (finally (files/release-owner! store))))))))
        nil
        (finally (.unlock lock))))))
