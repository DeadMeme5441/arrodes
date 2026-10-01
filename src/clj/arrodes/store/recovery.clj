(ns arrodes.store.recovery
  "Restart settlement without replaying external effects."
  (:require [arrodes.session :as session-model]
            [arrodes.platform :as util]
            [arrodes.store.codec :as codec]
            [arrodes.store.db :as db]
            [arrodes.store.jobs :as jobs]
            [arrodes.store.records :as records]
            [arrodes.store.routing :as routing]
            [arrodes.store.sql :as sql]))

(defn recover!
  "Marks unfinished operations interrupted and closes pending tool calls without replaying effects."
  [store]
  (db/transact! store
    (fn [connection]
      (let [active (sql/query-sql connection
                              "SELECT * FROM operations WHERE status IN ('queued','running','cancelling') ORDER BY created_at"
                              [] records/operation-row)
            by-session (group-by :session-id active)
            now (util/now)
            events (volatile! (transient []))]
        (doseq [record (sql/query-sql connection "SELECT * FROM jobs WHERE status IN ('queued','running','cancelling')" [] jobs/job-row)]
          (let [record (assoc record :status :interrupted :finished-at now :revision (inc (or (:revision record) 0))
                                    :error {:code "interrupted" :message "Runtime stopped before execution settled. Effects were not replayed."})]
            (sql/execute-sql! connection "UPDATE jobs SET status='interrupted',record=? WHERE id=?" [(codec/encode record) (:id record)])
            (vswap! events conj! (records/insert-event! connection (:session-id record)
                                  {:type :job/changed :data {:job record}}))))
        (doseq [[sid ops] by-session]
          (let [snapshot (records/require-session connection sid)
                all (records/all-entries connection sid)
                path (session-model/active-path all (:head snapshot))
                pending-calls (records/pending-tool-calls path)
                start-seq (long (or (sql/scalar connection "SELECT MAX(seq) FROM entries WHERE session_id=?" [sid]) 0))
                result-seq (volatile! (long (or (sql/scalar connection "SELECT MAX(id) FROM results WHERE session_id=?" [sid]) 0)))
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
                (sql/execute-sql! connection
                              "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                              [sid result-id "inline" (codec/encode result) now])
                (sql/execute-sql! connection
                              "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                              [(:id entry) sid (:parent-id entry) (:seq entry) "message" (codec/encode (:data entry)) now])
                (vreset! final-head (:id entry))))
            (doseq [op ops]
              (let [interrupted (assoc op :status :interrupted :finished-at now
                                      :error {:code "interrupted"
                                              :message "Process restarted before the operation completed"})]
                (sql/execute-sql! connection "UPDATE operations SET status='interrupted',finished_at=?,error=? WHERE id=?"
                              [now (codec/encode (:error interrupted)) (:id op)])
                (vswap! events conj! (records/insert-event! connection sid
                                                    {:operation-id (:id op)
                                                     :type :operation/interrupted
                                                     :data {:reason :restart}
                                                     :time now}))
                (doseq [event (routing/completion! connection interrupted)]
                  (vswap! events conj! event))))
            (records/update-session! connection (assoc snapshot :head @final-head :status :interrupted
                                               :revision (inc (:revision snapshot)) :updated-at now))))
        (sql/execute-sql! connection "UPDATE agent_routes SET paused=1" [])
        (doseq [op (sql/query-sql connection
                              "SELECT o.* FROM operations o JOIN agent_routes r ON r.session_id=o.session_id WHERE r.parent_session_id IS NOT NULL AND o.status IN ('completed','failed','cancelled','interrupted')"
                              [] records/operation-row)]
          (doseq [event (routing/completion! connection op)]
            (vswap! events conj! event)))
        (persistent! @events)))))
