(ns arrodes.tui-turns
  "Presentation-only turn ownership. Assistant reasoning, tools, and prose share a boundary.")

(defn owner [row]
  (if (= :message (:kind row))
    (case (:role row) :user :user (:assistant :tool) :assistant :system)
    :assistant))

(defn annotate [rows]
  (loop [remaining rows previous nil turn-id nil result []]
    (if-let [row (first remaining)]
      (let [role (owner row)
            start? (or (= :user role) (not= role previous))
            turn-id (if start? (:id row) turn-id)]
        (recur (next remaining) role turn-id
               (conj result (assoc row :turn-role role :turn-start? start? :turn-id turn-id))))
      result)))
