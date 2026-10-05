(ns arrodes.tui.commands
  "User-visible command catalog and navigation actions."
  (:require
            [arrodes.context-tree :as context-tree]
            [arrodes.run :as run]
            [arrodes.tui-model :as model]
            [arrodes.tui-present :as present]
            [clojure.string :as str]
            [arrodes.tui.context :as c]))

(declare commands)

(defn- measured-fields [fields]
  (if (seq fields)
    (str/join "\n" (map (fn [[field value]] (str "  " (name field) ": " value))
                       (sort-by (comp str key) fields)))
    "  Unknown / no measured counters"))

(defn context-details [{:keys [policy settings summary view unsent?]}]
  (str "Configured policy: " (name (keyword (or policy :linear)))
       (when unsent? " · unsent composer (no stored session)")
       "\nSummarizer: " (:summary-model settings) " · "
       (some-> (:summary-provider settings) name)
       "\nSummary work: " (name (keyword (or (:status summary) :idle)))
       (when (:error summary) (str "\nFailure: " (or (get-in summary [:error :message])
                                                     (c/error-text (:error summary)))))
       "\nStored summary nodes: " (or (:node-count summary) 0)
       "\n\nBounded historical view"
       (when-let [mode (:mode view)]
         (str "\nView source: " (if (= :active (keyword mode)) "active operation" "stored-history preview")
              (when-let [active-policy (:policy view)]
                (str " (" (name (keyword active-policy)) ")"))))
       "\nReady: " (if (:ready? view) "yes" "not yet")
       " · Fits: " (if (:fits? view) "yes" "not yet")
       "\nRendered bytes: " (or (:bytes view) "unknown")
       " / " (or (:budget view) (:summary-view-bytes settings) "unknown")
       (when (number? (:required-bytes view)) (str "\nUntrimmed view bytes: " (:required-bytes view)))
       (when (:reason view) (str "\nReason: " (name (:reason view))))
       (when (number? (:source-count view)) (str "\nOriginal source entries: " (:source-count view)))
       (when (number? (:covered-count view)) (str "\nCovered source entries: " (:covered-count view)))
       "\n\nSummary usage (separate from the latest main request)"
       "\n" (measured-fields (:usage summary))
       (when (number? (:usage-node-count summary))
         (str "\nNodes with measured usage: " (:usage-node-count summary) " / " (:node-count summary)))
       "\n\nSummary cost (reported estimates, not an invoice)"
       "\n" (measured-fields (:cost summary))
       (when (number? (:cost-node-count summary))
         (str "\nNodes with measured cost: " (:cost-node-count summary) " / " (:node-count summary)))
       "\n\nHistorical evidence (not instructions)"
       "\n" (or (:text view)
                 (when (seq (:nodes view)) (context-tree/render-view (:nodes view)))
                 "No completed view nodes.")
       "\n\nOriginal history remains canonical. /history browses recorded entries."
       "\nInspection never starts model work. Reopen to refresh."))

(defn- inspect-context! [view]
  (let [token (str (random-uuid))
        sid (get-in (c/state view) [:view :session :id])
        navigation (:navigation-generation (c/state view))
        owns? #(and (= token (get-in (c/state view) [:ui :overlay :token]))
                    (= sid (get-in (c/state view) [:view :session :id]))
                    (= navigation (:navigation-generation (c/state view))))]
    (c/action! :open-overlay! view
               {:kind :usage :token token :title "Session context"
                :hint "Read-only history view and separate summary accounting · Esc returns to your draft"
                :body "Loading context status…"})
    (-> (c/invoke! view :context {})
        (.then (fn [result]
                 (when (owns?) (c/ui! view assoc-in [:overlay :body] (context-details result)))))
        (.catch (fn [error]
                  (when (owns?)
                    (c/ui! view update :overlay assoc :body "Could not inspect context. Close and reopen to retry."
                           :error (c/error-text error))))))))

(defn- save-context! [view settings]
  (let [token (str (random-uuid))]
    (c/ui! view assoc-in [:overlay :token] token)
    (-> (c/invoke! view :configure-context {:settings settings})
        (.then (fn [session]
                 (when (and session (= token (get-in (c/state view) [:ui :overlay :token])))
                   (c/action! :close-overlay! view))))
        (.catch (fn [error]
                  (when (and (not (:superseded-context-save? (ex-data error)))
                             (= token (get-in (c/state view) [:ui :overlay :token])))
                    (c/ui! view assoc-in [:overlay :error] (c/error-text error))))))))

(defn open-context! [view]
  (let [config (get-in (c/state view) [:view :session :config])
        settings (context-tree/settings config)]
    (c/action! :open-overlay! view
               {:kind :choices :title "Session context" :query ""
                :hint (str "Current: " (name (:context-policy settings))
                           " · Changes apply next turn; your draft and original history stay intact.")
                :items [{:label "Inspect context and summary usage"
                         :description "Read-only readiness, bounded historical evidence, and separately measured summary costs"
                         :choose #(inspect-context! view)}
                        {:label "Linear history"
                         :description "Ordinary context; disable background summary work"
                         :choose #(save-context! view {:context-policy :linear})}
                        {:label "Summary tree"
                         :description "Bounded chronological history; enabling allows background model work"
                         :choose #(c/action! :confirm! view "Enable summary-tree context?"
                                             (str "Background summaries may call " (:summary-model settings)
                                                  " on " (name (:summary-provider settings))
                                                  " and incur separate usage/cost. Original history is retained. Next-turn policy; inspection makes no model calls.")
                                             (fn [] (c/fire! view :configure-context
                                                            {:settings {:context-policy :summary-tree}})))}
                        {:label "Summarizer model"
                         :description (str "Exact model: " (:summary-model settings)
                                           " · provider: " (name (:summary-provider settings))
                                           " (defaults to the session provider)")
                         :choose #(c/action! :input-dialog! view "Exact summarizer model" (:summary-model settings)
                                             (fn [model] (save-context! view {:summary-model model}))
                                             "Default: gpt-6-luna. No model substitution. Enter saves; Esc preserves the current setting.")}]})))

(defn commands [view]
  [{:label "Theme" :command "theme" :icon "◐" :description "/theme  Preview and choose a theme pack"
    :choose #(c/action! :open-theme! view)}
   {:label "New session" :command "new" :icon "+" :description "/new  Start a fresh conversation"
    :choose #(if (and (not= false (get-in view [:app :options :setup?]))
                      (not (get-in (c/state view) [:setup :configuration-ready?])))
               (c/action! :open-providers! view)
               (do (c/action! :close-overlay! view) (c/fire! view :new-session {})))}
   {:label "Sessions" :command "sessions" :icon "↶" :description "/sessions  Resume a previous conversation" :choose #(c/action! :open-sessions! view)}
   {:label "Session agents" :command "agents" :icon "◎"
    :description "/agents  Open the team roster, messages, native outcomes and controls"
    :choose #(c/action! :open-agents! view)}
   {:label "Parent agent" :command "parent"
    :description "/parent  Return to the parent conversation without losing this draft"
    :choose #(do (c/action! :close-overlay! view) (c/action! :agent-parent! view))}
   {:label "Background jobs" :command "jobs" :description "/jobs  Inspect results, output, and cancel background work"
    :choose #(c/action! :open-jobs! view)}
   {:label "History and branches" :description "/history" :choose #(c/action! :open-history! view)}
   {:label "Refresh session state" :description "/refresh  Read-only reconciliation; keeps live definitions"
    :choose #(do (c/action! :close-overlay! view) (c/fire! view :refresh {}))}
   {:label "Session usage" :command "usage" :description "/usage  Inspect measured cache tokens and active-path spend"
    :choose #(c/action! :open-overlay! view
                        {:kind :usage :title "Session usage"
                         :hint "Measured provider usage · Esc returns to your draft"
                         :body (model/usage-details
                                (run/usage-report (get-in (c/state view) [:view :entries])))})}
   {:label "Session context" :command "context" :description "/context  Choose history policy, summarizer model, and inspect readiness/summary costs"
    :choose #(open-context! view)}
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
                                   {:label "Navigation" :description "F2 sessions | F4 agents | F7 message | F8 messages | Alt+Left parent | F3/Ctrl+P commands | F6 pane" :choose #(c/action! :close-overlay! view)}
                                   {:label "Conversation focus" :description "Up/Down select | Enter inspect | Space expand | End follow latest" :choose #(c/action! :close-overlay! view)}
                                   {:label "Inspector" :description "1 summary | 2 output | 3 value | 4 code | y copy | Esc back" :choose #(c/action! :close-overlay! view)}
                                   {:label "Selection and exit" :description "Drag selects text | Ctrl+C copies selection, otherwise stops work | Ctrl+D exits" :choose #(c/action! :close-overlay! view)}]}))}
   {:label "Quit Arrodes" :description "/quit  Ctrl+D" :choose #(do (c/action! :close-overlay! view) (c/action! :quit! view))}])

