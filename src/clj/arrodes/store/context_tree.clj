(ns arrodes.store.context-tree
  "Immutable session-owned derived context nodes; opening a store never builds them."
  (:require [clojure.string :as str]
            [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.db :as db]
            [arrodes.store.records :as records]
            [arrodes.store.schema :as schema]
            [arrodes.store.sql :as sql])
  (:import (java.sql ResultSet)))

(defn- require-node-id! [id]
  (value/check! (and (string? id) (not (str/blank? id))) :invalid-context-node
                "Context node ID must be a nonblank string" {:node-id id}))

(defn nodes
  "Returns the completed derived cache for this session, across its retained branches."
  [store sid]
  (db/store-read store
    (fn [connection]
      (records/require-session connection sid)
      (into {} (map (juxt :id identity))
            (sql/query-sql connection
                           "SELECT * FROM context_nodes WHERE session_id=? ORDER BY count,start,id"
                           [sid] schema/context-node-row)))))

(defn node [store sid id]
  (db/store-read store
    (fn [connection]
      (records/require-session connection sid)
      (require-node-id! id)
      (schema/find-context-node connection sid id))))

(defn put-node!
  "Atomically retains a completed node. Identical puts are free; conflicting puts fail."
  [store sid node]
  (db/transact! store
    (fn [connection]
      (records/require-session connection sid)
      (value/check! (map? node) :invalid-context-node "Context node must be a map" {})
      (require-node-id! (:id node))
      (if-let [prior (schema/find-context-node connection sid (:id node))]
        (do
          (value/check! (= prior node) :context-node-conflict
                        "Completed context nodes are immutable"
                        {:session-id sid :node-id (:id node)})
          prior)
        (do
          (schema/validate-context-node! connection sid node)
          (sql/execute-sql!
           connection
           (str "INSERT INTO context_nodes(session_id,id,start,count,first_entry_id,last_entry_id,"
                "left_id,right_id,text,bytes,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
           [sid (:id node) (:start node) (:count node) (:first-entry-id node) (:last-entry-id node)
            (:left-id node) (:right-id node) (:text node) (:bytes node)
            (codec/encode (select-keys node schema/context-node-metadata-fields))])
          node)))))

(defn clear-nodes!
  "Explicitly clears only this session's derived cache, without changing canonical history."
  [store sid]
  (db/transact! store
    (fn [connection]
      (records/require-session connection sid)
      {:cleared (sql/execute-sql! connection "DELETE FROM context_nodes WHERE session_id=?" [sid])})))

(defn- add-reported [totals reported]
  (reduce-kv (fn [totals key amount]
               (if (number? amount) (update totals key (fnil + 0) amount) totals))
             totals (or reported {})))

(defn status
  "Compact persisted cache totals. Usage/cost sum only reported numeric fields, not estimates."
  [store sid]
  (db/store-read store
    (fn [connection]
      (records/require-session connection sid)
      (reduce
       (fn [status {:keys [leaf? bytes usage cost]}]
         (-> status
             (update :node-count inc)
             (update :leaf-count + (if leaf? 1 0))
             (update :summary-bytes + bytes)
             (update :usage add-reported usage)
             (update :cost add-reported cost)
             (update :usage-node-count + (if (some number? (vals usage)) 1 0))
             (update :cost-node-count + (if (some number? (vals cost)) 1 0))))
       {:node-count 0 :leaf-count 0 :summary-bytes 0
        :usage {} :cost {} :usage-node-count 0 :cost-node-count 0}
       (sql/query-sql connection
                      "SELECT count,bytes,metadata FROM context_nodes WHERE session_id=?"
                      [sid] (fn [^ResultSet rs]
                              (assoc (schema/context-node-metadata rs)
                                     :leaf? (= 1 (.getLong rs "count"))
                                     :bytes (.getLong rs "bytes"))))))))
