(ns arrodes.tui-view-test
  "TUI regression entry point: real RPC lifecycle followed by native screen rendering."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-app-test :as app-test]
            [arrodes.catalog-flow-test :as catalog-flow]
            [arrodes.tui-model :as model]
            [arrodes.tui-view :as view]
            [arrodes.tui.screens :as screens]
            [arrodes.theme-ui-test :as theme-test]
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

(defn- screen-fits? [terminal]
  (let [screen (node terminal "active-screen") renderer (.-renderer terminal)]
    (and (= 0 (.-screenX screen)) (= 0 (.-screenY screen))
         (= (.-width screen) (.-terminalWidth renderer))
         (= (.-height screen) (.-terminalHeight renderer)))))

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
             (str "Keyboard selection left the screen viewport "
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
                                           :title "Screen scrolling regression" :items items
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
                                    :inspector? false
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
        (.then (fn [_]
                 (swap! (:state application) assoc-in [:ui :inspector?] true)
                 (until! terminal #(and (= (.-width (node terminal "inspector")) (.-terminalWidth (.-renderer terminal)))
                                        (not (.-visible (.-parent (node terminal "conversation")))))
                         "Inspection must occupy its own full-width screen")))
        (.then (fn [_] (.pressArrow (.-mockInput terminal) "up")))
        (.then (fn [_]
                 (until! terminal
                         #(and (= "message:message" (get-in @(:state application) [:ui :selected]))
                               (nil? (get-in @(:state application) [:ui :inspection])))
                         "Selecting a message retained the prior result descriptor")))
        (.then
         (fn [_]
           (.pressEscape (.-mockInput terminal))
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
                         (.-focused (node terminal "composer"))
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
                {:provider :openai :id "gpt-example" :context-window 64000 :thinking-levels [:none]}
                {:provider :openai :id "other-model" :context-window 64000 :thinking-levels [:none]}]
        open! (fn [kind]
                (swap! (:state application)
                       #(-> % (assoc :providers providers :models models :host-requests [] :notice nil)
                            (assoc-in [:view :session :config] {:provider :codex-backend :model "gpt-example" :thinking :high})
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
                 (.pressTab (.-mockInput terminal) #js {:shift true})
                 (.pressArrow (.-mockInput terminal) "down")
                 (until! terminal #(and (str/includes? (.captureCharFrame terminal) "other-model")
                                       (not (str/includes? (.captureCharFrame terminal) "Current ·")))
                         "Provider navigation failed or another provider's same-named model was marked current")))
        (.then (fn [_]
                 (let [provider (node terminal "provider-codex-backend")]
                   (.click (.-mockMouse terminal) (+ 2 (.-screenX provider)) (.-screenY provider)))))
        (.then (fn [_]
                 (until! terminal #(and (= :codex-backend (get-in @(:state application) [:ui :overlay :provider]))
                                        (not (str/includes? (.captureCharFrame terminal) "other-model")))
                         "Clicking a provider must remove every other provider's models")))
        (.then (fn [_]
                 (let [provider (node terminal "provider-openai")]
                   (.click (.-mockMouse terminal) (+ 2 (.-screenX provider)) (.-screenY provider)))))
        (.then (fn [_]
                 (until! terminal #(and (= :openai (get-in @(:state application) [:ui :overlay :provider]))
                                        (str/includes? (.captureCharFrame terminal) "other-model")
                                        (.-focused (node terminal "dialog-input")))
                         "Provider clicks must select its catalog and keep search ready")))
        (.then (fn [_]
                 (.pressEnter (.-mockInput terminal))
                 (until! terminal #(and (= :models (get-in @(:state application) [:ui :overlay :kind]))
                                        (= :effort (get-in @(:state application) [:ui :overlay :pane]))) "Enter must focus inline effort controls")))
        (.then (fn [_]
                 (.pressEnter (.-mockInput terminal))
                 (until! terminal #(str/includes? (.captureCharFrame terminal) "Apply here + make default") "Model selection did not expose explicit default scope")))
        (.then (fn [_]
                 (.pressEscape (.-mockInput terminal))
                 (until! terminal #(= :models (get-in @(:state application) [:ui :overlay :kind])) "Model choice lost browser return location")))
        (.then (fn [_]
                 (.resize terminal 60 20)
                 (until! terminal #(and (not (.-visible (node terminal "provider-sidebar")))
                                        (str/includes? (.captureCharFrame terminal) "other-model")
                                        (screen-fits? terminal)) "Narrow model browser is not usable")))
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
                                 :data {:message/role :assistant :message/content "The provider flow is ready.\n\n- Connected accounts stay visible.\n- Model changes preserve the conversation and live values.\n- Defaults apply here and to new conversations.\n\n```clojure\n(result 23)\n```\n\nUse **/providers** to try it."}}]
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
                                        (false? (.-border (node terminal "row:message:answer")))
                                        (true? (.-border (node terminal "artifact:activity:eval")))) "Chat lost expanded source/output, plain prose, or the final answer")))
        (.then (fn [_]
                 (capture! terminal "chat")
                 (.setText (node terminal "composer") "Keep this draft while I inspect")
                 (.resize terminal 60 20)
                 (until! terminal #(= "Keep this draft while I inspect" (.-plainText (node terminal "composer"))) "Resize discarded composer draft")))
        (.then (fn [_] (capture! terminal "chat-narrow")
                 (println "Browser/chat rendering passed: provider states, two-pane navigation, scope selection, narrow layout, source/output, draft preservation."))))))

(defn- model-column-edges! [application terminal]
  (let [input (.-mockInput terminal) editor (node terminal "dialog-input")
        pane? #(= % (get-in @(:state application) [:ui :overlay :pane]))]
    (.pressArrow input "left")
    (-> (until! terminal #(pane? :providers) "Left at empty search must enter providers")
        (.then (fn [_] (.pressArrow input "left")
                 (until! terminal #(pane? :providers) "Left at outer provider edge must not wrap")))
        (.then (fn [_] (.pressArrow input "right")
                 (until! terminal #(and (pane? :models) (.-focused editor)) "Right from providers must focus model search")))
        (.then (fn [_] (.setText editor "luna")
                 (until! terminal #(= "luna" (get-in @(:state application) [:ui :overlay :query])) "Search should update")))
        (.then (fn [_] (set! (.-cursorOffset editor) 2) (.pressArrow input "left")
                 (until! terminal #(and (pane? :models) (= 1 (.-cursorOffset editor))) "Interior arrows must move the text cursor")))
        (.then (fn [_] (set! (.-cursorOffset editor) 0) (.pressArrow input "left")
                 (until! terminal #(pane? :providers) "Left at start of search must cross to providers")))
        (.then (fn [_] (.pressArrow input "right") (set! (.-cursorOffset editor) 4) (.pressArrow input "right")
                 (until! terminal #(and (pane? :effort) (= "luna" (.-plainText editor))) "Right at end must cross to effort without changing search")))
        (.then (fn [_] (.pressArrow input "left") (.pressArrow input "left")
                 (until! terminal #(and (pane? :effort) (= :none (get-in @(:state application) [:ui :overlay :thinking])))
                         "Arrows within the effort range must adjust it before changing columns")))
        (.then (fn [_] (.pressArrow input "left")
                 (until! terminal #(and (pane? :models) (.-focused editor)) "Left beyond minimum effort must return to models")))
        (.then (fn [_] (.pressArrow input "right")
                 (dotimes [_ 6] (.pressArrow input "right"))
                 (until! terminal #(and (pane? :effort) (= :max (get-in @(:state application) [:ui :overlay :thinking])))
                         "Right at the outer effort edge must clamp without wrapping")))
        (.then (fn [_] (.pressTab input) (.pressArrow input "left")
                 (until! terminal #(pane? :models) "Left from apply actions must return to model search")))
        (.then (fn [_] (.setText editor "no-match")
                 (until! terminal #(= "no-match" (get-in @(:state application) [:ui :overlay :query])) "Empty search result must settle")))
        (.then (fn [_] (set! (.-cursorOffset editor) 8) (.pressArrow input "right") (.pressArrow input "left")
                 (until! terminal #(pane? :models) "Empty results must not trap focus in settings")))
        (.then (fn [_] (.setText editor "")
                 (until! terminal #(= "" (get-in @(:state application) [:ui :overlay :query])) "Clearing search must restore models")))
        (.then (fn [_] (let [button (node terminal "effort-medium")]
                         (.click (.-mockMouse terminal) (+ 1 (.-screenX button)) (.-screenY button)))))
        (.then (fn [_] (.pressEscape input)
                 (until! terminal #(pane? :models) "Return to models after mouse selection"))))))

(defn- transcript-hierarchy! [application terminal]
  (.resize terminal 150 55)
  (-> (until! terminal
              #(and (= "Provider experience" (.-plainText (node terminal "session-title")))
                     (= 1 (alength (.getChildren (.-parent (node terminal "session-title"))))))
              "The app header must contain only the session title")
      (.then (fn [_]
               (until! terminal
                       #(and (= "You" (.-plainText (node terminal "heading:message:question")))
                              (= "Arrodes" (.-plainText (node terminal "heading:message:answer")))
                              (> (.-width (node terminal "row:message:answer")) 140)
                              (= (.-width (node terminal "row:message:question")) (.-width (node terminal "row:message:answer")))
                              (str/includes? (.captureCharFrame terminal) "clojure")
                              (str/includes? (.captureCharFrame terminal) "(result 23)"))
                       "Turns and fenced code must remain legible with clear labels and full-width blocks")))
      (.then (fn [_] (capture! terminal "conversation-hierarchy-wide") (.resize terminal 60 36)
               (until! terminal #(<= (.-width (node terminal "row:message:answer")) 60)
                       "Conversation blocks must fit narrow terminals")))
      (.then (fn [_] (capture! terminal "conversation-hierarchy-narrow")
               (println "Transcript hierarchy passed: minimal header, role labels, code blocks and full-width layout.")))))

(defn- inline-effort! [application terminal]
  (let [input (.-mockInput terminal) mouse (.-mockMouse terminal)]
    (.resize terminal 130 32)
    (swap! (:state application)
           #(-> %
                (assoc :notice nil :host-requests []
                       :models [{:provider :fixture :id "luna-example" :thinking-levels [:none :low :medium :high :xhigh :max]}
                                {:provider :fixture :id "second-model" :thinking-levels [:none :high]}]
                       :providers [{:provider :fixture :name "Fixture" :available? true}])
                (assoc-in [:view :session :config :thinking] :medium)
                (assoc-in [:ui :overlay] {:kind :models :title "Models" :provider :fixture :query "" :index 0 :pane :models :token "inline-effort"})))
    (-> (until! terminal #(and (node terminal "effort-medium")
                               (str/includes? (.captureCharFrame terminal) "Effort: Medium")
                               (> (.-screenX (node terminal "model-settings")) (.-screenX (node terminal "dialog-choices"))))
                "Wide terminals must keep effort beside the model list")
        (.then (fn [_] (model-column-edges! application terminal)))
        (.then (fn [_] (capture! terminal "model-effort-wide") (.pressTab input)
                 (until! terminal #(= :effort (get-in @(:state application) [:ui :overlay :pane]))
                         "Tab must go from the model list directly to effort")))
        (.then (fn [_] (.pressArrow input "right")))
        (.then (fn [_]
                 (until! terminal #(and (= :models (get-in @(:state application) [:ui :overlay :kind]))
                                        (= :high (get-in @(:state application) [:ui :overlay :thinking]))
                                        (= :medium (get-in @(:state application) [:view :session :config :thinking]))
                                        (str/includes? (.-plainText (node terminal "effort-high")) "✓"))
                         "Arrow changes must select effort without applying or leaving the model list")))
        (.then (fn [_] (let [button (node terminal "effort-low")]
                         (.click mouse (+ 1 (.-screenX button)) (.-screenY button)))))
        (.then (fn [_]
                 (until! terminal #(str/includes? (.-plainText (node terminal "effort-value")) "Low")
                         "Click must select the effort directly")))
        (.then (fn [_] (.pressTab input)
                 (until! terminal #(= :session (get-in @(:state application) [:ui :overlay :pane]))
                         "Tab must move from effort to apply")))
        (.then (fn [_] (.pressTab input)
                 (until! terminal #(= :default (get-in @(:state application) [:ui :overlay :pane]))
                         "Tab must advance to the default action")))
        (.then (fn [_] (.pressArrow input "up")
                 (until! terminal #(= :session (get-in @(:state application) [:ui :overlay :pane]))
                         "Up must move from default to the session action")))
        (.then (fn [_] (.pressArrow input "down")
                 (until! terminal #(= :default (get-in @(:state application) [:ui :overlay :pane]))
                         "Down must move from session to the default action")))
        (.then (fn [_] (.pressTab input #js {:shift true})
                 (until! terminal #(= :session (get-in @(:state application) [:ui :overlay :pane]))
                         "Shift+Tab must reverse the action order")))
        (.then (fn [_] (.pressTab input #js {:shift true}) (.pressArrow input "left")
                 (until! terminal #(= :none (get-in @(:state application) [:ui :overlay :thinking]))
                         "Left must lower effort when the effort field is active")))
        (.then (fn [_] (.pressTab input #js {:shift true})
                 (until! terminal #(and (= :models (get-in @(:state application) [:ui :overlay :pane]))
                                        (.-focused (node terminal "dialog-input")))
                         "Shift+Tab back to models must restore search focus")))
        (.then (fn [_] (.pressTab input #js {:shift true}) (.pressEnter input)
                 (until! terminal #(and (= :models (get-in @(:state application) [:ui :overlay :pane]))
                                        (.-focused (node terminal "dialog-input")))
                         "Enter from provider navigation must restore model search focus")))
        (.then (fn [_] (.pressArrow input "down")
                 (until! terminal #(and (str/includes? (.-plainText (node terminal "model-selection-summary")) "second-model")
                                        (str/includes? (.-plainText (node terminal "effort-value")) "None"))
                         "Changing models must use that model's supported effort")))
        (.then (fn [_] (.resize terminal 60 20)
                 (until! terminal #(and (node terminal "effort-next")
                                        (> (.-screenY (node terminal "model-settings")) (.-screenY (node terminal "dialog-choices")))
                                        (<= (+ (.-screenY (node terminal "apply-default-model")) 1) 20))
                         "Narrow terminals must fit effort and apply below the list")))
        (.then (fn [_] (let [button (node terminal "effort-next")]
                         (.click mouse (+ 1 (.-screenX button)) (.-screenY button)))))
        (.then (fn [_]
                 (until! terminal #(str/includes? (.-plainText (node terminal "effort-value")) "High")
                         "Compact effort arrows must work")))
        (.then (fn [_] (capture! terminal "model-effort-narrow")
                 (.pressEscape input) (.pressEscape input)
                 (println "Inline effort passed: same-screen selection, keyboard/mouse changes, apply focus, per-model levels and responsive layout."))))))

(defn- composer-interactions! [application terminal]
  (let [input (.-mockInput terminal) mouse (.-mockMouse terminal)
        composer #(node terminal "composer")
        bottom? #(let [box (node terminal "composer-box")]
                   (= (+ (.-screenY box) (.-height box) 2) (.-terminalHeight (.-renderer terminal))))
        above? #(let [menu (node terminal "command-menu")]
                  (and (>= (.-screenY menu) 0)
                       (<= (+ (.-screenY menu) (.-height menu)) (.-screenY (node terminal "composer-box")))))
        initial-y (atom nil)]
    (swap! (:state application)
           #(-> % (assoc :host-requests [] :notice nil)
                (assoc :view (assoc (model/empty-state) :session {:id "composer-test" :name "New session" :cwd "/tmp/project"}))
                (update :ui assoc :overlay nil :draft "" :inspector? false :focus :composer)))
    (.focus (composer))
    (.resize terminal 100 32)
    (-> (until! terminal #(and (.-visible (node terminal "welcome")) (bottom?)) "Fresh composer must stay at the terminal bottom")
        (.then (fn [_] (reset! initial-y (.-screenY (composer))) (capture! terminal "welcome") (.typeText input "/resume")))
        (.then (fn [_]
                 (until! terminal #(and (.-visible (node terminal "command-menu"))
                                        (not (.-visible (node terminal "dialog-layer")))
                                        (str/includes? (.captureCharFrame terminal) "Resume a previous")
                                        (above?) (bottom?) (= @initial-y (.-screenY (composer))))
                         "Commands must open upward without moving the composer")))
        (.then (fn [_] (capture! terminal "inline-commands") (.pressEscape input)
                 (until! terminal #(and (not (.-visible (node terminal "command-menu")))
                                        (= "/resume" (.-plainText (composer)))
                                        (bottom?) (= @initial-y (.-screenY (composer)))) "Escape must preserve the draft and composer position")))
        (.then (fn [_]
                 (.setText (composer) "")
                 (swap! (:state application) assoc-in [:view :entries]
                        [{:id "mouse-message" :kind :message :data {:message/role :assistant :message/content "Read this assistant response, then type again."}}])
                 (until! terminal #(and (not (.-visible (node terminal "welcome")))
                                        (node terminal "row:message:mouse-message")) "Conversation should replace welcome")))
        (.then (fn [_]
                 (let [scroll (node terminal "conversation")]
                   (.click mouse (+ 5 (.-screenX scroll)) (+ 1 (.-screenY scroll))))))
        (.then (fn [_] (.typeText input "jkby")))
        (.then (fn [_]
                 (until! terminal #(and (= "jkby" (.-plainText (composer)))
                                        (= :composer (get-in @(:state application) [:ui :focus])))
                         "Typing after reading must preserve every first character, including navigation letters")))
        (.then (fn [_]
                 (let [scroll (node terminal "conversation")]
                   (.click mouse (+ 5 (.-screenX scroll)) (+ 1 (.-screenY scroll))))))
        (.then (fn [_] (.click mouse (+ 2 (.-screenX (composer))) (.-screenY (composer)))))
        (.then (fn [_] (.typeText input "Z")))
        (.then (fn [_]
                 (until! terminal #(and (= :composer (get-in @(:state application) [:ui :focus]))
                                        (str/includes? (.-plainText (composer)) "Z"))
                         "Clicking the editor must restore app and native focus")))
        (.then (fn [_]
                 (.setText (composer) "/models")
                 (until! terminal #(= "models" (get-in @(:state application) [:ui :overlay :query])) "Command query must stay in the editor")))
        (.then (fn [_] (.pressEnter input)
                 (until! terminal #(and (= :models (get-in @(:state application) [:ui :overlay :kind]))
                                        (= "" (get-in @(:state application) [:ui :draft])))
                         "Enter must execute the selected command and clear its text")))
        (.then (fn [_] (.pressEscape input)
                 (.resize terminal 48 16)
                 (.setText (composer) "/")
                 (until! terminal #(and (above?) (bottom?))
                         "Upward commands must fit a short terminal and keep the composer at bottom")))
        (.then (fn [_] (capture! terminal "inline-narrow")
                 (swap! (:state application) assoc :view (assoc (model/empty-state) :session {:id "short-welcome" :cwd "/tmp/project"}))
                 (until! terminal #(and (.-visible (node terminal "welcome"))
                                        (above?) (bottom?))
                         "Welcome plus upward suggestions must fit a short terminal")))
        (.then (fn [_] (capture! terminal "welcome-narrow-commands") (.pressEscape input)
                 (println "Composer passed: welcome, inline description search, command execution, mouse focus and type-to-compose."))))))

(defn- wheel-follow! [application terminal]
  (let [scroll #(node terminal "conversation")
        at-bottom? #(<= (- (.-scrollHeight (scroll)) (.-height (.-viewport (scroll))) (.-scrollTop (scroll))) 1)
        wheel (fn [direction]
                (.scroll (.-mockMouse terminal) (+ 8 (.-screenX (scroll))) (+ (.-screenY (scroll)) (min 3 (max 0 (dec (.-height (.-viewport (scroll))))))) direction #js {:delayMs 10}))
        message (fn [id text] {:id id :kind :message :data {:message/role :assistant :message/content text}})]
    (.resize terminal 100 24)
    (swap! (:state application)
           #(-> % (assoc :notice nil :host-requests []
                          :view (assoc (model/empty-state) :session {:id "scroll-test" :cwd "/tmp/project"}
                                       :entries (mapv (fn [i] (message (str "line-" i) (str "Recorded response " i))) (range 40))))
                  (update :ui assoc :overlay nil :inspector? false :follow? true :draft "" :scroll-top 0)))
    (-> (until! terminal #(and (at-bottom?) (> (.-scrollHeight (scroll)) 50)) "Long conversation must initially follow the end")
        (.then (fn [_]
                 (letfn [(leave-bottom [remaining]
                           (if (or (zero? remaining) (not (at-bottom?)))
                             (js/Promise.resolve nil)
                             (-> (wheel "up") (.then (fn [_] (.renderOnce terminal)))
                                 (.then (fn [_] (leave-bottom (dec remaining)))))))]
                   (leave-bottom 20))))
        (.then (fn [_]
                 (until! terminal #(and (not (get-in @(:state application) [:ui :follow?]))
                                        (.-visible (node terminal "jump-to-latest")))
                         "Scrolling away must pause following and show a jump action")))
        (.then (fn [_]
                 (swap! (:state application) update-in [:view :entries] conj (message "new" "New content while reading earlier output"))
                 (until! terminal #(and (not (at-bottom?)) (not (get-in @(:state application) [:ui :follow?])))
                         "New output must not pull the reader to the bottom")))
        (.then (fn [_]
                 ;; Native wheel acceleration depends on prior event timing. Scroll
                 ;; until the actual boundary, not an assumed number of wheel ticks.
                 (letfn [(reach-bottom [remaining]
                           (if (or (zero? remaining)
                                   (and (at-bottom?) (get-in @(:state application) [:ui :follow?])))
                             (js/Promise.resolve nil)
                             (-> (wheel "down")
                                 (.then (fn [_] (.renderOnce terminal)))
                                 (.then (fn [_] (reach-bottom (dec remaining)))))))]
                   (reach-bottom 200))))
        (.then (fn [_]
                 (until! terminal #(and (at-bottom?) (get-in @(:state application) [:ui :follow?])
                                        (not (.-visible (node terminal "jump-to-latest"))))
                         #(str "Wheeling to the bottom must resume following and clear the banner "
                               (pr-str {:top (.-scrollTop (scroll)) :height (.-scrollHeight (scroll))
                                        :viewport (.-height (.-viewport (scroll)))
                                        :bar (.-scrollSize (.-verticalScrollBar (scroll)))
                                        :bar-viewport (.-viewportSize (.-verticalScrollBar (scroll)))
                                        :x (.-screenX (scroll)) :y (.-screenY (scroll))
                                        :follow (get-in @(:state application) [:ui :follow?])})))))
        (.then (fn [_] (wheel "down")))
        (.then (fn [_]
                 (until! terminal #(and (get-in @(:state application) [:ui :follow?])
                                        (not (.-visible (node terminal "jump-to-latest"))))
                         "Extra downward wheel events at the bottom must not leave a banner")))
        (.then (fn [_]
                 (swap! (:state application) update-in [:view :entries] conj (message "followed" "Latest content after follow resumes"))
                 (until! terminal #(and (at-bottom?) (str/includes? (.captureCharFrame terminal) "Latest content after follow resumes"))
                         "Further output must follow after manual return to bottom")))
        (.then (fn [_]
                 (swap! (:state application) assoc-in [:view :entries] [(message "short" "Short conversation")])
                 (until! terminal #(<= (.-scrollHeight (scroll)) (.-height (.-viewport (scroll)))) "Short content must settle")))
        (.then (fn [_] (wheel "up")))
        (.then (fn [_]
                 (until! terminal #(and (get-in @(:state application) [:ui :follow?])
                                        (not (.-visible (node terminal "jump-to-latest"))))
                         "Non-scrollable content must not leave follow mode")))
        (.then (fn [_] (println "Wheel following passed: pause, incoming activity, manual bottom return, repeated wheel and short content."))))))

(defn- footer-and-feedback! [application terminal]
  (let [pause #(js/Promise. (fn [resolve] (js/setTimeout resolve 3650)))
        routine {:kind :info :message "Model applied here and saved as default."}]
    (.resize terminal 110 30)
    (swap! (:state application)
           #(-> % (assoc :notice routine :models [{:provider :codex-backend :id "example-model" :context-window 128000}])
                (assoc-in [:ui :overlay] nil)
                (assoc-in [:view :session :config] {:provider :codex-backend :model "example-model" :thinking :medium})
                (assoc-in [:view :entries]
                          [{:id "usage" :kind :message :data {:message/role :assistant :message/content "Done"
                                                             :message/provider-data {:response/usage {:usage/input-tokens 1000 :usage/cached-input-tokens 10000 :usage/output-tokens 1000}}}}])))
    (-> (until! terminal
                #(and (> (.-screenY (node terminal "model-footer")) (.-screenY (node terminal "composer")))
                       (> (.-screenY (node terminal "project-footer")) (.-screenY (node terminal "model-footer")))
                       (str/includes? (.-plainText (node terminal "footer-context")) "12k / 128k · 9%")
                       (= "  codex" (.-plainText (node terminal "footer-provider")))
                       (not (.-visible (node terminal "notice-box")))
                       (not (.-visible (node terminal "notice-details"))))
                "Metadata and reported context belong below the composer; routine feedback must not create an alert")
        (.then (fn [_] (capture! terminal "footer-with-context") (pause)))
        (.then (fn [_]
                 (swap! (:state application) update-in [:view :entries] conj
                        {:id "new-usage" :kind :message
                         :data {:message/role :assistant :message/content "Next reply"
                                :message/provider-data {:response/usage {:usage/total-tokens 64000}}}})
                 (until! terminal #(str/includes? (.-plainText (node terminal "footer-context"))
                                                  "64k / 128k · 50%")
                         "Latest reported total replaces previous context; it is not accumulated")))
        (.then (fn [_]
                 (swap! (:state application) update-in [:view :entries] conj
                        {:id "compacted" :kind :compaction
                         :data {:summary "Reduced context" :usage {:usage/total-tokens 100000}}})
                 (until! terminal #(= "Context —" (.-plainText (node terminal "footer-context")))
                         "Compaction invalidates the old context count; summary usage is not the new context")))
        (.then (fn [_]
                 (until! terminal #(nil? (:notice @(:state application))) "Routine confirmation must expire automatically")))
        (.then (fn [_]
                 (swap! (:state application) assoc :notice routine)
                 (until! terminal #(str/includes? (.-plainText (node terminal "footer-feedback")) "Model applied") "Feedback must appear briefly")))
        (.then (fn [_]
                 (swap! (:state application) assoc :notice {:kind :error :message "Request failed" :data {:reason "fixture"}})
                 (swap! (:state application) assoc-in [:view :entries] [])
                 (until! terminal #(and (.-visible (node terminal "notice-box"))
                                        (.-visible (node terminal "notice-details"))
                                        (= "Context —" (.-plainText (node terminal "footer-context"))))
                         "Errors must retain diagnostics; missing usage must not be shown as zero")))
        (.then (fn [_] (pause)))
        (.then (fn [_]
                 (when-not (= :error (get-in @(:state application) [:notice :kind]))
                   (throw (js/Error. "An old confirmation timer cleared a newer error")))
                 (println "Footer passed: placement, compact provider, reported/unknown context, expiring confirmations and persistent errors."))))))

(defn- assistant-turn-ownership! [application terminal]
  (.resize terminal 110 54)
  (swap! (:state application)
         #(-> %
              (assoc :notice nil :host-requests []
                     :view {:session {:id "ownership" :name "Turn ownership" :cwd "/project"}
                            :entries [{:id "ask" :kind :message :data {:message/role :user :message/content "Explain how retained values work."}}
                                      {:id "work" :kind :message
                                       :data {:message/role :assistant
                                              :message/content [{:part/type :reasoning :text "Inspect the implementation first."}]
                                              :message/tool-calls [{:tool-call/id "read-owner" :tool-call/name "read"}]}}
                                      {:id "reply" :kind :message :data {:message/role :assistant :message/content "Values load on demand."}}
                                      {:id "ask-again" :kind :message :data {:message/role :user :message/content "And after restart?"}}
                                      {:id "reply-again" :kind :message :data {:message/role :assistant :message/content "Durable results remain available."}}]
                            :activities {"read-owner" {:id "read-owner" :kind :capability :name "read" :status :completed :start-seq 1 :content "Read session source"}}
                            :activity-order ["read-owner"] :snapshot-cursor 0})
              (update :ui assoc :overlay nil :inspector? false :follow? true :draft "")))
  (-> (until! terminal
              #(and (str/includes? (.captureCharFrame terminal) "Explain how retained values work.")
                     (str/includes? (.captureCharFrame terminal) "Values load on demand.")
                     (str/includes? (.captureCharFrame terminal) "Durable results remain available.")
                     (node terminal "turn-heading:reasoning:work")
                     (.-visible (node terminal "turn-heading:reasoning:work"))
                     (< (.-screenY (node terminal "turn-heading:reasoning:work"))
                        (.-screenY (node terminal "heading:reasoning:work")))
                     (< (.-screenY (node terminal "turn-heading:reasoning:work"))
                        (.-screenY (node terminal "row:activity:read-owner")))
                     (not (.-visible (node terminal "turn-rule:message:reply")))
                     (.-visible (node terminal "turn-rule:message:ask-again")))
              "The assistant turn must begin before reasoning/tools, with no new divider before its final prose")
      (.then (fn [_] (theme-test/capture! terminal "assistant-turn-ownership")
               (println "Assistant ownership passed: reasoning, tools and final prose share the assistant turn.")))))

(defn- compaction-and-titles! [application terminal]
  (let [sid "status-session" oid "status-operation"
        event! (fn [operation phase]
                 (app/event! application {:type :operation/phase :session-id sid :operation-id operation
                                          :data {:phase phase}}))]
    (swap! (:state application) assoc :notice nil
           :sessions [{:id sid :name "Initial title"} {:id "background" :name "Other title"}]
           :view (model/hydrate {:state {:session {:id sid :name "Initial title" :cwd "/project"}
                                        :operation {:id oid :status :running} :phase :compacting}
                                 :entries [] :cursor 0} []))
    (swap! (:state application) update :ui assoc :overlay nil :inspector? false :draft "")
    (-> (until! terminal #(str/includes? (.-plainText (node terminal "footer-feedback")) "Compacting context")
                "A snapshot taken during compaction must show its actual phase")
        (.then (fn [_] (event! "older-operation" :provider)
                 (until! terminal #(= :compacting (get-in @(:state application) [:view :phase]))
                         "A stale operation phase must not clear current compaction status")))
        (.then (fn [_] (event! oid :provider)
                 (until! terminal #(str/includes? (.-plainText (node terminal "footer-feedback")) "Working")
                         "Compaction completion must return to ordinary work status")))
        (.then (fn [_]
                 (app/event! application {:type :session/named :session-id "background"
                                          :data {:name "Generated elsewhere" :source :auto}})
                 (until! terminal #(and (= "Initial title" (get-in @(:state application) [:view :session :name]))
                                        (= "Generated elsewhere" (:name (second (:sessions @(:state application))))))
                         "A background title must update its own list row, not the active session")))
        (.then (fn [_]
                 (app/event! application {:type :session/named :session-id sid
                                          :data {:name "Generated session title" :source :auto}})
                 (until! terminal #(= "Generated session title" (.-plainText (node terminal "session-title")))
                         "Generated titles must update the header without a refresh")))
        (.then (fn [_]
                 (app/event! application {:type :operation/completed :session-id sid :operation-id oid :data {}})
                 (until! terminal #(= :idle (get-in @(:state application) [:view :phase]))
                         "Settling an operation must clear its phase")))
        (.then (fn [_] (println "Compaction/title UI passed: hydration, phase ownership, resumed work, and session-scoped title updates."))))))

(defn- streaming-layout! [application mounted terminal]
  (let [geometry (fn []
                   (let [title (node terminal "session-title")
                         transcript (node terminal "conversation")
                         composer (node terminal "composer-box")]
                     {:title-y (.-screenY title) :title-height (.-height title)
                      :transcript-y (.-screenY transcript) :transcript-height (.-height transcript)
                      :composer-y (.-screenY composer)}))
        baseline (atom nil)
        frames! (fn frames! [remaining check!]
                  (if (zero? remaining)
                    (js/Promise.resolve nil)
                    (-> (.renderOnce terminal)
                        (.then (fn [_] (check!)
                                 (js/Promise. (fn [resolve] (js/setTimeout resolve 20)))))
                        (.then #(frames! (dec remaining) check!)))))
        check! (fn []
                 (when-not (= @baseline (geometry))
                   (throw (js/Error. (str "Streaming moved the viewport or composer: "
                                         (pr-str @baseline) " -> " (pr-str (geometry)))))))]
    (.resize terminal 100 32)
    (swap! (:state application)
           #(-> % (assoc :notice nil :widgets-by-session {})
                (assoc :view (assoc (model/empty-state)
                                   :session {:id "stream-layout" :name "A session title kept apart from the conversation" :cwd "/project"}
                                   :operation {:id "stream-op" :status :running}
                                   :streams {:operation-id "stream-op" :content "Starting an answer." :reasoning ""}))
                (update :ui assoc :overlay nil :draft "" :attachments [] :inspector? false :follow? true)))
    (view/refresh! mounted)
    (-> (frames! 5 (fn [] nil))
        (.then (fn [_] (reset! baseline (geometry))))
        (.then (fn [_]
                 (reduce (fn [pending chunk]
                           (.then pending
                                  (fn [_]
                                    (swap! (:state application) update-in [:view :streams :content] str chunk)
                                    (view/refresh! mounted)
                                    (frames! 4 check!))))
                         (js/Promise.resolve nil)
                         (concat ["\n\n```clojure\n" "(def x 42)\n" "```\n"
                                  "\n- First item\n" "- Second item\n"]
                                 (map #(str "\n\nParagraph " % ": " (str/join " " (repeat 25 "streamed"))) (range 12))))))
        (.then (fn [_] (frames! 10 check!)))
        (.then (fn [_]
                 (until! terminal #(let [scroll (node terminal "conversation")]
                                     (and (<= (js/Math.abs (- (.-scrollTop scroll)
                                                             (max 0 (- (.-scrollHeight scroll) (.-height (.-viewport scroll)))))) 1)
                                          (str/includes? (.captureCharFrame terminal) "Paragraph 11")))
                         "Native following must keep the end of streamed content visible")))
        (.then (fn [_]
                 (let [{:keys [title-y title-height transcript-y transcript-height composer-y]} (geometry)]
                   (when-not (and (>= (- transcript-y (+ title-y title-height)) 1)
                                  (>= (- composer-y (+ transcript-y transcript-height)) 1))
                     (throw (js/Error. (str "Title and composer must have clear gaps around the transcript: " (pr-str (geometry)))))))
                 (println "Streaming layout passed: stable viewport/title/composer through Markdown growth, with gaps above and below."))))))

(defn- sessions-loading! [application mounted terminal]
  (let [open-delayed! (fn []
                        (let [resolve! (atom nil) reject! (atom nil)
                              response (js/Promise. (fn [resolve reject]
                                                      (reset! resolve! resolve)
                                                      (reset! reject! reject)))
                              pending (with-redefs [app/command! (fn [_ action _]
                                                                 (when-not (= :sessions action)
                                                                   (throw (js/Error. "Unexpected session browser action")))
                                                                 response)]
                                        (screens/open-sessions! mounted))]
                          {:pending pending
                           :resolve! (fn [rows] (swap! (:state application) assoc :sessions rows)
                                       (@resolve! rows))
                           :reject! (fn [] (@reject! (js/Error. "Synthetic list failure")))}))
        first-load (atom nil) old-load (atom nil) new-load (atom nil)]
    (swap! (:state application) assoc :sessions [] :notice nil)
    (reset! first-load (open-delayed!))
    (-> (until! terminal #(str/includes? (.captureCharFrame terminal) "Loading sessions")
                "First session open must show loading while the request is pending")
        (.then (fn [_]
                 (.setText (node terminal "dialog-input") "Saved")
                 ((:resolve! @first-load) [{:id "saved" :name "Saved conversation" :cwd "/project"}])
                 (:pending @first-load)))
        (.then (fn [_]
                 (until! terminal #(and (= :sessions (get-in @(:state application) [:ui :overlay :kind]))
                                        (= "Saved" (get-in @(:state application) [:ui :overlay :query]))
                                        (str/includes? (.captureCharFrame terminal) "Saved conversation")
                                        (not (get-in @(:state application) [:ui :overlay :loading?])))
                         "The first response must populate the open browser and preserve its filter")))
        (.then (fn [_]
                 (swap! (:state application) assoc :sessions [])
                 (reset! old-load (open-delayed!))
                 (reset! new-load (open-delayed!))
                 ((:resolve! @old-load) [])
                 (:pending @old-load)))
        (.then (fn [_]
                 (until! terminal #(true? (get-in @(:state application) [:ui :overlay :loading?]))
                         "A stale response must not settle the newer session browser")))
        (.then (fn [_] ((:reject! @new-load)) (:pending @new-load)))
        (.then (fn [_]
                 (until! terminal #(and (= :sessions (get-in @(:state application) [:ui :overlay :kind]))
                                        (str/includes? (.captureCharFrame terminal) "Synthetic list failure"))
                         "A session-list failure must remain visible in the browser")))
        (.then (fn [_]
                 (reset! new-load (open-delayed!))
                 ((:resolve! @new-load) [])
                 (:pending @new-load)))
        (.then (fn [_]
                 (until! terminal #(str/includes? (.captureCharFrame terminal) "No saved sessions.")
                         "A completed empty list must differ from a loading list")))
        (.then (fn [_]
                 (reset! new-load (open-delayed!))
                 (screens/close-overlay! mounted)
                 ((:resolve! @new-load) [{:id "late" :name "Late response" :cwd "/project"}])
                 (:pending @new-load)))
        (.then (fn [_]
                 (until! terminal #(nil? (get-in @(:state application) [:ui :overlay]))
                         "A late session-list response must not reopen a dismissed screen")))
        (.then (fn [_] (println "Sessions passed: loading, first response, filter preservation, stale responses, failure, empty and dismissal."))))))

(defn- session-timestamps! [application terminal]
  (swap! (:state application)
         #(-> %
              (assoc :notice nil :sessions [{:id "dated" :name "Recent conversation" :cwd "/project"
                                           :last-message-at (js/Date.UTC 2026 8 15 14 45)}
                                          {:id "empty" :name "Empty conversation" :cwd "/project"}])
              (assoc-in [:ui :overlay] {:kind :sessions :title "Sessions" :token "timestamps" :index 0 :query ""})))
  (-> (until! terminal #(let [frame (.captureCharFrame terminal)]
                         (and (str/includes? frame "Last message:") (str/includes? frame "2026")
                              (str/includes? frame "No messages yet")))
              "Session rows must show local message timestamps and an explicit empty state")
      (.then (fn [_] (println "Session timestamps passed: recorded message date and empty state.")))))

(defn- exercise! [terminal]
  (let [application (app/create! {:runtime-root (.cwd js/process) :cwd (.cwd js/process)
                                  :setup? false})
        mounted (view/mount! application (.-renderer terminal) {:theme "default"})]
    (show! application :choices)
    (-> (visible! application terminal)
        (.then (fn []
                 (reduce (fn [pending kind] (.then pending #(menu! application terminal kind)))
                         (js/Promise.resolve nil) [:sessions :models :history :files :pending :choices])))
        (.then #(walk! application terminal "down" 23))
        (.then (fn []
                 (.resize terminal 78 24)
                 (until! terminal
                         #(and (screen-fits? terminal)
                               (selected-visible? application terminal))
                         "Resize hid the selected screen row")))
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
                         #(and (screen-fits? terminal)
                               (selected-visible? application terminal))
                         "Short viewport hid the selected item label")))
        (.then #(walk! application terminal "down" 23))
        (.then #(walk! application terminal "up" 23))
        (.then #(menu! application terminal :choices))
        (.then (fn [_]
                 (.resize terminal 120 40)
                 (inspector-and-host-lifetimes! application terminal)))
        (.then (fn [_] (browser-and-chat! application terminal)))
        (.then (fn [_] (transcript-hierarchy! application terminal)))
        (.then (fn [_] (inline-effort! application terminal)))
        (.then (fn [_] (composer-interactions! application terminal)))
        (.then (fn [_] (footer-and-feedback! application terminal)))
        (.then (fn [_] (wheel-follow! application terminal)))
        (.then (fn [_] (compaction-and-titles! application terminal)))
        (.then (fn [_] (streaming-layout! application mounted terminal)))
        (.then (fn [_] (sessions-loading! application mounted terminal)))
        (.then (fn [_] (session-timestamps! application terminal)))
        (.then (fn [_] (assistant-turn-ownership! application terminal)))
        (.then (fn [] (println "Native TUI passed: full-screen layout, inspector selection ownership, session widgets, render/editor requests and cancelled overlay cleanup.")))
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
            (.then exercise!)
            (.then (fn [_] (theme-test/exercise!))))))

(set! *main-cli-fn* -main)
