(ns arrodes.tui.chrome
  "Welcome, footer, notices, queue and attachment presentation."
  (:require
            [arrodes.catalog :as catalog]
            [arrodes.run :as run]
            [arrodes.tui-model :as model]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]
            [arrodes.tui.context :as c]))

(declare active-widgets render-widgets! token-count expire-notice! render-chrome! render-welcome! render-pending! render-attachments!)

(defn active-widgets [state]
  (let [sid (get-in state [:view :session :id])]
    (sort-by (juxt #(or (:order %) 0) :id)
             (vals (get-in state [:widgets-by-session sid] {})))))


(defn render-widgets! [view]
  (let [widgets (remove #(= :status (:placement %)) (active-widgets (c/state view)))
        signature (vec widgets)]
    (set! (.-visible (:widget-box view)) (boolean (seq widgets)))
    (when (not= signature (:widget-signature @(:local view)))
      (swap! (:local view) assoc :widget-signature signature)
      (w/clear! (:widget-items view))
      (doseq [widget widgets]
        (.add (:widget-items view)
              (w/text (:renderer view) (c/display-content (:content widget))
                      {:width "100%" :maxHeight 3 :fg :text/secondary}))))))


(defn token-count [n]
  (if (>= n 1000) (str (/ (js/Math.round (/ n 100)) 10) "k") (str n)))


(defn expire-notice! [view notice]
  (when (not= notice (:observed-notice @(:local view)))
    (when-let [timer (:notice-timer @(:local view))] (js/clearTimeout timer))
    (swap! (:local view) assoc :observed-notice notice :notice-timer nil)
    (when (and notice (or (string? notice) (= :info (:kind notice)))
               (not (:unknown-outcome? notice)))
      (swap! (:local view) assoc :notice-timer
             (js/setTimeout
              (fn []
                (when (and (not @(:closed? view)) (= notice (:notice (c/state view))))
                  (swap! (:state (:app view)) assoc :notice nil)))
              3500)))))


(defn render-chrome! [view]
  (let [s (c/state view) renderer (:renderer view)
        width (.-terminalWidth renderer) height (.-terminalHeight renderer)
        session (get-in s [:view :session]) config (:config session)
        selected-model (some #(when (and (= (:id %) (:model config))
                                         (= (catalog/provider-id %) (some-> (:provider config) keyword))) %) (:models s))
        usage (run/latest-usage (vec (get-in s [:view :entries])))
        last-usage (when (number? (run/context-tokens usage)) usage)
        window (:context-window selected-model)
        context-percent (when (and (number? window) (pos? window) last-usage)
                          (js/Math.round (* 100 (/ (run/context-tokens last-usage) window))))
        connection (get-in s [:connection :status])
        operation (get-in s [:view :operation])
        running (c/busy? view)
        start (or (:started-at operation) (:created-at operation) (:time operation))
        elapsed (when (and running (number? start))
                  (str " " (max 0 (quot (- (.now js/Date) start) 1000)) "s"))
        phase (cond (= connection :starting) "Connecting"
                    (= connection :disconnected) "Disconnected"
                    (= connection :closing) "Closing"
                    (= :cancelling (:status operation)) "Stopping…"
                    (and running (= :compacting (get-in s [:view :phase]))) "Compacting context…"
                    running (str "Working" elapsed)
                    (= :failed (:status operation)) "Failed"
                    (= :cancelled (:status operation)) "Stopped"
                    (= :interrupted (:status operation)) "Interrupted"
                    :else "Idle")
        status-widgets (->> (active-widgets s)
                            (filter #(= :status (:placement %)))
                            (map #(-> (c/display-content (:content %))
                                      (str/replace #"\s+" " ")))
                            (remove str/blank?))
        notice (:notice s)
        notice-text (if (map? notice)
                      (str (when (:unknown-outcome? notice) "Outcome unknown; inspect before resubmitting. ")
                           (:message notice))
                      notice)]
    (doseq [node [(:composer-box view) (:footer view) (:metadata view)]]
      (set! (.-visible node) true))
    (w/content! (:session-title view) (or (:name session) "Untitled session"))
    (expire-notice! view notice)
    (w/content! (:footer-status view)
                (str (or (:model config) "Choose a model") " · "
                     (name (keyword (or (:thinking config) "none")))))
    (w/content! (:provider-status view)
                (when (:provider config)
                  (str "  " (case (keyword (:provider config))
                              (:codex-backend :openai-codex) "codex"
                              (name (keyword (:provider config)))))))
    (set! (.-visible (:provider-status view)) (and (some? (:provider config)) (>= width 70)))
    (w/content! (:context-status view)
                (str (if (< width 70) "Ctx " "Context ")
                     (if last-usage
                       (if (and context-percent (< width 70)) (str context-percent "%")
                           (str (token-count (run/context-tokens last-usage))
                                (when (and (number? window) (pos? window))
                                  (str " / " (token-count window) " · " context-percent "%"))))
                       "—")))
    (w/content! (:project-status view)
                (str (c/basename (or (:cwd session) (get-in view [:app :options :cwd])))
                     (when (:branch s) (str " · " (:branch s)))))
    (w/paint! (:footer-status view) :fg :text/primary)
    (let [routine? (and notice (or (string? notice) (= :info (:kind notice)))
                        (not (:unknown-outcome? notice)))
          status (cond routine? notice-text
                       (not= phase "Idle") phase
                       (seq (filter #(contains? #{:queued :running :cancelling} (:status %)) (get-in s [:view :jobs])))
                       (str (count (filter #(contains? #{:queued :running :cancelling} (:status %)) (get-in s [:view :jobs])))
                            " background jobs · /jobs")
                       (seq status-widgets) (str/join " · " status-widgets)
                       :else "")]
      ;; Routine feedback occupies the quiet footer briefly, never an alert row.
      (w/content! (:footer-keys view) (or status ""))
      (set! (.-maxWidth (:footer-keys view)) (if (< width 70) "45%" "55%"))
      (set! (.-visible (:notice-box view)) (and (boolean (seq notice-text)) (not routine?)))
      (set! (.-visible (:notice-detail view))
            (and (not routine?) (boolean (or (:data notice) (:unknown-outcome? notice)))))
      (set! (.-visible (:notice-close view)) (not routine?)))
    (w/content! (:notice-text view) (or notice-text ""))
    (w/paint! (:notice-text view) :fg (if (= :error (:kind notice)) :status/error :text/secondary))
    (set! (.-visible (:attachment-row view)) (and (>= height 14) (seq (get-in s [:ui :attachments]))))
    (set! (.-height (:header view)) 1)
    (set! (.-placeholder (:composer view))
          (cond (not= connection :ready) "Waiting for the core; your draft is preserved..."
                running "Steer the current run, or Ctrl+Q to queue a follow-up..."
                :else "Ask a question or describe a change..."))
    (let [draft (get-in s [:ui :draft] "")]
      (when (not= draft (.-plainText (:composer view)))
        (swap! (:local view) assoc :syncing-editor? true)
        (try (.setText (:composer view) draft)
             (finally (swap! (:local view) assoc :syncing-editor? false)))))
    (set! (.-height (:composer view))
          (max 1 (min (if (< height 18) 2 7) (max 2 (.-virtualLineCount (:composer view))))))
    (w/paint! (:composer-box view) :borderColor (if (= :composer (get-in s [:ui :focus])) :ui/accent :border/default))))


(defn render-welcome! [view]
  (let [s (c/state view) rows (c/row-list view)
        empty? (empty? rows)
        height (.-terminalHeight (:renderer view))
        sid (get-in s [:view :session :id])
        recent (vec (take (if (< height 24) 0 3) (remove #(= sid (:id %)) (:sessions s))))
        signature [sid recent height]]
    (set! (.-visible (:welcome view)) (and empty? (not (get-in s [:ui :inspector?]))))
    (set! (.-visible (:body view)) (or (not empty?) (get-in s [:ui :inspector?])))
    ;; Only the welcome screen needs a spacer. The transcript itself fills the
    ;; remaining space, independent of the size of a partially streamed answer.
    (set! (.-visible (:spacer view)) (not (.-visible (:body view))))
    (set! (.-height (:welcome view)) (if (< height 24) 5 (+ 7 (count recent))))
    (when (and empty? (not= signature (:welcome-signature @(:local view))))
      (swap! (:local view) assoc :welcome-signature signature)
      (w/clear! (:recent-sessions view))
      (when (seq recent)
        (.add (:recent-sessions view) (w/text (:renderer view) "RECENT SESSIONS" {:height 1 :fg :text/dim}))
        (doseq [session recent]
          (.add (:recent-sessions view)
                (w/button (:renderer view) (str "↶ " (or (:name session) "Untitled session"))
                          #(c/fire! view :switch-session {:id (:id session)})
                          {:height 1 :width "100%" :truncate true :wrapMode "none" :fg :text/secondary})))))))


(defn render-pending! [view]
  (let [items (get-in (c/state view) [:view :queue])
        signature items]
    (set! (.-visible (:pending view)) (boolean (seq items)))
    (set! (.-visible (:pending-more view)) (> (count items) 3))
    (w/content! (:pending-more view) (str "[" (- (count items) 3) " more pending - manage]"))
    (when (not= signature (:pending-signature @(:local view)))
      (swap! (:local view) assoc :pending-signature signature)
      (w/clear! (:pending-items view))
      (doseq [item (take 3 items)]
        (let [row (w/box (:renderer view) {:height 1 :width "100%" :flexDirection "row"})
              content (model/text-content (:content item))
              label (w/text (:renderer view)
                            (str (if (= :steering (keyword (:kind item))) "Steering: " "Follow-up: ")
                                 (str/replace content #"\s+" " "))
                            {:flexGrow 1 :flexShrink 1 :height 1 :wrapMode "none" :truncate true :fg :text/secondary})
              edit (w/button (:renderer view) "[Edit]"
                             #(c/action! :input-dialog! view "Edit pending message" content
                                             (fn [text] (c/invoke! view :queue-edit {:id (:id item) :text text}))
                                             "Edits the pending item atomically. Image attachments are preserved.")
                             {:width 8})
              drop (w/button (:renderer view) "[Drop]" #(c/fire! view :queue-drop {:id (:id item)})
                             {:width 6 :fg :text/secondary})]
          (w/add! row label edit drop)
          (.add (:pending-items view) row))))))


(defn render-attachments! [view]
  (let [items (get-in (c/state view) [:ui :attachments])]
    (when (not= items (:attachment-signature @(:local view)))
      (swap! (:local view) assoc :attachment-signature items)
      (w/clear! (:attachment-items view))
      (doseq [item items]
        (let [path (or (:path item) (:name item))
              button (w/button (:renderer view) (str "[" (c/basename path) " x]")
                               #(c/ui! view update :attachments
                                     (fn [items] (vec (remove (fn [x] (= path (or (:path x) (:name x)))) items))))
                               {:fg :text/secondary :marginRight 1})]
          (.add (:attachment-items view) button))))))
