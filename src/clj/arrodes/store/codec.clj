(ns arrodes.store.codec
  "Bounded durable EDN and identifier validation."
  (:refer-clojure :exclude [uuid?])
  (:require [clojure.edn :as edn]
            [arrodes.value :as value])
  (:import (java.util UUID)))

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


(defn encode [value]
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

(defn decode [value]
  (when-not (nil? value)
    (binding [*read-eval* false]
      (edn/read-string value))))
(defn uuid? [value]
  (and (string? value)
       (try (UUID/fromString value) true (catch IllegalArgumentException _ false))))

(defn require-uuid! [value field]
  (value/check! (uuid? value) :invalid-id "Expected a UUID string" {:field field :value value})
  value)
