(ns arrodes.runtime-lifecycle-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.session-test :as fixtures]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

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
        sid (:id (runtime/create-session! rt {:config fixtures/config}))
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
        extension (io/file directory ".arrodes-mono" "extensions" "blocking_close.clj")
        entered (promise)
        release (promise)]
    (io/make-parents extension)
    (spit extension
          "(fn [{:keys [on-close!]}]\n  (on-close! arrodes.runtime-lifecycle-test/block-close!))\n")
    (let [rt (runtime/open! {:cwd directory :home (str directory "/home")
                             :data-dir (str directory "/data") :trust true
                             :complete-fn (fn [_ _] (answer "Started after reload"))})
          sid (:id (runtime/create-session! rt {:config fixtures/config}))]
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

(deftest ordinary-completed-turns-compact-before-the-next-request
  (let [directory (fixtures/temp-directory)
        requests (atom [])
        provider (fn [request _]
                   (swap! requests conj request)
                   (if (summary-request? request)
                     (answer :fixture "tiny" "The first completed turn was summarized.")
                     (answer :fixture "tiny" "Ordinary final answer.")))
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
        sid (:id (runtime/create-session! rt {:config config}))]
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
