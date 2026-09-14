(ns arrodes.store-lifecycle-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.store :as store]
            [arrodes.run :as run]
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

(deftest native-reference-shaped-values-survive-copy-and-transfer
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          prefix (store/commit! database sid
                                {:entries [(message-entry :user "Keep native references literal")]})
          prefix-id (:id (first (:entries prefix)))
          artifact (artifacts/put! database sid "unreferenced" {:name "collision"})
          unused-result (artifacts/put-result! database sid
                                                {:kind :inline :content "unused" :details {}
                                                 :value :unused})
          native-value {:result {:id (:id unused-result)}
                        :message/result {:id (:id unused-result)}
                        :artifact-id (:id artifact)
                        :artifact artifact}
          retained (artifacts/put-result! database sid
                                          {:kind :inline :content "native" :details {}
                                           :value native-value})
          committed (store/commit!
                     database sid
                     {:entries [{:kind :custom
                                 :data {:type :invocation
                                        :arguments {:entry-id prefix-id
                                                    :from-id prefix-id}
                                        :result {:id "native-call"
                                                 :content "native"
                                                 :details {}
                                                 :error? false
                                                 :result retained}}}]})
          leaf (:id (first (:entries committed)))
          cloned (store/clone! database sid {:name "Native clone"})
          forked (store/fork! database sid {:entry-id leaf :name "Native fork"})
          imported (store/import-session! database
                                          (store/export-session database (:id cloned))
                                          {:name "Native import"})]
      (doseq [copied [cloned forked imported]]
        (let [copied-id (:id copied)
              entry (first (filter #(= :custom (:kind %))
                                   (store/entries database copied-id)))
              descriptor (get-in entry [:data :result :result])]
          (is (= {:entry-id prefix-id :from-id prefix-id}
                 (get-in entry [:data :arguments])))
          (is (= native-value
                 (:value (artifacts/result database copied-id (:id descriptor)))))
          (is (= 1 (count (artifacts/results database copied-id))))
          (is (empty? (artifacts/list-artifacts database copied-id))))))))

(deftest canonical-result-artifacts-still-copy-with-fresh-identities
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          artifact (artifacts/put! database sid "retained" {:name "canonical"})
          retained (artifacts/put-result! database sid
                                          {:kind :artifact
                                           :artifact-id (:id artifact)
                                           :content "retained"
                                           :details {:artifact artifact}})
          _ (store/commit! database sid
                           {:entries [{:kind :evaluation
                                      :data {:source "large-value"
                                             :result {:id "artifact-call"
                                                      :content "retained"
                                                      :details {:artifact artifact}
                                                      :error? false
                                                      :result retained}}}]})
          cloned (store/clone! database sid {:name "Artifact clone"})
          clone-id (:id cloned)
          entry (last (store/entries database clone-id))
          descriptor (get-in entry [:data :result :result])
          copied-artifact (get-in descriptor [:details :artifact])
          copied-wrapper-artifact (get-in entry [:data :result :details :artifact])]
      (is (not= (:id artifact) (:artifact-id descriptor)))
      (is (= (:artifact-id descriptor) (:id copied-artifact)))
      (is (= clone-id (:session-id copied-artifact)))
      (is (= copied-artifact copied-wrapper-artifact))
      (is (= "retained"
             (:content (artifacts/read! database clone-id (:id copied-artifact) {})))))))

(deftest imported-active-tool-call-is-closed-without-replay
  (with-memory-store [database]
    (let [sid (:id (new-session database))]
      (store/commit!
       database sid
       {:session {:status :running}
        :entries [(message-entry :user "Perform an effect")
                  {:kind :message
                   :data {:message/role :assistant
                          :message/content ""
                          :message/tool-calls
                          [{:tool-call/id "pending-import"
                            :tool-call/name "write"
                            :tool-call/arguments {:path "effect.txt" :content "once"}}]}}]})
      (let [imported (store/import-session! database (store/export-session database sid)
                                            {:name "Recovered import"})
            imported-id (:id imported)
            messages (store/context-messages database imported-id)
            boundary (last messages)]
        (is (= :idle (:status imported)))
        (is (= [:user :assistant :tool] (mapv :message/role messages)))
        (is (= "pending-import" (:message/tool-call-id boundary)))
        (is (str/includes? (:message/content boundary) "not replayed"))
        (is (provider-valid-history? messages))
        (store/commit! database imported-id
                       {:entries [(message-entry :user "Continue safely")
                                  (message-entry :assistant "Continued")]})
        (is (provider-valid-history? (store/context-messages database imported-id)))))))

(deftest copy-omits-excluded-labels-without-changing-compaction-context
  (with-memory-store [database]
    (testing "the next retained entry becomes the compaction cutoff"
      (let [sid (:id (new-session database))
            committed (store/commit! database sid
                                     {:entries [(message-entry :user "Shared")
                                                (message-entry :assistant "Abandoned")]})
            shared-id (:id (first (:entries committed)))
            abandoned-id (:id (second (:entries committed)))]
        (store/branch! database sid shared-id {})
        (let [labelled (store/commit!
                        database sid
                        {:entries [{:kind :label
                                    :data {:entry-id abandoned-id :label "Excluded"}}]
                         :session {:labels [{:entry-id abandoned-id :label "Excluded"}]}})
              label-id (:id (first (:entries labelled)))
              _ (store/commit! database sid
                               {:entries [(message-entry :user "Retained after label")]})]
          (store/commit! database sid
                         {:entries [{:kind :compaction
                                     :data {:summary "Earlier work"
                                            :first-kept-entry-id label-id}}]})
          (let [source-context (mapv run/provider-message (store/context-messages database sid))
                cloned (store/clone! database sid {:name "Compacted unlabeled clone"})
                clone-id (:id cloned)
                clone-entries (store/entries database clone-id)
                copied-retained-id (:id (first (filter #(= "Retained after label"
                                                           (get-in % [:data :message/content]))
                                                      clone-entries)))
                copied-cutoff (get-in (first (filter #(= :compaction (:kind %))
                                                     clone-entries))
                                      [:data :first-kept-entry-id])
                imported (store/import-session! database (store/export-session database clone-id)
                                                {:name "Compacted unlabeled import"})]
            (is (= copied-retained-id copied-cutoff))
            (is (empty? (:labels (store/session database clone-id))))
            (is (empty? (filter #(= :label (:kind %)) clone-entries)))
            (is (= source-context (mapv run/provider-message (store/context-messages database clone-id))))
            (is (= source-context (mapv run/provider-message (store/context-messages database (:id imported)))))))))
    (testing "an empty retained range remains empty"
      (let [sid (:id (new-session database))
            committed (store/commit! database sid
                                     {:entries [(message-entry :user "Shared empty")
                                                (message-entry :assistant "Abandoned empty")]})
            shared-id (:id (first (:entries committed)))
            abandoned-id (:id (second (:entries committed)))]
        (store/branch! database sid shared-id {})
        (let [labelled (store/commit! database sid
                                      {:entries [{:kind :label
                                                  :data {:entry-id abandoned-id
                                                         :label "Excluded empty"}}]})
              label-id (:id (first (:entries labelled)))]
          (store/commit! database sid
                         {:entries [{:kind :compaction
                                     :data {:summary "Everything earlier"
                                            :first-kept-entry-id label-id}}]})
          (let [source-context (mapv run/provider-message (store/context-messages database sid))
                cloned (store/clone! database sid {:name "Empty cutoff clone"})
                clone-id (:id cloned)
                compaction (first (filter #(= :compaction (:kind %))
                                          (store/entries database clone-id)))
                imported (store/import-session! database (store/export-session database clone-id)
                                                {:name "Empty cutoff import"})]
            (is (not (contains? (:data compaction) :first-kept-entry-id)))
            (is (= source-context (mapv run/provider-message (store/context-messages database clone-id))))
            (is (= source-context (mapv run/provider-message (store/context-messages database (:id imported)))))))))))

(deftest credential-shaped-fields-inside-sets-are-rejected
  (with-memory-store [database]
    (let [error (try
                  (store/create-session!
                   database
                   {:cwd (System/getProperty "java.io.tmpdir")
                    :name "Secret set"
                    :config (assoc-in config [:settings :profiles]
                                      #{{:api-key "must-not-persist"}})})
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= "secret-config" (:error/code (ex-data error))))
      (is (empty? (store/list-sessions database {}))))))
