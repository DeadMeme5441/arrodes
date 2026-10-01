(ns arrodes.store.jobs
  "Durable function-job records, transitions, and acknowledgement."
  (:require [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.db :as db]
            [arrodes.store.records :as records]
            [arrodes.store.sql :as sql])
  (:import (java.sql ResultSet)))

(def job-terminal-statuses #{:completed :failed :cancelled :interrupted})

(defn job-row [^ResultSet rs]
  (assoc (codec/decode (.getString rs "record")) :delivered? (pos? (.getInt rs "delivered"))))

(defn job [store sid id]
  (db/store-read store
    (fn [connection]
      (records/require-session connection sid)
      (codec/require-uuid! id :job-id)
      (or (first (sql/query-sql connection "SELECT * FROM jobs WHERE session_id=? AND id=?" [sid id] job-row))
          (value/fail! :job-not-found "Job does not exist in this session" {:job-id id :session-id sid})))))

(defn jobs
  ([store sid] (jobs store sid {}))
  ([store sid {:keys [limit before] :or {limit 100}}]
   (value/check! (and (integer? limit) (<= 1 limit 500)) :invalid-limit "Job limit must be 1..500" {})
   (db/store-read store
     (fn [connection]
       (records/require-session connection sid)
       (if before
         (let [cursor (job store sid before)]
           (sql/query-sql connection
             "SELECT * FROM jobs WHERE session_id=? AND (created_at<? OR (created_at=? AND id>?)) ORDER BY created_at DESC,id LIMIT ?"
             [sid (:created-at cursor) (:created-at cursor) before limit] job-row))
         (sql/query-sql connection "SELECT * FROM jobs WHERE session_id=? ORDER BY created_at DESC,id LIMIT ?"
                    [sid limit] job-row))))))

(defn active-jobs [store sid]
  (db/store-read store
    (fn [connection]
      (records/require-session connection sid)
      (sql/query-sql connection "SELECT * FROM jobs WHERE session_id=? AND status IN ('queued','running','cancelling') ORDER BY created_at DESC,id"
                 [sid] job-row))))

(defn create-job! [store record]
  (db/transact! store
    (fn [connection]
      (records/require-session connection (:session-id record))
      (codec/require-uuid! (:id record) :job-id)
      (value/check! (= :queued (:status record)) :invalid-job "New jobs must be queued" {})
      (sql/execute-sql! connection "INSERT INTO jobs(id,session_id,status,created_at,record) VALUES(?,?,?,?,?)"
                    [(:id record) (:session-id record) "queued" (:created-at record) (codec/encode record)])
      {:job record :events [(records/insert-event! connection (:session-id record)
                             {:type :job/changed :data {:job record}})]})))

(defn transition-job! [store sid id expected changes]
  (db/transact! store
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
            (sql/execute-sql! connection "UPDATE jobs SET status=?,record=? WHERE session_id=? AND id=?"
                          [(name next-status) (codec/encode next) sid id])
            {:job next :events [(records/insert-event! connection sid {:type :job/changed :data {:job next}})]}))))))

(defn pending-job-results [store sid]
  (db/store-read store
    #(sql/query-sql % "SELECT * FROM jobs WHERE session_id=? AND delivered=0 AND status IN ('completed','failed','cancelled','interrupted') ORDER BY created_at,id LIMIT 20"
                [sid] job-row)))

(defn acknowledge-jobs! [store sid ids]
  (db/transact! store
    (fn [connection]
      (doseq [id ids]
        (job store sid id)
        (sql/execute-sql! connection "UPDATE jobs SET delivered=1 WHERE session_id=? AND id=?" [sid id])))))

(defn acknowledge-all-jobs! [store sid]
  (db/transact! store
    (fn [connection]
      (records/require-session connection sid)
      (sql/execute-sql! connection "UPDATE jobs SET delivered=1 WHERE session_id=?" [sid]))))
