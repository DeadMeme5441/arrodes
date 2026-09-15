(ns arrodes.tui-view-test
  "TUI regression entry point: real RPC lifecycle followed by native popup rendering."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-app-test :as app-test]
            [arrodes.catalog-flow-test :as catalog-flow]
            [arrodes.tui-model :as model]
            [arrodes.tui-view :as view]
            [clojure.string :as str]))

(defn- node [terminal id]
  (.findDescendantById (.-root (.-renderer terminal)) id))

(defn- selected-visible? [application terminal]
  (when-let [row (node terminal (str "choice-" (get-in @(:state application) [:ui :overlay :index])))]
    (let [label (aget (.getChildren row) 0)
          viewport (.-viewport (node terminal "dialog-choices"))
          y (.-screenY label)
          line (get (str/split-lines (.captureCharFrame terminal)) y "")]
      (and (pos? (.-height label))
           (<= (.-screenY viewport) y)
           (< y (+ (.-screenY viewport) (.-height viewport)))
           (str/includes? line (.-plainText label))))))

(defn- popup-fits? [terminal]
  (let [popup (.-parent (node terminal "dialog-choices"))
        renderer (.-renderer terminal)]
    (and (<= (+ (.-screenX popup) (.-width popup)) (.-terminalWidth renderer))
         (<= (+ (.-screenY popup) (.-height popup)) (.-terminalHeight renderer)))))

(defn- until! [terminal predicate message]
  (let [deadline (+ (js/Date.now) 2000)]
    (letfn [(poll []
              (-> (.renderOnce terminal)
                  (.then (fn []
                           (cond
                             (predicate) nil
                             (> (js/Date.now) deadline)
                             (throw (js/Error. (str (if (fn? message) (message) message)
                                                    "\n" (.captureCharFrame terminal))))
                             :else
                             (-> (js/Promise. (fn [resolve] (js/setTimeout resolve 15)))
                                 (.then poll)))))))]
      (poll))))

(defn- visible! [application terminal]
  (until! terminal #(selected-visible? application terminal)
          #(let [index (get-in @(:state application) [:ui :overlay :index])
                 row (node terminal (str "choice-" index))
                 label (node terminal (str "choice-" index "-label"))
                 scroll (node terminal "dialog-choices")
                 viewport (.-viewport scroll)]
             (str "Keyboard selection left the popup viewport "
                  (pr-str {:index index :label (.-plainText label)
                           :row-y (.-y row) :row-height (.-height row)
                           :label-y (.-y label) :label-screen-y (.-screenY label)
                           :viewport-y (.-y viewport) :viewport-screen-y (.-screenY viewport)
                           :viewport-height (.-height viewport) :scroll-top (.-scrollTop scroll)
                           :scroll-height (.-scrollHeight scroll) :content-height (.-height (.-content scroll))
                           :bar-viewport (.-viewportSize (.-verticalScrollBar scroll))})))))

(defn- walk! [application terminal direction count]
  (if (zero? count)
    (js/Promise.resolve nil)
    (do
      (.pressArrow (.-mockInput terminal) direction)
      (-> (visible! application terminal)
          (.then #(walk! application terminal direction (dec count)))))))

(defn- show! [application kind]
  (let [labels (mapv #(str "Choice " (when (< % 10) "0") %) (range 24))
        description (str/join " " (repeat 14 "wrapped"))
        items (mapv (fn [label] {:label label :description description}) labels)
        entries (mapv (fn [label] {:id label :kind :message
                                   :data {:message/role :user :message/content label}}) labels)]
    (swap! (:state application)
           (fn [state]
             (-> state
                 (assoc :sessions (mapv (fn [label] {:id label :name label :cwd description}) labels)
                        :models (mapv (fn [label] {:id label :provider :codex-backend}) labels))
                 (assoc-in [:view :queue] (mapv (fn [label] {:id label :kind :steering :content label}) labels))
                 (assoc-in [:ui :overlay] {:kind kind :token (str (random-uuid)) :index 0 :query ""
                                           :title "Popup scrolling regression" :items items
                                           :entries entries :paths labels}))))))

(defn- menu! [application terminal kind]
  (show! application kind)
  (-> (visible! application terminal)
      (.then #(walk! application terminal "down" 23))
      (.then #(walk! application terminal "up" 23))))

(defn- inspector-and-host-lifetimes! [application terminal]
  (let [message {:id "message" :kind :message
                 :data {:message/role :user :message/content "new selection"}}
        activity {:id "old" :kind :capability :name "bash" :status :completed
                  :start-seq 1 :content "old output"
                  :presentation "custom renderer output"
                  :result {:id "old-result" :kind :inline
                           :value "old value" :available? true}}
        renderer-failed {:id "renderer-failed" :kind :capability :name "bash"
                         :status :completed :start-seq 2
                         :content "canonical renderer fallback"
                         :presentation-error "renderer exploded"}
        activity-row {:id "activity:old" :kind :activity :activity activity}]
    (swap! (:state application)
           (fn [state]
             (-> state
                 (assoc :view (assoc (model/empty-state)
                                     :session {:id "view-session" :name "View session" :cwd (.cwd js/process)}
                                     :entries [message]
                                     :activities {"old" activity
                                                  "renderer-failed" renderer-failed}
                                     :activity-order ["old" "renderer-failed"]
                                     :snapshot-cursor 0))
                 (update :ui merge {:selected "activity:old"
                                    :inspected-row activity-row
                                    :inspector? true
                                    :inspect-tab :value
                                    :inspection {:result-id "old-result"
                                                 :descriptor (:result activity)
                                                 :page {:content "stale artifact" :offset 1}}
                                    :overlay nil
                                    :focus :transcript
                                    :follow? false}))))
    (.focus (node terminal "conversation"))
    (-> (until! terminal
                #(let [frame (.captureCharFrame terminal)]
                   (and (str/includes? frame "custom renderer output")
                        (str/includes? frame "canonical renderer fallback")
                        (str/includes? frame "Renderer error")
                        (str/includes? frame "done")))
                "Custom presentation or its non-fatal renderer error was not visible")
        (.then (fn [_] (.pressArrow (.-mockInput terminal) "up")))
        (.then (fn [_]
                 (until! terminal
                         #(and (= "message:message" (get-in @(:state application) [:ui :selected]))
                               (nil? (get-in @(:state application) [:ui :inspection])))
                         "Selecting a message retained the prior result descriptor")))
        (.then
         (fn [_]
           (app/command! application :host-widget
                         {:request {:kind :widget :session-id "view-session"
                                    :id "widget" :placement :header
                                    :content "extension widget"}})))
        (.then
         (fn [_]
           (until! terminal
                   #(and (.-visible (node terminal "session-widgets"))
                         (str/includes? (.captureCharFrame terminal) "extension widget"))
                   "Session widget was not rendered by the native view")))
        (.then
         (fn [_]
           (app/command! application :host-widget
                         {:request {:kind :set-widget :session-id "view-session"
                                    :id "widget" :remove? true}})))
        (.then
         (fn [_]
           (until! terminal #(not (.-visible (node terminal "session-widgets")))
                   "Removed session widget remained visible")))
        (.then
         (fn [_]
           (swap! (:state application) assoc
                  :host-requests [{:id "render-request"
                                   :request {:kind :render :session-id "view-session"
                                             :title "Rendered output"
                                             :content ["first rendered line" "second rendered line"]}}])))
        (.then
         (fn [_]
           (until! terminal
                   #(and (= :render (get-in @(:state application) [:ui :overlay :kind]))
                         (str/includes? (.captureCharFrame terminal) "first rendered line"))
                   "Render reverse request did not use the native overlay")))
        (.then
         (fn [_]
           (swap! (:state application)
                  #(-> %
                       (assoc :host-requests [{:id "editor-request"
                                               :request {:kind :editor :session-id "view-session"
                                                         :title "Edit value" :initial "editable"}}])
                       (assoc-in [:ui :overlay] nil)))))
        (.then
         (fn [_]
           (until! terminal
                   #(and (= "editor-request" (get-in @(:state application) [:ui :overlay :host-id]))
                         (= "editable" (.-plainText (node terminal "dialog-input"))))
                   "Editor reverse request did not open the native multiline input")))
        (.then
         (fn [_]
           (swap! (:state application)
                  #(-> %
                       (assoc :host-requests [])
                       (assoc-in [:ui :overlay]
                                 {:kind :commands :token "return"
                                  :query "" :index 0 :title "Return overlay"})))))
        (.then
         (fn [_]
           (swap! (:state application) assoc
                  :host-requests [{:id "cancelled-input"
                                   :request {:kind :input :session-id "view-session"
                                             :title "Cancelled input" :secret? true}}])))
        (.then
         (fn [_]
           (until! terminal
                   #(= "cancelled-input" (get-in @(:state application) [:ui :overlay :host-id]))
                   "Host input did not open")))
        (.then (fn [_] (.typeText (.-mockInput terminal) "never-render-" 15)))
        (.then
         (fn [_]
           (swap! (:state application) assoc :notice {:kind :info :message "Refresh while typing"})
           (.pasteBracketedText (.-mockInput terminal) "this-secret")))
        (.then
         (fn [_]
           (until! terminal
                   #(let [frame (.captureCharFrame terminal)]
                      (and (str/includes? frame "••••")
                           (not (str/includes? frame "never-render-this-secret"))
                           (= "never-render-this-secret" (.-plainText (node terminal "dialog-input")))
                           (= "" (get-in @(:state application) [:ui :overlay :query]))))
                   "Secret input was visible or retained in overlay state")))
        (.then
         (fn [_]
           (swap! (:state application) assoc
                  :host-requests [{:id "next-confirm"
                                   :request {:kind :confirm :session-id "view-session"
                                             :title "Next confirmation"}}])))
        (.then
         (fn [_]
           (until! terminal
                   #(let [frame (.captureCharFrame terminal)]
                      (and (= "next-confirm" (get-in @(:state application) [:ui :overlay :host-id]))
                           (nil? (get-in @(:state application)
                                         [:ui :overlay :return-overlay :host-id]))
                           (not (str/includes? frame "never-render-this-secret"))
                           (= "" (.-plainText (node terminal "dialog-input")))))
                   "Cancelled secret survived in renderer output, input storage, or the next overlay")))
        (.then
         (fn [_]
           (swap! (:state application) assoc :host-requests [])))
        (.then
         (fn [_]
           (until! terminal
                   #(and (= :commands (get-in @(:state application) [:ui :overlay :kind]))
                         (nil? (get-in @(:state application) [:ui :overlay :host-id])))
                   "Cancelled reverse-request overlay remained active"))))))

(defn- capture! [terminal name]
  (when-let [directory (aget (.-env js/process) "ARRODES_CAPTURE_UI")]
    (let [fs (js/require "node:fs") path (js/require "node:path")
          frame (.captureSpans terminal)
          lines (mapv (fn [line]
                        (mapv (fn [span] {:text (.-text span) :width (.-width span)
                                         :fg (js->clj (.toInts (.-fg span)))
                                         :bg (js->clj (.toInts (.-bg span)))})
                              (array-seq (.-spans line)))) (array-seq (.-lines frame)))]
      (.mkdirSync fs directory #js {:recursive true})
      (.writeFileSync fs (.join path directory (str name ".json"))
                      (js/JSON.stringify (clj->js {:cols (.-cols frame) :rows (.-rows frame) :lines lines})))
      (.writeFileSync fs (.join path directory (str name ".txt")) (.captureCharFrame terminal)))))

(defn- browser-and-chat! [application terminal]
  (let [providers [{:provider :codex-backend :name "Codex" :available? true :auth {:type :oauth}}
                   {:provider :openai :name "OpenAI" :available? true :auth {:type :api-key}}
                   {:provider :anthropic :name "Anthropic" :available? false :auth {:type :api-key}}]
        models [{:provider :codex-backend :id "gpt-example" :context-window 128000 :thinking-levels [:none :medium :high] :input [:text :image]}
                {:provider :openai :id "other-model" :context-window 64000 :thinking-levels [:none]}]
        open! (fn [kind]
                (swap! (:state application)
                       #(-> % (assoc :providers providers :models models :host-requests [] :notice nil)
                            (assoc-in [:ui :overlay] {:kind kind :title (if (= kind :models) "Models" "Providers")
                                                     :token (str (random-uuid)) :index 0 :query ""
                                                     :provider :codex-backend :pane :models
                                                     :hint "Connect your accounts. Choose a model for this conversation or save a default."}))))]
    (.resize terminal 120 36)
    (open! :providers)
    (-> (until! terminal #(str/includes? (.captureCharFrame terminal) "Browser sign-in") "Provider status and authentication method are missing")
        (.then (fn [_] (capture! terminal "providers") (open! :models)))
        (.then (fn [_] (until! terminal #(and (.-visible (node terminal "provider-sidebar"))
                                             (str/includes? (.captureCharFrame terminal) "128000 context")) "Model browser lost sidebar or details")))
        (.then (fn [_]
                 (capture! terminal "models")
                 (.pressKey (.-mockInput terminal) "TAB")
                 (.pressArrow (.-mockInput terminal) "down")
                 (until! terminal #(str/includes? (.captureCharFrame terminal) "other-model") "Provider keyboard navigation did not filter models")))
        (.then (fn [_]
                 (.pressKey (.-mockInput terminal) "TAB")
                 (.pressEnter (.-mockInput terminal))
                 (until! terminal #(= "Reasoning" (get-in @(:state application) [:ui :overlay :title])) "Enter did not open model reasoning")))
        (.then (fn [_]
                 (.pressEnter (.-mockInput terminal))
                 (until! terminal #(str/includes? (.captureCharFrame terminal) "Make default for new conversations") "Model selection did not expose explicit default scope")))
        (.then (fn [_]
                 (.pressEscape (.-mockInput terminal))
                 (until! terminal #(= :models (get-in @(:state application) [:ui :overlay :kind])) "Model choice lost browser return location")))
        (.then (fn [_]
                 (.resize terminal 60 20)
                 (until! terminal #(and (not (.-visible (node terminal "provider-sidebar")))
                                        (str/includes? (.captureCharFrame terminal) "other-model")
                                        (popup-fits? terminal)) "Narrow model browser is not usable")))
        (.then (fn [_]
                 (capture! terminal "models-narrow")
                 (.resize terminal 120 36)
                 (let [entries [{:id "question" :kind :message :data {:message/role :user :message/content "Make the provider flow seamless, then verify it."}}
                                {:id "call" :parent-id "question" :kind :message
                                 :data {:message/role :assistant :message/content "I’ll check the configuration and exercise the selection flow."
                                        :message/tool-calls [{:tool-call/id "eval" :tool-call/name "repl"
                                                             :tool-call/arguments {:source "(def settings (read {:path \"config.edn\"}))\n(:provider settings)"}}]}}
                                {:id "result" :parent-id "call" :kind :message :data {:message/role :tool :message/tool-call-id "eval" :message/name "repl"
                                                                                 :message/content "=> :example"}}
                                {:id "answer" :parent-id "result" :kind :message
                                 :data {:message/role :assistant :message/content "The provider flow is ready.\n\n- Connected accounts stay visible.\n- Model changes preserve the conversation and live values.\n- Defaults apply to new conversations.\n\nUse **/providers** to try it."}}]
                       snapshot {:state {:session {:id "preview" :name "Provider experience" :cwd "/work/arrodes" :head "answer"
                                                   :config {:provider :codex-backend :model "gpt-example" :thinking :high}}}
                                 :entries entries :cursor 2}
                       events [{:seq 1 :type :evaluation/started :data {:call-id "eval" :source "(def settings (read {:path \"config.edn\"}))\n(:provider settings)"}}
                               {:seq 2 :type :evaluation/completed :data {:call-id "eval" :content "=> :example" :error? false
                                                                         :result {:id 1 :kind :inline :value :example}}}]]
                   (swap! (:state application) assoc :view (model/hydrate snapshot events) :branch "main"
                          :connection {:status :ready} :notice nil)
                   (swap! (:state application) update :ui assoc :overlay nil :draft "" :inspector? false
                          :expanded {"activity:eval" true} :focus :composer :follow? true))
                 (until! terminal #(and (str/includes? (.captureCharFrame terminal) "def settings")
                                        (str/includes? (.captureCharFrame terminal) "Output")
                                        (str/includes? (.captureCharFrame terminal) "provider flow is ready")
                                        (false? (.-border (node terminal "row:message:answer")))) "Chat lost expanded source/output, plain prose, or the final answer")))
        (.then (fn [_]
                 (capture! terminal "chat")
                 (.setText (node terminal "composer") "Keep this draft while I inspect")
                 (.resize terminal 60 20)
                 (until! terminal #(= "Keep this draft while I inspect" (.-plainText (node terminal "composer"))) "Resize discarded composer draft")))
        (.then (fn [_] (capture! terminal "chat-narrow")
                 (println "Browser/chat rendering passed: provider states, two-pane navigation, scope selection, narrow layout, source/output, draft preservation."))))))

(defn- exercise! [terminal]
  (let [application (app/create! {:runtime-root (.cwd js/process) :cwd (.cwd js/process)
                                  :setup? false})
        mounted (view/mount! application (.-renderer terminal) {})]
    (.pressKey (.-mockInput terminal) "F3")
    (-> (visible! application terminal)
        (.then #(walk! application terminal "down" 40))
        (.then #(walk! application terminal "up" 40))
        (.then (fn []
                 (reduce (fn [pending kind] (.then pending #(menu! application terminal kind)))
                         (js/Promise.resolve nil) [:sessions :models :history :files :pending :choices])))
        (.then #(walk! application terminal "down" 23))
        (.then (fn []
                 (.resize terminal 78 24)
                 (until! terminal
                         #(and (popup-fits? terminal)
                               (selected-visible? application terminal))
                         "Resize hid the selected popup row")))
        (.then #(walk! application terminal "up" 23))
        (.then #(walk! application terminal "down" 23))
        (.then (fn []
                 (.setText (node terminal "dialog-input") "Choice 2")
                 (until! terminal
                         #(and (= "Choice 2" (get-in @(:state application) [:ui :overlay :query]))
                               (zero? (get-in @(:state application) [:ui :overlay :index]))
                               (selected-visible? application terminal))
                         "Filtering did not reveal the first matching row")))
        (.then #(walk! application terminal "down" 3))
        (.then (fn []
                 (.setText (node terminal "dialog-input") "no-matching-choice")
                 (until! terminal #(nil? (node terminal "choice-0")) "Empty results retained old choices")))
        (.then (fn []
                 (.setText (node terminal "dialog-input") "")
                 (until! terminal #(and (= "" (get-in @(:state application) [:ui :overlay :query]))
                                        (selected-visible? application terminal))
                         "Restoring choices left the selection outside the viewport")))
        (.then (fn []
                 (.resize terminal 78 16)
                 (until! terminal
                         #(and (popup-fits? terminal)
                               (selected-visible? application terminal))
                         "Short viewport hid the selected item label")))
        (.then #(walk! application terminal "down" 23))
        (.then #(walk! application terminal "up" 23))
        (.then #(menu! application terminal :choices))
        (.then (fn [_]
                 (.resize terminal 120 40)
                 (inspector-and-host-lifetimes! application terminal)))
        (.then (fn [_] (browser-and-chat! application terminal)))
        (.then (fn [] (println "Native TUI passed: popup layout, inspector selection ownership, session widgets, render/editor requests and cancelled overlay cleanup.")))
        (.finally (fn []
                    (view/destroy! mounted)
                    (.destroy (.-renderer terminal))
                    (app/close! application))))))

(defn -main []
  (aset js/globalThis "ARRODES_TUI_TEST_DONE"
        (-> (if (aget (.-env js/process) "ARRODES_TUI_VISUAL_ONLY")
              (js/Promise.resolve nil)
              (-> (catalog-flow/exercise!) (.then (fn [] (app-test/exercise!)))))
            (.then (fn []
                     ((aget js/globalThis "ARRODES_CREATE_TEST_RENDERER")
                      #js {:width 120 :height 40 :kittyKeyboard true :consoleMode "disabled"})))
            (.then exercise!))))

(set! *main-cli-fn* -main)
