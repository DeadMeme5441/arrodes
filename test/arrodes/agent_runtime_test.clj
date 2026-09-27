(ns arrodes.agent-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [arrodes.agents :as agents]
            [arrodes.capabilities :as capabilities]
            [arrodes.commands :as commands]
            [arrodes.jobs :as jobs]
            [arrodes.platform :as util]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as session-fixtures]
            [arrodes.store :as store]))

(defn- eval! [rt sid source]
  (let [evaluated (runtime/evaluate! rt sid source)]
    (when (:error? evaluated) (throw (ex-info (:content evaluated) (:details evaluated))))
    (:value evaluated)))

(defn- await! [p]
  (let [value (deref p 10000 ::timeout)]
    (when (= ::timeout value) (throw (ex-info "Agent interaction did not reach its boundary" {})))
    value))

(defn- parent! [rt]
  (let [sid (:id (fixtures/create-session rt))]
    (agents/pause! (:agents rt) sid)
    sid))

(deftest independent-child-repl-retains-bindings-and-portable-native-results
  (let [steps (atom {})]
    (fixtures/with-runtime
      [rt (fn [request _]
            (let [sid (get-in request [:request/cache :scope-id])
                  n (get (swap! steps update sid (fnil inc 0)) sid)]
              (case n
                1 (fixtures/calls (fixtures/tool-call "child-data"
                                    "(def retained (atom 40)) (def report {:ratio 2/3}) report"))
                3 (fixtures/calls (fixtures/tool-call "child-follow-up" "(swap! retained + 2)"))
                (fixtures/answer "Reported"))))]
      (let [sid (parent! rt)
            handle (eval! rt sid "(def retained :parent) (def scout (agents/start! {:name \"Parser\" :task \"Read parser\"})) scout")
            child (:session-id handle)]
        (is (= :completed (:status (runtime/wait! rt (:operation-id handle) 10000))))
        (is (= :parent (eval! rt sid "retained")))
        (is (= 40 (eval! rt child "@retained")))
        (let [generation (:generation (runtime/registry rt child))
              descriptor (some #(when (= "child-data" (get-in % [:data :message/tool-call-id]))
                                  (get-in % [:data :message/result])) (runtime/entries rt child))
              live (runtime/evaluate! rt child "retained")
              finished (promise)
              unsubscribe (runtime/subscribe! rt
                            (fn [event]
                              (when (and (= child (:session-id event))
                                         (= :operation/completed (:type event))
                                         (not= (:operation-id handle) (:operation-id event))
                                         (contains? #{:run :continue}
                                                    (:kind (runtime/operation rt (:operation-id event)))))
                                (deliver finished (:operation-id event)))))]
          (try
            (is (= {:ratio 2/3} (agents/read-value (:agents rt) sid handle (:id descriptor))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Live JVM"
                                  (agents/read-value (:agents rt) sid handle (get-in live [:result :id]))))
            (agents/send-message! (:agents rt) sid handle "Add two using retained state" {})
            (is (= :completed (:status (runtime/wait! rt (await! finished) 10000))))
            (finally (unsubscribe)))
          (is (= 42 (eval! rt child "@retained")))
          (is (= generation (:generation (runtime/registry rt child)))))))))

(deftest completion-remains-pending-while-paused-and-resume-wakes-parent
  (let [parent-id (atom nil) root-called (promise)]
    (fixtures/with-runtime
      [rt (fn [request _]
            (when (= @parent-id (get-in request [:request/cache :scope-id]))
              (deliver root-called true))
            (fixtures/answer "Complete"))]
      (let [sid (parent! rt)
            _ (reset! parent-id sid)
            handle (agents/start-agent! (:agents rt) sid {:name "Worker" :task "Finish independently"})]
        (is (= :completed (:status (runtime/wait! rt (:operation-id handle) 10000))))
        (is (true? (:paused? (store/agent-state (:store rt) sid))))
        (is (store/pending-agent-messages? (:store rt) sid))
        (is (nil? (:operation-id (runtime/state rt sid))))
        (is (not (realized? root-called)))
        (is (= :completed (:status (agents/operation-result (:agents rt) sid handle (:operation-id handle)))))
        (agents/messages-for (:agents rt) sid {})
        (is (store/pending-agent-messages? (:store rt) sid))
        (agents/resume-agent! (:agents rt) sid sid)
        (await! root-called)
        (when-let [oid (:operation-id (runtime/state rt sid))] (runtime/wait! rt oid 10000))
        (is (not (store/pending-agent-messages? (:store rt) sid)))))))

(deftest cancelling-parent-preserves-child-and-function-job-until-explicit-tree-stop
  (let [parent-id (atom nil) parent-entered (promise) child-entered (promise)]
    (fixtures/with-runtime
      [rt (fn [request opts]
            (deliver (if (= @parent-id (get-in request [:request/cache :scope-id])) parent-entered child-entered) true)
            (loop []
              (util/check-cancelled! (:cancelled? opts))
              (Thread/sleep 10)
              (recur)))]
      (let [sid (parent! rt)
            _ (reset! parent-id sid)
            job (eval! rt sid "(def background (jobs/start! #(Thread/sleep 60000))) background")
            child (agents/start-agent! (:agents rt) sid {:name "Independent" :task "Wait"})]
        (await! child-entered)
        (let [op (runtime/start! rt sid "Run parent")]
          (await! parent-entered)
          (runtime/cancel-operation! rt (:id op))
          (is (= :cancelled (:status (runtime/wait! rt (:id op) 10000))))
          (is (= :running (:status (runtime/operation rt (:operation-id child)))))
          (is (contains? #{:queued :running} (:status (jobs/inspect-job (:jobs rt) sid (:id job)))))
          (is (true? (:paused? (store/agent-state (:store rt) sid)))))
        (is (= :stopped (:status (agents/stop-agent! (:agents rt) sid sid {:timeout-ms 10000}))))
        (is (= :cancelled (:status (runtime/operation rt (:operation-id child)))))
        (is (= :cancelled (:status (jobs/inspect-job (:jobs rt) sid (:id job)))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (agents/start-agent! (:agents rt) sid {:name "Rejected" :task "Must not start"})))))))

(deftest managed-wait-observes-message-before-or-after-registration-without-consuming-it
  (let [child-entered (promise)]
    (fixtures/with-runtime
      [rt (fn [_ opts]
            (deliver child-entered true)
            (loop []
              (util/check-cancelled! (:cancelled? opts))
              (Thread/sleep 10)
              (recur)))]
      (let [sid (parent! rt)
            child (agents/start-agent! (:agents rt) sid {:name "Peer" :task "Wait for coordination"})
            waiting (promise)]
        (await! child-entered)
        (capabilities/register! (runtime/registry rt sid)
          {:name "waiting_boundary" :description "Expose the managed-wait boundary"
           :permission :read :execution :parallel :parameters {:type "object" :properties {}}
           :fn (fn [_] (deliver waiting true) nil)})
        (let [worker (future (eval! rt sid "(waiting_boundary {}) (agents/wait {:timeout-ms 10000})"))]
          (try
            (await! waiting)
            (agents/send-message! (:agents rt) (:session-id child) :parent {:ratio 3/7} {})
            (is (= :message (:reason (await! worker))))
            (is (store/pending-agent-messages? (:store rt) sid))
            (is (some #(= {:ratio 3/7} (:content %)) (agents/messages-for (:agents rt) sid {})))
            (finally
              (agents/stop-agent! (:agents rt) sid child {:timeout-ms 10000})
              (future-cancel worker))))))))

(deftest stale-agent-handle-cannot-cancel-a-new-operation-and-foreign-team-is-rejected
  (let [steps (atom 0) second-entered (promise)]
    (fixtures/with-runtime
      [rt (fn [_ opts]
            (if (= 1 (swap! steps inc))
              (fixtures/answer "First completed")
              (do (deliver second-entered true)
                  (loop [] (util/check-cancelled! (:cancelled? opts)) (Thread/sleep 10) (recur)))))]
      (let [sid (parent! rt)
            handle (agents/start-agent! (:agents rt) sid {:name "Reusable" :task "Finish"})
            foreign (:id (fixtures/create-session rt))]
        (runtime/wait! rt (:operation-id handle) 10000)
        (let [next-op (runtime/start! rt (:session-id handle) "Second task")]
          (await! second-entered)
          (is (= :completed (:status (agents/cancel-agent! (:agents rt) sid handle (:operation-id handle)))))
          (is (= :running (:status (runtime/operation rt (:id next-op)))))
          (is (false? (:paused? (store/agent-state (:store rt) (:session-id handle)))))
          (is (thrown? clojure.lang.ExceptionInfo (agents/inspect-agent (:agents rt) foreign handle)))
          (agents/cancel-agent! (:agents rt) sid (:session-id handle) (:id next-op))
          (is (= :cancelled (:status (runtime/wait! rt (:id next-op) 10000)))))))))

(deftest native-wait-rejects-self-cycle-and-rpc-reconciles-accepted-spawn
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (parent! rt)
          result (runtime/evaluate! rt sid "(agents/wait {:handles [(select-keys arrodes.capabilities/*invocation-context* [:session-id :operation-id])]})")
          key (util/id)
          opts {:session-id sid :name "Receipt" :task "Finish" :submission-id key}
          handle (commands/dispatch! rt "agent.start" opts)]
      (is (:error? result))
      (is (= "agent-wait-cycle" (get-in result [:details :code])))
      (runtime/wait! rt (:operation-id handle) 10000)
      (is (= handle (commands/dispatch! rt "agent.submission" {:session-id sid :submission-id key})))
      (is (= handle (commands/dispatch! rt "agent.start" opts)))
      (is (= 2 (count (:agents (commands/dispatch! rt "agent.list" {:session-id sid}))))))))

(deftest capacity-release-in-another-team-wakes-accepted-input
  (let [directory (session-fixtures/temp-directory)
        busy-id (atom nil)
        busy-entered (promise)
        release-busy (promise)
        child-calls (atom 0)
        child-awake (promise)
        capacity-blocked (promise)
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :settings {:operation-limit 1}
                           :complete-fn
                           (fn [request _]
                             (if (= @busy-id (get-in request [:request/cache :scope-id]))
                               (do (deliver busy-entered true) @release-busy)
                               (when (= 2 (swap! child-calls inc)) (deliver child-awake true)))
                             (fixtures/answer "Finished"))})]
    (try
      (let [parent (parent! rt)
            child (agents/start-agent! (:agents rt) parent {:name "Capacity" :task "First task"})
            other (:id (fixtures/create-session rt))
            original runtime/start-continue!]
        (runtime/wait! rt (:operation-id child) 10000)
        (reset! busy-id other)
        (let [busy (runtime/start! rt other "Occupy the only worker slot")]
          (await! busy-entered)
          (with-redefs [runtime/start-continue!
                        (fn [runtime sid opts]
                          (try (original runtime sid opts)
                               (catch clojure.lang.ExceptionInfo error
                                 (when (and (= sid (:session-id child))
                                            (= "operation-limit" (:error/code (ex-data error))))
                                   (deliver capacity-blocked true))
                                 (throw error))))]
            (agents/send-message! (:agents rt) parent child "Accepted while another team is busy" {})
            (await! capacity-blocked)
            (deliver release-busy true)
            (runtime/wait! rt (:id busy) 10000)
            (is (= true (deref child-awake 3000 ::timeout))))))
      (finally
        (deliver release-busy true)
        (runtime/close! rt)
        (session-fixtures/remove-directory! directory)))))

(deftest nested-function-job-can-stop-its-team-without-awaiting-its-own-parent
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (parent! rt)
          setup (eval! rt sid
                       "(def release-stop (promise))
                        (def stop-returned (promise))
                        (def parent-job
                          (jobs/start!
                            #(do
                               (jobs/start!
                                 (fn []
                                   @release-stop
                                   (deliver stop-returned
                                     (agents/stop! (:session-id arrodes.capabilities/*invocation-context*)
                                                   {:timeout-ms 2000}))))
                               :parent)))
                        {:release release-stop :returned stop-returned :job parent-job}")]
      ;; This root has no parent agent: address its own session explicitly while
      ;; retaining nested function-job ownership.
      (deliver (:release setup) true)
      (let [outcome (deref (:returned setup) 1000 ::timeout)]
        (is (= :stopping (:status outcome))))
      (deref (:returned setup) 5000 nil)
      (jobs/await-job (:jobs rt) sid (get-in setup [:job :id]) 10000))))

(deftest cancelled-invocations-cannot-admit-new-owned-work-or-undo-the-stop
  (doseq [effect [:job :agent :message :resume]]
    (let [parent-id (atom nil)
          entered (promise)
          release (java.util.concurrent.CountDownLatch. 1)]
      (fixtures/with-runtime
        [rt (fn [request _]
              (if (= @parent-id (get-in request [:request/cache :scope-id]))
                (fixtures/calls (fixtures/tool-call "cancel-boundary" "(cancel_boundary {})"))
                (fixtures/answer "Unexpected child")))]
        (let [sid (parent! rt)]
          (reset! parent-id sid)
          (capabilities/register! (runtime/registry rt sid)
            {:name "cancel_boundary" :description "Exercise effect admission after cancellation"
             :permission :execute :execution :parallel
             :parameters {:type "object" :properties {}}
             :fn (fn [_]
                   (deliver entered true)
                   (loop []
                     (when-not (try (.await ^java.util.concurrent.CountDownLatch release) true
                                    (catch InterruptedException _ false))
                       (recur)))
                   (case effect
                     :job (jobs/start! (fn [] :escaped))
                     :agent (agents/start! {:name "Escaped" :task "Must not execute"})
                     :message (agents/send! sid "Must not be queued")
                     :resume (agents/resume! sid)))})
          (try
            (let [operation (runtime/start! rt sid "Wait at the cancellation boundary")]
              (await! entered)
              (runtime/cancel-operation! rt (:id operation))
              (.countDown ^java.util.concurrent.CountDownLatch release)
              (is (= :cancelled (:status (runtime/wait! rt (:id operation) 10000))))
              (case effect
                :job (is (empty? (jobs/list-jobs (:jobs rt) sid {})))
                :agent (is (= 1 (count (store/agent-team (:store rt) sid {}))))
                :message (is (empty? (agents/messages-for (:agents rt) sid {})))
                :resume (is (true? (:paused? (store/agent-state (:store rt) sid))))))
            (finally (.countDown ^java.util.concurrent.CountDownLatch release))))))))
