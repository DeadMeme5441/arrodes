(ns arrodes.titles-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as sessions]
            [arrodes.run :as run]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.util.concurrent ExecutorService Future TimeUnit)))

(defn- title-request? [request]
  (str/starts-with? (str (:message/content (first (:request/messages request))))
                    "Write a concise session title"))

(defn- name-of [rt sid] (:name (runtime/session rt sid)))

(defn- settle-titles! [rt]
  (.get ^Future (.submit ^ExecutorService (get-in rt [:titles :executor])
                        ^Runnable (fn [] nil)) 10 TimeUnit/SECONDS))

(deftest local-titles-are-short-safe-and-unicode-aware
  (is (= "Fix the streaming layout" (run/suggested-session-name "\n Fix  the streaming layout\nMore detail")))
  (is (= "Fix layout" (run/suggested-session-name "\u001b[31mFix layout\u001b[0m")))
  (let [title (run/suggested-session-name (apply str (repeat 100 "😀")))]
    (is (= 72 (.codePointCount ^String title 0 (count title)))))
  (is (= "Fix attachments" (run/suggested-session-name [{:part/type :text :text "Fix attachments"}
                                                       {:part/type :image :image/data "not-a-title"}]))))

(deftest generated-title-does-not-block-the-main-answer-or-inflate-context-usage
  (let [entered (promise) release (promise) requests (atom [])
        provider (fn [request _]
                   (swap! requests conj request)
                   (if (title-request? request)
                     (do (deliver entered request) @release
                         (assoc (fixtures/answer "Streaming layout fixes")
                                :response/usage {:usage/total-tokens 90000}))
                     (assoc (fixtures/answer "Main work done") :response/usage {:usage/total-tokens 100})))]
    (fixtures/with-runtime [rt provider]
      (try
        (let [sid (:id (runtime/create-session! rt {:config (assoc-in sessions/config [:settings :title-model] "title-fixture")}))
              result (runtime/run! rt sid "Fix the streaming layout")]
          (is (= "Main work done" (get-in result [:message/content 0 :text])))
          (is (= "Fix the streaming layout" (name-of rt sid)))
          (is (= "title-fixture" (:request/model (deref entered 10000 nil))))
          (is (nil? (:request/tools @entered)))
          (deliver release true)
          (settle-titles! rt)
          (is (= "Streaming layout fixes" (name-of rt sid)))
          (is (= 100 (run/context-tokens (run/latest-usage (runtime/active-path rt sid)))))
          (runtime/run! rt sid "Continue")
          (settle-titles! rt)
          (is (= 1 (count (filter title-request? @requests))))
          (runtime/reload! rt sid)
          (is (= "Streaming layout fixes" (name-of rt sid))))
        (finally (deliver release true))))))

(deftest manual-name-wins-over-a-delayed-title-model
  (let [entered (promise) release (promise)
        provider (fn [request _]
                   (if (title-request? request)
                     (do (deliver entered true) @release (fixtures/answer "Late generated title"))
                     (fixtures/answer "Done")))]
    (fixtures/with-runtime [rt provider]
      (try
        (let [sid (:id (runtime/create-session! rt {:config sessions/config}))]
          (runtime/run! rt sid "Name this conversation")
          (is (= true (deref entered 10000 nil)))
          (runtime/configure! rt sid {:name "Untitled session"})
          (deliver release true)
          (settle-titles! rt)
          (is (= "Untitled session" (name-of rt sid)))
          (is (= :user (get-in (runtime/session rt sid) [:metadata :title/source]))))
        (finally (deliver release true))))))

(deftest explicit-names-and-disabled-titles-do-not-call-a-title-model
  (let [requests (atom [])]
    (fixtures/with-runtime [rt (fn [request _] (swap! requests conj request) (fixtures/answer "Done"))]
      (doseq [opts [{:name "Untitled session" :config sessions/config}
                    {:config (assoc-in sessions/config [:settings :auto-title?] false)}]]
        (let [sid (:id (runtime/create-session! rt opts))]
          (runtime/run! rt sid "A proposed title")
          (settle-titles! rt)
          (is (= "Untitled session" (name-of rt sid)))))
      (is (not-any? title-request? @requests)))))

(deftest title-failure-keeps-the-local-fallback
  (fixtures/with-runtime [rt (fn [request _]
                             (if (title-request? request) (throw (ex-info "Title model unavailable" {}))
                                 (fixtures/answer "Main answer")))]
    (let [sid (:id (runtime/create-session! rt {:config sessions/config}))]
      (runtime/run! rt sid "Repair the REPL")
      (settle-titles! rt)
      (is (= "Repair the REPL" (name-of rt sid)))
      (is (= :idle (:status (runtime/session rt sid)))))))

(deftest runtime-shutdown-cancels-its-title-worker
  (let [entered (promise) interrupted (promise)
        provider (fn [request _]
                   (if (title-request? request)
                     (do (deliver entered true)
                         (try (Thread/sleep 30000)
                              (catch InterruptedException error (deliver interrupted true) (throw error)))
                         (fixtures/answer "Too late"))
                     (fixtures/answer "Main work completed")))]
    (fixtures/with-runtime [rt provider]
      (let [sid (:id (runtime/create-session! rt {:config sessions/config}))]
        (runtime/run! rt sid "A useful first message")
        (is (= true (deref entered 10000 nil)))
        (is (= :closed (:status (runtime/close! rt))))
        (is (= true (deref interrupted 1000 nil)))))))
