(ns arrodes.tui.screens
  "Screen lifecycle, selection lists and reverse-request UI."
  (:require
            [arrodes.catalog :as catalog]
            [arrodes.tui-model :as model]
            [arrodes.tui-present :as present]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]
            [arrodes.tui.commands :as commands]
            [arrodes.tui.context :as c]
            [arrodes.tui.inspection :as inspection]
            [arrodes.tui.models :as models]
            [arrodes.tui.theme-picker :as theme-picker]))

(declare input-dialog! confirm! open-sessions! open-history! open-files! history-row overlay-items choose-overlay! open-overlay! close-overlay! reconcile-host-overlays file-query! render-command-menu! render-overlay! respond-host! complete-widget! render-host-request!)

(defn input-dialog! [view title initial on-submit hint]
  (open-overlay! view {:kind :input :title title :query (or initial "")
                       :on-submit on-submit :hint hint}))

(defn confirm! [view title description action]
  (open-overlay! view
                 {:kind :confirm :title title :hint description :query ""
                  :items [{:label "Cancel" :description "Keep the current state"
                           :choose #(close-overlay! view)}
                          {:label "Continue" :description description
                           :choose (fn [] (close-overlay! view) (action))}]}))


(defn open-sessions! [view]
  (open-overlay! view {:kind :sessions :title "Sessions" :query "" :hint "Search by name or project. Enter opens; Esc returns."})
  (c/fire! view :sessions {}))

(defn open-history! [view]
  (open-overlay! view {:kind :history :title "History" :query "" :items []
                       :hint "Inspect a recorded turn. Branching does not restore files."})
  (-> (c/invoke! view :history {})
      (.then (fn [result]
               (when (= :history (get-in (c/state view) [:ui :overlay :kind]))
                 (c/ui! view assoc-in [:overlay :entries] (:entries result)))))
      (.catch (fn [_] nil))))

(defn open-files! [view marker]
  (open-overlay! view {:kind :files :title "Attach project context" :query "" :items []
                       :marker marker :hint "Text and image files are explicit attachments. Oversized files are rejected."})
  (-> (c/invoke! view :files {:query ""})
      (.then (fn [paths]
               (when (= :files (get-in (c/state view) [:ui :overlay :kind]))
                 (c/ui! view assoc-in [:overlay :paths] paths))))
      (.catch (fn [_] nil))))


(defn history-row [entry]
  (let [message (:data entry)
        role (keyword (or (:message/role message) "context"))]
    {:id (str "history-" (:id entry)) :entry-id (:id entry) :kind :message :role role
     :text (if (= :evaluation (keyword (:kind entry)))
             (str "Evaluation\n" (:source message))
             (or (not-empty (model/text-content (:message/content message)))
                 (str (name (keyword (:kind entry))) " entry")))}))


(defn last-message-label [timestamp]
  (if (and (number? timestamp) (js/Number.isFinite timestamp))
    (str "Last message: " (.toLocaleString (js/Date. timestamp) js/undefined
                                          #js {:year "numeric" :month "short" :day "2-digit"
                                               :hour "2-digit" :minute "2-digit" :second "2-digit"}))
    "No messages yet"))

(defn overlay-items [view overlay]
  (let [s (c/state view)
        query (str/lower-case (str/trim (or (:query overlay) "")))
        raw
        (case (:kind overlay)
          :commands (remove :alias? (commands/commands view))
          :themes (theme-picker/items view)
          :sessions
          (mapv (fn [session]
                  {:label (or (:name session) "Untitled session")
                   :description (str (last-message-label (:last-message-at session)) "\n"
                                     (:cwd session) "  " (name (keyword (or (:status session) "idle"))))
                   :choose (fn []
                             (-> (c/invoke! view :switch-session {:id (:id session)})
                                 (.then #(close-overlay! view))
                                 (.catch (fn [_] nil))))})
                (:sessions s))
          :providers
          (mapv (fn [entry]
                  {:label (str (if (:available? entry) "● " "○ ") (catalog/provider-name entry))
                   :description (str (catalog/status-label entry) " · " (catalog/auth-label entry))
                   :connected? (:available? entry)
                   :search-text (name (catalog/provider-id entry))
                   :choose #(models/provider-actions! view entry)}) (catalog/providers (:providers s)))
          :models
          (mapv (fn [m]
                  {:label (:id m) :model m
                   :description (str (when (and (= (:id m) (get-in s [:view :session :config :model]))
                                               (= (catalog/provider-id m) (some-> (get-in s [:view :session :config :provider]) keyword))) "Current · ")
                                     (when (:context-window m) (str (:context-window m) " context · "))
                                     (str/join "/" (map name (:thinking-levels m))))
                   :choose #(models/focus-model-settings! view m)})
                (catalog/models (:models s) (:provider overlay) (:query overlay)))
          :history
          (->> (:entries overlay)
               reverse
               (remove #(= "tool" (name (keyword (or (get-in % [:data :message/role]) "none")))))
               (mapv (fn [entry]
                       (let [row (history-row entry)]
                         {:label (present/brief (str/replace (:text row) #"\s+" " ") 100)
                          :description (str (name (:role row)) "  " (:created-at entry))
                          :choose (fn []
                                    (let [call-id (or (get-in entry [:data :result :id])
                                                      (get-in entry [:data :message/tool-calls 0 :tool-call/id]))
                                          activity (get-in (c/state view) [:view :activities call-id])]
                                      (close-overlay! view)
                                      (inspection/inspect! view (cond-> row activity (assoc :kind :activity :activity activity)))))}))))
          :files
          (mapv (fn [item]
                  (let [path (if (string? item) item (or (:path item) (:name item)))]
                    {:label path :description (if (:directory? item) "Open directory" "Attach to the current draft")
                     :choose (fn []
                               (if (:directory? item)
                                 (do (c/ui! view update :overlay assoc :query path :index 0)
                                     (file-query! view path))
                                 (-> (c/invoke! view :attach {:path path})
                                     (.then (fn [_]
                                              (when-let [marker (:marker overlay)]
                                                (c/ui! view update :draft
                                                     #(if (str/ends-with? % marker)
                                                        (subs % 0 (- (count %) (count marker))) %)))
                                              (close-overlay! view)))
                                     (.catch (fn [_] nil)))))}))
                (or (:paths overlay) []))
          :pending
          (mapv (fn [item]
                  (let [content (model/text-content (:content item))]
                    {:label (str (name (keyword (:kind item))) ": " (present/brief content 90))
                     :description "Enter edits; Delete drops this pending message"
                     :drop #(c/fire! view :queue-drop {:id (:id item)})
                     :choose #(input-dialog! view "Edit pending message" content
                                             (fn [text] (c/invoke! view :queue-edit {:id (:id item) :text text}))
                                             "Still-pending messages only; image attachments are preserved.")}))
                (get-in s [:view :queue]))
          (or (:items overlay) []))]
    (->> raw
         (filter #(or (str/blank? query)
                      (str/includes? (str/lower-case (str (:label %) " " (:description %) " " (:search-text %))) query)))
         (sort-by (fn [item]
                    (if (= :commands (:kind overlay))
                      (let [command (str/lower-case (or (:command item) (some-> (:description item) (str/split #"\s+") first (subs 1)) ""))
                            label (str/lower-case (:label item))]
                        (cond (str/blank? query) 0
                              (= query command) 0
                              (or (str/starts-with? command query) (str/starts-with? label query)) 1
                              :else 2)) 0)))
         (take (if (contains? #{:models :providers} (:kind overlay)) 2000 100))
         vec)))


(defn choose-overlay! [view]
  (let [overlay (get-in (c/state view) [:ui :overlay])
        query (if (= :commands (:kind overlay)) (:query overlay) (.-plainText (:modal-input view)))
        overlay (if (and (not (contains? #{:input :confirm} (:kind overlay)))
                         (nil? (:body overlay)) (not= query (:query overlay)))
                  (assoc overlay :query query :index 0) overlay)
        token (:token overlay)]
    (if (= :input (:kind overlay))
      (when (not= token (:submitting-modal @(:local view)))
        (when-let [submit (:on-submit overlay)]
          (swap! (:local view) assoc :submitting-modal token)
          (let [failed (fn [error]
                         (when (not= (c/error-text error) (get-in (c/state view) [:notice :message]))
                           (c/notify! view (c/error-text error) :error))
                         (when (= token (get-in (c/state view) [:ui :overlay :token]))
                           (c/ui! view assoc-in [:overlay :error] (c/error-text error))))]
            (try
              (-> (js/Promise.resolve (submit (.-plainText (:modal-input view))))
                  (.then (fn [_]
                           (when (= token (get-in (c/state view) [:ui :overlay :token]))
                             (close-overlay! view))))
                  (.catch failed)
                  (.finally (fn [] (swap! (:local view) dissoc :submitting-modal))))
              (catch :default error
                (swap! (:local view) dissoc :submitting-modal)
                (failed error))))))
      (when-let [item (when (or (:host-id overlay) (nil? (:catalog-operation (c/state view))))
                        (get (overlay-items view overlay) (or (:index overlay) 0)))]
        (when (and (= :commands (:kind overlay)) (:from-draft? overlay))
          (c/ui! view assoc :draft ""))
        ((:choose item))))))


(defn open-overlay! [view overlay]
  (let [previous (get-in (c/state view) [:ui :overlay])]
    (c/ui! view assoc :overlay
         (merge {:token (str (random-uuid)) :index 0 :query ""}
                (when (:host-id overlay) {:return-overlay previous}) overlay)))
  (when (= :commands (:kind overlay)) (c/action! :focus! view :composer))
  (render-overlay! view)
  (c/action! :schedule! view))

(defn close-overlay! [view]
  (let [previous (get-in (c/state view) [:ui :overlay :return-overlay])]
    (c/ui! view assoc :overlay previous :focus :composer)
    (if previous (render-overlay! view) (.focus (:composer view)))))


(defn reconcile-host-overlays [overlay pending]
  (when overlay
    (let [previous (reconcile-host-overlays (:return-overlay overlay) pending)]
      (if (and (:host-id overlay) (not (contains? pending (:host-id overlay))))
        previous
        (cond-> overlay
          (contains? overlay :return-overlay) (assoc :return-overlay previous))))))


(defn file-query! [view query]
  (when-let [timer (:file-query-timer @(:local view))] (js/clearTimeout timer))
  (swap! (:local view) assoc :file-query-timer
         (js/setTimeout
          (fn []
            (-> (c/invoke! view :files {:query query})
                (.then (fn [paths]
                         (when (and (= :files (get-in (c/state view) [:ui :overlay :kind]))
                                    (= query (get-in (c/state view) [:ui :overlay :query])))
                           (c/ui! view assoc-in [:overlay :paths] paths))))
                (.catch (fn [_] nil))))
          120)))


(defn render-command-menu! [view overlay]
  (let [items (overlay-items view overlay)
        fixed (+ 3 (max 4 (.-height (:composer-box view)))
                 (if (.-visible (:welcome view)) (.-height (:welcome view)) 1)
                 (reduce + 0 (for [node [(:notice-box view) (:pending view) (:widget-box view)]
                                  :when (.-visible node)] (.-height node))))
        capacity (max 1 (min 8 (- (.-terminalHeight (:renderer view)) fixed)))
        index (min (max 0 (dec (count items))) (or (:index overlay) 0))
        start (max 0 (- index (dec capacity)))
        visible (subvec items start (min (count items) (+ start capacity)))
        signature [(:query overlay) index capacity (mapv #(select-keys % [:label :description]) items)]]
    (set! (.-height (:command-menu view)) (max 1 (count visible)))
    (when (not= signature (:command-signature @(:local view)))
      (swap! (:local view) assoc :command-signature signature)
      (w/clear! (:command-menu view))
      (if (empty? visible)
        (.add (:command-menu view) (w/text (:renderer view) "No matching commands" {:fg :text/secondary :height 1}))
        (doseq [[offset item] (map-indexed vector visible)]
          (let [i (+ start offset)
                command (first (str/split (:description item) #"\s+"))
                description (str/trim (subs (:description item) (count command)))
                label (str (if (= i index) "› " "  ") (or (:icon item) "·") " " command)
                label-width (min 28 (max 14 (quot (.-terminalWidth (:renderer view)) 2)))
                row (w/box (:renderer view) {:id (str "command-" i) :height 1 :width "100%" :flexDirection "row"
                                             :backgroundColor (if (= i index) :surface/selected :surface/base)})
                choose #(do (c/ui! view assoc-in [:overlay :index] i) (choose-overlay! view))]
            (w/add! row
                    (w/button (:renderer view) label choose
                              {:width label-width :height 1 :truncate true :wrapMode "none"
                               :fg (if (= i index) :ui/accent :text/primary)})
                    (w/button (:renderer view) (if (seq description) description (:label item)) choose
                              {:flexGrow 1 :flexShrink 1 :minWidth 1 :height 1 :truncate true :wrapMode "none" :fg :text/secondary}))
            (.add (:command-menu view) row)))))))


(defn render-overlay! [view]
  (let [overlay (get-in (c/state view) [:ui :overlay])
        renderer (:renderer view)]
    (set! (.-visible (:command-menu view)) (= :commands (:kind overlay)))
    (when (= :commands (:kind overlay)) (render-command-menu! view overlay))
    (set! (.-visible (:modal-shade view)) (and (some? overlay) (not= :commands (:kind overlay))))
    (if (or (nil? overlay) (= :commands (:kind overlay)))
      (when (:modal-token @(:local view))
        (swap! (:local view) assoc :modal-token nil :modal-signature nil
               :modal-selection-signature nil :modal-scroll-choice nil :modal-index nil)
        (.setText (:modal-input view) "")
        (c/action! :focus! view (if (= :commands (:kind overlay)) :composer (get-in (c/state view) [:ui :focus] :composer))))
      (let [browser? (contains? #{:providers :models} (:kind overlay))
            models? (= :models (:kind overlay))
            wide-settings? (and models? (>= (.-terminalWidth renderer) 110))
            width (.-terminalWidth renderer)
            height (.-terminalHeight renderer)
            input? (= :input (:kind overlay))
            body? (some? (:body overlay))
            query? (not (or (contains? #{:confirm} (:kind overlay)) body?))
            items (overlay-items view overlay)
            index (min (max 0 (dec (count items))) (or (:index overlay) 0))
            signature [(:token overlay) (:query overlay) (:body overlay) (:provider overlay) browser?
                       (mapv #(select-keys % [:label :description]) items)]
            rebuild? (not= signature (:modal-signature @(:local view)))
            selection-signature [signature index width height]]
        (set! (.-width (:modal view)) width)
        (set! (.-height (:modal view)) height)
        (set! (.-left (:modal view)) 0)
        (set! (.-top (:modal view)) 0)
        (set! (.-visible (:modal-sidebar view)) (and models? (>= width 76)))
        (set! (.-visible (:model-settings view)) models?)
        (set! (.-flexDirection (:model-layout view)) (if wide-settings? "row" "column"))
        (set! (.-width (:model-settings view)) (if wide-settings? 38 "100%"))
        (set! (.-height (:model-settings view)) (if wide-settings? "100%" "auto"))
        (set! (.-width (:modal-content view)) (if wide-settings? "auto" "100%"))
        (set! (.-height (:modal-content view)) (if wide-settings? "100%" "auto"))
        (set! (.-visible (:modal-hint view)) (not (and models? (< height 24))))
        (when models? (models/render-model-settings! view overlay wide-settings?))
        (set! (.-visible (:browser-status view)) browser?)
        (w/content! (:browser-status view)
                    (let [discovery (get-in (c/state view) [:discovery (some-> (:provider overlay) keyword)])
                          entry (some #(when (= (catalog/provider-id %) (some-> (:provider overlay) keyword)) %) (:providers (c/state view)))]
                      (or (:catalog-operation (c/state view)) (:error discovery)
                          (when (:loading? discovery) "Loading this provider's models…")
                          (when (and models? entry (not (:available? entry))) "Connect this provider to use its models.")
                          (when models? (if (:refreshable? entry) "Provider discovery · F5 refreshes models"
                                                     "Configured / SDK catalog · No live listing endpoint")) "")))
        (w/content! (:modal-title view)
                    (if models? (str "Models · " (or (some-> (:provider overlay) name) "All providers")
                                     (when (= :providers (:pane overlay)) " · selecting provider")) (:title overlay)))
        (set! (.-visible (:provider-switch view)) models?)
        (set! (.-width (:provider-switch view)) (if (< width 60) 12 21))
        (w/content! (:provider-switch view) (if (< width 60) "Provider" "Change provider"))
        (w/content! (:modal-hint view)
                    (str (:hint overlay) (when (:error overlay) (str "\nError: " (:error overlay)))))
        (set! (.-visible (:modal-input-frame view)) query?)
        (when (and (:secret? overlay)
                   (not= (:token overlay) (:modal-token @(:local view))))
          (.setText (:modal-input view) ""))
        (let [input-height (if input? (min 7 (max 3 (- height 12))) 1)
              secret? (boolean (:secret? overlay))
              input-value (.-plainText (:modal-input view))]
          (set! (.-visible (:modal-input view)) (and query? (not secret?)))
          (set! (.-height (:modal-input view)) input-height)
          (set! (.-visible (:modal-mask view)) secret?)
          (set! (.-selectable (:modal-input view)) (not secret?))
          (w/paint! (:modal-input view) :textColor (if secret? :surface/panel :text/primary))
          (w/paint! (:modal-input view) :focusedTextColor (if secret? :surface/panel :text/primary))
          (w/paint! (:modal-input view) :selectionFg (if secret? :surface/panel :text/primary))
          (w/paint! (:modal-input view) :selectionBg (if secret? :surface/panel :selection/background))
          (set! (.-height (:modal-mask view)) input-height)
          (w/content! (:modal-mask view) (c/secret-mask input-value)))
        (set! (.-placeholder (:modal-input view)) (if input? "Enter a value..." "Type to filter..."))
        (when (and (not (:secret? overlay))
                   (not= (.-plainText (:modal-input view)) (or (:query overlay) "")))
          (.setText (:modal-input view) (or (:query overlay) "")))
        (when (not= (:token overlay) (:modal-token @(:local view)))
          (swap! (:local view) assoc :modal-token (:token overlay))
          (if query? (.focus (:modal-input view)) (.focus (:modal-list view))))
        (when rebuild?
          (swap! (:local view) assoc :modal-signature signature :modal-items items :modal-content-height nil)
          (set! (.-minHeight (.-content (:modal-list view))) 0)
          (w/clear! (:modal-list view))
          (cond
            input?
            (.add (:modal-list view)
                  (w/text renderer (if (:secret? overlay)
                                     "Input is hidden and is not saved in prompt history."
                                     "Shift+Enter inserts a newline. Enter submits this value.")
                          {:fg :text/secondary :marginTop 1}))
            body? (.add (:modal-list view) (w/text renderer (:body overlay) {:width "100%"}))
            (empty? items) (.add (:modal-list view) (w/text renderer "No matching items." {:fg :text/secondary}))
            :else
            (doseq [[i item] (map-indexed vector items)]
              (let [row (w/box renderer {:id (str "choice-" i) :width "100%" :paddingX 1 :paddingY 0
                                         :marginBottom (if browser? 0 1) :backgroundColor (if (= i index) :surface/selected :surface/panel)})
                    button (w/button renderer (:label item) (:choose item) {:id (str "choice-" i "-label") :width "100%"
                                                                            :fg (if (= i index) :ui/accent :text/primary)})
                    description (w/text renderer (:description item) {:width "100%" :fg :text/secondary})]
                (when browser?
                  (set! (.-flexDirection row) "row")
                  (set! (.-width button) "55%")
                  (set! (.-width description) "45%")
                  (set! (.-height description) 1)
                  (set! (.-truncate description) true)
                  (set! (.-truncate button) true)
                  (set! (.-wrapMode button) "none")
                  (set! (.-wrapMode description) "none"))
                (when (and browser? (:connected? item)) (w/paint! description :fg :status/success))
                (w/add! row button description)
                (.add (:modal-list view) row)))))
        (when (not= selection-signature (:modal-selection-signature @(:local view)))
          (when (and (not rebuild?) (not= index (:modal-index @(:local view))))
            (doseq [i [(:modal-index @(:local view)) index]]
              (when-let [row (.findDescendantById (:modal-list view) (str "choice-" i))]
                (w/paint! row :backgroundColor (if (= i index) :surface/selected :surface/panel))
                (when-let [label (.findDescendantById row (str "choice-" i "-label"))]
                  (w/paint! label :fg (if (= i index) :ui/accent :text/primary))))))
          ;; Keep measured rows when selecting; scroll only after layout has settled.
          (swap! (:local view) assoc :modal-selection-signature selection-signature :modal-index index
                 :modal-scroll-choice
                 (when (and (seq items) (not input?) (not body?)) (str "choice-" index))))
        (when models?
          (let [providers (catalog/providers (:providers (c/state view)))
                selected (:provider overlay)
                sidebar-signature [providers selected (:pane overlay) width]]
            (when (not= sidebar-signature (:sidebar-signature @(:local view)))
              (swap! (:local view) assoc :sidebar-signature sidebar-signature)
              (w/clear! (:modal-sidebar view))
              (.add (:modal-sidebar view) (w/button renderer "+ Connect a provider" #(models/open-providers! view)
                                                    {:height 2 :width "100%"}))
              (doseq [entry providers]
                (.add (:modal-sidebar view)
                      (w/button renderer (str (if (= (catalog/provider-id entry) (some-> selected keyword)) "› " "  ")
                                               (if (:available? entry) "● " "○ ") (catalog/provider-name entry))
                                #(models/browser-provider! view entry)
                                {:id (str "provider-" (name (catalog/provider-id entry)))
                                 :width "100%" :height 1 :wrapMode "none" :truncate true
                                 :fg (if (= (catalog/provider-id entry) (some-> selected keyword)) :ui/accent :text/secondary)}))))
            (swap! (:local view) assoc :sidebar-choice (when selected (str "provider-" (name selected))))
            (w/paint! (:modal-sidebar view) :borderColor (if (= :providers (:pane overlay)) :ui/accent :border/default))))
        (w/content! (:modal-footer view)
                    (cond models? (case (:pane overlay)
                                            :effort "← → effort / column edge · Tab apply · Esc models"
                                            :session "Enter applies here · Tab default · Esc models"
                                            :default "Enter applies + saves default · Esc models"
                                            "← → column edge · Tab pane · ↑↓ select · Enter configure")
                          browser? (if (< width 80) "↑↓ select · Enter manage · F5 refresh · Esc back"
                                        "Type to search · ↑↓ select · Enter manage · F5 refresh · Esc back")
                          input? "Enter submit   Shift+Enter newline   Esc cancel"
                          body? "Esc back"
                          (= :pending (:kind overlay)) "Up/Down select   Enter edit   Delete drop   Esc back"
                          :else (str "Up/Down select   Enter choose   Esc back"
                                     (when (= 100 (count items)) "   100 shown; narrow search"))))))))


(defn respond-host! [view id result cancel?]
  (swap! (:local view) assoc :handled-host id)
  (c/fire! view (if cancel? :host-cancel :host-response)
         (if cancel? {:id id} {:id id :result result}))
  (close-overlay! view))


(defn complete-widget! [view id request]
  (swap! (:local view) assoc :handled-host id)
  (-> (c/invoke! view :host-widget {:request request})
      (.then #(c/fire! view :host-response {:id id :result %}))
      (.catch (fn [failure]
                (c/notify! view (c/error-text failure) :error)
                (c/fire! view :host-cancel {:id id})))))


(defn render-host-request! [view]
  (let [pending (set (map :id (:host-requests (c/state view))))
        overlay (get-in (c/state view) [:ui :overlay])
        reconciled (reconcile-host-overlays overlay pending)]
    (when (not= overlay reconciled)
      (c/ui! view assoc :overlay reconciled)))
  (let [s (c/state view)
        envelope (first (:host-requests s))
        request (or (:request envelope) envelope)
        id (:id envelope)
        kind (keyword (or (:kind request) "unknown"))]
    (when (and id (not= id (:handled-host @(:local view)))
               (not= id (get-in s [:ui :overlay :host-id])))
      (let [title (or (:title request) (when (string? (:prompt request)) (:prompt request)) "Core interaction")
            cancel {:label "Cancel request" :description "No approval or result is invented"
                    :choose #(respond-host! view id nil true)}]
        (case kind
          :notify
          (do (c/notify! view (or (:message request) (:url request) (present/pretty (dissoc request :kind))))
              (swap! (:local view) assoc :handled-host id)
              (c/fire! view :host-response {:id id :result nil}))

          (:widget :set-widget)
          (complete-widget! view id request)

          :render
          (let [previous (get-in s [:ui :overlay])]
            (swap! (:local view) assoc :handled-host id)
            (open-overlay! view {:kind :render :title title
                                 :body (c/display-content (:content request))
                                 :return-overlay previous})
            (c/fire! view :host-response {:id id :result nil}))

          (:input :editor)
          (open-overlay! view {:kind :input :host-id id :title title
                               :query (if (= :editor kind)
                                        (or (:content request) (:initial request) "")
                                        "")
                               :secret? (:secret? request)
                               :hint (when (map? (:prompt request)) (present/pretty (:prompt request)))
                               :on-submit #(respond-host! view id % false)})

          :confirm
          (open-overlay! view {:kind :confirm :host-id id :title title
                               :hint (or (:message request) (present/pretty (dissoc request :kind :timeout-ms)))
                               :items [{:label "No" :description "Decline" :choose #(respond-host! view id false false)}
                                       {:label "Yes" :description "Approve the displayed request" :choose #(respond-host! view id true false)}]})

          :select
          (open-overlay! view {:kind :choices :host-id id :title title :query ""
                               :hint (:message request)
                               :items (conj (mapv (fn [item]
                                                    {:label (if (map? item) (or (:label item) (:name item) (str (:value item))) (str item))
                                                     :description (or (:description item) "")
                                                     :choose #(respond-host! view id
                                                                             (if (and (map? item) (contains? item :value))
                                                                               (:value item)
                                                                               (if (map? item) (or (:id item) (:label item)) item))
                                                                             false)})
                                                  (or (:items request) (:options request))) cancel)})

          (open-overlay! view {:kind :confirm :host-id id :title "Unsupported host capability"
                               :hint (str "No frontend implementation is registered for " (:name request)
                                          ". The core request is not approved or executed by this UI.")
                               :items [cancel]}))))))

