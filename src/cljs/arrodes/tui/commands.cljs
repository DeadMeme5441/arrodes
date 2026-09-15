(ns arrodes.tui.commands
  "User-visible command catalog and navigation actions."
  (:require
            [arrodes.tui-present :as present]
            [clojure.string :as str]
            [arrodes.tui.context :as c]))

(declare commands)

(defn commands [view]
  [{:label "Theme" :command "theme" :icon "◐" :description "/theme  Preview and choose a theme pack"
    :choose #(c/action! :open-theme! view)}
   {:label "New session" :command "new" :icon "+" :description "/new  Start a fresh conversation"
    :choose #(if (and (not= false (get-in view [:app :options :setup?]))
                      (not (get-in (c/state view) [:setup :configuration-ready?])))
               (c/action! :open-providers! view)
               (do (c/action! :close-overlay! view) (c/fire! view :new-session {})))}
   {:label "Sessions" :command "sessions" :icon "↶" :description "/sessions  Resume a previous conversation" :choose #(c/action! :open-sessions! view)}
   {:label "History and branches" :description "/history" :choose #(c/action! :open-history! view)}
   {:label "Refresh session state" :description "/refresh  Read-only reconciliation; keeps live definitions"
    :choose #(do (c/action! :close-overlay! view) (c/fire! view :refresh {}))}
   {:label "Pending messages" :description "/pending  Edit or drop queued input"
    :choose #(c/action! :open-overlay! view {:kind :pending :title "Pending messages" :query ""
                                  :hint "Enter edits; Delete drops a still-pending message."})}
   {:label "Manage attachments" :description "/attachments"
    :choose (fn []
              (c/action! :open-overlay!
               view
               {:kind :choices :title "Remove an attachment" :query ""
                :items (mapv (fn [item]
                               {:label (str "Remove " (or (:name item) (c/basename (:path item))))
                                :description (:path item)
                                :choose (fn []
                                          (c/ui! view update :attachments
                                               (fn [items] (vec (remove (fn [x] (= (:path x) (:path item))) items))))
                                          (c/action! :close-overlay! view))})
                             (get-in (c/state view) [:ui :attachments]))}))}
   {:label "Providers" :command "providers" :icon "◇" :search-text "setup login accounts" :description "/providers  Connect and manage accounts" :choose #(c/action! :open-providers! view)}
   {:alias? true :label "Provider setup" :description "/setup" :choose #(c/action! :open-providers! view)}
   {:alias? true :label "Provider login" :description "/login" :choose #(c/action! :open-providers! view)}
   {:label "Choose model" :command "models" :icon "◉" :description "/models  Choose provider, model and reasoning" :choose #(c/action! :open-models! view)}
   {:label "Refresh model catalog" :description "/refresh-models" :choose #(do (c/action! :close-overlay! view) (c/fire! view :models {:refresh? true}))}
   {:label "Thinking level" :description "/thinking"
    :choose
    (fn []
      (let [s (c/state view) config (get-in s [:view :session :config])
            current (some (fn [m] (when (and (= (:id m) (:model config))
                                             (= (keyword (:provider m)) (keyword (:provider config)))) m)) (:models s))
            levels (or (seq (:thinking-levels current)) [:none :low :medium :high :xhigh :max])]
        (c/action! :open-overlay! view {:kind :choices :title "Thinking level" :query ""
                             :items (mapv (fn [level]
                                            {:label (name level) :description "Explicit reasoning selection"
                                             :choose (fn []
                                                       (-> (c/invoke! view :select-model (assoc config :thinking level :scope :session))
                                                           (.then #(c/action! :close-overlay! view))
                                                           (.catch (fn [_] nil))))}) levels)})))}
   {:label "Rename session" :description "/rename" :choose
    #(c/action! :input-dialog! view "Rename session" (get-in (c/state view) [:view :session :name])
                    (fn [name] (c/invoke! view :rename-session {:name name})) "Enter saves; Esc preserves the current name.")}
   {:label "Attach project file" :description "/attach  @" :choose #(c/action! :open-files! view nil)}
   {:label "Continue session" :description "/continue" :choose #(do (c/action! :close-overlay! view) (c/fire! view :continue {}))}
   {:label "Compact context" :description "/compact" :choose #(do (c/action! :close-overlay! view) (c/fire! view :compact {}))}
   {:label "Evaluate Clojure" :description "/eval  Expert input; not the default composer" :choose
    #(c/action! :input-dialog! view "Evaluate Clojure" "" (fn [source] (c/invoke! view :submit {:mode :evaluate :text source}))
                    "Trusted local execution. Shift+Enter adds a line; Enter evaluates. Effects are not rolled back.")}
   {:label "Reset live environment" :description "/reload  Definitions and live objects are lost" :choose
    #(c/action! :confirm! view "Reset the live environment?" "Durable history stays. Live definitions are discarded."
               (fn [] (c/fire! view :reload {})))}
   {:label "Reconnect to core" :description "/reconnect  Never repeats an interrupted mutation" :choose
    #(c/action! :confirm! view "Reconnect to the core?" "The owned JVM is restarted; live definitions are lost."
               (fn [] (c/fire! view :reconnect {})))}
   {:label "Copy conversation" :description "/copy" :choose
    #(do (c/copy! view (str/join "\n\n" (map (fn [row]
                                             (str (present/row-title row) "\n"
                                                  (or (present/inline-content row true) ""))) (c/row-list view))))
         (c/action! :close-overlay! view))}
   {:label "Export HTML" :description "/export  Local file only; no sharing" :choose
    #(c/action! :input-dialog! view "Export conversation as HTML" "arrodes-session.html"
                    (fn [path] (c/invoke! view :export {:path path :format "html"}))
                    "Writes an explicit local export. It does not upload or share your session.")}
   {:label "Show or hide reasoning" :description "/reasoning" :choose
    #(do (c/ui! view update :show-reasoning? not) (c/action! :close-overlay! view))}
   {:label "Expand activity" :description "/expand" :choose
    #(do (c/ui! view assoc :expanded (into {} (map (juxt :id (constantly true)) (c/row-list view)))) (c/action! :close-overlay! view))}
   {:label "Collapse activity" :description "/collapse" :choose #(do (c/ui! view assoc :expanded {}) (c/action! :close-overlay! view))}
   {:label "Delete session" :description "/delete  Requires confirmation" :choose
    #(c/action! :confirm! view "Delete this session?" "Permanently removes this session's stored history and retained results."
               (fn [] (c/fire! view :delete-session {:id (get-in (c/state view) [:view :session :id])})))}
   {:label "Keyboard help" :description "/help  F1"
    :choose
    (fn []
      (c/action! :open-overlay! view {:kind :help :title "Keyboard" :query ""
                           :hint "Enter sends or steers. Ctrl+Q queues a follow-up. Escape closes a view before cancelling work."
                           :items [{:label "Composer" :description "Shift+Enter newline | @ files | / commands | Up/Down prompt history" :choose #(c/action! :close-overlay! view)}
                                   {:label "Navigation" :description "F2 sessions | F3 or Ctrl+P commands | F6 next pane | PgUp/PgDn scroll" :choose #(c/action! :close-overlay! view)}
                                   {:label "Conversation focus" :description "Up/Down select | Enter inspect | Space expand | End follow latest" :choose #(c/action! :close-overlay! view)}
                                   {:label "Inspector" :description "1 summary | 2 output | 3 value | 4 code | y copy | Esc back" :choose #(c/action! :close-overlay! view)}
                                   {:label "Selection and exit" :description "Drag selects text | Ctrl+C copies selection, otherwise stops work | Ctrl+D exits" :choose #(c/action! :close-overlay! view)}]}))}
   {:label "Quit Arrodes" :description "/quit  Ctrl+D" :choose #(do (c/action! :close-overlay! view) (c/action! :quit! view))}])

