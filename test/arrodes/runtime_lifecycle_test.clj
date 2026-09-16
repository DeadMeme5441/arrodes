(ns arrodes.runtime-lifecycle-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.mcp :as mcp]
            [arrodes.platform :as u]
            [arrodes.session-test :as fixtures]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn- create-test-session! [rt opts]
  (runtime/create-session! rt (merge {:name "Lifecycle fixture"} opts)))

(defn- answer
  ([text] (answer :openai "gpt-4o-mini" text))
  ([provider model text]
   {:response/provider provider
    :response/model model
    :response/parts [{:part/type :text :text text}]
    :response/finish-reason :stop
    :response/provider-data {}}))

(defn- await-latch! [^CountDownLatch latch]
  (when-not (.await latch 10 TimeUnit/SECONDS)
    (throw (ex-info "Expected concurrent boundary was not reached" {}))))

(defn- await-uninterruptibly! [^CountDownLatch latch]
  (loop []
    (when-not (try
                (.await latch 20 TimeUnit/MILLISECONDS)
                (catch InterruptedException _ false))
      (recur))))

(def close-gate (atom nil))

(defn block-close! []
  (when-let [{:keys [entered release]} @close-gate]
    (deliver entered true)
    @release))

(deftest incomplete-close-retains-a-blocking-foreground-runtime
  (let [directory (fixtures/temp-directory)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        provider (fn [_ _]
                   (.countDown entered)
                   (await-uninterruptibly! release)
                   (answer "Released"))
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :settings {:close-timeout-ms 50}
                           :complete-fn provider})
        sid (:id (create-test-session! rt {:config fixtures/config}))
        worker (future
                 (try
                   (runtime/run! rt sid "Block in the foreground" {})
                   (catch Throwable error error)))]
    (try
      (await-latch! entered)
      (let [oid (:operation-id (runtime/state rt sid))
            report (runtime/close! rt)]
        (is (= :closing (:status report)))
        (is (false? (:foreground-complete? report)))
        (is (false? (:store-closed? report)))
        (is (= [oid] (:active-operation-ids report)))
        (is (= sid (:id (runtime/session rt sid))))
        (is (contains? #{:running :cancelling}
                       (:status (runtime/operation rt oid)))))
      (finally
        (.countDown release)
        (deref worker 10000 nil)
        (is (= :closed (:status (runtime/close! rt))))
        (fixtures/remove-directory! directory)))))

(deftest reload-excludes-a-concurrent-start-through-handle-replacement
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        extension (io/file (u/project-dir home directory) "extensions" "blocking_close.clj")
        entered (promise)
        release (promise)]
    (io/make-parents extension)
    (spit extension
          "(fn [{:keys [on-close!]}]\n  (on-close! arrodes.runtime-lifecycle-test/block-close!))\n")
    (let [rt (runtime/open! {:cwd directory :home home
                             :data-dir (str directory "/data") :trust true
                             :complete-fn (fn [_ _] (answer "Started after reload"))})
          sid (:id (create-test-session! rt {:config fixtures/config}))]
      (try
        (reset! close-gate {:entered entered :release release})
        (runtime/registry rt sid)
        (let [reload-result (future (runtime/reload! rt sid))]
          (when (= ::timeout (deref entered 10000 ::timeout))
            (throw (ex-info "Reload did not enter extension teardown" {})))
          (let [attempted (promise)
                start-result (future
                               (deliver attempted true)
                               (runtime/start! rt sid "Start during reload" {}))]
            @attempted
            (is (= ::blocked (deref start-result 100 ::blocked)))
            (deliver release true)
            (is (= :reloaded (:status (deref reload-result 10000 ::timeout))))
            (let [operation (deref start-result 10000 ::timeout)]
              (is (map? operation))
              (is (= :completed (:status (runtime/wait! rt (:id operation) 10000)))))))
        (finally
          (deliver release true)
          (reset! close-gate nil)
          (runtime/close! rt)
          (fixtures/remove-directory! directory))))))

(defn- summary-request? [request]
  (let [content (get-in request [:request/messages (dec (count (:request/messages request)))
                                 :message/content])]
    (and (string? content)
         (str/starts-with? content "Summarize the supplied conversation faithfully"))))

(deftest provider-bookkeeping-does-not-force-compaction-even-after-reopen
  (let [directory (fixtures/temp-directory)
        requests (atom [])
        bookkeeping (apply str (repeat 400000 "x"))
        provider (fn [request _]
                   (swap! requests conj request)
                   (assoc (answer :fixture "accounting" "Small reply")
                          :response/provider-data
                          {:fixture {:responses/event {:response bookkeeping}}}
                          :response/usage {:usage/input-tokens 4000
                                           :usage/cached-input-tokens 46000
                                           :usage/output-tokens 200
                                           :usage/total-tokens 50200
                                           :usage/provider-raw {:attribution bookkeeping}}))
        options {:cwd directory :home (str directory "/home")
                 :data-dir (str directory "/data") :complete-fn provider
                 :settings {:providers
                            {:fixture {:type :profile-alias :provider :openai
                                       :models [{:id "accounting" :context-window 100000
                                                 :thinking-levels [:none]}]}}}}
        config {:provider :fixture :model "accounting" :thinking :none :tools []
                :settings {:compaction-keep-entries 1}}
        sid (atom nil)]
    (try
      (doseq [reopened? [false true]]
        (let [rt (runtime/open! options)]
          (try
            (when-not reopened?
              (reset! sid (:id (create-test-session! rt {:config config})))
              (runtime/run! rt @sid "First short prompt")
              (runtime/run! rt @sid "Second short prompt"))
            (runtime/run! rt @sid "Another short prompt")
            (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt @sid)))
            (finally (runtime/close! rt)))))
      (is (= 4 (count @requests)))
      (is (not-any? summary-request? @requests))
      ;; Accounting must not strip persisted metadata from actual provider requests.
      (is (some #(= bookkeeping (get-in % [:message/provider-data :fixture
                                           :responses/event :response]))
                (:request/messages (last @requests))))
      (finally (fixtures/remove-directory! directory)))))

(deftest cached-context-triggers-compaction-once-and-old-usage-expires
  (let [directory (fixtures/temp-directory)
        requests (atom [])
        completions (atom 0)
        provider (fn [request _]
                   (swap! requests conj request)
                   (if (summary-request? request)
                     (assoc (answer :fixture "accounting" "Short summary")
                            :response/usage {:usage/total-tokens 95000})
                     (cond-> (answer :fixture "accounting" "Ordinary reply")
                       (= 2 (swap! completions inc))
                       (assoc :response/usage {:usage/input-tokens 1000
                                               :usage/cached-input-tokens 83000
                                               :usage/cache-write-tokens 500
                                               :usage/output-tokens 500}))))
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data") :complete-fn provider
                           :settings {:providers
                                      {:fixture {:type :profile-alias :provider :openai
                                                 :models [{:id "accounting" :context-window 100000
                                                           :thinking-levels [:none]}]}}}})
        sid (:id (create-test-session! rt
                   {:config {:provider :fixture :model "accounting" :thinking :none
                             :tools [] :settings {:compaction-keep-entries 1}}}))]
    (try
      (runtime/run! rt sid "First prompt")
      (runtime/run! rt sid "Second prompt")
      (runtime/run! rt sid "Continue after compaction")
      (is (= [:completion :completion :summary :completion]
             (mapv #(if (summary-request? %) :summary :completion) @requests)))
      (is (= 1 (count (filter #(= :compaction (:kind %)) (runtime/entries rt sid)))))
      (finally
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest unmeasured-content-never-triggers-guessed-compaction
  (let [directory (fixtures/temp-directory)
        requests (atom [])
        provider (fn [request _]
                   (swap! requests conj request)
                   (answer :fixture "tiny" "Reply without usage"))
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data") :complete-fn provider
                           :settings {:providers
                                      {:fixture {:type :profile-alias :provider :openai
                                                 :models [{:id "tiny" :context-window 100
                                                           :thinking-levels [:none]}]}}}})
        sid (:id (create-test-session! rt
                   {:config {:provider :fixture :model "tiny" :thinking :none
                             :tools [] :settings {:compaction-keep-entries 1}}}))]
    (try
      (runtime/run! rt sid "First prompt")
      (runtime/run! rt sid (apply str (repeat 10000 "New, unmeasured input. ")))
      (is (= 2 (count @requests)))
      (is (not-any? summary-request? @requests))
      (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid)))
      (finally
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest ordinary-completed-turns-compact-before-the-next-request
  (let [directory (fixtures/temp-directory)
        requests (atom [])
        provider (fn [request _]
                   (swap! requests conj request)
                   (if (summary-request? request)
                     (answer :fixture "tiny" "The first completed turn was summarized.")
                     (assoc (answer :fixture "tiny" "Ordinary final answer.")
                            :response/usage {:usage/total-tokens 60})))
        settings {:providers
                  {:fixture {:type :profile-alias
                             :provider :openai
                             :models [{:id "tiny" :context-window 100
                                       :thinking-levels [:none]}]}}}
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :settings settings :complete-fn provider})
        config {:provider :fixture :model "tiny" :thinking :none :tools []
                :instructions ""
                :settings {:compaction-threshold 0.5
                           :compaction-keep-entries 1}}
        sid (:id (create-test-session! rt {:config config}))]
    (try
      (runtime/run! rt sid (apply str (repeat 240 "a")) {})
      (runtime/run! rt sid "Second ordinary prompt" {})
      (let [kinds (mapv #(if (summary-request? %) :summary :completion) @requests)
            second-request (last @requests)
            contents (mapv :message/content (:request/messages second-request))]
        (is (= [:completion :summary :completion] kinds))
        (is (some #(= :compaction (:kind %)) (runtime/entries rt sid)))
        (is (some #(and (string? %)
                        (str/starts-with? % "Conversation summary:\n"))
                  contents)))
      (finally
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest post-turn-compaction-does-not-reopen-input-admission
  (let [directory (fixtures/temp-directory)
        runtime* (atom nil)
        session-id* (atom nil)
        calls (atom 0)
        rejected (atom nil)
        provider (fn [_ _]
                   (case (swap! calls inc)
                     1 (answer :fixture "large" "First answer")
                     2 (assoc (answer :fixture "large" "Second answer")
                              :response/usage {:usage/input-tokens 60000})
                     3 (do
                         (reset! rejected
                                 (try
                                   (runtime/follow-up! @runtime* @session-id*
                                                       "Must not be stranded")
                                   (catch clojure.lang.ExceptionInfo error
                                     (ex-data error))))
                         (answer :fixture "large" "Durable summary"))))
        settings {:providers
                  {:fixture {:type :profile-alias
                             :provider :openai
                             :models [{:id "large" :context-window 100000
                                       :thinking-levels [:none]}]}}}
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :settings settings :complete-fn provider})
        config {:provider :fixture :model "large" :thinking :none :tools []
                :instructions ""
                :settings {:compaction-threshold 0.5
                           :compaction-keep-entries 1}}
        sid (:id (create-test-session! rt {:config config}))]
    (try
      (reset! runtime* rt)
      (reset! session-id* sid)
      (runtime/run! rt sid "First prompt")
      (runtime/run! rt sid "Second prompt")
      (is (= 3 @calls))
      (is (= "operation-not-active" (:error/code @rejected)))
      (is (some #(= :compaction (:kind %)) (runtime/entries rt sid)))
      (is (empty? (runtime/pending rt sid)))
      (finally
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest terminal-wait-timeout-remains-nonterminal-until-foreground-release
  (let [directory (fixtures/temp-directory)
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :complete-fn (fn [_ _] (answer "Done"))})
        sid (:id (create-test-session! rt {:config fixtures/config}))
        release-var (ns-resolve 'arrodes.runtime 'release-foreground!)
        release-foreground @release-var
        entered (promise)
        release (promise)]
    (try
      (with-redefs-fn
        {release-var
         (fn [runtime session-id operation-id]
           (deliver entered operation-id)
           @release
           (release-foreground runtime session-id operation-id))}
        (fn []
          (let [first-operation (runtime/start! rt sid "First operation")]
            (when (= ::timeout (deref entered 10000 ::timeout))
              (throw (ex-info "Operation did not reach foreground release" {})))
            (is (= :running
                   (:status (runtime/wait! rt (:id first-operation) 0))))
            (let [waiting (promise)
                  next-operation
                  (future
                    (deliver waiting true)
                    (runtime/wait! rt (:id first-operation) 10000)
                    (runtime/start! rt sid "Second operation"))]
              @waiting
              (try
                (is (= ::blocked (deref next-operation 100 ::blocked)))
                (finally
                  (deliver release true)))
              (let [second-operation (deref next-operation 10000 ::timeout)]
                (is (map? second-operation))
                (is (= :completed
                       (:status (runtime/wait! rt (:id second-operation) 10000)))))))))
      (finally
        (deliver release true)
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest terminal-publication-observes-released-foreground-snapshot
  (let [directory (fixtures/temp-directory)
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :complete-fn (fn [_ _] (answer "Done"))})
        sid (:id (create-test-session! rt {:config fixtures/config}))
        observed (promise)
        unsubscribe
        (runtime/subscribe!
         rt
         (fn [event]
           (when (and (= sid (:session-id event))
                      (= :operation/completed (:type event)))
             (deliver observed (runtime/session-view rt sid)))))]
    (try
      (runtime/run! rt sid "Settle coherently")
      (let [view (deref observed 10000 ::timeout)]
        (is (map? view))
        (is (= :idle (get-in view [:state :phase])))
        (is (nil? (get-in view [:state :operation-id])))
        (is (nil? (get-in view [:state :operation])))
        (is (= :idle (get-in view [:state :session :status]))))
      (finally
        (unsubscribe)
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest session-view-reports-cancelling-operation-until-worker-exits
  (let [directory (fixtures/temp-directory)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        provider (fn [_ _]
                   (.countDown entered)
                   (await-uninterruptibly! release)
                   (answer "Released"))
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :complete-fn provider})
        sid (:id (create-test-session! rt {:config fixtures/config}))]
    (try
      (let [operation (runtime/start! rt sid "Wait for cancellation")]
        (await-latch! entered)
        (runtime/cancel-operation! rt (:id operation))
        (let [view (runtime/session-view rt sid)]
          (is (= :provider (get-in view [:state :phase])))
          (is (= (:id operation) (get-in view [:state :operation-id])))
          (is (= :cancelling (get-in view [:state :operation :status])))
          (is (= (runtime/operation rt (:id operation))
                 (get-in view [:state :operation]))))
        (.countDown release)
        (is (= :cancelled (:status (runtime/wait! rt (:id operation) 10000)))))
      (finally
        (.countDown release)
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))

(deftest failed-mcp-cleanup-retains-runtime-store-ownership-until-retry
  (let [directory (fixtures/temp-directory)
        options {:cwd directory :home (str directory "/home")
                 :data-dir (str directory "/data")}
        rt (runtime/open! options)
        sid (:id (create-test-session! rt {:config fixtures/config}))
        fail-once? (atom true)
        close-pool mcp/close!]
    (try
      (runtime/registry rt sid)
      (with-redefs [mcp/close!
                    (fn [pool]
                      (if (compare-and-set! fail-once? true false)
                        {:closed? true :cleanup-complete? false
                         :errors [{:server "fixture" :message "Scope remains alive"}]}
                        (close-pool pool)))]
        (is (= :closing (:status (runtime/close! rt))))
        (let [contender (try {:runtime (runtime/open! options)}
                             (catch clojure.lang.ExceptionInfo error
                               {:code (:error/code (ex-data error))}))]
          (when-let [opened (:runtime contender)] (runtime/close! opened))
          (is (= "store-in-use" (:code contender))))
        (is (= :closed (:status (runtime/close! rt)))))
      (finally
        (runtime/close! rt)
        (fixtures/remove-directory! directory)))))
