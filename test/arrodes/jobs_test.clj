(ns arrodes.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [arrodes.jobs :as jobs]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as session-fixtures]
            [arrodes.store :as store]
            [arrodes.artifacts :as artifacts]
            [arrodes.commands :as commands])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn eval! [rt sid source]
  (let [result (runtime/evaluate! rt sid source)]
    (when (:error? result) (throw (ex-info (:content result) (:details result))))
    (:value result)))
(defn await! [rt sid handle] (jobs/await-job (:jobs rt) sid (:id handle) 10000))

(deftest foreground-and-background-have-independent-contexts-and-native-values
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def gate (promise)) (def started (promise)) (def j (jobs/start! {:name \"Compute\"} #(do (deliver started true) @gate (println \"job output\") {:ratio 2/3}))) @started j")]
      (is (= :running (:status (jobs/inspect-job (:jobs rt) sid (:id handle)))))
      (is (= :idle (:phase (runtime/state rt sid))))
      (is (= 42 (eval! rt sid "(+ 20 22)")))
      (is (:error? (runtime/evaluate! rt sid "(jobs/result j)")))
      (eval! rt sid "(deliver gate true)")
      (is (= :completed (:status (await! rt sid handle))))
      (is (= {:ratio 2/3} (eval! rt sid "(jobs/result j)")))
      (is (= "job output\n" (:text (jobs/output-job (:jobs rt) sid (:id handle) {}))))
      (is (true? (:eof? (jobs/output-job (:jobs rt) sid (:id handle) {}))))
      (is (empty? (store/pending-job-results (:store rt) sid)))
      (let [other (:id (fixtures/create-session rt))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"this session"
                             (jobs/inspect-job (:jobs rt) other (:id handle))))))))

(deftest failures-retain-output-and-do-not-replay-effects
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def effects (atom 0)) (def j (jobs/start! #(do (swap! effects inc) (println \"before failure\") (throw (ex-info \"boom\" {}))))) j")
          record (await! rt sid handle)]
      (is (= :failed (:status record)))
      (is (= "boom" (get-in record [:error :message])))
      (is (:error? (runtime/evaluate! rt sid "(jobs/result j)")))
      (is (= 1 (eval! rt sid "@effects")))
      (is (= "before failure\n" (:text (jobs/output-job (:jobs rt) sid (:id handle) {})))))))

(deftest cancellation-does-not-pretend-an-uncooperative-worker-exited
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def release (atom false)) (def started (promise)) (def j (jobs/start! #(do (deliver started true) (while (not @release) (try (Thread/sleep 5) (catch InterruptedException _))) :finished))) @started j")]
      (try
        (is (= :cancelling (:status (jobs/cancel-job! (:jobs rt) sid (:id handle)))))
        (is (= :cancelling (:status (jobs/await-job (:jobs rt) sid (:id handle) 10))))
        (eval! rt sid "(reset! release true)")
        (is (= :cancelled (:status (await! rt sid handle))))
        (is (= :cancelled (:status (jobs/cancel-job! (:jobs rt) sid (:id handle)))))
        (finally (eval! rt sid "(reset! release true)"))))))

(deftest managed-shell-retains-output-and-cancels-owned-process
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          output (promise)
          unsubscribe (runtime/subscribe! rt #(when (and (= :job/output (:type %))
                                                         (= "hello" (get-in % [:data :content])))
                                                (deliver output true)))
          handle (eval! rt sid "(def j (jobs/start! {:name \"Shell\"} #(bash {:command \"printf 'hello'; sleep 30\"}))) j")]
      (try
        (fixtures/await! output)
        (is (= "hello" (:text (jobs/output-job (:jobs rt) sid (:id handle) {}))))
        (jobs/cancel-job! (:jobs rt) sid (:id handle))
        (is (= :cancelled (:status (await! rt sid handle))))
        (is (= "hello" (:text (jobs/output-job (:jobs rt) sid (:id handle) {}))))
        (finally (unsubscribe))))))

(deftest parents-own-children-and-self-waits-are-rejected
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          parent (eval! rt sid "(def child-handle (promise)) (def p (jobs/start! #(do (deliver child-handle (jobs/start! (fn [] (Thread/sleep 30000)))) :parent-result))) @child-handle p")
          child (eval! rt sid "@child-handle")]
      (is (= (:id parent) (:parent-job-id (jobs/inspect-job (:jobs rt) sid (:id child)))))
      (is (= :running (:status (jobs/inspect-job (:jobs rt) sid (:id parent)))))
      (jobs/cancel-job! (:jobs rt) sid (:id parent))
      (is (= :cancelled (:status (await! rt sid parent))))
      (is (= :cancelled (:status (await! rt sid child))))
      (let [self (eval! rt sid "(def h (promise)) (def s (jobs/start! #(jobs/wait @h))) (deliver h s) s")]
        (is (= :failed (:status (await! rt sid self))))
        (is (= "job-wait-cycle" (get-in (jobs/inspect-job (:jobs rt) sid (:id self)) [:error :code])))))))

(deftest reload-cancels-jobs-before-removing-their-namespace
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def j (jobs/start! #(Thread/sleep 30000))) j")
          before (get-in (runtime/state rt sid) [:repl :generation])]
      (is (= :reloaded (:status (runtime/reload! rt sid))))
      (is (= :cancelled (:status (jobs/inspect-job (:jobs rt) sid (:id handle)))))
      (is (not= before (get-in (runtime/state rt sid) [:repl :generation])))
      (is (nil? (eval! rt sid "(resolve 'j)"))))))

(deftest output-is-paged-and-hard-truncation-is-honest
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(jobs/start! #(do (print (apply str (repeat 1100000 \"x\"))) :ok))")
          record (await! rt sid handle)
          page (jobs/output-job (:jobs rt) sid (:id handle) {:offset 2 :limit 4})]
      (is (= :completed (:status record)))
      (is (= "xxxx" (:text page)))
      (is (= 6 (:next-offset page)))
      (is (:truncated? page))
      (is (false? (:eof? page)))
      (is (= (* 1024 1024) (:bytes (artifacts/get-artifact (:store rt) sid (:output-artifact-id record))))))))

(deftest results-deliver-once-at-model-boundaries-and-fork-remaps-the-reference
  (let [requests (atom [])]
    (fixtures/with-runtime [rt (fn [request _] (swap! requests conj request) (fixtures/answer "Done"))]
      (let [sid (:id (fixtures/create-session rt))
            handle (eval! rt sid "(jobs/start! {:name \"Useful\"} #(hash-map :answer 42))")]
        (await! rt sid handle)
        (is (empty? @requests))
        (runtime/run! rt sid "Use the result" {})
        (runtime/continue! rt sid {})
        (is (= 1 (count (filter #(get-in % [:data :message/job-id]) (runtime/entries rt sid)))))
        (is (some #(re-find #"Retained result" (str (:message/content %))) (:request/messages (first @requests))))
        (let [fork (runtime/fork! rt sid {})
              child-sid (:id fork)
              entry (first (filter #(get-in % [:data :message/job-id]) (runtime/entries rt child-sid)))
              rid (get-in entry [:data :message/result :id])]
          (is (integer? rid))
          (is (= {:answer 42} (eval! rt child-sid (str "(result " rid ")")))))))))

(deftest rpc-job-controls-are-session-scoped-and-leave-foreground-idle
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(jobs/start! #(Thread/sleep 30000))")
          params {:session-id sid :job-id (:id handle)}]
      (is (= (:id handle) (:id (commands/dispatch! rt "job.inspect" params))))
      (is (= 1 (count (:jobs (commands/dispatch! rt "job.list" {:session-id sid})))))
      (is (= :idle (:phase (runtime/state rt sid))))
      (commands/dispatch! rt "job.cancel" params)
      (is (= :cancelled (:status (commands/dispatch! rt "job.wait" (assoc params :timeout-ms 10000))))))))

(deftest close-timeout-retains-job-resources-and-can-be-retried
  (let [directory (session-fixtures/temp-directory)
        rt (runtime/open! {:cwd directory :home (str directory "/home") :memory? true
                           :settings {:close-timeout-ms 30} :complete-fn (fn [_ _] (fixtures/answer "Done"))})
        sid (:id (fixtures/create-session rt))
        handle (eval! rt sid "(def release (atom false)) (def entered (promise)) (jobs/start! #(do (deliver entered true) (while (not @release) (try (Thread/sleep 5) (catch InterruptedException _)))))")
        registry (runtime/registry rt sid)
        release @(ns-resolve (:namespace registry) 'release)]
    (try
      (eval! rt sid "@entered")
      (let [generation (:generation registry)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"evaluator retained" (runtime/reload! rt sid)))
        (is (= generation (:generation (runtime/registry rt sid)))))
      (let [report (runtime/close! rt)]
        (is (= :closing (:status report)))
        (is (false? (:jobs-complete? report)))
        (is (false? (:store-closed? report)))
        (is (= :cancelling (:status (jobs/inspect-job (:jobs rt) sid (:id handle))))))
      (reset! release true)
      (await! rt sid handle)
      (is (= :closed (:status (runtime/close! rt))))
      (finally (reset! release true) (runtime/close! rt) (session-fixtures/remove-directory! directory)))))

(deftest restart-upgrades-v1-preserves-values-and-interrupts-unfinished-jobs
  (let [directory (session-fixtures/temp-directory)
        options {:cwd directory :home (str directory "/home") :data-dir (str directory "/data")
                 :complete-fn (fn [_ _] (fixtures/answer "Done"))}
        rt (runtime/open! options)
        sid (:id (fixtures/create-session rt))
        value-id (get-in (runtime/evaluate! rt sid "{:prior-version 2/3}") [:result :id])]
    (runtime/close! rt)
    ;; A synthetic schema-1 fixture: prior tables/history, no jobs table.
    (with-open [connection (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" directory "/data/sessions.sqlite"))
                statement (.createStatement connection)]
      (.execute statement "DROP TABLE jobs")
      (.execute statement "PRAGMA user_version=1"))
    (let [upgraded (runtime/open! options)
          handle (eval! upgraded sid "(jobs/start! #(hash-map :native 3/7))")
          _ (await! upgraded sid handle)
          unfinished {:id (str (java.util.UUID/randomUUID)) :session-id sid :name "Interrupted fixture"
                      :kind :clojure :status :queued :created-at 1 :origin {}}]
      (try
        (is (= {:prior-version 2/3} (eval! upgraded sid (str "(result " value-id ")"))))
        (store/create-job! (:store upgraded) unfinished)
        (runtime/close! upgraded)
        (let [reopened (runtime/open! options)]
          (try
            (is (= :interrupted (:status (jobs/inspect-job (:jobs reopened) sid (:id unfinished)))))
            (is (= :completed (:status (jobs/inspect-job (:jobs reopened) sid (:id handle)))))
            (is (= {:native 3/7} (eval! reopened sid (str "(jobs/result " (pr-str (:id handle)) ")"))))
            (is (= 2 (count (store/jobs (:store reopened) sid))))
            (finally (runtime/close! reopened))))
        (finally (runtime/close! upgraded) (session-fixtures/remove-directory! directory))))))

(deftest capacity-rejection-does-not-run-the-function
  (let [directory (session-fixtures/temp-directory)
        rt (runtime/open! {:cwd directory :home (str directory "/home") :memory? true
                           :settings {:job-limit 1} :complete-fn (fn [_ _] (fixtures/answer "Done"))})
        sid (:id (fixtures/create-session rt))]
    (try
      (eval! rt sid "(def effect (atom 0)) (jobs/start! #(Thread/sleep 30000))")
      (is (:error? (runtime/evaluate! rt sid "(jobs/start! #(swap! effect inc))")))
      (is (= 0 (eval! rt sid "@effect")))
      (is (= 1 (count (store/jobs (:store rt) sid))))
      (finally (runtime/close! rt) (session-fixtures/remove-directory! directory)))))

(deftest cancelling-a-foreground-turn-leaves-accepted-jobs-running
  (let [entered (promise)]
    (fixtures/with-runtime [rt (fn [_ options]
                                (deliver entered true)
                                (loop []
                                  (arrodes.platform/check-cancelled! (:cancelled? options))
                                  (Thread/sleep 5) (recur)))]
      (let [sid (:id (fixtures/create-session rt))
            handle (eval! rt sid "(jobs/start! #(Thread/sleep 30000))")
            op (runtime/start! rt sid "Wait")]
        (fixtures/await! entered)
        (runtime/cancel! rt sid)
        (is (= :cancelled (:status (runtime/wait! rt (:id op) 10000))))
        (is (= :running (:status (jobs/inspect-job (:jobs rt) sid (:id handle)))))
        (jobs/cancel-job! (:jobs rt) sid (:id handle))
        (is (= :cancelled (:status (await! rt sid handle))))))))

(deftest branch-movement-acknowledges-all-old-outcomes-not-just-one-delivery-page
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (dotimes [n 23]
        (await! rt sid (eval! rt sid (str "(jobs/start! (fn [] " n "))"))))
      (runtime/branch! rt sid nil {})
      (is (empty? (store/pending-job-results (:store rt) sid)))
      (runtime/run! rt sid "New branch" {})
      (is (empty? (filter #(get-in % [:data :message/job-id]) (runtime/active-path rt sid)))))))

(deftest admission-persistence-failure-settles-without-executing-the-function
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          transition store/transition-job!
          failed (atom false)]
      (with-redefs [store/transition-job!
                    (fn [storage session-id id expected changes]
                      (when (and (= :running (:status changes)) (compare-and-set! failed false true))
                        (throw (ex-info "Synthetic admission write failure" {})))
                      (transition storage session-id id expected changes))]
        (let [handle (eval! rt sid "(def effects (atom 0)) (jobs/start! #(swap! effects inc))")
              record (await! rt sid handle)]
          (is (= :failed (:status record)))
          (is (= 0 (eval! rt sid "@effects")))
          (is (= :closed (:status (runtime/close! rt)))))))))
