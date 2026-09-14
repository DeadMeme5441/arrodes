(ns arrodes.value
  "Portable value validation, composition, and error projection."
  (:require [clojure.string :as str]))

(defn fail!
  [code message data]
  (throw (ex-info message (assoc (or data {}) :error/code (name code)))))

(defn check!
  [condition code message data]
  (when-not condition
    (fail! code message data)))

(defn deep-merge
  ([] {})
  ([a] a)
  ([a b]
   (merge-with (fn [left right]
                 (if (and (map? left) (map? right))
                   (deep-merge left right)
                   right))
               (or a {})
               (or b {})))
  ([a b & more]
   (reduce deep-merge (deep-merge a b) more)))

(defn type-name
  [value]
  #?(:clj (some-> value class .getName)
     :cljs (some-> value type str)))

(defn text-content
  [content]
  (cond
    (nil? content) ""
    (string? content) content
    (sequential? content)
    (str/join "" (keep #(when (contains? #{:text "text"}
                                          (or (:part/type %) (:type %)))
                           (:text %))
                       content))
    :else (str content)))

(defn error-map
  [error]
  {:code (or (:error/code (ex-data error)) "internal-error")
   :message (or (ex-message error) (str error))
   :data (dissoc (ex-data error) :error/code)})

(def ^:private secret-key-pattern
  #?(:clj #"(?i)(api[-_]?key|password|secret|authorization|access[-_]?token|refresh[-_]?token)"
     :cljs (js/RegExp. "(api[-_]?key|password|secret|authorization|access[-_]?token|refresh[-_]?token)" "i")))

(defn redact
  [value]
  (cond
    (map? value)
    (into {} (map (fn [[key item]]
                    [key (if (re-find secret-key-pattern (str key))
                           (when item "[redacted]")
                           (redact item))])
                  value))
    (vector? value) (mapv redact value)
    (set? value) (set (map redact value))
    (sequential? value) (mapv redact value)
    :else value))
