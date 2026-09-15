(ns arrodes.catalog
  "Presentation rules shared by the provider and model browsers."
  (:require [clojure.string :as str]))

(defn provider-id [entry] (some-> (:provider entry) keyword))

(defn provider-name [entry]
  (case (provider-id entry)
    :codex-backend "ChatGPT / Codex"
    :openai "OpenAI"
    :anthropic "Anthropic"
    (or (:name entry) (some-> (provider-id entry) name) "Provider")))

(defn providers [entries]
  (vec (sort-by (juxt #(if (:available? %) 0 1) #(str/lower-case (provider-name %))) entries)))

(defn auth-label [entry]
  (case (some-> entry :auth :type keyword)
    :oauth "Browser sign-in" :api-key "API key" :ambient "Environment credentials"
    :none "No sign-in required" "Credentials"))

(defn status-label [entry]
  (if (:available? entry) "Connected" "Not connected"))

(defn models [entries provider query]
  (let [query (str/lower-case (str/trim (or query "")))]
    (->> entries
         (filter #(or (nil? provider) (= (keyword provider) (provider-id %))))
         (filter #(str/includes? (str/lower-case (str (:id %) " " (:name %))) query))
         (sort-by :id) vec)))

(defn thinking [model current]
  (let [levels (mapv keyword (or (seq (:thinking-levels model)) [:none]))]
    (or (some #{(keyword (or current :none))} levels)
        (some #{:medium} levels) (first levels))))

(defn model-details [model]
  (when model
    (str (:id model)
         (when-let [window (:context-window model)] (str " · " window " context"))
         (when (seq (:input model)) (str " · " (str/join ", " (map name (:input model)))))
         "\nReasoning: " (str/join " / " (map name (or (seq (:thinking-levels model)) [:none]))))))
