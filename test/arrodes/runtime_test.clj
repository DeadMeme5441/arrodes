(ns arrodes.runtime-test
  (:require [arrodes.capabilities :as capabilities]
            [arrodes.runtime :as runtime]
            [arrodes.run :as run]
            [arrodes.session-test :as fixtures]
            [arrodes.platform :as u]
            [arrodes.value :as value]
            [clojure.test :refer [deftest is]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn answer [text]
  {:response/provider :openai :response/model "gpt-4o-mini"
   :response/parts [{:part/type :text :text text}]
   :response/finish-reason :stop :response/provider-data {}})
(defn calls [& definitions]
  {:response/provider :openai :response/model "gpt-4o-mini"
   :response/parts [] :response/tool-calls (vec definitions)
   :response/finish-reason :tool-calls :response/provider-data {}})
(defn tool-call [id source]
  {:tool-call/id id :tool-call/name "repl" :tool-call/arguments {:source source}})
(defmacro with-runtime [[binding complete-fn] & body]
  `(let [directory# (fixtures/temp-directory)
         ~binding (runtime/open! {:cwd directory# :home (str directory# "/home")
                                  :data-dir (str directory# "/data")
                                  :complete-fn ~complete-fn})]
     (try ~@body
          (finally (runtime/close! ~binding) (fixtures/remove-directory! directory#)))))
(defn create-session [rt]
  (runtime/create-session! rt {:name "Runtime boundary" :config fixtures/config}))
(defn await! [promise-value]
  (let [result (deref promise-value 10000 ::timeout)]
    (when (= result ::timeout) (throw (ex-info "Expected execution boundary was not reached" {})))
    result))

(deftest composed-effects-complete-independently-and-evaluations-keep-call-order
  (let [turn (atom 0)
        completed (atom [])
        release-first (promise)
        provider (fn [_ _]
                   (if (= 1 (swap! turn inc))
                     (calls (tool-call "first-call"
                                       "(def completed-values (let [a (future (first_tool)) b (future (second_tool))] [@a @b])) completed-values")
                            (tool-call "second-call" "(first completed-values)"))
                     (answer "The operations completed")))]
    (with-runtime [rt provider]
      (let [sid (:id (create-session rt))
            registry (runtime/registry rt sid)]
        (capabilities/register! registry
                                {:name "first_tool" :description "Wait for the second operation"
                                 :parameters {:type "object" :properties {}} :execution :parallel :permission :read
                                 :fn (fn [_] (await! release-first) (swap! completed conj :first) "First result")})
        (capabilities/register! registry
                                {:name "second_tool" :description "Release the first operation"
                                 :parameters {:type "object" :properties {}} :execution :parallel :permission :read
                                 :fn (fn [_] (swap! completed conj :second) (deliver release-first true) "Second result")})
        (runtime/run! rt sid "Perform both operations" {})
        (let [messages (->> (runtime/entries rt sid) (filter #(= :message (:kind %))) (map :data))
              results (filter #(= :tool (:message/role %)) messages)]
          (is (= [:second :first] @completed))
          (is (= ["first-call" "second-call"] (mapv :message/tool-call-id results)))
          (is (.contains (value/text-content (:message/content (first results))) "First result"))
          (is (.contains (value/text-content (:message/content (second results))) "First result"))
          (is (= [:user :assistant :tool :tool :assistant] (mapv :message/role messages))))))))

(deftest stale-operation-control-cannot-cancel-a-new-run
  (let [starts (atom 0)
        first-entered (promise)
        second-entered (promise)
        provider (fn [_ options]
                   (deliver (if (= 1 (swap! starts inc)) first-entered second-entered) true)
                   (loop []
                     (u/check-cancelled! (:cancelled? options))
                     (Thread/sleep 10)
                     (recur)))]
    (with-runtime [rt provider]
      (let [sid (:id (create-session rt))
            first-op (runtime/start! rt sid "First request" {})]
        (await! first-entered)
        (is (thrown? clojure.lang.ExceptionInfo (runtime/start! rt sid "Conflicting request" {})))
        (runtime/cancel-operation! rt (:id first-op))
        (is (= :cancelled (:status (runtime/wait! rt (:id first-op) 10000))))
        (let [second-op (runtime/start! rt sid "Second request" {})]
          (await! second-entered)
          (try (runtime/cancel-operation! rt (:id first-op)) (catch clojure.lang.ExceptionInfo _ nil))
          (is (= :running (:status (runtime/operation rt (:id second-op)))))
          (runtime/cancel-operation! rt (:id second-op))
          (is (= :cancelled (:status (runtime/wait! rt (:id second-op) 10000)))))))))

(deftest live-evaluator-survives-configuration-but-not-branch-navigation
  (with-runtime [rt (fn [_ _] (answer "Done"))]
    (let [sid (:id (create-session rt))]
      (is (= 21 (:value (runtime/evaluate! rt sid "(def retained {:n 21}) (:n retained)" {}))))
      (runtime/configure! rt sid {:config {:thinking :high}})
      (is (= 42 (:value (runtime/evaluate! rt sid "(* 2 (:n retained))" {}))))
      (runtime/branch! rt sid nil {})
      (let [result (runtime/evaluate! rt sid "(resolve 'retained)" {})]
        (is (false? (:error? result)))
        (is (nil? (:value result)))))))

(deftest completed-external-effect-is-not-replayed-after-reopen
  (let [directory (fixtures/temp-directory)
        options {:cwd directory :home (str directory "/home") :data-dir (str directory "/data")}
        output (str directory "/effect.txt")
        phase (atom 0)
        next-request (promise)
        first-provider (fn [_ opts]
                         (if (= 1 (swap! phase inc))
                           (calls (tool-call "effect-call" "(external_effect)"))
                           (do (deliver next-request true)
                               (loop [] (u/check-cancelled! (:cancelled? opts)) (Thread/sleep 10) (recur)))))
        rt (runtime/open! (assoc options :complete-fn first-provider))
        sid (:id (create-session rt))]
    (try
      (capabilities/register! (runtime/registry rt sid)
                              {:name "external_effect" :description "Record an external effect"
                               :parameters {:type "object" :properties {}} :permission :write
                               :fn (fn [_] (spit output "effect\n" :append true) "Recorded")})
      (let [operation (runtime/start! rt sid "Perform the effect" {})]
        (await! next-request)
        (runtime/cancel-operation! rt (:id operation))
        (runtime/wait! rt (:id operation) 10000))
      (runtime/close! rt)
      (let [reopened (runtime/open! (assoc options :complete-fn (fn [_ _] (answer "Continuation completed"))))]
        (try
          (runtime/continue! reopened sid {})
          (is (= "effect\n" (slurp output)))
          (is (= 1 (count (filter #(= "effect-call" (get-in % [:data :message/tool-call-id]))
                                 (runtime/entries reopened sid)))))
          (finally (runtime/close! reopened))))
      (finally (runtime/close! rt) (fixtures/remove-directory! directory)))))

(deftest cancelled-branch-summary-preserves-head-and-live-evaluator
  (let [summarizing? (atom false)
        runtime* (atom nil)
        session-id* (atom nil)
        provider (fn [_ _]
                   (when @summarizing?
                     (runtime/cancel! @runtime* @session-id*))
                   (answer (if @summarizing? "Summary" "Done")))]
    (with-runtime [rt provider]
      (reset! runtime* rt)
      (let [sid (:id (create-session rt))]
        (reset! session-id* sid)
        (runtime/run! rt sid "First turn")
        (let [earlier-head (:head (runtime/session rt sid))]
          (runtime/run! rt sid "Second turn")
          (is (= 42 (:value (runtime/evaluate! rt sid "(def retained 41) (inc retained)"))))
          (let [head-before (:head (runtime/session rt sid))]
            (reset! summarizing? true)
            (is (thrown? clojure.lang.ExceptionInfo
                         (runtime/branch! rt sid earlier-head {:summarize? true})))
            (is (= head-before (:head (runtime/session rt sid))))
            (is (= 42 (:value (runtime/evaluate! rt sid "(inc retained)"))))))))))

(deftest compaction-plan-supports-an-empty-retained-range
  (let [message (fn [id role]
                  {:id id :kind :message
                   :data {:message/role role :message/content id}})
        entries [(message "abandoned-user" :user)
                 {:id "marker" :kind :compaction
                  :data {:summary "Imported summary"}}
                 (message "new-user" :user)
                 (message "new-assistant" :assistant)
                 (message "latest-user" :user)
                 (message "latest-assistant" :assistant)]
        plan (run/compaction-plan entries
                                  {:settings {:compaction-keep-entries 1}})]
    (is (= ["marker" "new-user" "new-assistant"]
           (mapv :id (:summary-entries plan))))
    (is (= ["latest-user" "latest-assistant"]
           (mapv :id (:kept-entries plan))))
    (is (= "latest-user" (:first-kept-entry-id plan)))))

(deftest default-runtime-storage-is-project-scoped-while-session-cwd-is-preserved
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        repository-a (str directory "/a/repo")
        cwd-a (str repository-a "/src/deep")
        repository-b (str directory "/b/repo")]
    (try
      (u/ensure-dir! (str repository-a "/.git"))
      (u/ensure-dir! cwd-a)
      (u/ensure-dir! (str repository-b "/.git"))
      (let [rt-a (runtime/open! {:cwd cwd-a :home home
                                 :complete-fn (fn [_ _] (answer "A"))})]
        (try
          (let [rt-b (runtime/open! {:cwd repository-b :home home
                                     :complete-fn (fn [_ _] (answer "B"))})]
            (try
              (is (= (u/resolve-path (u/project-dir home cwd-a) "data") (:data-dir rt-a)))
              (is (= (u/resolve-path (u/project-dir home repository-b) "data") (:data-dir rt-b)))
              (is (not= (:data-dir rt-a) (:data-dir rt-b)))
              (is (= (u/real-path cwd-a)
                     (:cwd (runtime/create-session! rt-a {:config fixtures/config}))))
              (is (= (u/real-path repository-b)
                     (:cwd (runtime/create-session! rt-b {:config fixtures/config}))))
              (finally (runtime/close! rt-b))))
          (finally (runtime/close! rt-a))))
      (finally (fixtures/remove-directory! directory)))))

(deftest explicit-runtime-data-directory-retains-precedence
  (let [directory (fixtures/temp-directory)
        explicit (str directory "/custom-data")
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir explicit
                           :complete-fn (fn [_ _] (answer "Done"))})]
    (try
      (is (= (u/canonical-path explicit) (:data-dir rt)))
      (finally
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest legacy-global-history-requires-an-explicit-data-directory
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        legacy-data (str home "/data")
        options {:cwd directory :home home :data-dir legacy-data
                 :complete-fn (fn [_ _] (answer "Done"))}
        first-runtime (runtime/open! options)]
    (try
      (runtime/create-session! first-runtime {:config fixtures/config})
      (runtime/close! first-runtime)
      (let [error (try
                    (runtime/open! (dissoc options :data-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= "legacy-data" (:error/code (ex-data error))))
        (is (= (u/canonical-path legacy-data) (:path (ex-data error)))))
      (let [reopened (runtime/open! options)]
        (try
          (is (= 1 (count (runtime/list-sessions reopened {}))))
          (finally (runtime/close! reopened))))
      (finally
        (runtime/close! first-runtime)
        (fixtures/remove-directory! directory)))))
