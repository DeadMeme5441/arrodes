(ns arrodes.tui.inspection
  "Native result inspection and artifact paging."
  (:require
            [arrodes.tui-model :as model]
            [arrodes.tui-present :as present]
            [arrodes.tui-widgets :as w]
            [arrodes.tui.context :as c]
            [arrodes.tui.transcript :as transcript]))

(declare close-inspector! inspect! load-artifact-page! request-inspection! branch! render-inspector!)

(defn close-inspector! [view]
  (let [return-focus (get-in (c/state view) [:ui :inspect-return-focus] :composer)]
    (c/ui! view assoc :inspector? false)
    (c/action! :focus! view return-focus)))


(defn inspect! [view row]
  (let [s (c/state view)]
    (c/ui! view assoc :selected (:id row) :inspected-row row :inspector? true
         :inspect-return-focus (get-in s [:ui :focus] :composer)
         :inspect-tab (present/default-tab row) :inspection nil)
    (request-inspection! view row)
    (c/action! :focus! view :inspector)))


(defn load-artifact-page! [view artifact-id offset]
  (let [selection (get-in (c/state view) [:ui :selected])]
    (c/ui! view assoc-in [:inspection :loading?] true)
    (-> (c/invoke! view :artifact {:artifact-id artifact-id :offset offset :limit 12000})
        (.then (fn [page]
                 (when (and (= selection (get-in (c/state view) [:ui :selected]))
                            (= artifact-id (get-in (c/state view) [:ui :inspection :descriptor :artifact-id])))
                   (c/ui! view update :inspection assoc :page page :loading? false))))
        (.catch (fn [_]
                  (when (and (= selection (get-in (c/state view) [:ui :selected]))
                             (= artifact-id (get-in (c/state view) [:ui :inspection :descriptor :artifact-id])))
                    (c/ui! view assoc-in [:inspection :loading?] false)))))))


(defn request-inspection! [view row]
  (let [selection (:id row)
        id (model/field (:result (present/activity row)) :id)]
    (c/ui! view assoc :inspection (when id {:selection-id selection :result-id id :loading? true}))
    (when id
      (-> (c/invoke! view :result {:result-id id})
          (.then (fn [descriptor]
                   (when (and (= selection (get-in (c/state view) [:ui :selected]))
                              (= id (get-in (c/state view) [:ui :inspection :result-id])))
                     (c/ui! view update :inspection assoc :descriptor descriptor :loading? false)
                     (when-let [artifact-id (model/field descriptor :artifact-id)]
                       (load-artifact-page! view artifact-id 1)))))
          (.catch (fn [_]
                    (when (and (= selection (get-in (c/state view) [:ui :selected]))
                               (= id (get-in (c/state view) [:ui :inspection :result-id])))
                      (c/ui! view assoc-in [:inspection :loading?] false))))))))


(defn branch! [view row]
  (when-let [entry-id (:entry-id row)]
    (c/action! :confirm! view "Branch from this recorded point?"
              "Creates a fresh session and REPL. It does not restore or undo files."
              (fn []
                (-> (c/invoke! view :branch {:entry-id entry-id})
                    (.then #(do (close-inspector! view) (transcript/follow! view)))
                    (.catch (fn [_] nil)))))))


(defn render-inspector! [view]
  (let [s (c/state view)
        row (c/selected-row view)
        open? (and (get-in s [:ui :inspector?]) row)
        panel (:inspector view)]
    (set! (.-visible panel) (boolean open?))
    (set! (.-visible (:conversation view)) (not open?))
    (set! (.-visible (:new-activity view))
          (and (not open?) (not (get-in s [:ui :follow?] true))
               (not (transcript/transcript-at-bottom? view))))
    (set! (.-width panel) "100%")
    (doseq [node [(:composer-box view) (:footer view) (:metadata view) (:pending view) (:widget-box view)]]
      (when open? (set! (.-visible node) false)))
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
          (w/paint! button :fg (if (= key tab) :ui/accent :text/dim)))
        (when (not= signature (:inspector-signature @(:local view)))
          (swap! (:local view) assoc :inspector-signature signature :inspector-text (:content data))
          (if (:diff? data)
            (c/styled-diff! (:inspector-output view) (:content data))
            (w/content! (:inspector-output view) (:content data))))
        (when (and (:result-id data) (not= (:result-id data) (get-in s [:ui :inspection :result-id])))
          (request-inspection! view row))))))

