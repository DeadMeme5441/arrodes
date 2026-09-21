(ns arrodes.jobs-ui-test
  "Jobs through a real JSONL core, controller, and native OpenTUI renderer."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-view :as view]
            [arrodes.tui.jobs :as jobs]
            [arrodes.tui.inspection :as inspection]
            [arrodes.tui-model :as model]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(defn check! [condition message] (when-not condition (throw (js/Error. message))))
(defn until! [terminal predicate message]
  (let [deadline (+ (js/Date.now) 15000)]
    (letfn [(poll []
              (-> (.renderOnce terminal)
                  (.then (fn []
                           (cond (predicate) nil
                                 (> (js/Date.now) deadline) (throw (js/Error. (str message "\n" (.captureCharFrame terminal))))
                                 :else (-> (js/Promise. (fn [resolve] (js/setTimeout resolve 20))) (.then poll)))))))]
      (poll))))
(defn job [application name]
  (some #(when (= name (:name %)) %) (get-in @(:state application) [:view :jobs])))
(defn choose! [application mounted terminal query]
  (.setText (:modal-input mounted) query)
  (swap! (:state application) assoc-in [:ui :overlay :query] query)
  (swap! (:state application) assoc-in [:ui :overlay :index] 0)
  (-> (.renderOnce terminal)
      (.then (fn [_] (.pressEnter (.-mockInput terminal))))))

(defn capture! [terminal name]
  (when-let [directory (aget (.-env js/process) "ARRODES_CAPTURE_UI")]
    (let [frame (.captureSpans terminal)
          lines (mapv (fn [line]
                        (mapv (fn [span] {:text (.-text span) :width (.-width span)
                                         :fg (js->clj (.toInts (.-fg span))) :bg (js->clj (.toInts (.-bg span)))})
                              (array-seq (.-spans line)))) (array-seq (.-lines frame)))]
      (.mkdirSync fs directory #js {:recursive true})
      (.writeFileSync fs (.join path directory (str name ".json"))
                      (js/JSON.stringify (clj->js {:cols (.-cols frame) :rows (.-rows frame) :lines lines})))
      (.writeFileSync fs (.join path directory (str name ".txt")) (.captureCharFrame terminal)))))

(defn exercise! []
  (let [temporary (.mkdtempSync fs (.join path (.tmpdir os) "arrodes-jobs-ui-"))
        script (.join path temporary "host.clj")
        root (or (aget (.-env js/process) "ARRODES_TUI_ROOT") (.cwd js/process))
        application (app/create! {:runtime-root root :cwd temporary :home (.join path temporary "home")
                                  :trust false :setup? false :handshake-timeout-ms 60000
                                  :rpc-command ["clojure" "-Srepro" "-Sdeps"
                                                "{:paths [\"src/clj\" \"src/cljc\" \"hosts/rpc\" \"resources\"]}" "-M" script]})
        mounted (atom nil) terminal (atom nil)]
    (.writeFileSync fs script
      "(require '[arrodes.rpc :as rpc]) (rpc/serve! {:complete-fn (fn [_ _] {:response/provider :test :response/model \"offline\" :response/parts [{:part/type :text :text \"Ready\"}] :response/finish-reason :stop :response/provider-data {}})}) (shutdown-agents)")
    (-> ((aget js/globalThis "ARRODES_CREATE_TEST_RENDERER") #js {:width 90 :height 28 :kittyKeyboard true :consoleMode "disabled"})
        (.then (fn [t] (reset! terminal t) (reset! mounted (view/mount! application (.-renderer t) {:on-quit (fn [])}))
                 (app/start! application)))
        (.then (fn [_] (app/command! application :submit {:text "Start jobs fixture"})))
        (.then (fn [_] (until! @terminal #(= :idle (get-in @(:state application) [:view :phase])) "Foreground fixture did not settle")))
        (.then (fn [_] (app/command! application :submit {:mode :evaluate :text "(def j (jobs/start! {:name \"Long runner\"} #(do (println \"RUNNING\") (Thread/sleep 30000)))) j"})))
        (.then (fn [_]
                 (until! @terminal #(and (= :idle (get-in @(:state application) [:view :phase]))
                                         (str/includes? (.captureCharFrame @terminal) "Job · Long runner")) "Job must be inline while the foreground is idle")))
        (.then (fn [_]
                 (check! (nil? (get-in @(:state application) [:ui :overlay])) "Job start opened a popup")
                 (capture! @terminal "jobs-inline")
                 (swap! (:state application) assoc-in [:ui :draft] "preserve this draft")
                 (jobs/open! @mounted)
                 (until! @terminal #(str/includes? (.captureCharFrame @terminal) "[running] Long runner") "Running job is absent")))
        (.then (fn [_] (capture! @terminal "jobs-running") (choose! application @mounted @terminal "Long runner")))
        (.then (fn [_] (until! @terminal #(and (get-in @(:state application) [:ui :inspector?])
                                                                  (nil? (get-in @(:state application) [:ui :overlay]))) "Job must open the existing inspector directly")))
        (.then (fn [_] (capture! @terminal "jobs-inspector") (.pressKey (.-mockInput @terminal) "k" #js {:ctrl true})))
        (.then (fn [_] (until! @terminal #(= :cancelled (:status (job application "Long runner"))) "Job cancellation did not settle")))
        (.then (fn [_]
                 (check! (= "cancelled" (get-in (job application "Long runner") [:error :code]))
                         "Cancellation must not be labelled job-failed")))
        (.then (fn [_]
                 (check! (= "preserve this draft" (get-in @(:state application) [:ui :draft])) "Jobs screen discarded composer draft")
                 (.pressEscape (.-mockInput @terminal))
                 (app/command! application :submit {:mode :evaluate :text "(jobs/start! {:name \"Completed value\"} #(do (print (apply str (repeat 25000 \"x\"))) (print \"LATEST-JOB-OUTPUT\") {:answer 42 :ratio 2/3}))"})))
        (.then (fn [_] (until! @terminal #(= :completed (:status (job application "Completed value"))) "Completion event not projected")))
        (.then (fn [_] (jobs/open! @mounted) (choose! application @mounted @terminal "Completed value")))
        (.then (fn [_] (until! @terminal #(= 12000 (count (get-in @(:state application) [:ui :inspection :job-output :text]))) "Output page not loaded")))
        (.then (fn [_] (inspection/page! @mounted :next)))
        (.then (fn [_] (until! @terminal #(= 12000 (get-in @(:state application) [:ui :inspection :job-output :offset])) "Next output page lost its offset")))
        (.then (fn [_] (.pressKey (.-mockInput @terminal) "END")))
        (.then (fn [_]
                 (until! @terminal #(and (get-in @(:state application) [:ui :inspection :job-output :tail?])
                                        (> (get-in @(:state application) [:ui :inspection :job-output :offset] 0) 12000)
                                        (str/includes? (.captureCharFrame @terminal) "LATEST-JOB-OUTPUT"))
                         "End must show the retained tail in the existing inspector")))
        (.then (fn [_] (.pressKey (.-mockInput @terminal) "F5")))
        (.then (fn [_]
                 (until! @terminal #(and (get-in @(:state application) [:ui :inspection :job-output :tail?])
                                        (str/includes? (.captureCharFrame @terminal) "LATEST-JOB-OUTPUT"))
                         "Refresh must preserve the tail view")))
        (.then (fn [_] (.pressEscape (.-mockInput @terminal)) (jobs/open! @mounted) (choose! application @mounted @terminal "Completed value")))
        (.then (fn [_] (.pressKey (.-mockInput @terminal) "3")))
        (.then (fn [_] (until! @terminal #(str/includes? (.captureCharFrame @terminal) "2/3") "Native EDN result was not rendered")))
        (.then (fn [_] (capture! @terminal "jobs-native-result") (.pressEscape (.-mockInput @terminal))
                 (app/command! application :submit {:mode :evaluate :text "(jobs/start! {:name \"Failed value\"} #(throw (ex-info \"intentional failure\" {})))"})))
        (.then (fn [_] (until! @terminal #(= :failed (:status (job application "Failed value"))) "Failure not projected")))
        (.then (fn [_] (.resize @terminal 44 16) (jobs/open! @mounted)
                 (until! @terminal #(str/includes? (.captureCharFrame @terminal) "[failed] Failed value") "Narrow jobs screen hides failure")))
        (.then (fn [_] (capture! @terminal "jobs-narrow") (.pressEscape (.-mockInput @terminal)) (app/command! application :reconnect {})))
        (.then (fn [_] (app/command! application :jobs {})))
        (.then (fn [_]
                 (check! (some #(and (:job-id %) (= "Completed value" (get-in % [:activity :name])))
                               (model/rows (:view @(:state application)))) "Reconnect lost inline job activity")
                 (check! (= :completed (:status (job application "Completed value"))) "Reconnect lost completed jobs")
                 (check! (= :failed (:status (job application "Failed value"))) "Reconnect lost failed jobs")
                 (println "Jobs UI passed: inline conversation activity, direct full-width inspector (no popup/action screen), real core, running/cancelled/failed/completed states, keyboard actions, output paging/tail refresh, cancellation classification, native values, narrow layout, drafts, reconnect.")))
        (.finally (fn []
                    (when @mounted (view/destroy! @mounted))
                    (when @terminal (.destroy (.-renderer @terminal)))
                    (-> (app/close! application)
                        (.finally (fn [] (.rmSync fs temporary #js {:recursive true :force true})))))))))

(defn -main [] (aset js/globalThis "ARRODES_TUI_TEST_DONE" (exercise!)))
(set! *main-cli-fn* -main)
