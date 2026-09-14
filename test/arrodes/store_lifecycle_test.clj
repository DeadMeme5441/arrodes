(ns arrodes.store-lifecycle-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.store :as store]
            [arrodes.platform :as util]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(def config
  {:provider :openai
   :model "gpt-4o-mini"
   :thinking :medium
   :tools :all
   :instructions "Store lifecycle test"
   :settings {}})

(defn- temp-directory []
  (str (Files/createTempDirectory "arrodes-store-lifecycle-"
                                  (make-array FileAttribute 0))))

(defn- remove-directory! [directory]
  (with-open [walk (Files/walk (util/path directory)
                               (make-array java.nio.file.FileVisitOption 0))]
    (doseq [file (sort-by #(.getNameCount ^Path %) >
                          (iterator-seq (.iterator walk)))]
      (Files/deleteIfExists file))))

(defmacro with-memory-store [[binding] & body]
  `(let [~binding (store/open! {:memory? true})]
     (try
       ~@body
       (finally (store/close! ~binding)))))

(defn- new-session [database]
  (store/create-session! database
                         {:cwd (System/getProperty "java.io.tmpdir")
                          :name "Store lifecycle"
                          :config config}))

(defn- message-entry [role content]
  {:kind :message
   :data {:message/role role :message/content content}})

(defn- provider-valid-history? [messages]
  (loop [remaining messages, pending []]
    (if-let [message (first remaining)]
      (case (:message/role message)
        :assistant
        (and (empty? pending)
             (recur (next remaining)
                    (mapv :tool-call/id (:message/tool-calls message))))

        :tool
        (let [call-id (:message/tool-call-id message)]
          (and (some #{call-id} pending)
               (recur (next remaining) (vec (remove #{call-id} pending)))))

        (and (empty? pending) (recur (next remaining) pending)))
      (empty? pending))))

(deftest contested-file-store-open-does-not-touch-live-owner-state
  (let [directory (temp-directory)
        options {:path (str directory "/sessions.sqlite")
                 :artifact-dir (str directory "/artifacts")}
        first-store (store/open! options)]
    (try
      (let [sid (:id (new-session first-store))
            _ (store/commit! first-store sid
                             {:entries [(message-entry :user "Keep the live owner intact")]})
            live (artifacts/put-result! first-store sid
                                        {:kind :live
                                         :content "Live native value"
                                         :details {:type "object"}})
            attempt (try
                      {:store (store/open! options)}
                      (catch Throwable error {:error error}))]
        (when-let [contender (:store attempt)]
          (store/close! contender))
        (is (instance? clojure.lang.ExceptionInfo (:error attempt)))
        (is (= "store-in-use" (:error/code (ex-data (:error attempt)))))
        (is (= ["Keep the live owner intact"]
               (mapv :message/content (store/context-messages first-store sid))))
        (is (true? (:available? (artifacts/result first-store sid (:id live)))))
        (is (Files/isRegularFile (util/path (str (:path first-store) ".lock"))
                                 (make-array java.nio.file.LinkOption 0))))
      (finally
        (store/close! first-store)))
    (try
      (let [reopened (store/open! options)]
        (try
          (is (= 1 (count (store/list-sessions reopened {}))))
          (finally (store/close! reopened))))
      (finally (remove-directory! directory)))))

(deftest clone-omits-external-branch-summary-source-and-remains-portable
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          original (store/commit! database sid
                                  {:entries [(message-entry :user "Shared prefix")
                                             (message-entry :assistant "Abandoned answer")]})
          prefix-id (:id (first (:entries original)))
          abandoned-id (:id (second (:entries original)))]
      (store/branch! database sid prefix-id {})
      (store/commit! database sid
                     {:entries [{:kind :branch-summary
                                 :data {:summary "The abandoned answer chose another route."
                                        :from-id abandoned-id}}]})
      (let [cloned (store/clone! database sid {:name "Portable clone"})
            clone-id (:id cloned)
            summary (first (filter #(= :branch-summary (:kind %))
                                   (store/entries database clone-id)))
            packet (store/export-session database clone-id)
            imported (store/import-session! database packet {:name "Imported clone"})]
        (is (= "The abandoned answer chose another route."
               (get-in summary [:data :summary])))
        (is (not (contains? (:data summary) :from-id)))
        (is (= (store/context-messages database clone-id)
               (store/context-messages database (:id imported))))))))

(deftest selected-and-copied-mid-tool-prefixes-end-at-explicit-valid-boundaries
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          committed
          (store/commit!
           database sid
           {:entries
            [(message-entry :user "Perform both actions")
             {:kind :message
              :data {:message/role :assistant
                     :message/content ""
                     :message/tool-calls
                     [{:tool-call/id "call-one"
                       :tool-call/name "write"
                       :tool-call/arguments {:path "one.txt" :content "one"}}
                      {:tool-call/id "call-two"
                       :tool-call/name "write"
                       :tool-call/arguments {:path "two.txt" :content "two"}}]}}
             {:kind :message
              :data {:message/role :tool
                     :message/tool-call-id "call-one"
                     :message/name "write"
                     :message/content "First action completed"}}]})
          partial-leaf (:id (last (:entries committed)))
          cloned (store/clone! database sid {:name "Mid-tool clone"})
          forked (store/fork! database sid {:entry-id partial-leaf :name "Mid-tool fork"})
          _ (store/branch! database sid partial-leaf {})
          selected-session-ids [sid (:id forked) (:id cloned)]]
      (doseq [selected-id selected-session-ids]
        (testing (str "provider-valid branch boundary for " selected-id)
          (let [messages (store/context-messages database selected-id)
                assistant (second messages)
                tool-messages (filterv #(= :tool (:message/role %)) messages)
                boundary (last tool-messages)]
            (is (= ["call-one" "call-two"]
                   (mapv :tool-call/id (:message/tool-calls assistant))))
            (is (= ["call-one" "call-two"]
                   (mapv :message/tool-call-id tool-messages)))
            (is (str/includes? (:message/content boundary)
                               "Selecting history did not undo external effects"))
            (is (str/includes? (:message/content boundary) "not replayed"))
            (is (provider-valid-history? messages)))
          (store/commit! database selected-id
                         {:entries [(message-entry :user "Continue after the boundary")
                                    (message-entry :assistant "Continuation is valid")]})
          (is (provider-valid-history?
               (store/context-messages database selected-id))))))))
