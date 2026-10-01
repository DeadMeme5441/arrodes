(ns arrodes.store.command
  "Internal transaction envelopes; nested durable records retain their format."
  (:require [clojure.spec.alpha :as s]
            [arrodes.value :as value]))

(s/def ::expected-revision (s/nilable integer?))
(s/def ::entries (s/nilable (s/coll-of map? :kind sequential?)))
(s/def ::session (s/nilable map?))
(s/def ::queue-deliver (s/nilable (s/coll-of string? :kind sequential?)))
(s/def ::queue-enqueue (s/nilable (s/coll-of map? :kind sequential?)))
(s/def ::job-deliver (s/nilable (s/coll-of string? :kind sequential?)))
(s/def ::operation (s/nilable map?))
(s/def ::agent-branch? (s/nilable boolean?))
(s/def ::events (s/nilable (s/coll-of map? :kind sequential?)))

(def ^:private command-keys
  #{::expected-revision ::entries ::session ::queue-deliver ::queue-enqueue
    ::job-deliver ::operation ::agent-branch? ::events})

(s/def ::command
  (s/and (s/keys :opt [::expected-revision ::entries ::session ::queue-deliver
                       ::queue-enqueue ::job-deliver ::operation ::agent-branch? ::events])
         #(every? command-keys (keys %))))

(defn validate!
  "Rejects malformed or misspelled envelopes before entering a transaction."
  [command]
  (when-not (s/valid? ::command command)
    (value/fail! :invalid-store-command "Invalid store transaction command"
                 {:spec ::command :problems (::s/problems (s/explain-data ::command command))}))
  command)
