(ns arrodes.theme
  "Pure theme-pack schema and resolution. Themes cannot carry layout or executable code."
  (:require [clojure.string :as str]
            #?(:clj [arrodes.theme-macros :refer [builtin-packs]]))
  #?(:cljs (:require-macros [arrodes.theme-macros :refer [builtin-packs]])))

(def builtins (builtin-packs))
(def default-pack (first builtins))
(def color-roles (set (keys (:colors default-pack))))
(def style-roles (set (keys (:styles default-pack))))
(def fields #{:schema-version :id :name :version :author :description :homepage :colors :styles})

(defn- check! [condition message data]
  (when-not condition (throw (ex-info message (assoc data :error/type :theme/invalid)))))

(defn validate [pack]
  (check! (map? pack) "Theme must contain one EDN map" {})
  (check! (every? fields (keys pack)) "Unknown theme field; themes cannot define layout or behavior" {})
  (check! (= 1 (:schema-version pack)) "Theme :schema-version must be 1" {})
  (check! (and (string? (:id pack)) (re-matches #"[a-z0-9][a-z0-9-]{0,63}" (:id pack))) "Invalid theme :id" {})
  (doseq [key [:name :version]]
    (check! (and (string? (get pack key)) (not (str/blank? (get pack key)))) (str "Theme requires " key) {}))
  (doseq [key [:name :version :author :description :homepage] :when (contains? pack key)]
    (let [value (get pack key)]
      (check! (and (string? value) (<= (count value) 512) (not (re-find #"[\u0000-\u001f\u007f]" value)))
              (str "Invalid theme metadata " key) {})))
  (doseq [key [:colors :styles]]
    (check! (or (nil? (get pack key)) (map? (get pack key))) (str key " must be a map") {}))
  (doseq [[role value] (:colors pack)]
    (check! (contains? color-roles role) (str "Unknown color role " role) {})
    (check! (and (string? value) (re-matches #"#[0-9a-fA-F]{6}" value)) (str "Color " role " must be #RRGGBB") {}))
  (doseq [[role style] (:styles pack)]
    (check! (contains? style-roles role) (str "Unknown style role " role) {})
    (check! (and (map? style) (every? #{:bold :italic :underline} (keys style))
                 (every? boolean? (vals style))) (str "Style " role " supports boolean bold/italic/underline only") {}))
  pack)

(defn resolve-pack [pack]
  (validate pack)
  (assoc pack :colors (merge (:colors default-pack) (:colors pack))
         :styles (merge-with merge (:styles default-pack) (:styles pack))))

(defn builtin [id]
  (some #(when (= id (:id %)) (resolve-pack %)) builtins))
