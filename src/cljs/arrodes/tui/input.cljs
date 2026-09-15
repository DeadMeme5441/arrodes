(ns arrodes.tui.input
  "Composer submission, keyboard routing and focus transitions."
  (:require
            [arrodes.catalog :as catalog]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]
            [arrodes.tui.commands :as commands]
            [arrodes.tui.context :as c]
            [arrodes.tui.inspection :as inspection]
            [arrodes.tui.models :as models]
            [arrodes.tui.screens :as screens]
            [arrodes.tui.transcript :as transcript]))

(declare focus! submit! history-draft! quit! escape! key!)

(defn focus! [view target]
  (let [target (if (and (= target :inspector) (not (get-in (c/state view) [:ui :inspector?])))
                 :composer target)]
    (c/ui! view assoc :focus target)
    (when (= target :composer)
      (c/ui! view assoc :keyboard-navigation? false :inspector? false)
      (set! (.-visible (:composer-box view)) true))
    (when (and (= target :transcript) (not (get-in (c/state view) [:ui :inspector?])))
      (set! (.-visible (:conversation view)) true))
    (case target
      :transcript (.focus (:transcript view))
      :inspector (.focus (:inspector-scroll view))
      (.focus (:composer view)))))


(defn submit! [view mode]
  (let [raw (.-plainText (:composer view))
        session-id (get-in (c/state view) [:view :session :id])
        slash (when (and (= mode :prompt) (not (str/starts-with? raw "//")))
                (re-matches #"(?s)^/([^\s]+)(?:\s+(.*))?$" (str/trim raw)))
        command (when slash
                  (some #(when (= (str "/" (second slash))
                                  (first (str/split (:description %) #"\s+"))) %) (commands/commands view)))]
    ;; Read the editor itself at submit time: native change notifications may
    ;; still be queued after a fast paste/Enter sequence.
    (c/ui! view assoc :draft raw)
    (cond
      (and session-id (= session-id (:submitting-session @(:local view)))) nil
      (and slash (= "eval" (second slash)) (seq (nth slash 2)))
      (do (c/ui! view assoc :draft "") (.setText (:composer view) "")
          (screens/input-dialog! view "Evaluate Clojure" (nth slash 2)
                         #(c/invoke! view :submit {:mode :evaluate :text %})
                         "Trusted execution. Enter evaluates; Shift+Enter adds a line."))
      command
      (do (c/ui! view assoc :draft "") (.setText (:composer view) "") ((:choose command)))
      slash (c/notify! view (str "Unknown command /" (second slash) ". F3 lists commands; prefix // to send a slash literally.") :error)
      (not (c/ready? view)) (c/notify! view "Core is not connected. Use /reconnect; the draft is preserved." :error)
      (and (str/blank? raw) (empty? (get-in (c/state view) [:ui :attachments]))) nil
      :else
      (let [text (if (and (= mode :prompt) (str/starts-with? raw "//")) (subs raw 1) raw)]
        (swap! (:local view) assoc :submitting-session session-id :history-index nil)
        (-> (c/invoke! view :submit {:text text :draft-text raw :mode mode})
            (.then (fn [_]
                     (when (= session-id (get-in (c/state view) [:view :session :id]))
                       (let [current (.-plainText (:composer view))]
                         (if (= current raw)
                           (do
                             (swap! (:local view) assoc :syncing-editor? true)
                             (try (.setText (:composer view) "")
                                  (finally (swap! (:local view) assoc :syncing-editor? false))))
                           (c/ui! view assoc :draft current)))
                       (transcript/follow! view))
                     (swap! (:local view) update :prompt-history
                            #(vec (take-last 100 (if (= raw (peek %)) % (conj (or % []) raw)))))))
            (.catch (fn [_] nil))
            (.finally (fn []
                        (when (= session-id (:submitting-session @(:local view)))
                          (swap! (:local view) dissoc :submitting-session))
                        (c/action! :schedule! view))))))))


(defn history-draft! [view direction]
  (let [{:keys [prompt-history history-index]} @(:local view)
        n (count prompt-history)]
    (when (pos? n)
      (when (nil? history-index)
        (swap! (:local view) assoc :history-draft (.-plainText (:composer view))))
      (let [index (max 0 (min n (+ (or history-index n) direction)))
            text (if (= index n) (:history-draft @(:local view)) (nth prompt-history index))]
        (swap! (:local view) assoc :history-index index)
        (c/ui! view assoc :draft text)))))


(defn quit! [view]
  (if (c/busy? view)
    (screens/confirm! view "Stop work and close Arrodes?"
              "Cancellation is cooperative. Completed external effects are not rolled back."
              (:on-quit view))
    ((:on-quit view))))


(defn escape! [view]
  (let [s (c/state view) overlay (get-in s [:ui :overlay])]
    (cond
      (:host-id overlay) (screens/respond-host! view (:host-id overlay) nil true)
      (and (= :models (:kind overlay)) (contains? #{:effort :session :default} (:pane overlay)))
      (do (c/ui! view assoc-in [:overlay :pane] :models) (.focus (:modal-input view)))
      overlay (do (.clearSelection (:renderer view)) (screens/close-overlay! view))
      (.-hasSelection (:renderer view)) (do (.clearSelection (:renderer view)) (focus! view :composer))
      (get-in s [:ui :inspector?]) (inspection/close-inspector! view)
      (not= :composer (get-in s [:ui :focus])) (focus! view :composer)
      (c/busy? view) (c/fire! view :cancel {})
      :else (focus! view :composer))))


(defn key! [view event]
  (when (not= "release" (.-eventType event))
    (let [name (.-name event) ctrl (.-ctrl event) alt (or (.-meta event) (.-option event))
          shift (.-shift event) s (c/state view) overlay (get-in s [:ui :overlay])
          focus (get-in s [:ui :focus] :composer)
          enter? (contains? #{"return" "enter"} name)
          handled
          (cond
            (and ctrl (= name "c"))
            (do
              (when-not (c/copy-selection! view)
                (cond
                  overlay (escape! view)
                  (c/busy? view) (c/fire! view :cancel {})
                  :else
                  (do
                    (when (seq (.-plainText (:composer view)))
                      (swap! (:local view) update :prompt-history
                             #(vec (take-last 100 (conj (or % []) (.-plainText (:composer view)))))))
                    (c/ui! view assoc :draft ""))))
              true)
            (and ctrl (= name "d")) (do (quit! view) true)
            (= name "escape")
            (do (if (and (not (:host-id overlay))
                         (str/starts-with? (or (:catalog-operation s) "") "Connecting provider"))
                  (c/fire! view :provider-cancel {})
                  (escape! view)) true)
            (= name "f1") (do ((:choose (some #(when (= "Keyboard help" (:label %)) %) (commands/commands view)))) true)
            (= name "f2") (do (screens/open-sessions! view) true)
            (or (= name "f3") (and ctrl (= name "p")))
            (do (screens/open-overlay! view {:kind :commands :title "Commands" :query "" :hint "Search actions or slash commands."}) true)
            overlay
            (cond
              (and (= :models (:kind overlay)) (not (or shift ctrl alt))
                   (contains? #{"left" "right"} name))
              (models/model-horizontal! view (if (= name "left") -1 1))
              (and (= :models (:kind overlay)) (contains? #{:effort :session :default} (:pane overlay)) enter?)
              (do (if (= :effort (:pane overlay))
                    (c/ui! view assoc-in [:overlay :pane] (if (get-in s [:view :session]) :session :default))
                    (models/apply-model! view (:pane overlay))) true)
              (and (= :models (:kind overlay)) (contains? #{:effort :session :default} (:pane overlay))
                   (contains? #{"up" "down"} name))
              (do (let [panes (cond-> [:models :effort]
                                  (get-in s [:view :session]) (conj :session)
                                  true (conj :default))
                        index (first (keep-indexed #(when (= %2 (:pane overlay)) %1) panes))
                        next-pane (get panes (max 0 (min (dec (count panes)) (+ index (if (= name "up") -1 1)))))]
                    (c/ui! view assoc-in [:overlay :pane] next-pane)
                    (when (= next-pane :models) (.focus (:modal-input view)))) true)
              (or (= name "pageup") (= name "pagedown"))
              (do (.scrollBy (:modal-list view) (* (if (= name "pageup") -1 1)
                                                  (max 1 (- (.-height (:modal-list view)) 2)))) true)
              (and (:body overlay) (contains? #{"up" "down" "j" "k"} name))
              (do (.scrollBy (:modal-list view) (if (contains? #{"up" "k"} name) -1 1)) true)
              (and (= name "f5") (contains? #{:providers :models} (:kind overlay)))
              (do (if (= :providers (:kind overlay)) (c/fire! view :providers {})
                      (when (:provider overlay)
                        (c/fire! view :browse-provider {:provider (:provider overlay) :refresh? true
                                                     :available? (:available? (some #(when (= (catalog/provider-id %) (keyword (:provider overlay))) %) (:providers s)))}))) true)
              (and (= :models (:kind overlay)) (= name "tab"))
              (do (let [panes (cond-> [:providers :models :effort]
                                  (get-in s [:view :session]) (conj :session)
                                  true (conj :default))
                        index (or (first (keep-indexed #(when (= %2 (:pane overlay)) %1) panes)) 0)
                        next-pane (get panes (mod (+ index (if shift -1 1)) (count panes)))]
                    (c/ui! view assoc-in [:overlay :pane] next-pane)
                    (if (= next-pane :models) (.focus (:modal-input view)) (.focus (:modal-list view)))) true)
              (and (= :models (:kind overlay)) (= :providers (:pane overlay))
                   (contains? #{"up" "down"} name))
              (do (let [entries (catalog/providers (:providers s))
                        index (or (first (keep-indexed #(when (= (catalog/provider-id %2) (some-> (:provider overlay) keyword)) %1) entries)) 0)
                        next (get entries (max 0 (min (dec (count entries)) (+ index (if (= name "up") -1 1)))))]
                    (when next
                      (models/browser-provider! view next)
                      (c/ui! view assoc-in [:overlay :pane] :providers))) true)
              (and (= :models (:kind overlay)) (= :providers (:pane overlay)) enter?)
              (do (c/ui! view assoc-in [:overlay :pane] :models) (.focus (:modal-input view)) true)
              (and enter? (not shift))
              (do (if (and (= :commands (:kind overlay))
                           (or (empty? (screens/overlay-items view overlay))
                               (re-matches #"(?s)^/eval\s+.*" (.-plainText (:composer view)))))
                    (submit! view :prompt)
                    (screens/choose-overlay! view)) true)
              (and (contains? #{"up" "down"} name) (not= :input (:kind overlay)))
              (do (c/ui! view update-in [:overlay :index]
                       #(let [n (count (screens/overlay-items view overlay))]
                          (max 0 (min (max 0 (dec n)) (+ (or % 0) (if (= name "up") -1 1)))))) true)
              (and (= name "delete") (= :pending (:kind overlay)))
              (do (when-let [drop (:drop (get (screens/overlay-items view overlay) (or (:index overlay) 0)))] (drop)) true)
              :else false)
            (= name "f6")
            (do (c/ui! view assoc :keyboard-navigation? true)
                (focus! view (case focus :composer :transcript
                                   :transcript (if (get-in s [:ui :inspector?]) :inspector :composer)
                                   :composer)) true)
            (or (= name "pageup") (= name "pagedown"))
            (do (when (= focus :composer) (focus! view :transcript))
                (let [scroll (if (= focus :inspector) (:inspector-scroll view) (:transcript view))]
                  (when (not= focus :inspector) (c/ui! view assoc :follow? false))
                  (.scrollBy scroll (* (if (= name "pageup") -1 1) (max 3 (- (.-height scroll) 2))))) true)
            (and (= name "end") (not= focus :composer)) (do (transcript/follow! view) true)
            (and (not= focus :composer) (not (get-in s [:ui :keyboard-navigation?]))
                 (not ctrl) (not alt)
                 (or (= name "space")
                     (and (seq (.-sequence event)) (not (re-find #"[\x00-\x1f\x7f]" (.-sequence event))))))
            (do (.clearSelection (:renderer view))
                (when (get-in s [:ui :inspector?]) (c/ui! view assoc :inspector? false))
                (set! (.-visible (:composer-box view)) true)
                (focus! view :composer)
                (.insertText (:composer view) (if (= name "space") " " (.-sequence event))) true)
            (= focus :transcript)
            (cond
              (contains? #{"up" "down" "j" "k"} name) (do (transcript/select-row! view (if (contains? #{"up" "k"} name) -1 1)) true)
              enter? (do (when-let [row (c/selected-row view)] (inspection/inspect! view row)) true)
              (or (= name "space") (= name " ")) (do (when-let [row (c/selected-row view)] (c/toggle! view row)) true)
              (= name "b") (do (when-let [row (c/selected-row view)] (inspection/branch! view row)) true)
              :else false)
            (= focus :inspector)
            (cond
              (contains? #{"1" "2" "3" "4"} name)
              (do (c/ui! view assoc :inspect-tab (get {"1" :summary "2" :output "3" :value "4" :code} name))
                  (.scrollTo (:inspector-scroll view) 0) true)
              (= name "y") (do (c/copy! view (or (:inspector-text @(:local view)) "")) true)
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

