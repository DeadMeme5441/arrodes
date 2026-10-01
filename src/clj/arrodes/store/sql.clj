(ns arrodes.store.sql
  "Prepared SQL execution and row queries."
  (:import (java.sql Connection PreparedStatement ResultSet)))

(def ^:private bytes-class (Class/forName "[B"))
(defn- set-params! [^PreparedStatement statement params]
  (doseq [[index value] (map-indexed vector params)]
    (cond
      (nil? value) (.setObject statement (inc index) nil)
      (instance? bytes-class value) (.setBytes statement (inc index) value)
      :else (.setObject statement (inc index) value)))
  statement)

(defn execute-sql! [^Connection connection sql params]
  (with-open [statement (set-params! (.prepareStatement connection sql) params)]
    (.executeUpdate statement)))

(defn execute-command! [^Connection connection sql]
  (with-open [statement (.createStatement connection)]
    (.execute statement sql)))

(defn query-sql [^Connection connection sql params row-fn]
  (with-open [statement (set-params! (.prepareStatement connection sql) params)
              result (.executeQuery statement)]
    (loop [rows []]
      (if (.next result)
        (recur (conj rows (row-fn result)))
        rows))))

(defn scalar [^Connection connection sql params]
  (first (query-sql connection sql params #(.getObject ^ResultSet % 1))))
