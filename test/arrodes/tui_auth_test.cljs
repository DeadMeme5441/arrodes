(ns arrodes.tui-auth-test
  "Offline provider login choices; no browser, credentials or remote calls."
  (:require [arrodes.tui.models :as models]
            [arrodes.tui.context :as c]
            [arrodes.tui-app :as app]
            [arrodes.tui-view :as view]
            [clojure.string :as str]))

(defn- check! [condition message]
  (when-not condition (throw (js/Error. message))))

(defn- until! [terminal predicate message]
  (let [deadline (+ (js/Date.now) 2000)]
    (letfn [(poll []
              (-> (.renderOnce terminal)
                  (.then (fn []
                           (cond
                             (predicate) nil
                             (> (js/Date.now) deadline)
                             (throw (js/Error. (str message "\n" (.captureCharFrame terminal))))
                             :else
                             (-> (js/Promise. (fn [resolve] (js/setTimeout resolve 15)))
                                 (.then poll)))))))]
      (poll))))

(defn- render-options! []
  (-> ((aget js/globalThis "ARRODES_CREATE_TEST_RENDERER")
       #js {:width 78 :height 16 :kittyKeyboard true :consoleMode "disabled"})
      (.then
       (fn [terminal]
         (let [application (app/create! {:runtime-root (.cwd js/process) :setup? false})
               mounted (view/mount! application (.-renderer terminal) {:theme "default"})
               previous {:kind :choices :title "Previous screen" :items [] :index 0 :query ""}]
           (swap! (:state application) assoc-in [:ui :overlay] previous)
           (models/provider-actions! mounted {:provider :claude-profile :name "Claude profile"
                                             :auth {:type :api-key} :auth-modes [:api-key :oauth]})
           (-> (until! terminal
                       #(let [frame (.captureCharFrame terminal)]
                          (and (str/includes? frame "Use an API key")
                               (str/includes? frame "Claude browser sign-in (experimental)")))
                       "Narrow provider screen must display both authentication methods")
               (.then (fn []
                        (.pressEscape (.-mockInput terminal))
                        (until! terminal #(= previous (get-in @(:state application) [:ui :overlay]))
                                "Escape must dismiss authentication choices without signing in")))
               (.then (fn [] (println "Native authentication screen passed: both modes and Escape at 78x16.")))
               (.finally (fn []
                           (view/destroy! mounted)
                           (.destroy (.-renderer terminal))
                           (app/close! application)))))))))

(defn exercise! []
  (doseq [provider [:anthropic :claude-profile]
          available? [false true]
          current-type [:api-key :oauth]
          modes [[:api-key :oauth] ["api-key" "oauth"]]]
    (let [previous {:kind :providers :query "claude"}
          state (atom {:ui {:overlay previous :draft "Keep my draft"}})
          calls (atom [])
          view {:app {:state state}
                :actions {:open-overlay! (fn [_ overlay] (swap! state assoc-in [:ui :overlay] overlay))
                          :render-overlay! (fn [_])}}]
      (with-redefs [c/fire! (fn [_ action params] (swap! calls conj [action params]))]
        (models/provider-actions! view {:provider provider :name "Claude" :available? available?
                                       :auth {:type current-type} :auth-modes modes})
        (let [overlay (get-in @state [:ui :overlay])
              items (:items overlay)
              key-choice (some #(when (= "Use an API key" (:label %)) %) items)
              oauth-choice (some #(when (= "Claude browser sign-in (experimental)" (:label %)) %) items)]
          (check! (= previous (:return-overlay overlay)) "Escape must return to the provider list")
          (check! (and key-choice oauth-choice) "Both advertised authentication modes must be visible")
          (check! (empty? @calls) "Opening login choices must not authenticate")
          ((:choose oauth-choice))
          (check! (= previous (get-in @state [:ui :overlay])) "Login must restore the provider list")
          ((:choose key-choice))
          (check! (= [[:provider-login {:provider provider :type :oauth}]
                      [:provider-login {:provider provider :type :api-key}]] @calls)
                  "Login must preserve provider alias and explicit method")
          (check! (= "Keep my draft" (get-in @state [:ui :draft])) "Authentication must not replace the draft")))))
  (doseq [auth-type [:oauth :api-key]]
    (let [provider (if (= :oauth auth-type) :codex-backend :fixture)
          overlay (atom nil) calls (atom [])
          view {:app {:state (atom {:ui {:overlay {:kind :providers}}})}
                :actions {:open-overlay! (fn [_ value] (reset! overlay value))
                          :render-overlay! (fn [_])}}]
      (with-redefs [c/fire! (fn [_ action params] (swap! calls conj [action params]))]
        (models/provider-actions! view {:provider provider :auth {:type auth-type}})
        (check! (= ["Connect provider"] (mapv :label (:items @overlay)))
                "Single-mode providers, including Codex, must keep their existing action")
        ((:choose (first (:items @overlay))))
        (check! (= [[:provider-login {:provider provider :type auth-type}]] @calls)
                "Single-mode login must keep its original type"))))
  (println "Provider auth choices passed: API key, Claude browser sign-in, aliases, reuse actions, draft preservation and single-mode compatibility.")
  (render-options!))

(defn -main []
  (aset js/globalThis "ARRODES_TUI_TEST_DONE" (exercise!)))

(set! *main-cli-fn* -main)
