(ns arrodes.tui.themes
  "Local, data-only pack discovery and atomic preference persistence. No RPC or rendering."
  (:require [arrodes.theme :as theme]
            [cljs.tools.reader.edn :as edn]
            [cljs.tools.reader.reader-types :as reader]
            [clojure.string :as str]))
(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def max-pack-bytes 65536)

(defn home [options]
  (.resolve path (or (:theme-home options) (:home options) (aget (.-env js/process) "ARRODES_HOME")
                    (.join path (.homedir (js/require "node:os")) ".arrodes"))))

(defn read-data [file]
  (let [stat (.statSync fs file)]
    (when-not (.isFile stat) (throw (js/Error. "Theme must be a regular file")))
    (when (> (.-size stat) max-pack-bytes) (throw (js/Error. "Theme file exceeds 64 KiB"))))
  (let [input (reader/string-push-back-reader (.readFileSync fs file "utf8"))
        opts {:eof ::eof :readers {} :default (fn [tag _] (throw (js/Error. (str "Tagged data is not allowed: " tag))))}
        data (edn/read opts input)]
    (when-not (= ::eof (edn/read opts input)) (throw (js/Error. "Expected exactly one EDN value")))
    data))

(defn discover [directory]
  (let [result (atom {:packs (mapv theme/resolve-pack theme/builtins) :errors []})
        root (.join path directory "themes")]
    (when (.existsSync fs root)
      (try
        (doseq [entry (sort (js->clj (.readdirSync fs root)))]
          (let [candidate (.join path root entry)]
            (try
              (let [file (if (.isDirectory (.statSync fs candidate)) (.join path candidate "theme.edn") candidate)]
                (when (and (.existsSync fs file) (str/ends-with? file ".edn"))
                  (let [pack (theme/resolve-pack (read-data file))]
                    (when (some #(= (:id pack) (:id %)) (:packs @result))
                      (throw (js/Error. (str "Duplicate theme id: " (:id pack)))))
                    (swap! result update :packs conj (assoc pack :path file)))))
              (catch :default error
                (swap! result update :errors conj {:file candidate :message (.-message error)})))))
        (catch :default error (swap! result update :errors conj {:file root :message (.-message error)}))))
    @result))

(defn create [options]
  (let [directory (home options) catalog (discover directory)
        preference (.join path directory "config" "tui.edn")
        saved (try (when (.existsSync fs preference) (read-data preference))
                   (catch :default _ nil))
        requested (or (:theme options) (:theme saved) "default")
        chosen (or (some #(when (= requested (:id %)) %) (:packs catalog)) (theme/builtin "default"))]
    {:home directory :preference preference :catalog (atom catalog)
     :selected (atom (:id chosen)) :committed (atom chosen) :current (atom chosen)}))

(defn reload! [manager]
  (reset! (:catalog manager) (discover (:home manager))))

(defn pack [manager id]
  (or (some #(when (= id (:id %)) %) (:packs @(:catalog manager)))
      (throw (js/Error. (str "Theme is unavailable: " id)))))

(defn select! [manager id]
  (let [selected (pack manager id)
        file (:preference manager)
        previous (if (.existsSync fs file) (read-data file) {})
        _ (when-not (map? previous) (throw (js/Error. "UI settings must be an EDN map")))
        temporary (str file "." (random-uuid) ".tmp")]
    (.mkdirSync fs (.dirname path file) #js {:recursive true})
    (try
      (.writeFileSync fs temporary (pr-str (assoc previous :theme id)) #js {:mode 384 :flag "wx"})
      (.renameSync fs temporary file)
      (finally (when (.existsSync fs temporary) (.unlinkSync fs temporary))))
    (reset! (:selected manager) id)
    (reset! (:committed manager) selected)
    selected))
