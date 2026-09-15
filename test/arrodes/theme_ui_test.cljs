(ns arrodes.theme-ui-test
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-view :as view]
            [arrodes.tui.themes :as themes]
            [arrodes.tui.palette :as palette]
            [arrodes.tui.theme-picker :as picker]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]))
(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(defn check! [condition message] (when-not condition (throw (js/Error. message))))
(defn node [terminal id] (.findDescendantById (.-root (.-renderer terminal)) id))
(defn until! [terminal predicate message]
  (let [deadline (+ (.now js/Date) 3000)]
    (letfn [(poll []
              (-> (.renderOnce terminal)
                  (.then (fn [_]
                           (cond (predicate) nil
                                 (> (.now js/Date) deadline) (throw (js/Error. (str (if (fn? message) (message) message) "\n" (.captureCharFrame terminal))))
                                 :else (-> (js/Promise. (fn [resolve] (js/setTimeout resolve 15))) (.then poll)))))))]
      (poll))))
(defn rgb [value] (vec (take 3 (js->clj (.toInts value)))))

(defn span-color [terminal text]
  (some (fn [line] (some (fn [span] (when (str/includes? (.-text span) text) (rgb (.-fg span))))
                         (array-seq (.-spans line))))
        (array-seq (.-lines (.captureSpans terminal)))))

(defn capture! [terminal name]
  (when-let [directory (aget (.-env js/process) "ARRODES_CAPTURE_UI")]
    (let [frame (.captureSpans terminal)
          lines (mapv (fn [line]
                        (mapv (fn [span] {:text (.-text span) :width (.-width span) :attributes (.-attributes span)
                                         :fg (js->clj (.toInts (.-fg span))) :bg (js->clj (.toInts (.-bg span)))})
                              (array-seq (.-spans line)))) (array-seq (.-lines frame)))]
      (.mkdirSync fs directory #js {:recursive true})
      (.writeFileSync fs (.join path directory (str name ".json"))
                      (js/JSON.stringify (clj->js {:cols (.-cols frame) :rows (.-rows frame) :lines lines})))
      (.writeFileSync fs (.join path directory (str name ".txt")) (.captureCharFrame terminal)))))

(defn exercise! []
  (let [directory (.mkdtempSync fs (.join path (.tmpdir (js/require "node:os")) "arrodes-themes-"))
        custom (.join path directory "themes" "rose")
        bad (.join path directory "themes" "bad")
        mounted (atom nil) terminal* (atom nil) editor* (atom nil) position (atom nil)
        application (app/create! {:runtime-root (.cwd js/process) :cwd directory :home directory :setup? false})]
    (.mkdirSync fs custom #js {:recursive true})
    (.mkdirSync fs bad #js {:recursive true})
    (.writeFileSync fs (.join path custom "theme.edn")
                    (pr-str {:schema-version 1 :id "rose" :name "Rose" :version "1" :colors {:ui/accent "#cc5577" :markdown/heading "#cc5577"}}))
    (.writeFileSync fs (.join path bad "theme.edn") "{:schema-version 1 :id \"bad\" :name \"Bad\" :version \"1\" :layout {:width 5}}")
    (let [catalog (themes/discover directory)]
      (check! (= 3 (count (:packs catalog))) "Theme folders must load beside builtins")
      (check! (= 1 (count (:errors catalog))) "Invalid packs must be isolated with a diagnostic"))
    (.writeFileSync fs (.join path directory "trailing.edn") "{} {}")
    (check! (try (themes/read-data (.join path directory "trailing.edn")) false (catch :default _ true)) "Trailing EDN values must be rejected")
    (.writeFileSync fs (.join path directory "tagged.edn") "#=(println :not-executable)")
    (check! (try (themes/read-data (.join path directory "tagged.edn")) false (catch :default _ true)) "Reader evaluation must be rejected")
    (.writeFileSync fs (.join path directory "large.edn") (apply str (repeat 65537 "x")))
    (check! (try (themes/read-data (.join path directory "large.edn")) false (catch :default _ true)) "Oversized manifests must be rejected before reading")
    (-> ((aget js/globalThis "ARRODES_CREATE_TEST_RENDERER") #js {:width 100 :height 46 :kittyKeyboard true :consoleMode "disabled"})
        (.then (fn [terminal]
                 (reset! terminal* terminal)
                 (let [v (view/mount! application (.-renderer terminal) {:theme-home directory})]
                   (reset! mounted v)
                   (swap! (:state application) assoc :connection {:status :ready}
                          :models [{:provider :codex-backend :id "gpt-5.6-sol" :context-window 922000}]
                          :branch "main"
                          :view {:activities {"execution-preview" {:id "execution-preview" :kind :evaluation :name "repl"
                                                                  :status :completed :start-seq 1
                                                                  :source "(def answer 42)" :content "42"}}
                                 :activity-order ["execution-preview"] :snapshot-cursor 0
                                 :session {:id "theme-session" :name "Session model" :cwd directory
                                           :config {:provider :codex-backend :model "gpt-5.6-sol" :thinking :medium}}
                                 :entries [{:id "user" :kind :message :data {:message/role :user :message/content "Make it readable"}}
                                           {:id "answer" :kind :message :data {:message/role :assistant :message/content "# A heading\n\nA **strong** reply and a [link](https://example.invalid).\n\n```clojure\n(def answer 42)\n```\n\n| Value | Lifetime |\n| --- | --- |\n| Result 23 | Durable |\n| REPL binding | Live |"}}]})
                   (swap! (:state application) assoc-in [:ui :expanded "activity:execution-preview"] true)
                   (.setText (node terminal "composer") "keep this input")
                   (until! terminal #(str/includes? (.captureCharFrame terminal) "Make it readable") "Fixture must render"))))
        (.then (fn [_] (.waitForVisualIdle @terminal*)))
        (.then (fn [_]
                 (let [terminal @terminal* editor (node terminal "composer")]
                   (capture! terminal "theme-default-conversation")
                   (reset! editor* editor)
                   (reset! position [(.-screenY editor) (.-scrollTop (node terminal "conversation"))])
                   (set! (.-cursorOffset editor) 4)
                   (.setSelection editor 1 4)
                   (picker/open! @mounted)
                   (until! terminal #(= :themes (get-in @(:state application) [:ui :overlay :kind])) "Theme picker must open"))))
        (.then (fn [_]
                 (.pressArrow (.-mockInput @terminal*) "down")
                 (until! @terminal* #(= "dracula" (:id (palette/current (.-renderer @terminal*)))) "Arrow selection must live-preview Dracula")))
        (.then (fn [_]
                 (let [terminal @terminal* editor (node terminal "composer")]
                   (capture! terminal "theme-dracula-picker")
                   (check! (< (.-screenY (node terminal "turn-rule:message:user"))
                              (.-screenY (node terminal "heading:message:user"))) "Turn rules must sit above role headings")
                   (check! (= [189 147 249] (rgb (.-borderColor (node terminal "turn-rule:message:user")))) "Turn rules must follow the active theme")
                   (check! (.-border (node terminal "artifact:activity:execution-preview")) "Execution boundaries must remain visible under Dracula")
                   (check! (identical? editor @editor*) "Preview must not replace the editor")
                   (check! (= "eep" (.getSelectedText editor)) "Preview must preserve editor selection")
                   (check! (= @position [(.-screenY editor) (.-scrollTop (node terminal "conversation"))]) "Preview must not change layout or scroll position")
                   (check! (= "keep this input" (.-plainText editor)) "Preview must preserve text")
                   (check! (= 4 (.-cursorOffset editor)) "Preview must preserve cursor")
                   (check! (= [40 42 54] (rgb (.-backgroundColor (:root @mounted)))) "Existing root must repaint")
                   (check! (= [139 233 253] (rgb (.-fg (node terminal "heading:message:answer")))) "Semantic assistant heading must repaint")
                   (check! (not (.existsSync fs (.join path directory "config" "tui.edn"))) "Preview must not persist settings")
                   (.pressEscape (.-mockInput terminal))
                   (until! terminal #(= "default" (:id (palette/current (.-renderer terminal)))) "Escape must restore the saved theme"))))
        (.then (fn [_]
                 (check! (= [20 24 33] (rgb (.-backgroundColor (:root @mounted)))) "Cancel must repaint existing nodes")
                 (.mkdirSync fs (.join path directory "config") #js {:recursive true})
                 (.writeFileSync fs (.join path directory "config" "tui.edn") (pr-str {:other-setting 42}))
                 (picker/open! @mounted)
                 (.pressArrow (.-mockInput @terminal*) "down")
                 (.pressArrow (.-mockInput @terminal*) "down")
                 (.pressEnter (.-mockInput @terminal*))
                 (until! @terminal* #(= [204 85 119] (span-color @terminal* "heading"))
                         "Changing only the heading color must repaint cached Markdown")))
        (.then (fn [_]
                 (picker/open! @mounted)
                 (.pressArrow (.-mockInput @terminal*) "up")
                 (.pressEnter (.-mockInput @terminal*))
                 (until! @terminal* #(and (nil? (get-in @(:state application) [:ui :overlay]))
                                          (= "dracula" @(:selected (:themes @mounted)))) "Enter must apply and save")))
        (.then (fn [_]
                 (until! @terminal* #(= [189 147 249] (span-color @terminal* "heading")) #(str "Markdown spans must repaint to Dracula; actual=" (pr-str (span-color @terminal* "heading"))))))
        (.then (fn [_]
                 (capture! @terminal* "theme-dracula-conversation")
                 (check! (= "dracula" @(:selected (themes/create {:home directory}))) "Selection must survive restarting")
                 (check! (= 42 (:other-setting (themes/read-data (.join path directory "config" "tui.edn")))) "Saving must preserve other UI preferences")
                 ;; Reload a malformed installed pack while Dracula is active.
                 (picker/open! @mounted)
                 (.pressEscape (.-mockInput @terminal*))
                 (until! @terminal* #(and (= "dracula" (:id (palette/current (.-renderer @terminal*))))
                                          (not (.-visible (node @terminal* "dialog-layer")))) "Bad packs must not replace active styling")))
        (.then (fn [_]
                 (.resize @terminal* 56 24)
                 (until! @terminal* #(and (= 56 (.-width (:root @mounted)))
                                          (not (.-visible (node @terminal* "dialog-layer")))) "Narrow preview must resize")))
        (.then (fn [_] (.waitForVisualIdle @terminal*)))
        (.then (fn [_] (capture! @terminal* "theme-dracula-narrow")
                 (picker/open! @mounted)
                 (.pressArrow (.-mockInput @terminal*) "up")
                 (.pressEnter (.-mockInput @terminal*))
                 (until! @terminal* #(and (= "default" (:id (palette/current (.-renderer @terminal*))))
                                          (not (.-visible (node @terminal* "dialog-layer")))) "Default narrow theme must settle")))
        (.then (fn [_] (.waitForVisualIdle @terminal*)))
        (.then (fn [_] (capture! @terminal* "theme-default-narrow")
                 (.resize @terminal* 100 46)
                 (until! @terminal* #(= 100 (.-width (:root @mounted))) "Default wide preview must settle")))
        (.then (fn [_] (.waitForVisualIdle @terminal*)))
        (.then (fn [_] (capture! @terminal* "theme-default-conversation")
                 (println "Themes passed: pack discovery/validation, live preview, cancel, persistence, and editor preservation.")))
        (.finally (fn []
                    (when @mounted (view/destroy! @mounted))
                    (when @terminal* (.destroy (.-renderer @terminal*)))
                    (.rmSync fs directory #js {:recursive true :force true}))))))

(defn -main []
  (aset js/globalThis "ARRODES_TUI_TEST_DONE" (exercise!)))
(set! *main-cli-fn* -main)
