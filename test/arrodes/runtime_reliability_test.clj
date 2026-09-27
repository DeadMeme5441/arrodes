(ns arrodes.runtime-reliability-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [clojure.test :refer [deftest is]]))

(deftest transient-provider-retry-does-not-repeat-settled-effects
  (let [calls (atom 0)
        complete (fn [_ _]
                   (case (swap! calls inc)
                     1 (fixtures/calls
                        (fixtures/tool-call "effect"
                          "(spit (str cwd \"/effect.txt\") \"once\\n\" :append true) :written"))
                     2 (throw (ex-info "Temporary outage" {:status 503 :retryable? true}))
                     (fixtures/answer "Finished")))]
    (fixtures/with-runtime [rt complete]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "Write once and finish")
        (is (= "once\n" (slurp (str (:cwd (runtime/session rt sid)) "/effect.txt"))))
        (is (= :idle (:status (runtime/session rt sid))))
        (is (= 1 (count (filter #(= "effect" (get-in % [:data :message/tool-call-id]))
                               (runtime/entries rt sid)))))))))

(deftest visible-stream-failure-remains-failed-without-automatic-resubmission
  (let [calls (atom 0)
        complete (fn [_ options]
                   (swap! calls inc)
                   ((:on-event options) {:event/type :stream/content-delta
                                         :event/delta "Partial answer"})
                   (throw (ex-info "Connection lost" {:status 503 :retryable? true})))]
    (fixtures/with-runtime [rt complete]
      (let [sid (:id (fixtures/create-session rt))
            error (try (runtime/run! rt sid "Start work")
                       (catch clojure.lang.ExceptionInfo error error))]
        (is (true? (:provider/output-started? (ex-data error))))
        (is (= 1 @calls))
        (is (= :failed (:status (runtime/session rt sid))))))))

(deftest cancellation-at-retry-boundary-prevents-another-provider-request
  (let [calls (atom 0)
        complete (fn [_ _]
                   (swap! calls inc)
                   (throw (ex-info "Rate limited" {:status 429 :retryable? true})))]
    (fixtures/with-runtime [rt complete]
      (let [sid (:id (fixtures/create-session rt))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (runtime/run! rt sid "Start work"
                       {:on-event (fn [event]
                                    (when (= :provider-retry (:type event))
                                      (runtime/cancel! rt sid)))})))
        (is (= 1 @calls))
        (is (= :interrupted (:status (runtime/session rt sid))))))))

(deftest truncated-tool-response-is-retained-but-never-executed
  (fixtures/with-runtime
    [rt (fn [_ _]
          (assoc (fixtures/calls
                  (fixtures/tool-call "truncated"
                    "(spit (str cwd \"/should-not-exist\") \"effect\")"))
                 :response/finish-reason :length
                 :response/usage {:usage/input-tokens 8 :usage/output-tokens 20}))]
    (let [sid (:id (fixtures/create-session rt))
          error (try (runtime/run! rt sid "Do not run truncated work")
                     (catch clojure.lang.ExceptionInfo error error))
          messages (map :data (filter #(= :message (:kind %)) (runtime/entries rt sid)))
          assistant (first (filter #(= :assistant (:message/role %)) messages))]
      (is (= "output-truncated" (:error/code (ex-data error))))
      (is (not (.exists (java.io.File. (str (:cwd (runtime/session rt sid)) "/should-not-exist")))))
      (is (= :length (get-in assistant [:message/provider-data :response/finish-reason])))
      (is (= 20 (get-in assistant [:message/provider-data :response/usage :usage/output-tokens])))
      (is (= "failed" (get-in (last messages) [:message/result :details :error/code]))))))
