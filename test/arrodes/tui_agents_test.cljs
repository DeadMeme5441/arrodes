(ns arrodes.tui-agents-test
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-model :as model]
            [arrodes.tui.controller.agents :as agents]
            [arrodes.tui.controller.client :as client]
            [arrodes.tui.controller.sessions :as sessions]))

(defn- check! [condition message]
  (when-not condition (throw (js/Error. message))))

(defn exercise! []
  (let [application (app/create! {:runtime-root (.cwd js/process) :setup? false})
        root {:session-id "root" :root-id "root" :name "Main" :depth 0}
        child {:session-id "child" :root-id "root" :parent-session-id "root"
               :name "Parser" :depth 1 :operation {:id "run-1" :status :queued}}
        background {:seq 21 :session-id "root" :type :agent/changed
                    :data {:root-id "root" :session-id "child"}}
        started {:seq 22 :session-id "child" :operation-id "run-1"
                 :type :operation/started :data {:session-id "child"}}
        attempts (atom [])]
    (swap! (:state application)
           #(-> % (assoc :view (assoc (model/empty-state) :session {:id "root"})
                         :agents {:root-id "root" :agents [root child] :cursor 20
                                  :loading? true :buffer [] :submissions {}})
                (assoc-in [:ui :draft] "parent's unfinished draft")
                (assoc-in [:ui :scroll-top] 19)
                (assoc-in [:ui :selected] "message:parent")))
    (app/event! application background)
    (app/event! application started)
    (check! (= :running (get-in @(:state application) [:agents :agents 1 :operation :status]))
            "An off-focus child start must update the root roster")
    (check! (= 2 (count (get-in @(:state application) [:agents :buffer])))
            "Events received while the team snapshot is in flight must be buffered")
    (check! (nil? (get-in @(:state application) [:view :operation]))
            "An off-focus child must not replace the focused root operation")
    (let [navigate
          (fn [sid]
            (with-redefs [client/call!
                          (fn [_ method params]
                            (check! (= "session.view" method) "Navigation requested an unexpected transcript")
                            (check! (= sid (:session-id params)) "Navigation hydrated the wrong session")
                            (client/resolved {:state {:session {:id sid}} :entries [] :cursor 0}))]
              (sessions/hydrate-session! application sid true)))]
      (-> (navigate "child")
          (.then (fn [_]
                   (check! (= "root" (get-in @(:state application) [:agents :root-id]))
                           "Opening a child must retain its root roster")
                   (swap! (:state application)
                          #(-> % (assoc-in [:ui :draft] "child's unfinished draft")
                               (assoc-in [:ui :scroll-top] 7)))
                   (navigate "root")))
          (.then (fn [_]
                   (check! (= "parent's unfinished draft" (get-in @(:state application) [:ui :draft]))
                           "Returning from a child must restore the parent's unsent draft")
                   (check! (= 19 (get-in @(:state application) [:ui :scroll-top]))
                           "Returning from a child must restore its scroll anchor")
                   (check! (= "child's unfinished draft" (get-in @(:state application) [:ui :drafts "child"]))
                           "Returning to a parent must retain the child's own draft")
                   (with-redefs [client/mutation!
                                 (fn [_ method params]
                                   (swap! attempts conj [method params])
                                   (js/Promise.reject
                                    (client/error "disconnected" "Response lost" {:unknown-outcome? true})))]
                     (agents/send! application {:target "child" :content "Review the result"}))))
          (.then (fn [_] (throw (js/Error. "Unknown send unexpectedly succeeded")))
                 (fn [_]
                   (let [id (get-in @attempts [0 1 :submission-id])]
                     (check! (string? id) "An addressed mutation needs its own durable submission identity")
                     (check! (= :unknown (get-in @(:state application) [:agents :submissions id :status]))
                             "An unacknowledged message must remain inspectable as unknown")
                     (check! (= "parent's unfinished draft" (get-in @(:state application) [:ui :draft]))
                             "A failed addressed send must not touch the conversation draft")
                     (try
                       (agents/send! application {:target "child" :content "Review the result"})
                       (throw (js/Error. "A second submission duplicated an unresolved send"))
                       (catch :default error
                         (check! (= "agent-submission-unknown" (:code (ex-data error)))
                                 "The same uncertain message must be blocked until receipt inspection")))
                     (check! (= 1 (count @attempts)) "The unknown send was issued twice"))))))))
