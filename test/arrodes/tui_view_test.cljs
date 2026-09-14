(ns arrodes.tui-view-test
  "TUI regression entry point: real RPC lifecycle followed by native popup rendering."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-app-test :as app-test]
            [arrodes.tui-view :as view]
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

(defn- popup-fits? [terminal]
  (let [popup (.-parent (node terminal "dialog-choices"))
        renderer (.-renderer terminal)]
    (and (<= (+ (.-screenX popup) (.-width popup)) (.-terminalWidth renderer))
         (<= (+ (.-screenY popup) (.-height popup)) (.-terminalHeight renderer)))))

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
             (str "Keyboard selection left the popup viewport "
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
                                          :title "Popup scrolling regression" :items items
                                          :entries entries :paths labels}))))))

(defn- menu! [application terminal kind]
  (show! application kind)
  (-> (visible! application terminal)
      (.then #(walk! application terminal "down" 23))
      (.then #(walk! application terminal "up" 23))))

(defn- exercise! [terminal]
  (let [application (app/create! {:runtime-root (.cwd js/process) :cwd (.cwd js/process)})
        mounted (view/mount! application (.-renderer terminal) {})]
    (.pressKey (.-mockInput terminal) "F3")
    (-> (visible! application terminal)
        (.then #(walk! application terminal "down" 40))
        (.then #(walk! application terminal "up" 40))
        (.then (fn []
                 (reduce (fn [pending kind] (.then pending #(menu! application terminal kind)))
                         (js/Promise.resolve nil) [:sessions :models :history :files :pending :choices])))
        (.then #(walk! application terminal "down" 23))
        (.then (fn []
                 (.resize terminal 78 24)
                 (until! terminal
                         #(and (popup-fits? terminal)
                               (selected-visible? application terminal))
                         "Resize hid the selected popup row")))
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
                         #(and (popup-fits? terminal)
                               (selected-visible? application terminal))
                         "Short viewport hid the selected item label")))
        (.then #(walk! application terminal "down" 23))
        (.then #(walk! application terminal "up" 23))
        (.then #(menu! application terminal :choices))
        (.then (fn [] (println "Popup scrolling passed: commands, sessions, models, history, files, pending, wrapped choices, resize and filtering.")))
        (.finally (fn []
                    (view/destroy! mounted)
                    (.destroy (.-renderer terminal))
                    (app/close! application))))))

(defn -main []
  (aset js/globalThis "ARRODES_TUI_TEST_DONE"
        (-> (app-test/exercise!)
            (.then (fn []
                     ((aget js/globalThis "ARRODES_CREATE_TEST_RENDERER")
                      #js {:width 120 :height 40 :kittyKeyboard true :consoleMode "disabled"})))
            (.then exercise!))))

(set! *main-cli-fn* -main)
