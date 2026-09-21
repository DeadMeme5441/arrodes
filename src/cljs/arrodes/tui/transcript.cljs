(ns arrodes.tui.transcript
  "Keyed conversation rows, expansion, and scroll ownership."
  (:require
            [arrodes.tui-present :as present]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]
            [arrodes.tui.context :as c]))

(declare remember-anchor! make-row! update-row! render-rows! transcript-at-bottom? follow! select-row!)

(defn remember-anchor! [view]
  (when (and (not (get-in (c/state view) [:ui :follow?] true))
             (nil? (:anchor @(:local view)))
             (not (:manual-scroll? @(:local view))))
    (let [top (.-screenY (.-viewport (:transcript view)))
          entries (keep #(get @(:records view) (:id %)) (c/row-list view))
          record (first (filter #(> (+ (.-screenY (:root %)) (.-height (:root %))) top) entries))]
      (when record
        (swap! (:local view) assoc :anchor
               {:id (:id @(:row record)) :offset (- (.-screenY (:root record)) top)})))))


(defn make-row! [view row]
  (let [renderer (:renderer view)
        row* (atom row)
        message? (= :message (:kind row))
        root (w/box renderer {:id (str "row:" (:id row)) :width "auto" :alignSelf "stretch" :paddingX 1
                              :marginTop (if message? (:section-gap w/layout) 0) :marginBottom (if message? (:section-gap w/layout) 0)})
        artifact (w/box renderer {:id (str "artifact:" (:id row)) :width "auto" :alignSelf "stretch"})
        turn-heading (w/box renderer {:id (str "turn-heading:" (:id row)) :width "100%" :height 1 :marginBottom 1 :visible false})
        turn-label (w/text renderer "✦ Arrodes" {:height 1 :fg :assistant/heading :style-role :assistant/heading})
        header (w/box renderer {:height 1 :flexDirection "row" :width "100%"})
        toggle (w/button renderer "›" #(c/toggle! view @row*) {:width 2})
        label-options {:id (str "heading:" (:id row)) :flexGrow 1 :flexShrink 1 :height 1 :wrapMode "none" :truncate true}
        label (if message?
                (w/text renderer "" label-options)
                (w/button renderer "" #(c/toggle! view @row*) label-options))
        role-mark (w/text renderer "" {:width 2 :height 1 :selectable false})
        heading-rule (w/box renderer {:id (str "turn-rule:" (:id row)) :width "100%" :height 1 :marginBottom 1
                                      :border ["bottom"] :borderColor :ui/accent})
        heading-space (w/box renderer {:flexGrow 1 :minWidth 0})
        status (w/text renderer "" {:width 12 :height 1 :fg :text/secondary :wrapMode "none"})
        inspect (w/button renderer "inspect" #(c/action! :inspect! view @row*) {:width 7 :fg :text/dim})
        source (w/text renderer "" {:width "100%" :visible false :paddingX 1 :marginTop 1 :maxHeight 20})
        output-label (w/box renderer {:width "100%" :height 2 :visible false :border ["top"] :borderColor :border/default})
        output-title (w/text renderer "Output" {:height 1 :fg :text/dim})
        body (if message?
               (w/markdown renderer (:syntax view) "" {:width "100%"})
               (w/text renderer "" {:width "100%" :fg :text/secondary}))]
    (set! (.-visible toggle) (not message?))
    (w/add! header toggle role-mark label heading-space status inspect)
    (w/add! output-label output-title)
    (w/add! turn-heading turn-label)
    (w/add! artifact header source output-label body)
    (w/add! root heading-rule turn-heading artifact)
    {:root root :artifact artifact :turn-heading turn-heading :row row* :header header :toggle toggle :label label :status status
     :role-mark role-mark :heading-rule heading-rule :heading-space heading-space
     :inspect inspect :body body :source source :output-label output-label :message? message? :signature (atom nil)}))


(defn update-row! [view record row]
  (let [s (c/state view)
        expanded (or (c/expanded? view row)
                     (and (= :reasoning (:kind row)) (get-in s [:ui :show-reasoning?])))
        selected (= (:id row) (get-in s [:ui :selected]))
        signature [row expanded selected]]
    (when (not= signature @(:signature record))
      (reset! (:signature record) signature)
      (reset! (:row record) row)
      (let [a (present/activity row)
            text (or (present/inline-content row expanded) "")
            user? (= :user (:role row))
            assistant? (= :assistant (:role row))
            activity? (not (:message? record))
            turn-start? (:turn-start? row)
            execution (when a (present/execution (:view s) a))
            source (when (and expanded (not (:message? record))) (or (:source execution) (:source a)))
            color (cond (:error? row) :status/error
                        (present/failed? a) :status/error
                        (= :running (:status a)) :ui/accent
                        :else :text/secondary)]
        (w/paint! (:root record) :backgroundColor (if selected :surface/selected :surface/base))
        ;; Artifact frames distinguish execution from the surrounding conversation.
        (w/paint! (:artifact record) :borderColor :border/default)
        (set! (.-border (:artifact record)) (boolean (and activity? (not= :reasoning (:kind row))
                                                                   (or source (seq text)))))
        (set! (.-paddingLeft (:root record)) (if (and a (:parent-id a)) 3 1))
        (set! (.-paddingTop (:root record)) 0)
        (set! (.-paddingBottom (:root record)) 0)
        (set! (.-marginLeft (:root record)) 0)
        (set! (.-marginLeft (:artifact record)) (if activity? 2 0))
        (set! (.-marginBottom (:header record)) (if (or user? assistant?) 1 0))
        (set! (.-visible (:header record)) (or activity? turn-start? selected (:streaming? row)))
        (set! (.-visible (:turn-heading record)) (and turn-start? (= :assistant (:turn-role row)) (not assistant?)))
        (set! (.-visible (:inspect record)) selected)
        (set! (.-visible (:label record)) (or activity? turn-start? selected))
        (set! (.-visible (:role-mark record)) (and turn-start? (or user? assistant?)))
        (w/content! (:role-mark record) (if user? "◇" "✦"))
        (w/paint! (:role-mark record) :fg (if user? :user/heading :assistant/heading))
        (set! (.-visible (:heading-rule record)) (boolean turn-start?))
        (w/paint! (:heading-rule record) :borderColor (if user? :user/heading :assistant/heading))
        (set! (.-border (:heading-rule record)) #js ["bottom"])
        (set! (.-visible (:heading-space record)) (or user? assistant?))
        (set! (.-visible (:status record)) (or activity? (:streaming? row)))
        (set! (.-flexGrow (:label record)) (if (or user? assistant?) 0 1))
        (set! (.-width (:label record)) (if (or user? assistant?) 9 "auto"))
        (set! (.-visible (:source record)) (boolean source))
        (set! (.-visible (:output-label record))
              (boolean (and activity? (not= :reasoning (:kind row)) (seq text))))
        (when source (w/code-content! (:source record) (present/lines-preview source 20)))
        (w/content! (:label record) (cond user? "You" assistant? "Arrodes" :else (present/row-title row)))
        (w/paint! (:label record) :fg (cond user? :user/heading
                                               assistant? :assistant/heading
                                               (= :running (:status a)) :ui/accent
                                               (present/failed? a) :status/error
                                               (contains? #{:reasoning :read-group} (:kind row)) :text/secondary
                                               :else :execution/heading))
        (w/style! (:label record) (cond user? :user/heading assistant? :assistant/heading :else :execution/heading))
        (w/paint! (:toggle record) :fg :text/secondary)
        (w/content! (:toggle record) (if expanded "⌄" "›"))
        (w/content! (:status record)
                    (cond a (present/status-label a) (:streaming? row) "writing" :else ""))
        (w/paint! (:status record) :fg color)
        (set! (.-visible (:body record)) (not (str/blank? text)))
        (when-not (:message? record)
          (set! (.-maxHeight (:body record)) (if expanded 40 6)))
        (if (:message? record)
          (do
            (when (not= text (.-content (:body record))) (set! (.-content (:body record)) text))
            (set! (.-streaming (:body record)) (boolean (:streaming? row))))
          (if (present/recorded-diff a)
            (c/styled-diff! (:body record) text)
            (w/content! (:body record) text)))))))


(defn render-rows! [view]
  (let [rows (c/row-list view)
        ui (:ui (c/state view))
        signature [rows (:expanded ui) (:selected ui) (:show-reasoning? ui)]]
    (when (not= signature (:rows-signature @(:local view)))
      (when-not (contains? @(:local view) :restore-scroll) (remember-anchor! view))
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


(defn transcript-at-bottom? [view]
  (let [scroll (:transcript view)
        bottom (max 0 (- (.-scrollHeight scroll) (.-height (.-viewport scroll))))]
    (<= (- bottom (.-scrollTop scroll)) 0.5)))


(defn resume-follow!
  "Reconcile manual return to the end before new content changes the scroll bounds.
  Native sticky scrolling owns the actual movement during layout."
  [view]
  (when (and (.-visible (:conversation view))
             (not (contains? @(:local view) :restore-scroll))
             (not (get-in (c/state view) [:ui :follow?] true))
             (transcript-at-bottom? view)
             (not (.-hasSelection (:renderer view))))
    (swap! (:local view) assoc :anchor nil)
    (c/ui! view assoc :follow? true)))

(defn follow! [view]
  (swap! (:local view) assoc :anchor nil)
  (c/ui! view assoc :follow? true)
  (set! (.-stickyScroll (:transcript view)) true)
  (.scrollTo (:transcript view) (.-scrollHeight (:transcript view)))
  (c/action! :schedule! view))


(defn select-row! [view direction]
  (let [rows (c/row-list view)
        selected (get-in (c/state view) [:ui :selected])
        old (first (keep-indexed #(when (= selected (:id %2)) %1) rows))
        index (max 0 (min (dec (count rows)) (+ (or old (if (pos? direction) -1 (count rows))) direction)))]
    (when-let [row (get rows index)]
      (c/ui! view assoc :selected (:id row) :inspected-row row :follow? false)
      (.scrollChildIntoView (:transcript view) (str "row:" (:id row)))
      (when (get-in (c/state view) [:ui :inspector?]) (c/action! :request-inspection! view row)))))
