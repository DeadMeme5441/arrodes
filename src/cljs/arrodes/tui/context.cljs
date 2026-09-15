(ns arrodes.tui.context
  "Shared view state, projection and clipboard helpers; navigation is injected by the shell."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-model :as model]
            [arrodes.tui-turns :as turns]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]))

(declare state ui! notify! error-text invoke! fire! ready? busy? basename secret-mask row-list selected-row expanded? toggle! copy! copy-selection! styled-diff! display-content)

(defn action! [action view & args]
  (if-let [handler (get (:actions view) action)]
    (apply handler view args)
    (throw (js/Error. (str "Missing UI action: " action)))))

(defn state [view] @(:state (:app view)))

(defn ui! [view f & args] (apply swap! (:state (:app view)) update :ui f args))

(defn notify!
  ([view text] (notify! view text :info))
  ([view text kind]
   (swap! (:state (:app view)) assoc :notice {:kind kind :message (model/safe-text text)})))

(defn error-text [error]
  (or (.-message error) (:message (ex-data error)) (str error)))

(defn invoke! [view action data]
  (app/command! (:app view) action data))

(defn fire! [view action data]
  (.catch (invoke! view action data) (fn [_] nil)))

(defn ready? [view] (= :ready (get-in (state view) [:connection :status])))

(defn busy? [view]
  (contains? #{:running :cancelling :starting} (get-in (state view) [:view :operation :status])))

(defn basename [path]
  (or (last (remove str/blank? (str/split (str (or path "")) #"[/\\]"))) "project"))

(defn secret-mask [text]
  (->> (array-seq (.split (str (or text "")) "\n"))
       (map #(apply str (repeat (alength (js/Array.from %)) "•")))
       (str/join "\n")))

(defn row-list [view]
  (let [m (:view (state view))
        source (select-keys m [:entries :activities :activity-order :presentations
                               :streams :operation :snapshot-cursor])]
    (if (= source (:model-source @(:local view)))
      (:rows @(:local view))
      (let [rows (turns/annotate (model/rows m))]
        (swap! (:local view) assoc :model-source source :rows rows)
        rows))))

(defn selected-row [view]
  (let [s (state view)
        id (get-in s [:ui :selected])
        row (or (some #(when (= id (:id %)) %) (row-list view))
                (get-in s [:ui :inspected-row]))]
    (if-let [a (and (:activity row) (get-in s [:view :activities (get-in row [:activity :id])]))]
      (assoc row :activity a)
      row)))

(defn expanded? [view row]
  (boolean (get-in (state view) [:ui :expanded (:id row)])))

(defn toggle! [view row]
  (ui! view assoc-in [:expanded (:id row)] (not (expanded? view row))))


(defn copy! [view text]
  (let [text (model/safe-text text)
        child-process (js/require "node:child_process")
        candidates (case (.-platform js/process)
                     "darwin" [["pbcopy"]]
                     "win32" [["clip.exe"]]
                     [["wl-copy"] ["xclip" "-selection" "clipboard"]])
        copied? (some (fn [[command & args]]
                        (let [result (.spawnSync child-process command (clj->js args)
                                                 #js {:input text :timeout 1500 :stdio #js ["pipe" "ignore" "ignore"]})]
                          (= 0 (.-status result))))
                      candidates)]
    (if (or copied? (.copyToClipboardOSC52 (:renderer view) text))
      (notify! view "Copied to clipboard")
      (notify! view "Clipboard unavailable in this terminal; use text selection or Export HTML."))))


(defn copy-selection! [view]
  (if-let [selection (.getSelection (:renderer view))]
    (let [text (.getSelectedText selection)]
      (when (seq text) (copy! view text) true))
    (when (.hasSelection (:composer view))
      (copy! view (.getSelectedText (:composer view))) true)))


(defn styled-diff! [node text]
  (when (not= text (aget node "arrodesContent"))
    (aset node "arrodesContent" text)
    (aset node "arrodesRepaint" (fn [] (aset node "arrodesContent" nil) (styled-diff! node text)))
    (let [chunks (mapv (fn [line]
                         ((.fg w/core (cond (str/starts-with? line "+ ") (w/color (w/renderer node) :status/success)
                                            (str/starts-with? line "- ") (w/color (w/renderer node) :status/error)
                                            (str/starts-with? line "@@") (w/color (w/renderer node) :ui/accent)
                                            :else (w/color (w/renderer node) :text/secondary)))
                          (str (model/safe-text line) "\n")))
                       (str/split-lines text))]
      (set! (.-content node) (js/Reflect.construct (.-StyledText w/core) #js [(clj->js chunks)])))))


(defn display-content [content]
  (model/safe-text
   (if (sequential? content)
     (str/join "\n" (map #(if (string? %) % (model/text-content %)) content))
     (model/text-content content))))

