(ns arrodes.coordination
  "Small decisions shared by foreground admission and settlement. Effects remain in runtime."
  (:require [arrodes.value :as value]))

(defn terminal-status [requested cancelled?]
  (if cancelled? :cancelled requested))

(defn reserve [admission oid limit]
  (value/check! (not (contains? admission oid)) :operation-already-admitted
                "Operation is already admitted" {:operation-id oid})
  (value/check! (< (count admission) limit) :operation-limit
                "Foreground operation capacity is exhausted" {:limit limit})
  (assoc admission oid :reserved))

(defn occupy [admission oid]
  (value/check! (= :reserved (get admission oid)) :operation-not-reserved
                "Operation does not own a reserved worker slot" {:operation-id oid})
  (assoc admission oid :working))
