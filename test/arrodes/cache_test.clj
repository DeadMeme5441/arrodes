(ns arrodes.cache-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as sessions]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest ordinary-continuation-preserves-the-cacheable-request-prefix
  (let [requests (atom [])
        complete (fn [request _]
                   (swap! requests conj request)
                   (fixtures/answer "Recorded the request"))]
    (fixtures/with-runtime [rt complete]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "Read this as the beginning of an ongoing conversation." {})
        (runtime/run! rt sid "Continue the same conversation." {})
        (let [[first-request second-request] @requests
              prefix (:request/messages first-request)
              continued (:request/messages second-request)]
          (is (= prefix (subvec continued 0 (count prefix))))
          (is (= (:request/tools first-request) (:request/tools second-request)))
          (is (true? (get-in first-request [:request/cache :enabled?])))
          (is (string? (get-in first-request [:request/cache :scope-id])))
          (is (= (get-in first-request [:request/cache :scope-id])
                 (get-in second-request [:request/cache :scope-id]))))))))

(deftest reload-and-restart-preserve-context-prefix-with-an-honest-reset-notice
  (let [directory (sessions/temp-directory)
        requests (atom [])
        options {:cwd directory :home (str directory "/home")
                 :data-dir (str directory "/data") :trust false
                 :complete-fn (fn [request _]
                                (swap! requests conj request)
                                (fixtures/answer "Recorded"))}
        rt (runtime/open! options)
        sid (:id (fixtures/create-session rt))]
    (try
      (runtime/evaluate! rt sid "(def retained 42)")
      (runtime/run! rt sid "Remember the result")
      (let [initial (first @requests)]
        (runtime/reload! rt sid)
        (runtime/run! rt sid "Continue after reload")
        (let [reloaded (second @requests)
              prefix (:request/messages initial)]
          (is (= prefix (subvec (:request/messages reloaded) 0 (count prefix))))
          (is (nil? (:value (runtime/evaluate! rt sid "(resolve 'retained)"))))
          (is (= 1 (count (filter #(get-in % [:data :message/repl-generation])
                                 (runtime/entries rt sid)))))
          (runtime/run! rt sid "Continue without another reset")
          (is (= 1 (count (filter #(get-in % [:data :message/repl-generation])
                                 (runtime/entries rt sid)))))
          (runtime/close! rt)
          (let [reopened (runtime/open! options)]
            (try
              (runtime/continue! reopened sid)
              (let [continued (last @requests)
                    previous (:request/messages (nth @requests 2))]
                (is (= previous
                       (subvec (:request/messages continued) 0 (count previous))))
                (is (= (:request/cache initial) (:request/cache continued)))
                (is (= 2 (count (filter #(get-in % [:data :message/repl-generation])
                                       (runtime/entries reopened sid)))))
                (is (some #(and (= :user (:message/role %))
                                (str/includes? (str (:message/content %))
                                               "live JVM objects are no longer available"))
                          (drop (count previous) (:request/messages continued)))))
              (finally (runtime/close! reopened))))))
      (finally
        (runtime/close! rt)
        (sessions/remove-directory! directory)))))

(deftest summaries-do-not-reuse-the-foreground-provider-cache-scope
  (let [requests (atom [])
        complete (fn [request _]
                   (swap! requests conj request)
                   (assoc (fixtures/answer "Summary or response")
                          :response/usage {:usage/input-tokens 20 :usage/output-tokens 3}
                          :response/cost {:cost/usd 0.01 :cost/estimated? false}))]
    (fixtures/with-runtime [rt complete]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "Earlier task")
        (runtime/run! rt sid "Current task")
        (let [generation (:generation (runtime/registry rt sid))]
          (runtime/compact! rt sid {:config {:settings {:compaction-keep-entries 1}}})
          (is (= generation (:generation (runtime/registry rt sid))))
          (let [scopes (mapv #(get-in % [:request/cache :scope-id]) @requests)
                summary (last (filter #(= :compaction (:kind %)) (runtime/entries rt sid)))]
            (is (= [sid sid (str sid ":compaction")] scopes))
            (is (= {:cost/usd 0.01 :cost/estimated? false} (get-in summary [:data :cost]))))
          (runtime/run! rt sid "Continue the compacted task")
          (is (= sid (get-in (last @requests) [:request/cache :scope-id]))))))))
