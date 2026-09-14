(ns arrodes.tui-view
  "The OpenTUI host: keyed conversation, persistent editor and contextual inspection."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-model :as model]
            [arrodes.tui-present :as present]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]))

(declare refresh! schedule! open-overlay! close-overlay! choose-overlay! commands
         inspect! submit! follow! focus! close-inspector! request-inspection! quit! file-query! escape!)
(declare render-overlay! destroy!)

(defn- state [view] @(:state (:app view)))
(defn- ui! [view f & args] (apply swap! (:state (:app view)) update :ui f args))
(defn- notify!
  ([view text] (notify! view text :info))
  ([view text kind]
   (swap! (:state (:app view)) assoc :notice {:kind kind :message (model/safe-text text)})))
(defn- error-text [error]
  (or (.-message error) (:message (ex-data error)) (str error)))
(defn- invoke! [view action data]
  (app/command! (:app view) action data))
(defn- fire! [view action data]
  (.catch (invoke! view action data) (fn [_] nil)))
(defn- ready? [view] (= :ready (get-in (state view) [:connection :status])))
(defn- busy? [view]
  (contains? #{:running :cancelling :starting} (get-in (state view) [:view :operation :status])))
(defn- basename [path]
  (or (last (remove str/blank? (str/split (str (or path "")) #"[/\\]"))) "project"))
(defn- row-list [view]
  (let [m (:view (state view))
        source (select-keys m [:entries :activities :activity-order :streams :operation :snapshot-cursor])]
    (if (= source (:model-source @(:local view)))
      (:rows @(:local view))
      (let [rows (model/rows m)]
        (swap! (:local view) assoc :model-source source :rows rows)
        rows))))
(defn- selected-row [view]
  (let [s (state view)
        id (get-in s [:ui :selected])
        row (or (some #(when (= id (:id %)) %) (row-list view))
                (get-in s [:ui :inspected-row]))]
    (if-let [a (and (:activity row) (get-in s [:view :activities (get-in row [:activity :id])]))]
      (assoc row :activity a)
      row)))
(defn- expanded? [view row]
  (boolean (get-in (state view) [:ui :expanded (:id row)])))
(defn- toggle! [view row]
  (ui! view assoc-in [:expanded (:id row)] (not (expanded? view row))))

(defn- copy! [view text]
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

(defn- copy-selection! [view]
  (if-let [selection (.getSelection (:renderer view))]
    (let [text (.getSelectedText selection)]
      (when (seq text) (copy! view text) true))
    (when (.hasSelection (:composer view))
      (copy! view (.getSelectedText (:composer view))) true)))

(defn- input-dialog! [view title initial on-submit hint]
  (open-overlay! view {:kind :input :title title :query (or initial "")
                       :on-submit on-submit :hint hint}))
(defn- confirm! [view title description action]
  (open-overlay! view
                 {:kind :confirm :title title :hint description :query ""
                  :items [{:label "Cancel" :description "Keep the current state"
                           :choose #(close-overlay! view)}
                          {:label "Continue" :description description
                           :choose (fn [] (close-overlay! view) (action))}]}))

(defn- open-sessions! [view]
  (open-overlay! view {:kind :sessions :title "Sessions" :query "" :hint "Search by name or project. Enter opens; Esc returns."})
  (fire! view :sessions {}))
(defn- open-history! [view]
  (open-overlay! view {:kind :history :title "History" :query "" :items []
                       :hint "Inspect a recorded turn. Branching does not restore files."})
  (-> (invoke! view :history {})
      (.then (fn [result]
               (when (= :history (get-in (state view) [:ui :overlay :kind]))
                 (ui! view assoc-in [:overlay :entries] (:entries result)))))
      (.catch (fn [_] nil))))
(defn- open-models! [view]
  (open-overlay! view {:kind :models :title "Choose a model" :query ""
                       :hint "Explicit selection only; no automatic model fallback. /refresh-models refreshes OAuth catalogs."})
  (fire! view :models {}))
(defn- open-files! [view marker]
  (open-overlay! view {:kind :files :title "Attach project context" :query "" :items []
                       :marker marker :hint "Text and image files are explicit attachments. Oversized files are rejected."})
  (-> (invoke! view :files {:query ""})
      (.then (fn [paths]
               (when (= :files (get-in (state view) [:ui :overlay :kind]))
                 (ui! view assoc-in [:overlay :paths] paths))))
      (.catch (fn [_] nil))))

(defn- history-row [entry]
  (let [message (:data entry)
        role (keyword (or (:message/role message) "context"))]
    {:id (str "history-" (:id entry)) :entry-id (:id entry) :kind :message :role role
     :text (if (= :evaluation (keyword (:kind entry)))
             (str "Evaluation\n" (:source message))
             (or (not-empty (model/text-content (:message/content message)))
                 (str (name (keyword (:kind entry))) " entry")))}))

(defn- overlay-items [view overlay]
  (let [s (state view)
        query (str/lower-case (str/trim (or (:query overlay) "")))
        raw
        (case (:kind overlay)
          :commands (commands view)
          :sessions
          (mapv (fn [session]
                  {:label (or (:name session) "Untitled session")
                   :description (str (:cwd session) "  " (name (keyword (or (:status session) "idle"))))
                   :choose (fn []
                             (-> (invoke! view :switch-session {:id (:id session)})
                                 (.then #(close-overlay! view))
                                 (.catch (fn [_] nil))))})
                (:sessions s))
          :models
          (mapv (fn [m]
                  {:label (:id m) :description (str (name (keyword (:provider m)))
                                                       "  " (str/join "/" (map name (:thinking-levels m))))
                   :choose (fn []
                             (let [current (get-in s [:view :session :config :thinking])
                                   levels (mapv keyword (:thinking-levels m))
                                   thinking (cond (some #{(keyword current)} levels) (keyword current)
                                                  (some #{:high} levels) :high
                                                  (seq levels) (first levels)
                                                  :else :none)]
                               (-> (invoke! view :set-model {:provider (:provider m) :model (:id m) :thinking thinking})
                                   (.then #(close-overlay! view))
                                   (.catch (fn [_] nil)))))})
                (:models s))
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
                                          activity (get-in (state view) [:view :activities call-id])]
                                      (close-overlay! view)
                                      (inspect! view (cond-> row activity (assoc :kind :activity :activity activity)))))}))))
          :files
          (mapv (fn [item]
                  (let [path (if (string? item) item (or (:path item) (:name item)))]
                    {:label path :description (if (:directory? item) "Open directory" "Attach to the current draft")
                     :choose (fn []
                               (if (:directory? item)
                                 (do (ui! view update :overlay assoc :query path :index 0)
                                     (file-query! view path))
                                 (-> (invoke! view :attach {:path path})
                                     (.then (fn [_]
                                              (when-let [marker (:marker overlay)]
                                                (ui! view update :draft
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
                     :drop #(fire! view :queue-drop {:id (:id item)})
                     :choose #(input-dialog! view "Edit pending message" content
                                             (fn [text] (invoke! view :queue-edit {:id (:id item) :text text}))
                                             "Still-pending messages only; image attachments are preserved.")}))
                (get-in s [:view :queue]))
          (or (:items overlay) []))]
    (->> raw
         (filter #(or (str/blank? query)
                      (str/includes? (str/lower-case (str (:label %) " " (:description %))) query)))
         (take 100)
         vec)))

(defn- choose-overlay! [view]
  (let [overlay (get-in (state view) [:ui :overlay])
        query (.-plainText (:modal-input view))
        overlay (if (and (not (contains? #{:input :confirm} (:kind overlay)))
                         (nil? (:body overlay)) (not= query (:query overlay)))
                  (assoc overlay :query query :index 0) overlay)
        token (:token overlay)]
    (if (= :input (:kind overlay))
      (when (not= token (:submitting-modal @(:local view)))
        (when-let [submit (:on-submit overlay)]
          (swap! (:local view) assoc :submitting-modal token)
          (let [failed (fn [error]
                         (when (not= (error-text error) (get-in (state view) [:notice :message]))
                           (notify! view (error-text error) :error))
                         (when (= token (get-in (state view) [:ui :overlay :token]))
                           (ui! view assoc-in [:overlay :error] (error-text error))))]
            (try
              (-> (js/Promise.resolve (submit (.-plainText (:modal-input view))))
                  (.then (fn [_]
                           (when (= token (get-in (state view) [:ui :overlay :token]))
                             (close-overlay! view))))
                  (.catch failed)
                  (.finally (fn [] (swap! (:local view) dissoc :submitting-modal))))
              (catch :default error
                (swap! (:local view) dissoc :submitting-modal)
                (failed error))))))
      (when-let [item (get (overlay-items view overlay) (or (:index overlay) 0))]
        (when (and (= :commands (:kind overlay)) (= "/" (get-in (state view) [:ui :draft])))
          (ui! view assoc :draft ""))
        ((:choose item))))))

(defn- open-overlay! [view overlay]
  (let [previous (get-in (state view) [:ui :overlay])]
    (ui! view assoc :overlay
         (merge {:token (str (random-uuid)) :index 0 :query ""}
                (when (:host-id overlay) {:return-overlay previous}) overlay)))
  (render-overlay! view)
  (schedule! view))
(defn- close-overlay! [view]
  (let [previous (get-in (state view) [:ui :overlay :return-overlay])]
    (ui! view assoc :overlay previous :focus :composer)
    (if previous (render-overlay! view) (.focus (:composer view)))))

(defn- file-query! [view query]
  (when-let [timer (:file-query-timer @(:local view))] (js/clearTimeout timer))
  (swap! (:local view) assoc :file-query-timer
         (js/setTimeout
          (fn []
            (-> (invoke! view :files {:query query})
                (.then (fn [paths]
                         (when (and (= :files (get-in (state view) [:ui :overlay :kind]))
                                    (= query (get-in (state view) [:ui :overlay :query])))
                           (ui! view assoc-in [:overlay :paths] paths))))
                (.catch (fn [_] nil))))
          120)))

(defn- commands [view]
  [{:label "New session" :description "/new" :choose #(do (close-overlay! view) (fire! view :new-session {}))}
   {:label "Sessions" :description "/sessions  F2" :choose #(open-sessions! view)}
   {:label "History and branches" :description "/history" :choose #(open-history! view)}
   {:label "Refresh session state" :description "/refresh  Read-only reconciliation; keeps live definitions"
    :choose #(do (close-overlay! view) (fire! view :refresh {}))}
   {:label "Pending messages" :description "/pending  Edit or drop queued input"
    :choose #(open-overlay! view {:kind :pending :title "Pending messages" :query ""
                                  :hint "Enter edits; Delete drops a still-pending message."})}
   {:label "Manage attachments" :description "/attachments"
    :choose (fn []
              (open-overlay!
               view
               {:kind :choices :title "Remove an attachment" :query ""
                :items (mapv (fn [item]
                               {:label (str "Remove " (or (:name item) (basename (:path item))))
                                :description (:path item)
                                :choose (fn []
                                          (ui! view update :attachments
                                               (fn [items] (vec (remove (fn [x] (= (:path x) (:path item))) items))))
                                          (close-overlay! view))})
                             (get-in (state view) [:ui :attachments]))}))}
   {:label "Choose model" :description "/models" :choose #(open-models! view)}
   {:label "Refresh model catalog" :description "/refresh-models" :choose #(do (close-overlay! view) (fire! view :models {:refresh? true}))}
   {:label "Thinking level" :description "/thinking"
    :choose
    (fn []
      (let [s (state view) config (get-in s [:view :session :config])
            current (some (fn [m] (when (and (= (:id m) (:model config))
                                            (= (keyword (:provider m)) (keyword (:provider config)))) m)) (:models s))
            levels (or (seq (:thinking-levels current)) [:none :low :medium :high :xhigh :max])]
        (open-overlay! view {:kind :choices :title "Thinking level" :query ""
                             :items (mapv (fn [level]
                                            {:label (name level) :description "Explicit reasoning selection"
                                             :choose (fn []
                                                       (-> (invoke! view :set-model (assoc config :thinking level))
                                                           (.then #(close-overlay! view))
                                                           (.catch (fn [_] nil))))}) levels)})))}
   {:label "Rename session" :description "/rename" :choose
    #(input-dialog! view "Rename session" (get-in (state view) [:view :session :name])
                    (fn [name] (invoke! view :rename-session {:name name})) "Enter saves; Esc preserves the current name.")}
   {:label "Attach project file" :description "/attach  @" :choose #(open-files! view nil)}
   {:label "Continue session" :description "/continue" :choose #(do (close-overlay! view) (fire! view :continue {}))}
   {:label "Compact context" :description "/compact" :choose #(do (close-overlay! view) (fire! view :compact {}))}
   {:label "Evaluate Clojure" :description "/eval  Expert input; not the default composer" :choose
    #(input-dialog! view "Evaluate Clojure" "" (fn [source] (invoke! view :submit {:mode :evaluate :text source}))
                    "Trusted local execution. Shift+Enter adds a line; Enter evaluates. Effects are not rolled back.")}
   {:label "Reset live environment" :description "/reload  Definitions and live objects are lost" :choose
    #(confirm! view "Reset the live environment?" "Durable history stays. Live definitions are discarded."
               (fn [] (fire! view :reload {})))}
   {:label "Reconnect to core" :description "/reconnect  Never repeats an interrupted mutation" :choose
    #(confirm! view "Reconnect to the core?" "The owned JVM is restarted; live definitions are lost."
               (fn [] (fire! view :reconnect {})))}
   {:label "Copy conversation" :description "/copy" :choose
    #(do (copy! view (str/join "\n\n" (map (fn [row]
                                              (str (present/row-title row) "\n"
                                                   (or (present/inline-content row true) ""))) (row-list view))))
         (close-overlay! view))}
   {:label "Export HTML" :description "/export  Local file only; no sharing" :choose
    #(input-dialog! view "Export conversation as HTML" "arrodes-session.html"
                    (fn [path] (invoke! view :export {:path path :format "html"}))
                    "Writes an explicit local export. It does not upload or share your session.")}
   {:label "Show or hide reasoning" :description "/reasoning" :choose
    #(do (ui! view update :show-reasoning? not) (close-overlay! view))}
   {:label "Expand activity" :description "/expand" :choose
    #(do (ui! view assoc :expanded (into {} (map (juxt :id (constantly true)) (row-list view)))) (close-overlay! view))}
   {:label "Collapse activity" :description "/collapse" :choose #(do (ui! view assoc :expanded {}) (close-overlay! view))}
   {:label "Delete session" :description "/delete  Requires confirmation" :choose
    #(confirm! view "Delete this session?" "Permanently removes this session's stored history and retained results."
               (fn [] (fire! view :delete-session {:id (get-in (state view) [:view :session :id])})))}
   {:label "Keyboard help" :description "/help  F1"
    :choose
    (fn []
      (open-overlay! view {:kind :help :title "Keyboard" :query ""
                           :hint "Enter sends or steers. Ctrl+Q queues a follow-up. Escape closes a view before cancelling work."
                           :items [{:label "Composer" :description "Shift+Enter newline | @ files | / commands | Up/Down prompt history" :choose #(close-overlay! view)}
                                   {:label "Navigation" :description "F2 sessions | F3 or Ctrl+P commands | F6 next pane | PgUp/PgDn scroll" :choose #(close-overlay! view)}
                                   {:label "Conversation focus" :description "Up/Down select | Enter inspect | Space expand | End follow latest" :choose #(close-overlay! view)}
                                   {:label "Inspector" :description "1 summary | 2 output | 3 value | 4 code | y copy | Esc back" :choose #(close-overlay! view)}
                                   {:label "Selection and exit" :description "Drag selects text | Ctrl+C copies selection, otherwise stops work | Ctrl+D exits" :choose #(close-overlay! view)}]}))}
   {:label "Quit Arrodes" :description "/quit  Ctrl+D" :choose #(do (close-overlay! view) (quit! view))}])

(defn- remember-anchor! [view]
  (when (and (not (get-in (state view) [:ui :follow?] true))
             (nil? (:anchor @(:local view))))
    (let [top (.-screenY (.-viewport (:transcript view)))
          entries (keep #(get @(:records view) (:id %)) (row-list view))
          record (first (filter #(> (+ (.-screenY (:root %)) (.-height (:root %))) top) entries))]
      (when record
        (swap! (:local view) assoc :anchor
               {:id (:id @(:row record)) :offset (- (.-screenY (:root record)) top)})))))

(defn- styled-diff! [node text]
  (when (not= text (aget node "arrodesContent"))
    (aset node "arrodesContent" text)
    (let [chunks (mapv (fn [line]
                         ((.fg w/core (cond (str/starts-with? line "+ ") (:success w/colors)
                                            (str/starts-with? line "- ") (:error w/colors)
                                            (str/starts-with? line "@@") (:accent w/colors)
                                            :else (:muted w/colors)))
                          (str (model/safe-text line) "\n")))
                       (str/split-lines text))]
      (set! (.-content node) (js/Reflect.construct (.-StyledText w/core) #js [(clj->js chunks)])))))

(defn- make-row! [view row]
  (let [renderer (:renderer view)
        row* (atom row)
        message? (= :message (:kind row))
        root (w/box renderer {:id (str "row:" (:id row)) :width "100%" :paddingX 1
                              :marginTop (if message? 1 0) :marginBottom (if message? 1 0)})
        header (w/box renderer {:height 1 :flexDirection "row" :width "100%"})
        toggle (w/button renderer "[+]" #(toggle! view @row*) {:width 4})
        label (w/text renderer "" {:flexGrow 1 :flexShrink 1 :height 1 :wrapMode "none" :truncate true})
        status (w/text renderer "" {:width 12 :height 1 :fg (:muted w/colors) :wrapMode "none"})
        inspect (w/button renderer "inspect" #(inspect! view @row*) {:width 7 :fg (:faint w/colors)})
        body (if message?
               (w/markdown renderer (:syntax view) "" {:width "100%"})
               (w/text renderer "" {:width "100%" :fg (:muted w/colors)}))]
    (set! (.-visible toggle) (not message?))
    (w/add! header toggle label status inspect)
    (w/add! root header body)
    {:root root :row row* :header header :toggle toggle :label label :status status
     :body body :message? message? :signature (atom nil)}))

(defn- update-row! [view record row]
  (let [s (state view)
        expanded (or (expanded? view row)
                     (and (= :reasoning (:kind row)) (get-in s [:ui :show-reasoning?])))
        selected (= (:id row) (get-in s [:ui :selected]))
        signature [row expanded selected]]
    (when (not= signature @(:signature record))
      (reset! (:signature record) signature)
      (reset! (:row record) row)
      (let [a (present/activity row)
            text (or (present/inline-content row expanded) "")
            user? (= :user (:role row))
            color (cond (present/failed? a) (:error w/colors)
                        (= :running (:status a)) (:accent w/colors)
                        :else (:muted w/colors))]
        (set! (.-backgroundColor (:root record))
              (cond selected (:raised w/colors) user? (:surface w/colors) :else (:background w/colors)))
        (w/content! (:label record) (present/row-title row))
        (set! (.-fg (:label record)) (if (:message? record) (:accent w/colors) (:text w/colors)))
        (w/content! (:toggle record) (if expanded "[-]" "[+]"))
        (w/content! (:status record)
                    (cond a (present/status-label a) (:streaming? row) "writing" :else ""))
        (set! (.-fg (:status record)) color)
        (set! (.-visible (:body record)) (not (str/blank? text)))
        (when-not (:message? record)
          (set! (.-maxHeight (:body record)) (if expanded 40 6)))
        (if (:message? record)
          (do
            (when (not= text (.-content (:body record))) (set! (.-content (:body record)) text))
            (set! (.-streaming (:body record)) (boolean (:streaming? row))))
          (if (present/recorded-diff a)
            (styled-diff! (:body record) text)
            (w/content! (:body record) text)))))))

(defn- render-rows! [view]
  (let [rows (row-list view)
        ui (:ui (state view))
        signature [rows (:expanded ui) (:selected ui) (:show-reasoning? ui)]]
    (when (not= signature (:rows-signature @(:local view)))
      (swap! (:local view) assoc :rows-signature signature)
      (let [existing @(:records view)
            desired (set (map :id rows))]
        (doseq [[id record] existing :when (not (contains? desired id))]
          (.remove (:transcript view) (:root record))
          (.destroyRecursively (:root record))
          (swap! (:records view) dissoc id))
        (doseq [[index row] (map-indexed vector rows)]
          (let [record (or (get @(:records view) (:id row))
                           (let [record (make-row! view row)]
                             (swap! (:records view) assoc (:id row) record)
                             (.add (:transcript view) (:root record) index)
                             record))]
            (update-row! view record row))))
      (set! (.-visible (:welcome view)) (empty? rows)))))

(defn- focus! [view target]
  (let [target (if (and (= target :inspector) (not (get-in (state view) [:ui :inspector?])))
                 :composer target)]
    (ui! view assoc :focus target)
    (case target
      :transcript (.focus (:transcript view))
      :inspector (.focus (:inspector-scroll view))
      (.focus (:composer view)))))

(defn- follow! [view]
  (swap! (:local view) assoc :anchor nil)
  (ui! view assoc :follow? true)
  (set! (.-stickyScroll (:transcript view)) true)
  (.scrollTo (:transcript view) (.-scrollHeight (:transcript view)))
  (schedule! view))

(defn- close-inspector! [view]
  (let [return-focus (get-in (state view) [:ui :inspect-return-focus] :composer)]
    (ui! view assoc :inspector? false)
    (focus! view return-focus)))

(defn- inspect! [view row]
  (let [s (state view)]
    (ui! view assoc :selected (:id row) :inspected-row row :inspector? true
         :inspect-return-focus (get-in s [:ui :focus] :composer)
         :inspect-tab (present/default-tab row) :inspection nil)
    (request-inspection! view row)
    (focus! view :inspector)))

(defn- load-artifact-page! [view artifact-id offset]
  (let [selection (get-in (state view) [:ui :selected])]
    (ui! view assoc-in [:inspection :loading?] true)
    (-> (invoke! view :artifact {:artifact-id artifact-id :offset offset :limit 12000})
        (.then (fn [page]
                 (when (= selection (get-in (state view) [:ui :selected]))
                   (ui! view update :inspection assoc :page page :loading? false))))
        (.catch (fn [_] (ui! view assoc-in [:inspection :loading?] false))))))

(defn- request-inspection! [view row]
  (when-let [id (model/field (:result (present/activity row)) :id)]
    (let [selection (:id row)]
      (ui! view assoc :inspection {:result-id id :loading? true})
      (-> (invoke! view :result {:result-id id})
          (.then (fn [descriptor]
                   (when (= selection (get-in (state view) [:ui :selected]))
                     (ui! view update :inspection assoc :descriptor descriptor :loading? false)
                     (when-let [artifact-id (model/field descriptor :artifact-id)]
                       (load-artifact-page! view artifact-id 1)))))
          (.catch (fn [_] (ui! view assoc-in [:inspection :loading?] false)))))))

(defn- branch! [view row]
  (when-let [entry-id (:entry-id row)]
    (confirm! view "Branch from this recorded point?"
              "Creates a fresh session and REPL. It does not restore or undo files."
              (fn []
                (-> (invoke! view :branch {:entry-id entry-id})
                    (.then #(do (close-inspector! view) (follow! view)))
                    (.catch (fn [_] nil)))))))

(defn- render-inspector! [view]
  (let [s (state view)
        row (selected-row view)
        open? (and (get-in s [:ui :inspector?]) row)
        wide? (>= (.-terminalWidth (:renderer view)) 112)
        panel (:inspector view)]
    (set! (.-visible panel) (boolean open?))
    (set! (.-visible (:conversation view)) (or (not open?) wide?))
    (set! (.-visible (:new-activity view))
          (and (or (not open?) wide?) (not (get-in s [:ui :follow?] true))))
    (set! (.-width panel) (if wide? (min 54 (js/Math.floor (* 0.42 (.-terminalWidth (:renderer view))))) "100%"))
    (when open?
      (let [tab (get-in s [:ui :inspect-tab] :summary)
            data (present/inspection (:view s) row tab (get-in s [:ui :inspection]))
            signature [row tab (get-in s [:ui :inspection])]]
        (w/content! (:inspector-title view) (:title data))
        (w/content! (:inspector-lifetime view) (:lifetime data))
        (set! (.-visible (:inspector-branch view)) (boolean (:entry-id row)))
        (set! (.-visible (:inspector-next view))
              (boolean (get-in s [:ui :inspection :page :truncated?])))
        (set! (.-visible (:inspector-prev view))
              (> (or (get-in s [:ui :inspection :page :offset]) 1) 1))
        (doseq [[key button] (:inspector-tabs view)]
          (set! (.-fg button) (if (= key tab) (:accent w/colors) (:faint w/colors))))
        (when (not= signature (:inspector-signature @(:local view)))
          (swap! (:local view) assoc :inspector-signature signature :inspector-text (:content data))
          (if (:diff? data)
            (styled-diff! (:inspector-output view) (:content data))
            (w/content! (:inspector-output view) (:content data))))
        (when (and (:result-id data) (not= (:result-id data) (get-in s [:ui :inspection :result-id])))
          (request-inspection! view row))))))

(defn- render-pending! [view]
  (let [items (get-in (state view) [:view :queue])
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
                            {:flexGrow 1 :flexShrink 1 :height 1 :wrapMode "none" :truncate true :fg (:muted w/colors)})
              edit (w/button (:renderer view) "[Edit]"
                             #(input-dialog! view "Edit pending message" content
                                             (fn [text] (invoke! view :queue-edit {:id (:id item) :text text}))
                                             "Edits the pending item atomically. Image attachments are preserved.")
                             {:width 8})
              drop (w/button (:renderer view) "[Drop]" #(fire! view :queue-drop {:id (:id item)})
                             {:width 6 :fg (:muted w/colors)})]
          (w/add! row label edit drop)
          (.add (:pending-items view) row))))))

(defn- render-attachments! [view]
  (let [items (get-in (state view) [:ui :attachments])]
    (when (not= items (:attachment-signature @(:local view)))
      (swap! (:local view) assoc :attachment-signature items)
      (w/clear! (:attachment-items view))
      (doseq [item items]
        (let [path (or (:path item) (:name item))
              button (w/button (:renderer view) (str "[" (basename path) " x]")
                               #(ui! view update :attachments
                                     (fn [items] (vec (remove (fn [x] (= path (or (:path x) (:name x)))) items))))
                               {:fg (:muted w/colors) :marginRight 1})]
          (.add (:attachment-items view) button))))))

(defn- render-overlay! [view]
  (let [overlay (get-in (state view) [:ui :overlay])
        renderer (:renderer view)]
    (set! (.-visible (:modal-shade view)) (boolean overlay))
    (if-not overlay
      (when (:modal-token @(:local view))
        (swap! (:local view) assoc :modal-token nil :modal-signature nil
               :modal-selection-signature nil :modal-scroll-choice nil :modal-index nil)
        (.setText (:modal-input view) ""))
      (let [width (max 24 (min 94 (- (.-terminalWidth renderer) 4)))
            height (max 10 (min 26 (- (.-terminalHeight renderer) 4)))
            input? (= :input (:kind overlay))
            body? (some? (:body overlay))
            query? (not (or (= :confirm (:kind overlay)) body?))
            items (overlay-items view overlay)
            index (min (max 0 (dec (count items))) (or (:index overlay) 0))
            signature [(:token overlay) (:query overlay) (:body overlay)
                       (mapv #(select-keys % [:label :description]) items)]
            rebuild? (not= signature (:modal-signature @(:local view)))
            selection-signature [signature index width height]]
        (set! (.-width (:modal view)) width)
        (set! (.-height (:modal view)) height)
        (set! (.-left (:modal view)) (max 0 (js/Math.floor (/ (- (.-terminalWidth renderer) width) 2))))
        (set! (.-top (:modal view)) (max 0 (js/Math.floor (/ (- (.-terminalHeight renderer) height) 2))))
        (w/content! (:modal-title view) (:title overlay))
        (w/content! (:modal-hint view)
                    (str (:hint overlay) (when (:error overlay) (str "\nError: " (:error overlay)))))
        (set! (.-visible (:modal-input view)) query?)
        (set! (.-height (:modal-input view)) (if input? (min 7 (max 3 (- height 12))) 1))
        (set! (.-textColor (:modal-input view)) (if (:secret? overlay) (:surface w/colors) (:text w/colors)))
        (set! (.-focusedTextColor (:modal-input view)) (if (:secret? overlay) (:surface w/colors) (:text w/colors)))
        (set! (.-selectionFg (:modal-input view)) (if (:secret? overlay) (:surface w/colors) (:text w/colors)))
        (set! (.-selectionBg (:modal-input view)) (if (:secret? overlay) (:surface w/colors) (:selection w/colors)))
        (set! (.-placeholder (:modal-input view)) (if input? "Enter a value..." "Type to filter..."))
        (when (not= (.-plainText (:modal-input view)) (or (:query overlay) ""))
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
                          {:fg (:muted w/colors) :marginTop 1}))
            body? (.add (:modal-list view) (w/text renderer (:body overlay) {:width "100%"}))
            (empty? items) (.add (:modal-list view) (w/text renderer "No matching items." {:fg (:muted w/colors)}))
            :else
            (doseq [[i item] (map-indexed vector items)]
              (let [row (w/box renderer {:id (str "choice-" i) :width "100%" :paddingX 1 :paddingY 0
                                         :marginBottom 1 :backgroundColor (if (= i index) (:raised w/colors) (:surface w/colors))})
                    button (w/button renderer (:label item) (:choose item) {:id (str "choice-" i "-label") :width "100%"
                                                                          :fg (if (= i index) (:accent w/colors) (:text w/colors))})
                    description (w/text renderer (:description item) {:width "100%" :fg (:muted w/colors)})]
                (w/add! row button description)
                (.add (:modal-list view) row)))))
        (when (not= selection-signature (:modal-selection-signature @(:local view)))
          (when (and (not rebuild?) (not= index (:modal-index @(:local view))))
            (doseq [i [(:modal-index @(:local view)) index]]
              (when-let [row (.findDescendantById (:modal-list view) (str "choice-" i))]
                (set! (.-backgroundColor row) (if (= i index) (:raised w/colors) (:surface w/colors)))
                (when-let [label (.findDescendantById row (str "choice-" i "-label"))]
                  (set! (.-fg label) (if (= i index) (:accent w/colors) (:text w/colors)))))))
          ;; Keep measured rows when selecting; scroll only after layout has settled.
          (swap! (:local view) assoc :modal-selection-signature selection-signature :modal-index index
                 :modal-scroll-choice
                 (when (and (seq items) (not input?) (not body?)) (str "choice-" index))))
        (w/content! (:modal-footer view)
                    (cond input? "Enter submit   Shift+Enter newline   Esc cancel"
                          body? "Esc back"
                          (= :pending (:kind overlay)) "Up/Down select   Enter edit   Delete drop   Esc back"
                          :else (str "Up/Down select   Enter choose   Esc back"
                                     (when (= 100 (count items)) "   100 shown; narrow search"))))))))

(defn- respond-host! [view id result cancel?]
  (swap! (:local view) assoc :handled-host id)
  (fire! view (if cancel? :host-cancel :host-response) (if cancel? {:id id} {:id id :result result}))
  (close-overlay! view))

(defn- render-host-request! [view]
  (let [s (state view)
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
          (do (notify! view (or (:message request) (:url request) (present/pretty (dissoc request :kind))))
              (swap! (:local view) assoc :handled-host id)
              (fire! view :host-response {:id id :result nil}))
          :input
          (open-overlay! view {:kind :input :host-id id :title title :query "" :secret? (:secret? request)
                               :hint (when (map? (:prompt request)) (present/pretty (:prompt request)))
                               :on-submit #(respond-host! view id % false)})
          :confirm
          (open-overlay! view {:kind :confirm :host-id id :title title
                               :hint (or (:message request) (present/pretty (dissoc request :kind :timeout-ms)))
                               :items [{:label "No" :description "Decline" :choose #(respond-host! view id false false)}
                                       {:label "Yes" :description "Approve the displayed request" :choose #(respond-host! view id true false)}]})
          :select
          (open-overlay! view {:kind :choices :host-id id :title title :query ""
                               :items (conj (mapv (fn [item]
                                                   {:label (if (map? item) (or (:label item) (:name item) (str (:value item))) (str item))
                                                    :description ""
                                                    :choose #(respond-host! view id (if (map? item) (or (:value item) (:id item) (:label item)) item) false)})
                                                 (:options request)) cancel)})
          (open-overlay! view {:kind :confirm :host-id id :title "Unsupported host capability"
                               :hint (str "No frontend implementation is registered for " (:name request)
                                          ". The core request is not approved or executed by this UI.")
                               :items [cancel]}))))))

(defn- submit! [view mode]
  (let [raw (.-plainText (:composer view))
        session-id (get-in (state view) [:view :session :id])
        slash (when (and (= mode :prompt) (not (str/starts-with? raw "//")))
                (re-matches #"(?s)^/([^\s]+)(?:\s+(.*))?$" (str/trim raw)))
        command (when slash
                  (some #(when (= (str "/" (second slash))
                                  (first (str/split (:description %) #"\s+"))) %) (commands view)))]
    ;; Read the editor itself at submit time: native change notifications may
    ;; still be queued after a fast paste/Enter sequence.
    (ui! view assoc :draft raw)
    (cond
      (and session-id (= session-id (:submitting-session @(:local view)))) nil
      (and slash (= "eval" (second slash)) (seq (nth slash 2)))
      (do (ui! view assoc :draft "") (.setText (:composer view) "")
          (input-dialog! view "Evaluate Clojure" (nth slash 2)
                         #(invoke! view :submit {:mode :evaluate :text %})
                         "Trusted execution. Enter evaluates; Shift+Enter adds a line."))
      command
      (do (ui! view assoc :draft "") (.setText (:composer view) "") ((:choose command)))
      slash (notify! view (str "Unknown command /" (second slash) ". F3 lists commands; prefix // to send a slash literally.") :error)
      (not (ready? view)) (notify! view "Core is not connected. Use /reconnect; the draft is preserved." :error)
      (and (str/blank? raw) (empty? (get-in (state view) [:ui :attachments]))) nil
      :else
      (let [text (if (and (= mode :prompt) (str/starts-with? raw "//")) (subs raw 1) raw)]
        (swap! (:local view) assoc :submitting-session session-id :history-index nil)
        (-> (invoke! view :submit {:text text :draft-text raw :mode mode})
            (.then (fn [_]
                     (when (= session-id (get-in (state view) [:view :session :id]))
                       (let [current (.-plainText (:composer view))]
                         (if (= current raw)
                           (do
                             (swap! (:local view) assoc :syncing-editor? true)
                             (try (.setText (:composer view) "")
                                  (finally (swap! (:local view) assoc :syncing-editor? false))))
                           (ui! view assoc :draft current)))
                       (follow! view))
                     (swap! (:local view) update :prompt-history
                            #(vec (take-last 100 (if (= raw (peek %)) % (conj (or % []) raw)))))))
            (.catch (fn [_] nil))
            (.finally (fn []
                        (when (= session-id (:submitting-session @(:local view)))
                          (swap! (:local view) dissoc :submitting-session))
                        (schedule! view))))))))

(defn- history-draft! [view direction]
  (let [{:keys [prompt-history history-index]} @(:local view)
        n (count prompt-history)]
    (when (pos? n)
      (when (nil? history-index)
        (swap! (:local view) assoc :history-draft (.-plainText (:composer view))))
      (let [index (max 0 (min n (+ (or history-index n) direction)))
            text (if (= index n) (:history-draft @(:local view)) (nth prompt-history index))]
        (swap! (:local view) assoc :history-index index)
        (ui! view assoc :draft text)))))

(defn- select-row! [view direction]
  (let [rows (row-list view)
        selected (get-in (state view) [:ui :selected])
        old (first (keep-indexed #(when (= selected (:id %2)) %1) rows))
        index (max 0 (min (dec (count rows)) (+ (or old (if (pos? direction) -1 (count rows))) direction)))]
    (when-let [row (get rows index)]
      (ui! view assoc :selected (:id row) :inspected-row row :follow? false)
      (.scrollChildIntoView (:transcript view) (str "row:" (:id row)))
      (when (get-in (state view) [:ui :inspector?]) (request-inspection! view row)))))

(defn- quit! [view]
  (if (busy? view)
    (confirm! view "Stop work and close Arrodes?"
              "Cancellation is cooperative. Completed external effects are not rolled back."
              (:on-quit view))
    ((:on-quit view))))

(defn- escape! [view]
  (let [s (state view) overlay (get-in s [:ui :overlay])]
    (cond
      (.-hasSelection (:renderer view)) (.clearSelection (:renderer view))
      (:host-id overlay) (respond-host! view (:host-id overlay) nil true)
      overlay (close-overlay! view)
      (get-in s [:ui :inspector?]) (close-inspector! view)
      (busy? view) (fire! view :cancel {})
      :else (focus! view :composer))))

(defn- key! [view event]
  (when (not= "release" (.-eventType event))
    (let [name (.-name event) ctrl (.-ctrl event) alt (or (.-meta event) (.-option event))
          shift (.-shift event) s (state view) overlay (get-in s [:ui :overlay])
          focus (get-in s [:ui :focus] :composer)
          enter? (contains? #{"return" "enter"} name)
          handled
          (cond
            (and ctrl (= name "c"))
            (do
              (when-not (copy-selection! view)
                (cond
                  overlay (escape! view)
                  (busy? view) (fire! view :cancel {})
                  :else
                  (do
                    (when (seq (.-plainText (:composer view)))
                      (swap! (:local view) update :prompt-history
                             #(vec (take-last 100 (conj (or % []) (.-plainText (:composer view)))))))
                    (ui! view assoc :draft ""))))
              true)
            (and ctrl (= name "d")) (do (quit! view) true)
            (= name "escape") (do (escape! view) true)
            (= name "f1") (do ((:choose (some #(when (= "Keyboard help" (:label %)) %) (commands view)))) true)
            (= name "f2") (do (open-sessions! view) true)
            (or (= name "f3") (and ctrl (= name "p")))
            (do (open-overlay! view {:kind :commands :title "Commands" :query "" :hint "Search actions or slash commands."}) true)
            overlay
            (cond
              (and enter? (not shift)) (do (choose-overlay! view) true)
              (and (contains? #{"up" "down"} name) (not= :input (:kind overlay)))
              (do (ui! view update-in [:overlay :index]
                       #(let [n (count (overlay-items view overlay))]
                          (max 0 (min (max 0 (dec n)) (+ (or % 0) (if (= name "up") -1 1)))))) true)
              (and (= name "delete") (= :pending (:kind overlay)))
              (do (when-let [drop (:drop (get (overlay-items view overlay) (or (:index overlay) 0)))] (drop)) true)
              :else false)
            (= name "f6")
            (do (focus! view (case focus :composer :transcript
                                   :transcript (if (get-in s [:ui :inspector?]) :inspector :composer)
                                   :composer)) true)
            (or (= name "pageup") (= name "pagedown"))
            (do (when (= focus :composer) (focus! view :transcript))
                (let [scroll (if (= focus :inspector) (:inspector-scroll view) (:transcript view))]
                  (when (not= focus :inspector) (ui! view assoc :follow? false))
                  (.scrollBy scroll (* (if (= name "pageup") -1 1) (max 3 (- (.-height scroll) 2))))) true)
            (and (= name "end") (not= focus :composer)) (do (follow! view) true)
            (= focus :transcript)
            (cond
              (contains? #{"up" "down" "j" "k"} name) (do (select-row! view (if (contains? #{"up" "k"} name) -1 1)) true)
              enter? (do (when-let [row (selected-row view)] (inspect! view row)) true)
              (or (= name "space") (= name " ")) (do (when-let [row (selected-row view)] (toggle! view row)) true)
              (= name "b") (do (when-let [row (selected-row view)] (branch! view row)) true)
              :else false)
            (= focus :inspector)
            (cond
              (contains? #{"1" "2" "3" "4"} name)
              (do (ui! view assoc :inspect-tab (get {"1" :summary "2" :output "3" :value "4" :code} name))
                  (.scrollTo (:inspector-scroll view) 0) true)
              (= name "y") (do (copy! view (or (:inspector-text @(:local view)) "")) true)
              (contains? #{"j" "k"} name) (do (.scrollBy (:inspector-scroll view) (if (= name "j") 1 -1)) true)
              :else false)
            (or (and ctrl (= name "q")) (and ctrl enter?)) (do (submit! view :follow-up) true)
            (and enter? (not (or shift alt ctrl))) (do (submit! view :prompt) true)
            (and (= name "up") (zero? (.-line (.-logicalCursor (:composer view)))))
            (do (history-draft! view -1) true)
            (and (= name "down") (some? (:history-index @(:local view))))
            (do (history-draft! view 1) true)
            :else false)]
      (when handled (w/consume! event)))))

(defn- render-chrome! [view]
  (let [s (state view) renderer (:renderer view)
        width (.-terminalWidth renderer) height (.-terminalHeight renderer)
        session (get-in s [:view :session]) config (:config session)
        connection (get-in s [:connection :status])
        operation (get-in s [:view :operation])
        running (busy? view)
        start (or (:started-at operation) (:created-at operation) (:time operation))
        elapsed (when (and running (number? start))
                  (str " " (max 0 (quot (- (.now js/Date) start) 1000)) "s"))
        phase (cond (= connection :starting) "Connecting"
                    (= connection :disconnected) "Disconnected"
                    (= connection :closing) "Closing"
                    (= :cancelling (:status operation)) "Cancelling"
                    running (str "Working" elapsed)
                    (= :failed (:status operation)) "Failed"
                    (= :cancelled (:status operation)) "Cancelled"
                    (= :interrupted (:status operation)) "Interrupted"
                    :else "Idle")
        notice (:notice s)
        notice-text (if (map? notice)
                      (str (when (:unknown-outcome? notice) "Outcome unknown; inspect before resubmitting. ")
                           (:message notice))
                      notice)]
    (w/content! (:brand view) (str "ARRODES / " (basename (:cwd session))))
    (w/content! (:session-title view) (or (:name session) "Opening session"))
    (set! (.-visible (:header-sessions view)) (>= width 70))
    (set! (.-visible (:header-commands view)) (>= width 55))
    (w/content! (:footer-status view)
                (str (or (:model config) "Core") " / "
                     (name (keyword (or (:thinking config) "high"))) "   " phase))
    (set! (.-fg (:footer-status view))
          (if (contains? #{:disconnected :closing} connection) (:error w/colors) (:accent w/colors)))
    (w/content! (:footer-keys view)
                (case (get-in s [:ui :focus])
                  :transcript (if (< width 85) "Enter inspect | F6 focus"
                                  "Enter inspect | Space expand | F6 focus | End latest")
                  :inspector (if (< width 85) "1-4 view | y copy | Esc back"
                                 "1-4 views | y copy | F6 focus | Esc back")
                  (if (< width 85)
                    (if running "Enter steer | ^Q queue | Esc stop" "Enter send | F3 commands")
                    (if running "Enter steer | Ctrl+Q follow-up | Esc stop"
                        "Enter send | @ attach | / commands | F6 focus"))))
    (set! (.-visible (:notice-box view)) (boolean (seq notice-text)))
    (w/content! (:notice-text view) (or notice-text ""))
    (set! (.-fg (:notice-text view))
          (if (= :error (:kind notice)) (:error w/colors) (:muted w/colors)))
    (set! (.-visible (:attachment-row view)) (>= height 14))
    (set! (.-height (:header view)) (if (< height 14) 1 2))
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
    (set! (.-borderColor (:composer-box view))
          (if (= :composer (get-in s [:ui :focus])) (:accent w/colors) (:border w/colors)))
    (set! (.-stickyScroll (:transcript view)) (boolean (get-in s [:ui :follow?] true)))))

(defn- refresh! [view]
  (when-not @(:closed? view)
    (let [session-id (get-in (state view) [:view :session :id])
          switching? (not= session-id (:rendered-session @(:local view)))]
      (if switching?
        (swap! (:local view) assoc :rendered-session session-id :anchor nil :inspector-signature nil
               :restore-scroll (get-in (state view) [:ui :scroll-top] 0))
        (remember-anchor! view))
      (render-chrome! view)
      (render-rows! view)
      (render-pending! view)
      (render-attachments! view)
      (render-inspector! view)
      (render-host-request! view)
      (render-overlay! view)
      (.requestRender (:renderer view)))))

(defn- schedule! [view]
  (when (and (not @(:closed? view)) (nil? (:render-timer @(:local view))))
    (swap! (:local view) assoc :render-timer
           (js/setTimeout (fn []
                            (swap! (:local view) assoc :render-timer nil)
                            (try (refresh! view)
                                 (catch :default error
                                   (notify! view (str "Display error: " (error-text error)) :error))))
                          16))))

(defn- frame! [view]
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
             (= (get-in (state view) [:view :session :id])
                (:rendered-session @(:local view))))
    (let [scroll (:transcript view) renderer (:renderer view)]
      (when-some [position (:restore-scroll @(:local view))]
        (swap! (:local view) dissoc :restore-scroll)
        (.scrollTo scroll position))
      (when-let [{:keys [id offset]} (:anchor @(:local view))]
        (swap! (:local view) assoc :anchor nil)
        (when-let [record (get @(:records view) id)]
          (let [delta (- (.-screenY (:root record)) (.-screenY (.-viewport scroll)) offset)]
            (when (not (zero? delta)) (.scrollBy scroll delta)))))
      (when (and (get-in (state view) [:ui :follow?] true) (not (.-hasSelection renderer)))
        (let [target (max 0 (- (.-scrollHeight scroll) (.-height (.-viewport scroll))))]
          (when (> (js/Math.abs (- target (.-scrollTop scroll))) 1)
            (.scrollTo scroll target))))
      (let [position (.-scrollTop scroll)]
        (when (not= position (get-in (state view) [:ui :scroll-top]))
          (ui! view assoc :scroll-top position))))))

(defn mount!
  "Mount the real view on an OpenTUI renderer; the controller remains renderer-independent."
  [application renderer options]
  (let [view-ref (atom nil)
        local (atom {:rows [] :prompt-history []})
        closed? (atom false)
        root (w/box renderer {:id "arrodes" :width "100%" :height "100%" :backgroundColor (:background w/colors)})
        header (w/box renderer {:height 2 :width "100%" :flexDirection "row" :paddingX 2
                                :border ["bottom"] :borderColor (:border w/colors)})
        brand (w/text renderer "ARRODES" {:fg (:accent w/colors) :width 30 :height 1 :truncate true :wrapMode "none"})
        session-title (w/text renderer "Opening session" {:flexGrow 1 :flexShrink 1 :height 1 :truncate true :wrapMode "none"})
        header-sessions (w/button renderer "[Sessions]" (fn [] (open-sessions! @view-ref)) {:width 12})
        header-commands (w/button renderer "[Commands]" (fn [] (open-overlay! @view-ref {:kind :commands :title "Commands" :query ""})) {:width 10})
        body (w/box renderer {:width "100%" :flexDirection "row" :flexGrow 1 :flexShrink 1 :minHeight 1 :overflow "hidden"})
        conversation (w/box renderer {:flexGrow 1 :flexShrink 1 :minWidth 1 :height "100%"})
        transcript (w/scrollbox renderer {:id "conversation" :width "100%" :height "100%" :stickyScroll true :stickyStart "bottom"
                                          :contentOptions {:paddingX 1 :paddingBottom 1 :flexDirection "column"}
                                          :onMouseDown (fn [_] (when-let [view @view-ref] (focus! view :transcript)))
                                          :onMouseScroll (fn [_] (when-let [view @view-ref] (ui! view assoc :follow? false)))})
        welcome (w/text renderer "Describe what you want to do.\n\nUse @ to attach project context, or / to find a command.\nExecution stays inspectable without taking over the conversation."
                        {:position "absolute" :top 3 :left 3 :width "85%" :fg (:muted w/colors)})
        inspector (w/box renderer {:id "inspector" :visible false :width 44 :height "100%" :paddingX 1
                                   :border ["left"] :borderColor (:border w/colors)})
        inspector-header (w/box renderer {:width "100%" :height 2 :flexDirection "row"})
        inspector-title (w/text renderer "INSPECT" {:flexGrow 1 :flexShrink 1 :height 1 :truncate true :wrapMode "none" :fg (:accent w/colors)})
        inspector-close (w/button renderer "[x]" (fn [] (close-inspector! @view-ref)) {:width 3})
        inspector-tab-row (w/box renderer {:height 2 :width "100%" :flexDirection "row" :gap 1})
        inspector-tabs (into {} (map (fn [[tab label]]
                                       [tab (w/button renderer label
                                                      (fn []
                                                        (ui! @view-ref assoc :inspect-tab tab)
                                                        (focus! @view-ref :inspector)
                                                        (.scrollTo (:inspector-scroll @view-ref) 0))
                                                      {:width 8})])
                                     [[:summary "1 Sum"] [:output "2 Output"] [:value "3 Value"] [:code "4 Code"]]))
        inspector-scroll (w/scrollbox renderer {:id "inspection" :width "100%"
                                                :onMouseDown (fn [_] (focus! @view-ref :inspector))})
        inspector-output (w/text renderer "" {:width "100%"})
        inspector-actions (w/box renderer {:width "100%" :flexDirection "row" :height 2 :marginTop 1})
        inspector-copy (w/button renderer "[Copy]" (fn [] (copy! @view-ref (or (:inspector-text @local) ""))) {:width 8})
        inspector-prev (w/button renderer "[Prev]"
                                 (fn []
                                   (let [s (state @view-ref)]
                                     (load-artifact-page! @view-ref
                                                          (get-in s [:ui :inspection :descriptor :artifact-id])
                                                          (max 1 (- (get-in s [:ui :inspection :page :offset] 1) 12000)))))
                                 {:width 8 :visible false})
        inspector-next (w/button renderer "[Next page]"
                                 (fn []
                                   (let [s (state @view-ref)]
                                     (load-artifact-page! @view-ref
                                                          (get-in s [:ui :inspection :descriptor :artifact-id])
                                                          (get-in s [:ui :inspection :page :next-offset]))))
                                 {:width 13 :visible false})
        inspector-branch (w/button renderer "[Branch]" (fn [] (when-let [row (selected-row @view-ref)] (branch! @view-ref row)))
                                   {:width 9 :visible false})
        inspector-lifetime (w/text renderer "" {:fg (:faint w/colors) :width "100%" :maxHeight 3 :marginBottom 1})
        new-activity (w/button renderer "New activity below - jump to latest" (fn [] (follow! @view-ref))
                               {:visible false :width "100%" :height 1 :paddingX 2 :bg (:surface w/colors)})
        pending (w/box renderer {:visible false :width "100%" :paddingX 2 :border ["top"] :borderColor (:border w/colors)})
        pending-title (w/text renderer "PENDING" {:fg (:accent w/colors) :height 1})
        pending-items (w/box renderer {:width "100%"})
        pending-more (w/button renderer "" (fn [] (open-overlay! @view-ref {:kind :pending :title "Pending messages" :query ""
                                                                           :hint "Enter edits; Delete drops a still-pending message."}))
                               {:visible false :height 1 :fg (:muted w/colors)})
        notice-box (w/box renderer {:visible false :height 1 :width "100%" :paddingX 2 :flexDirection "row"})
        notice-text (w/text renderer "" {:height 1 :flexGrow 1 :flexShrink 1 :truncate true :wrapMode "none"})
        notice-detail (w/button renderer "[Details]"
                                (fn []
                                  (let [notice (:notice (state @view-ref))]
                                    (open-overlay! @view-ref
                                                   {:kind :notice :title "Notice"
                                                    :body (if (map? notice)
                                                            (str (:message notice) "\n\n" (when (:data notice) (present/pretty (:data notice))))
                                                            (str notice))})))
                                {:width 10 :fg (:muted w/colors)})
        notice-close (w/button renderer "[x]" (fn [] (swap! (:state application) assoc :notice nil)) {:width 3 :fg (:faint w/colors)})
        composer-box (w/box renderer {:width "100%" :paddingX 2 :paddingTop 1 :border ["top"] :borderColor (:accent w/colors)})
        composer (w/create renderer "TextareaRenderable"
                           {:id "composer" :height 2 :width "100%" :wrapMode "word"
                            :initialValue "" :placeholder "Ask a question or describe a change..."
                            :backgroundColor (:background w/colors) :textColor (:text w/colors)
                            :focusedBackgroundColor (:background w/colors) :focusedTextColor (:text w/colors)
                            :cursorColor (:accent w/colors) :selectionBg (:selection w/colors)
                            :keyBindings [{:name "return" :action "submit"}
                                          {:name "return" :shift true :action "newline"}
                                          {:name "j" :ctrl true :action "newline"}]
                            :onSubmit (fn [_] (when-let [view @view-ref] (submit! view :prompt)))
                            :onPaste (fn [event]
                                       (when (> (.-byteLength (.-bytes event)) 262144)
                                         (w/consume! event)
                                         (notify! @view-ref "Paste exceeds 256 KiB; attach a file instead." :error)))
                            :onContentChange
                            (fn [_]
                              (when-let [view @view-ref]
                                (when-not (:syncing-editor? @local)
                                  (let [text (.-plainText (:composer view))]
                                    (ui! view assoc :draft text)
                                    (when-not (get-in (state view) [:ui :overlay])
                                      (cond
                                        (= text "/") (open-overlay! view {:kind :commands :title "Commands" :query "" :hint "Search actions or slash commands."})
                                        (re-find #"(?:^|\s)@$" text) (open-files! view "@")))))))})
        attachment-row (w/box renderer {:height 1 :width "100%" :flexDirection "row"})
        attach-button (w/button renderer "[@ Context]" (fn [] (open-files! @view-ref nil)) {:width 13 :fg (:faint w/colors)})
        attachment-items (w/box renderer {:height 1 :flexGrow 1 :flexShrink 1 :flexDirection "row" :overflow "hidden"})
        footer (w/box renderer {:width "100%" :height 1 :paddingX 2 :flexDirection "row" :backgroundColor (:surface w/colors)})
        footer-status (w/text renderer "" {:flexGrow 1 :flexShrink 1 :height 1 :truncate true :wrapMode "none"})
        footer-keys (w/text renderer "" {:height 1 :fg (:muted w/colors) :wrapMode "none" :truncate true})
        modal-shade (w/box renderer {:id "dialog-layer" :visible false :position "absolute" :top 0 :left 0
                                     :width "100%" :height "100%" :zIndex 100 :backgroundColor "#0b0e12"})
        modal (w/box renderer {:position "absolute" :width 90 :height 24 :paddingX 2 :paddingY 1
                               :border true :borderColor (:accent w/colors) :backgroundColor (:surface w/colors)})
        modal-header (w/box renderer {:width "100%" :height 2 :flexDirection "row"})
        modal-title (w/text renderer "" {:height 1 :flexGrow 1 :flexShrink 1 :fg (:accent w/colors) :truncate true :wrapMode "none"})
        modal-close (w/button renderer "[x]" (fn [] (escape! @view-ref)) {:width 3})
        modal-hint (w/text renderer "" {:width "100%" :maxHeight 4 :fg (:muted w/colors) :marginBottom 1})
        modal-input (w/create renderer "TextareaRenderable"
                             {:id "dialog-input" :width "100%" :height 1 :initialValue ""
                              :backgroundColor (:surface w/colors) :focusedBackgroundColor (:surface w/colors)
                              :textColor (:text w/colors) :focusedTextColor (:text w/colors)
                              :selectionBg (:selection w/colors) :cursorColor (:accent w/colors)
                              :keyBindings [{:name "return" :action "submit"} {:name "return" :shift true :action "newline"}]
                              :onSubmit (fn [_] (choose-overlay! @view-ref))
                              :onContentChange
                              (fn [_]
                                (when-let [view @view-ref]
                                  (when-let [overlay (get-in (state view) [:ui :overlay])]
                                    (let [query (.-plainText (:modal-input view))]
                                      (when (not= query (:query overlay))
                                        (ui! view update :overlay assoc :query query :index 0)
                                        (when (= :files (:kind overlay)) (file-query! view query)))))))})
        modal-list (w/scrollbox renderer {:id "dialog-choices" :width "100%" :marginTop 1})
        modal-footer (w/text renderer "" {:width "100%" :height 1 :fg (:faint w/colors) :wrapMode "none" :truncate true})
        view {:app application :renderer renderer :root root :local local :closed? closed?
              :syntax (w/syntax-style) :records (atom {})
              :header header :brand brand :session-title session-title :header-sessions header-sessions :header-commands header-commands
              :body body :conversation conversation :transcript transcript :welcome welcome
              :inspector inspector :inspector-title inspector-title :inspector-tabs inspector-tabs
              :inspector-scroll inspector-scroll :inspector-output inspector-output :inspector-next inspector-next
              :inspector-prev inspector-prev
              :inspector-branch inspector-branch :inspector-lifetime inspector-lifetime
              :pending pending :pending-items pending-items :pending-more pending-more :new-activity new-activity
              :notice-box notice-box :notice-text notice-text :composer-box composer-box :composer composer
              :attachment-row attachment-row :attachment-items attachment-items :footer-status footer-status :footer-keys footer-keys
              :modal-shade modal-shade :modal modal :modal-title modal-title :modal-hint modal-hint
              :modal-input modal-input :modal-list modal-list :modal-footer modal-footer
              :on-quit (or (:on-quit options) (fn [] (-> (app/close! application) (.finally (fn [] (.destroy renderer))))))}
        key-handler (fn [event] (key! view event))
        frame-handler (fn [_] (frame! view))
        resize-handler (fn [& _] (remember-anchor! view) (schedule! view))]
    (reset! view-ref view)
    (w/add! header brand session-title header-sessions header-commands)
    (w/add! conversation transcript welcome)
    (w/add! inspector-header inspector-title inspector-close)
    (doseq [tab [:summary :output :value :code]] (.add inspector-tab-row (get inspector-tabs tab)))
    (w/add! inspector-scroll inspector-output)
    (w/add! inspector-actions inspector-copy inspector-prev inspector-next inspector-branch)
    (w/add! inspector inspector-header inspector-tab-row inspector-scroll inspector-actions inspector-lifetime)
    (w/add! body conversation inspector)
    (w/add! pending pending-title pending-items pending-more)
    (w/add! notice-box notice-text notice-detail notice-close)
    (w/add! attachment-row attach-button attachment-items)
    (w/add! composer-box composer attachment-row)
    (w/add! footer footer-status footer-keys)
    (w/add! modal-header modal-title modal-close)
    (w/add! modal modal-header modal-hint modal-input modal-list modal-footer)
    (w/add! modal-shade modal)
    (w/add! root header body new-activity pending notice-box composer-box footer modal-shade)
    (.add (.-root renderer) root)
    (swap! local assoc :key-handler key-handler :frame-handler frame-handler :resize-handler resize-handler
           :pulse-timer (js/setInterval (fn [] (when (busy? view) (schedule! view))) 1000))
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
    (doseq [key [:render-timer :file-query-timer]]
      (when-let [timer (get @(:local view) key)] (js/clearTimeout timer)))
    (when-let [timer (:pulse-timer @(:local view))] (js/clearInterval timer))
    (.off (.-keyInput (:renderer view)) "keypress" (:key-handler @(:local view)))
    (.off (:renderer view) "frame" (:frame-handler @(:local view)))
    (.off (:renderer view) "resize" (:resize-handler @(:local view)))
    (when-not (.-isDestroyed (:root view))
      (.remove (.-root (:renderer view)) (:root view))
      (.destroyRecursively (:root view)))
    (.destroy (:syntax view))))
