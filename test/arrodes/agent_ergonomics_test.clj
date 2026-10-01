(ns arrodes.agent-ergonomics-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [arrodes.agents :as agents]
            [arrodes.commands :as commands]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.store.agents :as store-agents]))

(defn- evaluate [rt sid source]
  (let [outcome (runtime/evaluate! rt sid source)]
    (when (:error? outcome) (throw (ex-info (:content outcome) (:details outcome))))
    (:value outcome)))

(defn- parent [rt]
  (let [sid (:id (fixtures/create-session rt))]
    (agents/pause! (:agents rt) sid)
    sid))

(deftest default-agent-inspection-is-bounded-with-full-native-details-on-demand
  (let [opaque (apply str (repeat 5000 "PROVIDER-ONLY-"))
        instructions (apply str (repeat 2000 "CONFIG-ONLY-"))
        answer (apply str (repeat 2000 "a"))]
    (fixtures/with-runtime
      [rt (fn [_ _] (assoc (fixtures/answer answer) :response/provider-data {:fixture {:opaque opaque}}))]
      (let [sid (parent rt)
            _ (runtime/configure! rt sid {:config {:instructions instructions}})
            children (mapv #(agents/start-agent! (:agents rt) sid
                                                {:name (str "Worker" %) :task "Return an answer"}) (range 10))
            first-child (first children)]
        (doseq [child children] (runtime/wait! rt (:operation-id child) 10000))
        (evaluate rt sid (str "(def child " (pr-str first-child) ")"))
        (let [page (evaluate rt sid "(agents/list)")
              second-page (evaluate rt sid (str "(agents/list {:offset " (:next-offset page) "})"))
              inspected (evaluate rt sid "(agents/inspect child)")
              result (evaluate rt sid "(agents/result child)")
              detailed (evaluate rt sid "(agents/result child {:detailed? true})")]
          (is (= (set (cons sid (map :session-id children)))
                 (set (map :session-id (concat (:agents page) (:agents second-page))))))
          (is (nil? (:next-offset second-page)))
          (is (<= (count (pr-str page)) 5000))
          (is (<= (count (pr-str inspected)) 1000))
          (is (<= (count (pr-str result)) 1800))
          (is (not (str/includes? (pr-str [page inspected result]) "PROVIDER-ONLY")))
          (is (not (str/includes? (pr-str [page inspected result]) "CONFIG-ONLY")))
          (is (= (:session-id first-child) (:session-id inspected)))
          (is (= :completed (:status result)))
          (is (= (subs answer 0 1200) (:answer result)))
          (is (true? (:answer-truncated? result)))
          (is (= opaque (get-in detailed [:result :message/provider-data :fixture :opaque])))
          (is (= answer (get-in detailed [:result :message/content 0 :text])))
          (is (= instructions (get-in (evaluate rt sid "(agents/inspect child {:detailed? true})")
                                      [:session :config :instructions]))))))))

(deftest message-pages-preserve-small-native-data-and-omit-large-bodies
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Finished"))]
    (let [sid (parent rt)
          child (agents/start-agent! (:agents rt) sid {:name "Peer" :task "Finish"})
          child-id (:session-id child)
          large (apply str (repeat 10000 "x"))]
      (runtime/wait! rt (:operation-id child) 10000)
      (dotimes [n 10] (agents/send-message! (:agents rt) child-id :parent {:ratio 2/3 :index n} {}))
      (let [receipt (agents/send-message! (:agents rt) child-id :parent large {})
            page (evaluate rt sid "(agents/messages)")
            older (evaluate rt sid (str "(agents/messages {:before " (:next-before page) "})"))
            summary (first (:messages page))
            full (agents/delivery-state (:agents rt) sid receipt {:detailed? true})]
        (is (= (:id receipt) (:id summary)))
        (is (:content-omitted? summary))
        (is (:preview-truncated? summary))
        (is (= (subs large 0 240) (:preview summary)))
        (is (= large (:content full)))
        (is (<= (count (pr-str page)) 6000))
        (is (= 2/3 (get-in page [:messages 1 :content :ratio])))
        (is (= (set (range 10))
               (set (keep #(get-in % [:content :index]) (concat (:messages page) (:messages older))))))
        (is (store-agents/pending-agent-messages? (:store rt) sid))))))

(deftest receipt-wait-yields-the-followup-operation-without-roster-bookkeeping
  (let [parent-id (atom nil) step (atom 0) entered (promise) release (promise)]
    (fixtures/with-runtime
      [rt (fn [request _]
            (if (= @parent-id (get-in request [:request/cache :scope-id]))
              (fixtures/answer "Parent context updated")
              (case (swap! step inc)
                1 (fixtures/calls (fixtures/tool-call "initialize" "(def memo (atom :alive)) @memo"))
                2 (fixtures/answer "Initial")
                3 (do (deliver entered true) @release
                      (fixtures/calls (fixtures/tool-call "followup" "(reset! memo :followup)")))
                (fixtures/answer "Followup"))))]
      (let [sid (parent rt)
            _ (reset! parent-id sid)
            original (evaluate rt sid "(def child (agents/start! {:name \"Reusable\" :task \"Initialize\"})) child")
            original-link (get-in (evaluate rt sid "(agents/inspect child)") [:next :result])]
        (try
          (runtime/wait! rt (:operation-id original) 10000)
          (runtime/continue! rt sid)
          (agents/pause! (:agents rt) sid)
          (let [receipt (evaluate rt sid "(def followup (agents/send! child \"Update the existing atom\")) followup")
                ready (evaluate rt sid "(agents/wait {:receipts [followup] :until :delivered :timeout-ms 10000})")
                operation (first (:ready ready))
                delivery (evaluate rt sid "(agents/delivery followup)")]
            (is (= :delivered (:reason ready)))
            (is (= (:session-id original) (:session-id operation)))
            (is (not= (:operation-id original) (:operation-id operation)))
            (is (= (:operation-id operation) (get-in delivery [:deliveries 0 :operation-id])))
            (is (= :running (get-in delivery [:deliveries 0 :operation-status])))
            (is (= :delivered (get-in delivery [:deliveries 0 :status])))
            (is (= true (deref entered 10000 ::timeout)))
            (deliver release true)
            (runtime/wait! rt (:operation-id operation) 10000)
            (let [completed (commands/dispatch! rt "agent.wait"
                                               {:session-id sid :receipts [receipt]
                                                :until "completed" :timeout-ms 0})]
              (is (= [operation] (:ready completed))))
            (is (= :followup (evaluate rt (:session-id original) "@memo")))
            (is (= "Initial" (:answer (evaluate rt sid "(agents/result child)"))))
            (is (= "Initial" (:answer (evaluate rt sid original-link))))
            (is (= "Followup" (:answer (evaluate rt sid (get-in delivery [:deliveries 0 :next :result])))))
            (is (= "Followup" (:answer (agents/result-view (:agents rt) sid operation
                                                           (:operation-id operation) {}))))
            (is (= (:operation-id operation)
                   (get-in (commands/dispatch! rt "agent.delivery" {:session-id sid :receipt receipt})
                           [:deliveries 0 :operation-id]))))
          (finally (deliver release true)))))))

(deftest compact-errors-retain-a-bounded-cause-and-detail-access
  (let [message (apply str (repeat 1000 "failure "))]
    (fixtures/with-runtime
      [rt (fn [_ _] (throw (ex-info message {:error/code "fixture-failure" :detail "private diagnostic"})))]
      (let [sid (parent rt)
            child (agents/start-agent! (:agents rt) sid {:name "Failure" :task "Fail"})]
        (runtime/wait! rt (:operation-id child) 10000)
        (let [brief (agents/result-view (:agents rt) sid child (:operation-id child) {})
              detailed (agents/result-view (:agents rt) sid child (:operation-id child) {:detailed? true})]
          (is (= :failed (:status brief)))
          (is (= (subs message 0 240) (get-in brief [:error :message])))
          (is (true? (get-in brief [:error :truncated?])))
          (is (not (contains? (:error brief) :data)))
          (is (= message (get-in detailed [:error :message]))))))))
