(ns arrodes.tui-view
  "Composition root: mount, render scheduling and native renderer lifecycle."
  (:require [arrodes.tui-app :as app]
            [arrodes.catalog :as catalog]
            [arrodes.tui-present :as present]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]
            [arrodes.tui.chrome :as chrome]
            [arrodes.tui.context :as c]
            [arrodes.tui.input :as input]
            [arrodes.tui.inspection :as inspection]
            [arrodes.tui.models :as models]
            [arrodes.tui.screens :as screens]
            [arrodes.tui.transcript :as transcript]
            [arrodes.tui.themes :as themes]
            [arrodes.tui.theme-picker :as theme-picker]
            [arrodes.tui.palette :as palette]))

(declare refresh! schedule! frame! mount! destroy!)

(defn refresh! [view]
  (when-not @(:closed? view)
    (let [session-id (get-in (c/state view) [:view :session :id])
          switching? (not= session-id (:rendered-session @(:local view)))]
      (when switching?
        (swap! (:local view) assoc :rendered-session session-id :anchor nil :inspector-signature nil
               :restore-scroll (get-in (c/state view) [:ui :scroll-top] 0)))
      (theme-picker/sync! view)
      (chrome/render-chrome! view)
      (transcript/render-rows! view)
      (chrome/render-welcome! view)
      (chrome/render-pending! view)
      (chrome/render-attachments! view)
      (chrome/render-widgets! view)
      (inspection/render-inspector! view)
      (screens/render-host-request! view)
      (screens/render-overlay! view)
      (.requestRender (:renderer view)))))


(defn schedule! [view]
  (when (and (not @(:closed? view)) (nil? (:render-timer @(:local view))))
    (swap! (:local view) assoc :render-timer
           (js/setTimeout (fn []
                            (swap! (:local view) assoc :render-timer nil)
                            (try (refresh! view)
                                 (catch :default error
                                   (c/notify! view (str "Display error: " (c/error-text error)) :error))))
                          16))))


(defn frame! [view]
  (when-not @(:closed? view) (transcript/size-conversation! view))
  (when (and (not @(:closed? view)) (.-visible (:modal-sidebar view)))
    (when-let [id (:sidebar-choice @(:local view))]
      (when-let [row (.findDescendantById (:modal-sidebar view) id)]
        (let [scroll (:modal-sidebar view) viewport (.-viewport scroll)
              top (.-y row) bottom (+ top (.-height row))
              delta (cond (< top (.-y viewport)) (- top (.-y viewport))
                          (> bottom (+ (.-y viewport) (.-height viewport))) (- bottom (+ (.-y viewport) (.-height viewport)))
                          :else 0)]
          (swap! (:local view) dissoc :sidebar-choice)
          (when-not (zero? delta) (.scrollBy scroll delta))))))
  (when (and (not @(:closed? view)) (.-visible (:modal-shade view)))
    (when-let [choice (:modal-scroll-choice @(:local view))]
      (let [scroll (:modal-list view)
            viewport (.-viewport scroll)]
        ;; Intrinsic column sizing can undercount overflowing wrapped rows in a
        ;; short viewport. The measured final row defines the actual scroll extent.
        (let [children (.getChildren scroll)]
          (when-let [last-row (aget children (dec (alength children)))]
            (let [content (.-content scroll)
                  height (- (+ (.-y last-row) (.-height last-row)) (.-y content))]
              (when (not= height (:modal-content-height @(:local view)))
                (swap! (:local view) assoc :modal-content-height height)
                (set! (.-minHeight content) height)))))
        (when-let [row (.findDescendantById scroll choice)]
          (let [target (if (> (.-height row) (.-height viewport))
                         (.findDescendantById row (str choice "-label"))
                         row)
                ;; Culled rows have stale painted screen coordinates; use layout bounds.
                top (.-y target)
                bottom (+ top (.-height target))
                viewport-top (.-y viewport)
                viewport-bottom (+ viewport-top (.-height viewport))
                delta (cond (< top viewport-top) (- top viewport-top)
                            (> bottom viewport-bottom) (- bottom viewport-bottom)
                            :else 0)]
            (if (zero? delta)
              (swap! (:local view) dissoc :modal-scroll-choice)
              (do
                (.scrollBy scroll delta)
                ;; Scroll limits can settle a frame after wrapped row measurements.
                (.requestRender (:renderer view)))))))))
  (when (and (not @(:closed? view)) (.-visible (:conversation view))
             (= (get-in (c/state view) [:view :session :id])
                (:rendered-session @(:local view))))
    (let [scroll (:transcript view) renderer (:renderer view)]
      (when-some [position (:restore-scroll @(:local view))]
        (swap! (:local view) dissoc :restore-scroll)
        (.scrollTo scroll position))
      (when (:manual-scroll? @(:local view)) (swap! (:local view) assoc :anchor nil))
      (when-let [{:keys [id offset]} (:anchor @(:local view))]
        (swap! (:local view) assoc :anchor nil)
        (when-let [record (get @(:records view) id)]
          (let [delta (- (.-screenY (:root record)) (.-screenY (.-viewport scroll)) offset)]
            (when (not (zero? delta)) (.scrollBy scroll delta)))))
      (when (and (get-in (c/state view) [:ui :follow?] true) (not (.-hasSelection renderer)))
        (let [target (max 0 (- (.-scrollHeight scroll) (.-height (.-viewport scroll))))]
          (when (> (js/Math.abs (- target (.-scrollTop scroll))) 1)
            (.scrollTo scroll target))))
      ;; Wheel/scrollbar movement can reach the end without using Jump to latest.
      ;; Reconcile after native scrolling and layout, never from pre-scroll bounds.
      (when (and (not (get-in (c/state view) [:ui :follow?] true))
                 (transcript/transcript-at-bottom? view) (not (.-hasSelection renderer)))
        (swap! (:local view) assoc :anchor nil)
        (set! (.-stickyScroll scroll) true)
        (c/ui! view assoc :follow? true))
      (swap! (:local view) dissoc :manual-scroll?)
      (let [position (.-scrollTop scroll)]
        (when (not= position (get-in (c/state view) [:ui :scroll-top]))
          (c/ui! view assoc :scroll-top position))))))


(defn mount!
  "Mount the real view on an OpenTUI renderer; the controller remains renderer-independent."
  [application renderer options]
  (let [theme-manager (or (:theme-manager options) (themes/create (merge (:options application) options)))
        _ (palette/attach! renderer theme-manager)
        view-ref (atom nil)
        local (atom {:rows [] :prompt-history []})
        closed? (atom false)
        root (w/box renderer {:id "arrodes" :width "100%" :height "100%" :backgroundColor :surface/base})
        header (w/box renderer {:height 1 :width "100%" :flexDirection "row" :paddingX (:gutter w/layout)
                                :border false})
        session-title (w/text renderer "Untitled session" {:id "session-title" :flexGrow 1 :flexShrink 1 :height 1
                                                            :fg :text/secondary :truncate true :wrapMode "none"})
        body (w/box renderer {:width "100%" :flexDirection "row" :height 1 :flexGrow 0 :flexShrink 1 :minHeight 1 :overflow "hidden"})
        conversation (w/box renderer {:flexGrow 1 :flexShrink 1 :minWidth 1 :height "100%"})
        transcript (w/scrollbox renderer {:id "conversation" :width "100%" :height "100%" :stickyScroll true :stickyStart "bottom"
                                          :contentOptions {:paddingX 1 :paddingBottom 1 :flexDirection "column"}
                                          :onMouseDown (fn [_] (when-let [view @view-ref] (c/ui! view assoc :keyboard-navigation? false)
                                                                    (input/focus! view :transcript)))
                                          :onMouseScroll (fn [_]
                                                           (when-let [view @view-ref]
                                                             (swap! (:local view) assoc :anchor nil :manual-scroll? true)
                                                             (set! (.-stickyScroll (:transcript view)) false)
                                                             (c/ui! view assoc :follow? false)))})
        welcome (w/box renderer {:id "welcome" :width "100%" :paddingX 2 :paddingTop 1})
        welcome-title (w/text renderer "ARRODES" {:height 1 :fg :ui/accent})
        welcome-hint (w/text renderer "What are we working on?" {:height 1 :fg :text/primary})
        recent-sessions (w/box renderer {:id "recent-sessions" :width "100%" :marginTop 1})
        welcome-help (w/text renderer "/ commands   @ attach context   F2 resume a session"
                             {:height 1 :truncate true :wrapMode "none" :fg :text/dim :marginTop 1})
        inspector (w/box renderer {:id "inspector" :visible false :width "100%" :height "100%" :paddingX 2
                                   :border false})
        inspector-header (w/box renderer {:width "100%" :height 2 :flexDirection "row"})
        inspector-title (w/text renderer "INSPECT" {:flexGrow 1 :flexShrink 1 :height 1 :truncate true :wrapMode "none" :fg :ui/accent})
        inspector-close (w/button renderer "← Back" (fn [] (inspection/close-inspector! @view-ref)) {:width 8})
        inspector-tab-row (w/box renderer {:height 2 :width "100%" :flexDirection "row" :gap 1})
        inspector-tabs (into {} (map (fn [[tab label]]
                                       [tab (w/button renderer label
                                                      (fn []
                                                        (c/ui! @view-ref assoc :inspect-tab tab)
                                                        (input/focus! @view-ref :inspector)
                                                        (.scrollTo (:inspector-scroll @view-ref) 0))
                                                      {:width 8})])
                                     [[:summary "1 Sum"] [:output "2 Output"] [:value "3 Value"] [:code "4 Code"]]))
        inspector-scroll (w/scrollbox renderer {:id "inspection" :width "100%"
                                                :onMouseDown (fn [_] (c/ui! @view-ref assoc :keyboard-navigation? false) (input/focus! @view-ref :inspector))})
        inspector-output (w/text renderer "" {:width "100%"})
        inspector-actions (w/box renderer {:width "100%" :flexDirection "row" :height 2 :marginTop 1})
        inspector-copy (w/button renderer "[Copy]" (fn [] (c/copy! @view-ref (or (:inspector-text @local) ""))) {:width 8})
        inspector-prev (w/button renderer "[Prev]"
                                 (fn []
                                   (let [s (c/state @view-ref)]
                                     (inspection/load-artifact-page! @view-ref
                                                          (get-in s [:ui :inspection :descriptor :artifact-id])
                                                          (max 1 (- (get-in s [:ui :inspection :page :offset] 1) 12000)))))
                                 {:width 8 :visible false})
        inspector-next (w/button renderer "[Next page]"
                                 (fn []
                                   (let [s (c/state @view-ref)]
                                     (inspection/load-artifact-page! @view-ref
                                                          (get-in s [:ui :inspection :descriptor :artifact-id])
                                                          (get-in s [:ui :inspection :page :next-offset]))))
                                 {:width 13 :visible false})
        inspector-branch (w/button renderer "[Branch]" (fn [] (when-let [row (c/selected-row @view-ref)] (inspection/branch! @view-ref row)))
                                   {:width 9 :visible false})
        inspector-lifetime (w/text renderer "" {:fg :text/dim :width "100%" :maxHeight 3 :marginBottom 1})
        new-activity (w/button renderer "Jump to latest" (fn [] (transcript/follow! @view-ref))
                               {:id "jump-to-latest" :visible false :width "100%" :height 1 :paddingX (:gutter w/layout) :bg :surface/panel})
        pending (w/box renderer {:visible false :width "100%" :paddingX (:gutter w/layout) :border ["top"] :borderColor :border/default})
        pending-title (w/text renderer "PENDING" {:fg :ui/accent :height 1})
        pending-items (w/box renderer {:width "100%"})
        pending-more (w/button renderer "" (fn [] (screens/open-overlay! @view-ref {:kind :pending :title "Pending messages" :query ""
                                                                            :hint "Enter edits; Delete drops a still-pending message."}))
                               {:visible false :height 1 :fg :text/secondary})
        widget-box (w/box renderer {:id "session-widgets" :visible false :width "100%"
                                    :maxHeight 6 :paddingX (:gutter w/layout) :border ["top"]
                                    :borderColor :border/default})
        widget-items (w/box renderer {:width "100%"})
        notice-box (w/box renderer {:id "notice-box" :visible false :height 1 :width "100%" :paddingX (:gutter w/layout) :flexDirection "row"})
        notice-text (w/text renderer "" {:height 1 :flexGrow 1 :flexShrink 1 :truncate true :wrapMode "none"})
        notice-detail (w/button renderer "[Details]"
                                (fn []
                                  (let [notice (:notice (c/state @view-ref))]
                                    (screens/open-overlay! @view-ref
                                                   {:kind :notice :title "Notice"
                                                    :body (if (map? notice)
                                                            (str (:message notice) "\n\n" (when (:data notice) (present/pretty (:data notice))))
                                                            (str notice))})))
                                {:id "notice-details" :width 10 :fg :text/secondary})
        notice-close (w/button renderer "[x]" (fn [] (swap! (:state application) assoc :notice nil)) {:id "notice-close" :width 3 :fg :text/dim})
        composer-box (w/box renderer {:id "composer-box" :width "100%" :paddingX 1 :border true :borderColor :ui/accent})
        composer (w/create renderer "TextareaRenderable"
                           {:id "composer" :height 2 :width "100%" :wrapMode "word"
                            :initialValue "" :placeholder "Ask a question or describe a change..."
                            :backgroundColor :surface/base :textColor :text/primary
                            :focusedBackgroundColor :surface/base :focusedTextColor :text/primary
                            :cursorColor :ui/accent :selectionBg :selection/background
                            :keyBindings [{:name "return" :action "submit"}
                                          {:name "return" :shift true :action "newline"}
                                          {:name "j" :ctrl true :action "newline"}]
                            :onMouseDown (fn [_] (when-let [view @view-ref] (input/focus! view :composer)))
                            :onSubmit (fn [_] (when-let [view @view-ref] (input/submit! view :prompt)))
                            :onPaste (fn [event]
                                       (when (> (.-byteLength (.-bytes event)) 262144)
                                         (w/consume! event)
                                         (c/notify! @view-ref "Paste exceeds 256 KiB; attach a file instead." :error)))
                            :onContentChange
                            (fn [_]
                              (when-let [view @view-ref]
                                (when-not (:syncing-editor? @local)
                                  (let [text (.-plainText (:composer view))]
                                    (c/ui! view assoc :draft text)
                                    (let [overlay (get-in (c/state view) [:ui :overlay])
                                          slash? (and (str/starts-with? text "/") (not (str/starts-with? text "//"))
                                                      (not (str/includes? text "\n")))]
                                      (cond
                                        (and (= :commands (:kind overlay)) (:from-draft? overlay) (not slash?))
                                        (screens/close-overlay! view)
                                        (= :commands (:kind overlay))
                                        (c/ui! view update :overlay assoc :query (if slash? (subs text 1) text) :index 0)
                                        (and (nil? overlay) slash?)
                                        (screens/open-overlay! view {:kind :commands :title "Commands" :query (subs text 1) :from-draft? true})
                                        (and (nil? overlay) (re-find #"(?:^|\s)@$" text)) (screens/open-files! view "@")))))))})
        attachment-row (w/box renderer {:height 1 :width "100%" :flexDirection "row"})
        attach-button (w/button renderer "[@ Context]" (fn [] (screens/open-files! @view-ref nil)) {:width 13 :fg :text/dim})
        attachment-items (w/box renderer {:height 1 :flexGrow 1 :flexShrink 1 :flexDirection "row" :overflow "hidden"})
        footer (w/box renderer {:id "model-footer" :width "100%" :height 1 :paddingX (:gutter w/layout) :flexDirection "row" :backgroundColor :surface/panel})
        footer-status (w/button renderer "" (fn [] (models/open-models! @view-ref)) {:id "footer-model" :flexShrink 1 :minWidth 1 :height 1 :truncate true :wrapMode "none"})
        provider-status (w/text renderer "" {:id "footer-provider" :maxWidth 16 :height 1 :truncate true :wrapMode "none" :fg :text/dim})
        footer-space (w/box renderer {:flexGrow 1 :minWidth 2})
        context-status (w/text renderer "" {:id "footer-context" :height 1 :fg :text/secondary :wrapMode "none"})
        metadata (w/box renderer {:id "project-footer" :width "100%" :height 1 :paddingX 2 :flexDirection "row"})
        project-status (w/text renderer "" {:flexGrow 1 :flexShrink 1 :height 1 :truncate true :wrapMode "none" :fg :text/secondary})
        command-menu (w/box renderer {:id "command-menu" :visible false :width "100%" :paddingX 2})
        spacer (w/box renderer {:flexGrow 1 :minHeight 0})
        footer-keys (w/text renderer "" {:id "footer-feedback" :flexShrink 1 :height 1 :fg :text/secondary :wrapMode "none" :truncate true})
        modal-shade (w/box renderer {:id "dialog-layer" :visible false :position "absolute" :top 0 :left 0
                                     :width "100%" :height "100%" :zIndex 100 :backgroundColor :surface/base})
        modal (w/box renderer {:id "active-screen" :position "absolute" :width "100%" :height "100%" :paddingX 2 :paddingY 1
                               :border false :backgroundColor :surface/base})
        modal-header (w/box renderer {:width "100%" :height 2 :flexDirection "row"})
        modal-title (w/text renderer "" {:height 1 :flexGrow 1 :flexShrink 1 :fg :ui/accent :truncate true :wrapMode "none"})
        provider-switch (w/button renderer "Change provider"
                                  (fn []
                                    (let [previous (get-in (c/state @view-ref) [:ui :overlay])]
                                      (screens/open-overlay! @view-ref
                                                     {:kind :choices :title "Choose provider" :return-overlay previous
                                                      :items (mapv (fn [entry]
                                                                     {:label (catalog/provider-name entry)
                                                                      :description (catalog/status-label entry)
                                                                      :choose (fn []
                                                                                (c/ui! @view-ref assoc :overlay previous)
                                                                                (models/browser-provider! @view-ref entry))})
                                                                   (catalog/providers (:providers (c/state @view-ref))))})))
                                  {:width 21 :visible false})
        modal-close (w/button renderer "← Back" (fn [] (input/escape! @view-ref)) {:width 8})
        modal-hint (w/text renderer "" {:width "100%" :maxHeight 4 :fg :text/secondary :marginBottom 1})
        modal-input-frame (w/box renderer {:width "100%" :height 1})
        modal-input (w/create renderer "TextareaRenderable"
                              {:id "dialog-input" :position "absolute" :top 0 :left 0
                               :width "100%" :height 1 :initialValue ""
                               :backgroundColor :surface/panel :focusedBackgroundColor :surface/panel
                               :textColor :text/primary :focusedTextColor :text/primary
                               :selectionBg :selection/background :cursorColor :ui/accent
                               :keyBindings [{:name "return" :action "submit"} {:name "return" :shift true :action "newline"}]
                               :onSubmit (fn [_] (screens/choose-overlay! @view-ref))
                               :onContentChange
                               (fn [_]
                                 (when-let [view @view-ref]
                                   (when-let [overlay (get-in (c/state view) [:ui :overlay])]
                                     (when (not= :commands (:kind overlay))
                                     (let [query (.-plainText (:modal-input view))]
                                       (if (:secret? overlay)
                                         (do
                                           (w/content! (:modal-mask view) (c/secret-mask query))
                                           (.requestRender (:renderer view)))
                                         (when (not= query (:query overlay))
                                           (c/ui! view update :overlay assoc :query query :index 0)
                                           (when (= :files (:kind overlay)) (screens/file-query! view query)))))))))})
        modal-mask (w/text renderer "" {:id "dialog-secret-mask" :position "absolute" :top 0 :left 0
                                        :zIndex 2 :width "100%" :height 1 :visible false
                                        :selectable false :fg :text/primary :bg :surface/panel})
        modal-content (w/box renderer {:width "100%" :flexDirection "row" :flexGrow 1 :flexShrink 1 :minHeight 1 :minWidth 1})
        modal-sidebar (w/scrollbox renderer {:id "provider-sidebar" :width (:sidebar-width w/layout) :flexGrow 0 :visible false
                                             :border ["right"] :borderColor :border/default :marginRight 2 :paddingRight 1})
        modal-list (w/scrollbox renderer {:id "dialog-choices" :flexGrow 1 :flexShrink 1 :minWidth 1 :marginTop 1})
        model-layout (w/box renderer {:width "100%" :flexGrow 1 :flexShrink 1 :minHeight 1 :flexDirection "row"})
        model-settings (w/box renderer {:id "model-settings" :visible false :width 38 :paddingX 1 :paddingTop 1})
        browser-status (w/text renderer "" {:id "browser-status" :width "100%" :height 1 :visible false
                                           :fg :ui/accent :truncate true :wrapMode "none"})
        modal-footer (w/text renderer "" {:width "100%" :height 1 :fg :text/dim :wrapMode "none" :truncate true})
        view {
              :actions {:open-theme! theme-picker/open!
                        :close-overlay! screens/close-overlay!
                        :confirm! screens/confirm!
                        :focus! input/focus!
                        :input-dialog! screens/input-dialog!
                        :inspect! inspection/inspect!
                        :open-files! screens/open-files!
                        :open-history! screens/open-history!
                        :open-models! models/open-models!
                        :open-overlay! screens/open-overlay!
                        :open-providers! models/open-providers!
                        :open-sessions! screens/open-sessions!
                        :overlay-items screens/overlay-items
                        :quit! input/quit!
                        :render-overlay! screens/render-overlay!
                        :request-inspection! inspection/request-inspection!
                        :schedule! schedule!}
              :themes theme-manager :app application :renderer renderer :root root :local local :closed? closed?
              :syntax (w/syntax-style renderer) :records (atom {})
              :header header :session-title session-title
              :body body :conversation conversation :transcript transcript :welcome welcome
              :welcome-title welcome-title :welcome-hint welcome-hint :recent-sessions recent-sessions
              :metadata metadata :project-status project-status :command-menu command-menu :spacer spacer
              :inspector inspector :inspector-title inspector-title :inspector-tabs inspector-tabs
              :inspector-scroll inspector-scroll :inspector-output inspector-output :inspector-next inspector-next
              :inspector-prev inspector-prev
              :inspector-branch inspector-branch :inspector-lifetime inspector-lifetime
              :pending pending :pending-items pending-items :pending-more pending-more :new-activity new-activity
              :widget-box widget-box :widget-items widget-items
              :notice-box notice-box :notice-text notice-text :notice-detail notice-detail :notice-close notice-close :composer-box composer-box :composer composer
              :attachment-row attachment-row :attachment-items attachment-items :footer footer :footer-status footer-status :footer-keys footer-keys :provider-status provider-status :context-status context-status
              :provider-switch provider-switch :modal-shade modal-shade :modal modal :modal-title modal-title :modal-hint modal-hint
              :modal-input-frame modal-input-frame :modal-input modal-input :modal-mask modal-mask
              :modal-list modal-list :modal-footer modal-footer :modal-sidebar modal-sidebar
              :model-settings model-settings :model-layout model-layout :modal-content modal-content :browser-status browser-status
              :on-quit (or (:on-quit options) (fn [] (-> (app/close! application) (.finally (fn [] (.destroy renderer))))))}
        key-handler (fn [event] (input/key! view event))
        frame-handler (fn [_] (frame! view))
        resize-handler (fn [& _] (transcript/remember-anchor! view) (schedule! view))]
    (reset! view-ref view)
    (w/add! header session-title)
    (w/add! conversation transcript)
    (w/add! welcome welcome-title welcome-hint recent-sessions welcome-help)
    (w/add! inspector-header inspector-title inspector-close)
    (doseq [tab [:summary :output :value :code]] (.add inspector-tab-row (get inspector-tabs tab)))
    (w/add! inspector-scroll inspector-output)
    (w/add! inspector-actions inspector-copy inspector-prev inspector-next inspector-branch)
    (w/add! inspector inspector-header inspector-tab-row inspector-scroll inspector-actions inspector-lifetime)
    (w/add! body conversation inspector)
    (w/add! pending pending-title pending-items pending-more)
    (w/add! widget-box widget-items)
    (w/add! notice-box notice-text notice-detail notice-close)
    (w/add! attachment-row attach-button attachment-items)
    (w/add! composer-box composer attachment-row)
    (w/add! footer footer-status provider-status footer-space context-status)
    (w/add! metadata project-status footer-keys)
    (w/add! modal-header modal-title provider-switch modal-close)
    (w/add! modal-input-frame modal-input modal-mask)
    (w/add! modal-content modal-sidebar modal-list)
    (w/add! model-layout modal-content model-settings)
    (w/add! modal modal-header modal-hint modal-input-frame model-layout browser-status modal-footer)
    (w/add! modal-shade modal)
    (w/add! root header welcome body spacer new-activity pending widget-box notice-box command-menu composer-box footer metadata modal-shade)
    (.add (.-root renderer) root)
    (swap! local assoc :key-handler key-handler :frame-handler frame-handler :resize-handler resize-handler
           :pulse-timer (js/setInterval (fn [] (when (c/busy? view) (schedule! view))) 1000))
    (.on (.-keyInput renderer) "keypress" key-handler)
    (.on renderer "frame" frame-handler)
    (.on renderer "resize" resize-handler)
    (.once renderer "destroy" (fn [] (destroy! view)))
    (add-watch (:state application) ::render (fn [_ _ _ _] (schedule! view)))
    (refresh! view)
    (.focus composer)
    view))


(defn destroy! [view]
  (when (compare-and-set! (:closed? view) false true)
    (remove-watch (:state (:app view)) ::render)
    (doseq [key [:render-timer :file-query-timer :notice-timer]]
      (when-let [timer (get @(:local view) key)] (js/clearTimeout timer)))
    (when-let [timer (:pulse-timer @(:local view))] (js/clearInterval timer))
    (.off (.-keyInput (:renderer view)) "keypress" (:key-handler @(:local view)))
    (.off (:renderer view) "frame" (:frame-handler @(:local view)))
    (.off (:renderer view) "resize" (:resize-handler @(:local view)))
    (when-not (.-isDestroyed (:root view))
      (.remove (.-root (:renderer view)) (:root view))
      (.destroyRecursively (:root view)))
    (palette/detach! (:renderer view))))
