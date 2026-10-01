(ns arrodes.store-lifecycle-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.store :as store]
            [arrodes.store.sql :as store-sql]
            [arrodes.store.agents :as store-agents]
            [arrodes.store.command :as command]
            [arrodes.store.db :as store-db]
            [arrodes.store.jobs :as store-jobs]
            [arrodes.store.recovery :as store-recovery]
            [arrodes.store.transfer :as store-transfer]
            [arrodes.run :as run]
            [arrodes.platform :as util]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

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
  `(let [~binding (store-db/open! {:memory? true})]
     (try
       ~@body
       (finally (store-db/close! ~binding)))))

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
        first-store (store-db/open! options)]
    (try
      (let [sid (:id (new-session first-store))
            _ (store/commit! first-store sid
                             {::command/entries [(message-entry :user "Keep the live owner intact")]})
            live (artifacts/put-result! first-store sid
                                        {:kind :live
                                         :content "Live native value"
                                         :details {:type "object"}})
            attempt (try
                      {:store (store-db/open! options)}
                      (catch Throwable error {:error error}))]
        (when-let [contender (:store attempt)]
          (store-db/close! contender))
        (is (instance? clojure.lang.ExceptionInfo (:error attempt)))
        (is (= "store-in-use" (:error/code (ex-data (:error attempt)))))
        (is (= ["Keep the live owner intact"]
               (mapv :message/content (store/context-messages first-store sid))))
        (is (true? (:available? (artifacts/result first-store sid (:id live)))))
        (is (Files/isRegularFile (util/path (str (:path first-store) ".lock"))
                                 (make-array java.nio.file.LinkOption 0))))
      (finally
        (store-db/close! first-store)))
    (try
      (let [reopened (store-db/open! options)]
        (try
          (is (= 1 (count (store/list-sessions reopened {}))))
          (finally (store-db/close! reopened))))
      (finally (remove-directory! directory)))))

(deftest clone-omits-external-branch-summary-source-and-remains-portable
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          original (store/commit! database sid
                                  {::command/entries [(message-entry :user "Shared prefix")
                                             (message-entry :assistant "Abandoned answer")]})
          prefix-id (:id (first (:entries original)))
          abandoned-id (:id (second (:entries original)))]
      (store/branch! database sid prefix-id {})
      (store/commit! database sid
                     {::command/entries [{:kind :branch-summary
                                 :data {:summary "The abandoned answer chose another route."
                                        :from-id abandoned-id}}]})
      (let [cloned (store-transfer/clone! database sid {:name "Portable clone"})
            clone-id (:id cloned)
            summary (first (filter #(= :branch-summary (:kind %))
                                   (store/entries database clone-id)))
            packet (store-transfer/export-session database clone-id)
            imported (store-transfer/import-session! database packet {:name "Imported clone"})]
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
           {::command/entries
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
          cloned (store-transfer/clone! database sid {:name "Mid-tool clone"})
          forked (store-transfer/fork! database sid {:entry-id partial-leaf :name "Mid-tool fork"})
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
                         {::command/entries [(message-entry :user "Continue after the boundary")
                                    (message-entry :assistant "Continuation is valid")]})
          (is (provider-valid-history?
               (store/context-messages database selected-id))))))))

(deftest native-reference-shaped-values-survive-copy-and-transfer
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          prefix (store/commit! database sid
                                {::command/entries [(message-entry :user "Keep native references literal")]})
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
                     {::command/entries [{:kind :custom
                                 :data {:type :invocation
                                        :arguments {:entry-id prefix-id
                                                    :from-id prefix-id}
                                        :result {:id "native-call"
                                                 :content "native"
                                                 :details {}
                                                 :error? false
                                                 :result retained}}}]})
          leaf (:id (first (:entries committed)))
          cloned (store-transfer/clone! database sid {:name "Native clone"})
          forked (store-transfer/fork! database sid {:entry-id leaf :name "Native fork"})
          imported (store-transfer/import-session! database
                                          (store-transfer/export-session database (:id cloned))
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
                           {::command/entries [{:kind :evaluation
                                      :data {:source "large-value"
                                             :result {:id "artifact-call"
                                                      :content "retained"
                                                      :details {:artifact artifact}
                                                      :error? false
                                                      :result retained}}}]})
          cloned (store-transfer/clone! database sid {:name "Artifact clone"})
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
       {::command/session {:status :running}
        ::command/entries [(message-entry :user "Perform an effect")
                  {:kind :message
                   :data {:message/role :assistant
                          :message/content ""
                          :message/tool-calls
                          [{:tool-call/id "pending-import"
                            :tool-call/name "write"
                            :tool-call/arguments {:path "effect.txt" :content "once"}}]}}]})
      (let [imported (store-transfer/import-session! database (store-transfer/export-session database sid)
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
                       {::command/entries [(message-entry :user "Continue safely")
                                  (message-entry :assistant "Continued")]})
        (is (provider-valid-history? (store/context-messages database imported-id)))))))

(deftest copy-omits-excluded-labels-without-changing-compaction-context
  (with-memory-store [database]
    (testing "the next retained entry becomes the compaction cutoff"
      (let [sid (:id (new-session database))
            committed (store/commit! database sid
                                     {::command/entries [(message-entry :user "Shared")
                                                (message-entry :assistant "Abandoned")]})
            shared-id (:id (first (:entries committed)))
            abandoned-id (:id (second (:entries committed)))]
        (store/branch! database sid shared-id {})
        (let [labelled (store/commit!
                        database sid
                        {::command/entries [{:kind :label
                                    :data {:entry-id abandoned-id :label "Excluded"}}]
                         ::command/session {:labels [{:entry-id abandoned-id :label "Excluded"}]}})
              label-id (:id (first (:entries labelled)))
              _ (store/commit! database sid
                               {::command/entries [(message-entry :user "Retained after label")]})]
          (store/commit! database sid
                         {::command/entries [{:kind :compaction
                                     :data {:summary "Earlier work"
                                            :first-kept-entry-id label-id}}]})
          (let [source-context (mapv run/provider-message (store/context-messages database sid))
                cloned (store-transfer/clone! database sid {:name "Compacted unlabeled clone"})
                clone-id (:id cloned)
                clone-entries (store/entries database clone-id)
                copied-retained-id (:id (first (filter #(= "Retained after label"
                                                           (get-in % [:data :message/content]))
                                                      clone-entries)))
                copied-cutoff (get-in (first (filter #(= :compaction (:kind %))
                                                     clone-entries))
                                      [:data :first-kept-entry-id])
                imported (store-transfer/import-session! database (store-transfer/export-session database clone-id)
                                                {:name "Compacted unlabeled import"})]
            (is (= copied-retained-id copied-cutoff))
            (is (empty? (:labels (store/session database clone-id))))
            (is (empty? (filter #(= :label (:kind %)) clone-entries)))
            (is (= source-context (mapv run/provider-message (store/context-messages database clone-id))))
            (is (= source-context (mapv run/provider-message (store/context-messages database (:id imported)))))))))
    (testing "an empty retained range remains empty"
      (let [sid (:id (new-session database))
            committed (store/commit! database sid
                                     {::command/entries [(message-entry :user "Shared empty")
                                                (message-entry :assistant "Abandoned empty")]})
            shared-id (:id (first (:entries committed)))
            abandoned-id (:id (second (:entries committed)))]
        (store/branch! database sid shared-id {})
        (let [labelled (store/commit! database sid
                                      {::command/entries [{:kind :label
                                                  :data {:entry-id abandoned-id
                                                         :label "Excluded empty"}}]})
              label-id (:id (first (:entries labelled)))]
          (store/commit! database sid
                         {::command/entries [{:kind :compaction
                                     :data {:summary "Everything earlier"
                                            :first-kept-entry-id label-id}}]})
          (let [source-context (mapv run/provider-message (store/context-messages database sid))
                cloned (store-transfer/clone! database sid {:name "Empty cutoff clone"})
                clone-id (:id cloned)
                compaction (first (filter #(= :compaction (:kind %))
                                          (store/entries database clone-id)))
                imported (store-transfer/import-session! database (store-transfer/export-session database clone-id)
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

(deftest session-list-reports-message-time-not-configuration-time
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          listed #(first (filter (fn [s] (= sid (:id s))) (store/list-sessions database)))]
      (is (nil? (:last-message-at (listed))))
      (with-redefs [util/now (constantly 1000)]
        (store/commit! database sid {::command/entries [(message-entry :user "Hello")]}))
      (with-redefs [util/now (constantly 2000)]
        (store/commit! database sid {::command/entries [(message-entry :assistant "Reply")]}))
      (with-redefs [util/now (constantly 3000)]
        (store/configure! database sid {:name "Renamed later"}))
      (is (= 2000 (:last-message-at (listed))))
      (is (= 3000 (:updated-at (listed))))
      (is (= 2000 (:last-message-at (first (store/list-sessions database {:cwd (System/getProperty "java.io.tmpdir")}))))))))

(defn- create-child [database parent name]
  (store-agents/create-agent!
   database parent {:id (util/id) :operation-id (util/id) :submission-id (util/id)
                    :name name :cwd (System/getProperty "java.io.tmpdir")
                    :config config :task "Inspect the retained evidence"
                    :context "Use native Clojure."}))

(defn- delivery-operation-id [database sid]
  (if-let [operation (some (fn [operation]
                             (when (and (contains? #{:queued :running} (:status operation))
                                        (contains? #{:run :continue} (:kind operation)))
                               operation))
                           (store/operations database {:session-id sid}))]
    (do
      (when (= :queued (:status operation))
        (store/commit! database sid {::command/operation {:id (:id operation) :status :running}}))
      (:id operation))
    (let [id (util/id)]
      (store/commit! database sid {::command/operation {:id id :kind :continue :status :running}})
      id)))

(defn- deliver! [database sid]
  (store-agents/deliver-agent-messages! database sid (delivery-operation-id database sid)))

(deftest agent-submissions-are-atomic-and-parent-completions-are-unique
  (with-memory-store [database]
    (let [root (:id (new-session database))
          opts {:id (util/id) :operation-id (util/id) :submission-id (util/id)
                :name "Reader" :cwd (System/getProperty "java.io.tmpdir")
                :config config :task "Investigate" :context "Return native findings"}
          first-launch (store-agents/create-agent! database root opts)
          child (get-in first-launch [:handle :session-id])
          op (get-in first-launch [:handle :operation-id])]
      (is (= (:handle first-launch) (store-agents/agent-submission database root (:submission-id opts))))
      (is (:existing? (store-agents/create-agent! database root
                                            (assoc opts :id (util/id) :operation-id (util/id)
                                                   :origin {:operation-id (util/id)}))))
      (is (= ["Context:\nReturn native findings\n\nInvestigate"]
             (mapv :message/content (store/context-messages database child))))
      (is (= [root child] (mapv :session-id (store-agents/agent-team database root {}))))
      (is (= [root child] (store-agents/agent-descendants database root)))
      (is (= 2 (count (store-agents/agent-team database child {:limit 2 :offset 0}))))
      (is (= 1 (count (store-agents/agent-team database child {:limit 1 :offset 1}))))
      (is (pos? (store/latest-event-seq database)))
      (is (= :queued (:status (store/operation database op))))
      (is (= "agent-submission-conflict"
             (:error/code (ex-data
                           (try (store-agents/create-agent! database root
                                                     (assoc opts :task "Different"))
                                (catch clojure.lang.ExceptionInfo e e))))))
      (let [result {:message/role :assistant
                    :message/content [{:part/type :text :text "Done"
                                       :part/provider-data {:internal "Never show this"}}]
                    :message/provider-data {:response/provider :codex
                                            :response/model "test-model"
                                            :response/usage {:input-tokens 1}
                                            :response/internal {:tools "Never show this"}}}
            canonical {:message/role :assistant
                       :message/content [{:part/type :text :text "Done"}]
                       :message/provider-data {:response/provider :codex
                                               :response/model "test-model"
                                               :response/usage {:input-tokens 1}}}
            _ (store/commit! database child {::command/operation {:id op :status :completed :result result}})
            _ (store/commit! database child {::command/operation {:id op :status :completed :result result}})
            messages (store-agents/agent-messages database root {})]
        (is (= 1 (count messages)))
        (is (= :completion (:kind (first messages))))
        (is (= op (:operation-id (store-agents/agent-result database root child op))))
        (is (= result (:result (store-agents/agent-result database root child op))))
        (is (= 1 (:pending-count (first (store-agents/agent-team database root {})))))
        (is (true? (store-agents/agent-wake? database root)))
        (let [delivery (deliver! database root)
              descriptor (get-in delivery [:entries 0 :data :message/result])]
          (is (= [(:id (first messages))] (:delivered delivery)))
          (is (= canonical (get-in descriptor [:value :result])))
          (is (str/includes? (get-in delivery [:entries 0 :data :message/content]) "Done"))
          (is (not (str/includes? (get-in delivery [:entries 0 :data :message/content])
                                  "Never show this")))
          (is (= (:id descriptor)
                 (get-in (first (store/context-messages database root)) [:message/result :id])))
          (is (empty? (:entries (deliver! database root))))
          (store/delete-session! database child)
          (is (= canonical
                 (get-in (artifacts/result database root (:id descriptor)) [:value :result])))
          (let [imported (store-transfer/import-session! database (store-transfer/export-session database root)
                                                {:name "Independent receipt"})]
            (is (= canonical
                   (get-in (artifacts/result database (:id imported) 1)
                           [:value :result])))))))))

(deftest agent-human-messages-preserve-attribution-and-pause-policy
  (with-memory-store [database]
    (let [root (:id (new-session database))
          child (:session-id (:handle (create-child database root "Recipient")))
          receipt (:receipt (store-agents/send-agent-message! database root child "Please continue"
                                                       {:kind :human :submission-id (util/id)
                                                        :wake? true}))]
      (store-agents/set-agent-paused! database child true)
      (is (false? (store-agents/agent-wake? database child)))
      (is (true? (store-agents/pending-agent-messages? database child)))
      (store-agents/set-agent-paused! database child false)
      (is (true? (store-agents/agent-wake? database child)))
      (let [entry (first (:entries (deliver! database child)))]
        (is (= :human (get-in entry [:data :message/agent :kind])))
        (is (= (:id receipt) (get-in entry [:data :message/agent :id])))
        (is (= :delivered (get-in (first (store-agents/agent-messages database root {}))
                                  [:deliveries 0 :status])))))))

(deftest stopped-agent-keeps-current-context-deliveries-pending
  (with-memory-store [database]
    (let [root (:id (new-session database))
          child (:session-id (:handle (create-child database root "Stopped recipient")))
          message-id (:id (:receipt (store-agents/send-agent-message! database root child "Retain this"
                                                              {:submission-id (util/id)})))]
      (store-agents/set-agent-stopped! database root true)
      (is (empty? (:entries (deliver! database child))))
      (is (= :pending (get-in (first (store-agents/agent-messages database child {}))
                              [:deliveries 0 :status])))
      (store-agents/set-agent-stopped! database root false)
      (let [first-delivery (deliver! database child)]
        (is (= [message-id] (:delivered first-delivery)))
        (is (= "Retain this" (get-in first-delivery [:entries 0 :data :message/content])))
        (is (empty? (:entries (deliver! database child))))))))

(deftest automatic-wake-roots-scan-is-cross-team-and-nonconsuming
  (with-memory-store [database]
    (let [root-a (:id (new-session database))
          root-b (:id (new-session database))
          child-a (:session-id (:handle (create-child database root-a "A worker")))]
      (store-agents/send-agent-message! database root-a child-a "Wake child"
                                 {:submission-id (util/id)})
      (store-agents/send-agent-message! database root-b root-b "Wake root"
                                 {:submission-id (util/id) :wake? true})
      (is (= (vec (sort [root-a root-b])) (store-agents/agent-wake-roots database)))
      (is (= 1 (count (store-agents/agent-messages database child-a {}))))
      (store-agents/set-agent-paused! database child-a true)
      (is (= [root-b] (store-agents/agent-wake-roots database)))
      (store-agents/set-agent-paused! database child-a false)
      (store-agents/set-agent-stopped! database root-b true)
      (is (= [root-a] (store-agents/agent-wake-roots database)))
      (deliver! database child-a)
      (is (empty? (store-agents/agent-wake-roots database)))
      (is (= :pending (get-in (first (store-agents/agent-messages database root-b {}))
                              [:deliveries 0 :status]))))))

(deftest agent-routing-branch-stop-and-native-transfer
  (with-memory-store [database]
    (let [root (:id (new-session database))
          child (:session-id (:handle (create-child database root "Worker")))
          native {:answer 3/7 :labels #{:a :b} :nested [:x {:flag true}]}
          submission (util/id)
          sent (store-agents/send-agent-message! database root child native {:submission-id submission})
          duplicate (store-agents/send-agent-message! database root child native {:submission-id submission})
          receipt (:receipt sent)]
      (is (= receipt (:receipt duplicate)))
      (is (= receipt (store-agents/agent-submission database root submission)))
      (is (= [child] (:recipients receipt)))
      (is (true? (store-agents/agent-wake? database child)))
      (let [delivery (deliver! database child)
            descriptor (get-in delivery [:entries 0 :data :message/result])
            packet (store-transfer/export-session database child)
            forked (store-transfer/clone! database child {:name "History only"})
            imported (store-transfer/import-session! database packet {:name "Imported history"})]
        (is (= native (:value (artifacts/result database child (:id descriptor)))))
        (is (= native (get-in (artifacts/result database (:id forked) 1) [:value])))
        (is (= native (get-in (artifacts/result database (:id imported) 1) [:value])))
        (is (= (:id forked) (:root-id (store-agents/agent-state database (:id forked)))))
        (is (nil? (:parent-session-id (store-agents/agent-state database (:id imported)))))
        (is (not (contains? (:metadata imported) :agent/origin))))
      (is (= "agent-descendants-exist"
             (:error/code (ex-data
                           (try (store/delete-session! database root)
                                (catch clojure.lang.ExceptionInfo e e))))))
      (store-agents/set-agent-stopped! database root true)
      (is (false? (store-agents/agent-wake? database child)))
      (is (= "agent-stopped"
             (:error/code (ex-data
                           (try (store-agents/send-agent-message! database root child "blocked"
                                                            {:submission-id (util/id)})
                                (catch clojure.lang.ExceptionInfo e e))))))
      (store-agents/set-agent-stopped! database root false)
      (let [head (:head (store/session database root))]
        (store/branch! database root head {})
        (is (= "agent-stale-context"
               (:error/code (ex-data
                             (try (store-agents/send-agent-message! database child :parent "stale"
                                                              {:submission-id (util/id)})
                                  (catch clojure.lang.ExceptionInfo e e))))))))))

(deftest agent-recovery-interrupts-without-launching-or-duplicating
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")]
    (try
      (let [database (store-db/open! {:path path})
            root (:id (new-session database))
            child (:session-id (:handle (create-child database root "Interrupted")))
            oid (:id (first (store/operations database {:session-id child})))]
        (store-db/close! database)
        (let [reopened (store-db/open! {:path path})]
          (try
            (store-recovery/recover! reopened)
            (is (= :interrupted (:status (store/operation reopened oid))))
            (is (:paused? (store-agents/agent-state reopened child)))
            (is (false? (store-agents/agent-wake? reopened root)))
            (is (= 1 (count (store-agents/agent-messages reopened root {}))))
            (store-recovery/recover! reopened)
            (is (= 1 (count (store-agents/agent-messages reopened root {}))))
            (finally (store-db/close! reopened)))))
      (finally (remove-directory! directory)))))

(deftest explicit-retained-value-crosses-ownership-without-sharing-live-values
  (with-memory-store [database]
    (let [root (:id (new-session database))
          child (:session-id (:handle (create-child database root "Values")))
          native {:ratio 8/13 :symbols '(a b) :nil nil}
          retained (artifacts/put-result! database root
                                          {:kind :inline :value native
                                           :content "native" :details {}})
          reference {:result/ref {:session-id root :id (:id retained)}}
          receipt (:receipt (store-agents/send-agent-message!
                             database root child reference {:submission-id (util/id)}))
          result (get-in (first (:entries (deliver! database child)))
                         [:data :message/result])]
      (is (= {:value native :source-result {:session-id root :id (:id retained)}}
             (:value (artifacts/result database child (:id result)))))
      (is (= :peer (:kind (first (store-agents/agent-messages database child {})))))
      (is (= (:id receipt)
             (get-in (last (store/context-messages database child)) [:message/agent :id])))
      (let [live (artifacts/put-result! database root {:kind :live :content "atom"
                                                        :details {}})
            error (try
                    (store-agents/send-agent-message! database root child
                                               {:result/ref {:session-id root :id (:id live)}}
                                               {:submission-id (util/id)})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= "agent-result-unavailable" (:error/code (ex-data error)))))
      (is (= 1 (count (store-agents/agent-messages database child {})))))))

(deftest branch-supersedes-pending-deliveries-without-consuming-user-queue
  (with-memory-store [database]
    (let [root (:id (new-session database))
          child (:session-id (:handle (create-child database root "Branch")))
          id (:id (:receipt (store-agents/send-agent-message! database child root "old route"
                                                       {:submission-id (util/id)})))]
      (store/commit! database root
                     {::command/queue-enqueue [{:id (util/id) :kind :follow-up
                                       :content "human followup" :options {}}]})
      (store/branch! database root nil {})
      (is (= :superseded (get-in (first (store-agents/agent-messages database root {}))
                                [:deliveries 0 :status])))
      (is (= id (:id (first (store-agents/agent-messages database root {})))))
      (is (empty? (:entries (deliver! database root))))
      (is (= ["human followup"] (mapv :content (store/pending database root)))))))

(deftest agent-receipts-bind-incorporating-operation-and-enforce-visibility
  (with-memory-store [database]
    (let [root (:id (new-session database))
          child-a (:session-id (:handle (create-child database root "Reader A")))
          child-b (:session-id (:handle (create-child database root "Reader B")))
          foreign (:id (new-session database))
          first-id (:id (:receipt (store-agents/send-agent-message! database root child-a "First"
                                                             {:submission-id (util/id)})))
          second-id (:id (:receipt (store-agents/send-agent-message! database root child-a "Second"
                                                              {:submission-id (util/id)})))]
      (is (= :pending (:status (store-agents/agent-delivery database root first-id {}))))
      (is (nil? (get-in (store-agents/agent-delivery database root first-id {})
                       [:deliveries 0 :operation-id])))
      (let [queued-id (:id (first (store/operations database {:session-id child-a})))]
        (is (= "invalid-agent-operation"
               (:error/code
                (ex-data (try (store-agents/deliver-agent-messages! database child-a queued-id)
                              (catch clojure.lang.ExceptionInfo error error))))))
        (is (= :pending (:status (store-agents/agent-delivery database root first-id {})))))
      (is (= "operation-forbidden"
             (:error/code
              (ex-data (try (store-agents/deliver-agent-messages!
                             database child-a
                             (:id (first (store/operations database {:session-id child-b}))))
                            (catch clojure.lang.ExceptionInfo error error))))))
      (let [op (delivery-operation-id database child-a)
            delivery (store-agents/deliver-agent-messages! database child-a op)
            first-receipt (store-agents/agent-delivery database root first-id {})
            second-receipt (store-agents/agent-delivery database child-a second-id {})]
        (is (= 2 (count (:entries delivery))))
        (is (= op (get-in first-receipt [:deliveries 0 :operation-id])
               (get-in second-receipt [:deliveries 0 :operation-id])))
        (is (= (mapv :id (:entries delivery))
               [(get-in first-receipt [:deliveries 0 :entry-id])
                (get-in second-receipt [:deliveries 0 :entry-id])]))
        (is (not (contains? first-receipt :content)))
        (is (= "First" (:content (store-agents/agent-delivery database root first-id
                                                      {:detailed? true}))))
        (store/commit! database child-a {::command/operation {:id op :status :cancelled}})
        (is (= :cancelled (get-in (store-agents/agent-delivery database root first-id {})
                                 [:deliveries 0 :operation-status])))
        (doseq [viewer [child-b foreign]]
          (is (= "agent-message-not-found"
                 (:error/code
                  (ex-data (try (store-agents/agent-delivery database viewer first-id {})
                                (catch clojure.lang.ExceptionInfo error error))))))))
      (let [broadcast (:id (:receipt
                            (store-agents/send-agent-message! database child-a :all "Broadcast"
                                                       {:submission-id (util/id)})))
            pending (store-agents/agent-delivery database child-a broadcast {:limit 1})
            root-op (delivery-operation-id database root)
            child-op (delivery-operation-id database child-b)]
        (is (= 2 (:total-recipients pending)))
        (is (= 1 (:next-offset pending)))
        (is (= 1 (count (:deliveries (store-agents/agent-delivery database child-a broadcast
                                                          {:offset 1 :limit 1})))))
        (is (= 2 (count (:deliveries
                         (store-agents/agent-delivery database child-a broadcast
                                               {:internal? true :limit 500})))))
        (store-agents/deliver-agent-messages! database root root-op)
        (is (= :partial (:status (store-agents/agent-delivery database child-a broadcast {}))))
        (store-agents/deliver-agent-messages! database child-b child-op)
        (let [receipt (store-agents/agent-delivery database child-a broadcast {})
              links (into {} (map (juxt :session-id :operation-id) (:deliveries receipt)))]
          (is (= :delivered (:status receipt)))
          (is (= {root root-op child-b child-op} links))
          (let [page (store-agents/agent-summaries database root {:limit 1})]
            (is (= 3 (:total page)))
            (is (= 1 (:next-offset page)))
            (is (= 1 (count (:agents (store-agents/agent-summaries database root
                                                          {:offset 1 :limit 1}))))))
          (is (= 1 (:total (store-agents/agent-summaries database root {:target-id child-a}))))
          (is (= child-a (get-in (store-agents/agent-summaries database root {:target-id child-a})
                                [:agents 0 :session-id])))
          (is (= child-a (store-agents/agent-target-id database root "Reader A")))
          (is (= root (store-agents/agent-target-id database child-a "Main")))
          (is (= root (store-agents/agent-target-id database root nil)))
          (is (= "agent-target-forbidden"
                 (:error/code (ex-data
                               (try (store-agents/agent-summaries database root
                                                           {:target-id foreign})
                                    (catch clojure.lang.ExceptionInfo error error))))))
          (is (not-any? #(contains? (first (:agents (store-agents/agent-summaries database root {}))) %)
                        [:result :error :history :session :operation]))))
      (let [stale (:id (:receipt (store-agents/send-agent-message! database child-a root "Stale"
                                                            {:submission-id (util/id)})))]
        (store/branch! database root nil {})
        (is (= :superseded (:status (store-agents/agent-delivery database child-a stale {}))))
        (is (nil? (get-in (store-agents/agent-delivery database child-a stale {})
                         [:deliveries 0 :operation-id])))))))

(deftest delivery-links-persist-through-restart-and-unfinished-operation-recovery
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")]
    (try
      (let [database (store-db/open! {:path path})]
        (try
          (let [root (:id (new-session database))
                child (:session-id (:handle (create-child database root "Persisted receipt")))
                id (:id (:receipt (store-agents/send-agent-message! database root child "Keep receipt"
                                                             {:submission-id (util/id)})))
                operation-id (delivery-operation-id database child)
                entry-id (:id (first (:entries (store-agents/deliver-agent-messages!
                                                database child operation-id))))]
            (store-db/close! database)
            (let [reopened (store-db/open! {:path path})]
              (try
                (let [receipt (store-agents/agent-delivery reopened root id {})]
                  (is (= :delivered (:status receipt)))
                  (is (= operation-id (get-in receipt [:deliveries 0 :operation-id])))
                  (is (= entry-id (get-in receipt [:deliveries 0 :entry-id]))))
                (store-recovery/recover! reopened)
                (is (= :interrupted
                       (get-in (store-agents/agent-delivery reopened root id {})
                               [:deliveries 0 :operation-status])))
                (finally (store-db/close! reopened)))))
          (finally (store-db/close! database))))
      (finally (remove-directory! directory)))))

(defn- sqlite-statements! [path statements]
  (with-open [connection (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
              statement (.createStatement connection)]
    (doseq [sql statements] (.execute statement sql))))

(defn- sqlite-version [path]
  (with-open [connection (java.sql.DriverManager/getConnection
                           (str "jdbc:sqlite:" (.toUri (util/path path)) "?mode=ro"))
              statement (.createStatement connection)
              result (.executeQuery statement "PRAGMA user_version")]
    (.next result)
    (.getInt result 1)))

(deftest schema-four-retains-current-shape-history-and-backup
  ;; Schema 4 is evidenced as the present tables with the prior version marker.
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        neighbor (util/path (str directory "/keep.txt"))]
    (try
      (let [database (store-db/open! {:path path})
            sid (:id (new-session database))]
        (store/commit! database sid {::command/entries [(message-entry :user "Keep prior history")]})
        (store-db/close! database)
        (Files/writeString neighbor "unrelated" (make-array java.nio.file.OpenOption 0))
        (sqlite-statements! path ["PRAGMA user_version=4"])
        (let [reopened (store-db/open! {:path path})]
          (try
            (is (= ["Keep prior history"]
                   (mapv :message/content (store/context-messages reopened sid))))
            (is (= 5 (sqlite-version path)))
            (is (= "unrelated" (Files/readString neighbor)))
            (finally (store-db/close! reopened))))
        (with-open [files (Files/list (util/path directory))]
          (let [backups (filter #(re-find #"\.schema4-.*\.backup$" (str %))
                                (iterator-seq (.iterator files)))]
            (is (= 1 (count backups)))
            (is (= 4 (sqlite-version (str (first backups)))))
            (is (= "rw-------"
                   (PosixFilePermissions/toString
                    (Files/getPosixFilePermissions (first backups)
                                                  (make-array java.nio.file.LinkOption 0))))))))
      (finally (remove-directory! directory)))))

(deftest malformed-current-agent-record-is-rejected-without-mutation
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        neighbor (util/path (str directory "/keep.edn"))]
    (try
      (let [database (store-db/open! {:path path})
            root (:id (new-session database))
            _ (create-child database root "Corrupt record")]
        (store-db/close! database))
      (Files/writeString neighbor "{:settings :preserved}"
                         (make-array java.nio.file.OpenOption 0))
      (sqlite-statements! path
                          ["UPDATE agent_routes SET depth=-1 WHERE parent_session_id IS NOT NULL"])
      (let [before (Files/readAllBytes (util/path path))
            error (try (store-db/open! {:path path}) nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
        (is (= "unsupported-store-format" (:error/code (ex-data error))))
        (is (java.util.Arrays/equals before (Files/readAllBytes (util/path path))))
        (is (= "{:settings :preserved}" (Files/readString neighbor))))
      (finally (remove-directory! directory)))))

(deftest fresh-root-counts-toward-agent-cap-and-reserves-main-name
  (with-memory-store [database]
    (let [root (:id (new-session database))
          opts {:id (util/id) :operation-id (util/id) :submission-id (util/id)
                :name "Reader" :cwd (System/getProperty "java.io.tmpdir")
                :config config :task "Inspect evidence"}]
      (is (= "agent-limit"
             (:error/code
              (ex-data (try (store-agents/create-agent! database root (assoc opts :limit 1))
                            (catch clojure.lang.ExceptionInfo error error))))))
      (is (= "agent-name-exists"
             (:error/code
              (ex-data (try (store-agents/create-agent! database root
                                                 (assoc opts :name "Main" :limit 2))
                            (catch clojure.lang.ExceptionInfo error error))))))
      (is (= [root] (mapv :session-id (store-agents/agent-team database root {}))))
      (is (empty? (store/operations database {:session-id root}))))))

(deftest upgrade-cannot-bypass-exclusive-owner
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        first-store (store-db/open! {:path path})]
    (try
      (let [sid (:id (new-session first-store))]
        (store-db/store-read first-store
          (fn [connection]
            (with-open [statement (.createStatement connection)]
              (.execute statement "PRAGMA user_version=4"))))
        (let [error (try (store-db/open! {:path path}) nil
                         (catch clojure.lang.ExceptionInfo failure failure))]
          (is (= "store-in-use" (:error/code (ex-data error))))
          (is (= sid (:id (store/session first-store sid)))))
        (store-db/close! first-store)
        (let [replacement (store-db/open! {:path path})]
          (try
            (is (= sid (:id (store/session replacement sid))))
            (finally (store-db/close! replacement)))))
      (finally
        (store-db/close! first-store)
        (remove-directory! directory)))))

(deftest unsafe-artifact-symlink-prevents-upgrade
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        outside (util/path (str directory "/unrelated"))
        linked (util/path (str directory "/linked-artifacts"))]
    (try
      (let [database (store-db/open! {:path path})]
        (new-session database)
        (store-db/close! database))
      (with-open [connection (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
                  statement (.createStatement connection)]
        (.execute statement "PRAGMA user_version=4"))
      (Files/createDirectory outside (make-array FileAttribute 0))
      (Files/writeString (.resolve outside "untouched") "Keep this"
                         (make-array java.nio.file.OpenOption 0))
      (Files/createSymbolicLink linked outside (make-array FileAttribute 0))
      (let [before (Files/readAllBytes (util/path path))
            error (try (store-db/open! {:path path :artifact-dir (str linked)}) nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
        (is (= "insecure-database" (:error/code (ex-data error))))
        (is (java.util.Arrays/equals before (Files/readAllBytes (util/path path))))
        (is (= "Keep this" (Files/readString (.resolve outside "untouched")))))
      (finally (remove-directory! directory)))))

(deftest unrelated-sqlite-errors-do-not-delete-database
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        bytes (.getBytes "not an SQLite database" java.nio.charset.StandardCharsets/UTF_8)]
    (try
      (Files/write (util/path path) bytes (make-array java.nio.file.OpenOption 0))
      (is (thrown? Throwable (store-db/open! {:path path})))
      (is (java.util.Arrays/equals bytes (Files/readAllBytes (util/path path))))
      (finally (remove-directory! directory)))))

(deftest foreign-sqlite-is-not-a-resettable-arrodes-store
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")]
    (try
      (with-open [connection (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
                  statement (.createStatement connection)]
        (.execute statement "CREATE TABLE foreign_notes (content TEXT NOT NULL)")
        (.execute statement "INSERT INTO foreign_notes(content) VALUES('retain')"))
      (Files/setPosixFilePermissions (util/path path)
                                     (PosixFilePermissions/fromString "rw-r--r--"))
      (let [before (Files/readAllBytes (util/path path))
            error (try (store-db/open! {:path path}) nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
        (is (= "unrecognized-store" (:error/code (ex-data error))))
        (is (java.util.Arrays/equals before (Files/readAllBytes (util/path path))))
        (is (= "rw-r--r--"
               (PosixFilePermissions/toString
                (Files/getPosixFilePermissions (util/path path)
                                               (make-array java.nio.file.LinkOption 0)))))
        (is (false? (Files/exists (util/path (str path ".artifacts"))
                                  (make-array java.nio.file.LinkOption 0)))))
      (finally (remove-directory! directory)))))

(deftest shared-artifact-root-cannot-claim-another-stores-content
  (let [directory (temp-directory)
        first-path (str directory "/first.sqlite")
        second-path (str directory "/second.sqlite")
        shared (str directory "/shared-artifacts")
        first (store-db/open! {:path first-path :artifact-dir shared})]
    (try
      (let [sid (:id (new-session first))
            descriptor (artifacts/put! first sid "Owned by first" {})
            second (store-db/open! {:path second-path})]
        (try
          (new-session second)
          (finally (store-db/close! second)))
        (with-open [connection (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" second-path))
                    statement (.createStatement connection)]
          (.execute statement "PRAGMA user_version=4"))
        (let [error (try (store-db/open! {:path second-path :artifact-dir shared}) nil
                         (catch clojure.lang.ExceptionInfo failure failure))]
          (is (= "artifact-store-in-use" (:error/code (ex-data error))))
          (is (= "Owned by first" (:content (artifacts/read! first sid (:id descriptor) {})))))
        (store-db/close! first)
        (let [error (try (store-db/open! {:path second-path :artifact-dir shared}) nil
                         (catch clojure.lang.ExceptionInfo failure failure))]
          (is (= "artifact-owner-conflict" (:error/code (ex-data error)))))
        (let [reopened (store-db/open! {:path first-path :artifact-dir shared})]
          (try
            (is (= "Owned by first"
                   (:content (artifacts/read! reopened sid (:id descriptor) {}))))
            (finally (store-db/close! reopened)))))
      (finally
        (store-db/close! first)
        (remove-directory! directory)))))

(deftest hardlinked-database-alias-cannot-bypass-owner-lock
  (let [directory (temp-directory)
        path (util/path (str directory "/sessions.sqlite"))
        alias (util/path (str directory "/alias.sqlite"))
        original (store-db/open! {:path (str path)})]
    (try
      (let [sid (:id (new-session original))]
        (Files/createLink alias path)
        (let [error (try (store-db/open! {:path (str alias)}) nil
                         (catch clojure.lang.ExceptionInfo failure failure))]
          (is (= "insecure-database" (:error/code (ex-data error))))
          (is (= sid (:id (store/session original sid)))))
        (Files/delete alias))
      (finally
        (Files/deleteIfExists alias)
        (store-db/close! original)
        (remove-directory! directory)))))

(deftest interrupted-legacy-reset-marker-blocks-deletion
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        database (store-db/open! {:path path})
        sid (:id (new-session database))
        artifact (artifacts/put! database sid "Old retained artifact" {})
        content (util/path (str path ".artifacts/"
                                (subs (:sha256 artifact) 0 2) "/" (:sha256 artifact)))
        marker (util/path (str path ".reset"))]
    (store-db/close! database)
    (try
      (Files/writeString marker (pr-str {:database path :artifacts (str path ".artifacts")})
                         (make-array java.nio.file.OpenOption 0))
      (let [before (Files/readAllBytes (util/path path))
            error (try (store-db/open! {:path path}) nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
        (is (= "incomplete-legacy-reset" (:error/code (ex-data error))))
        (is (java.util.Arrays/equals before (Files/readAllBytes (util/path path))))
        (is (Files/exists content (make-array java.nio.file.LinkOption 0)))
        (is (Files/exists marker (make-array java.nio.file.LinkOption 0))))
      (finally (remove-directory! directory)))))

(deftest historical-schema-three-retains-history-results-jobs-and-artifacts
  ;; The 0.1.5 schema-3 DDL is precisely these eight tables (no agent tables).
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        database (store-db/open! {:path path})]
    (try
      (let [sid (:id (new-session database))
            artifact (artifacts/put! database sid "Keep the blob" {:name "owned"})
            result (artifacts/put-result! database sid
                                          {:kind :inline :content "value" :details {}
                                           :value {:retained true}})
            job-id (util/id)]
        (store/commit! database sid
                       {::command/entries [(message-entry :user "Keep the history")]
                        ::command/operation {:id (util/id) :kind :run :status :completed}
                        ::command/events [{:type :history/saved :data {:retained true}}]})
        (store-jobs/create-job! database {:id job-id :session-id sid :status :queued
                                     :name "Retained job" :created-at 1})
        (store-db/close! database)
        (sqlite-statements!
         path ["DROP TABLE agent_deliveries" "DROP TABLE agent_messages"
               "DROP TABLE agent_submissions" "DROP TABLE agent_routes"
               "PRAGMA application_id=0" "PRAGMA user_version=3"])
        (let [reopened (store-db/open! {:path path})]
          (try
            (is (= 5 (sqlite-version path)))
            (is (= ["Keep the history"]
                   (mapv :message/content (store/context-messages reopened sid))))
            (is (= config (:config (store/session reopened sid))))
            (is (= {:retained true}
                   (:value (artifacts/result reopened sid (:id result)))))
            (is (= "Keep the blob"
                   (:content (artifacts/read! reopened sid (:id artifact) {}))))
            (is (= :queued (:status (store-jobs/job reopened sid job-id))))
            (is (= :completed (:status (first (store/operations reopened {:session-id sid})))))
            (is (some #(= :history/saved (:type %))
                      (store/events-since reopened {:session-id sid})))
            (is (= sid (:root-id (store-agents/agent-state reopened sid))))
            (finally (store-db/close! reopened))))
        (with-open [files (Files/list (util/path directory))]
          (let [backup (first (filter #(re-find #"\.schema3-.*\.backup$" (str %))
                                      (iterator-seq (.iterator files))))]
            (is backup)
            (when backup
              (is (= 3 (sqlite-version (str backup))))
              (with-open [connection (java.sql.DriverManager/getConnection
                                      (str "jdbc:sqlite:" (.toUri backup) "?mode=ro"))
                          statement (.createStatement connection)
                          result (.executeQuery statement "SELECT COUNT(*) FROM jobs")]
                (.next result)
                (is (= 1 (.getInt result 1))))))))
      (finally
        (store-db/close! database)
        (remove-directory! directory)))))

(deftest interrupted-upgrade-rolls-back-and-remains-retryable
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        database (store-db/open! {:path path})
        sid (:id (new-session database))]
    (try
      (store/commit! database sid {::command/entries [(message-entry :user "Survive failed migration")]})
      (store-db/close! database)
      (sqlite-statements!
       path ["DROP TABLE agent_deliveries" "DROP TABLE agent_messages"
             "DROP TABLE agent_submissions" "DROP TABLE agent_routes"
             "PRAGMA application_id=0" "PRAGMA user_version=3"])
      (let [target-var #'store-sql/execute-command!
            original @target-var
            error (try
                    (with-redefs-fn
                      {target-var (fn [connection sql]
                                    (if (= sql "PRAGMA user_version = 5")
                                      (throw (ex-info "simulated migration interruption" {}))
                                      (original connection sql)))}
                      #(store-db/open! {:path path}))
                    nil
                    (catch clojure.lang.ExceptionInfo failure failure))]
        (is (= "simulated migration interruption" (ex-message error)))
        (is (= 3 (sqlite-version path)))
        (with-open [connection (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
                    statement (.createStatement connection)
                    result (.executeQuery statement
                                          "SELECT COUNT(*) FROM sqlite_master WHERE name='agent_routes'")]
          (.next result)
          (is (zero? (.getInt result 1)))))
      (let [reopened (store-db/open! {:path path})]
        (try
          (is (= ["Survive failed migration"]
                 (mapv :message/content (store/context-messages reopened sid))))
          (is (= 5 (sqlite-version path)))
          (finally (store-db/close! reopened))))
      (with-open [files (Files/list (util/path directory))]
        (is (= 2 (count (filter #(re-find #"\.schema3-.*\.backup$" (str %))
                                (iterator-seq (.iterator files)))))))
      (finally
        (store-db/close! database)
        (remove-directory! directory)))))

(deftest upgrade-backup-includes-uncheckpointed-wal-commits
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        database (store-db/open! {:path path})]
    (try
      (let [sid (:id (new-session database))]
        (store-db/close! database)
        (with-open [writer (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
                    statement (.createStatement writer)]
          (.execute statement "PRAGMA journal_mode=WAL")
          (.execute statement "UPDATE sessions SET name='Committed in WAL'")
          (.execute statement "PRAGMA user_version=4")
          (let [reopened (store-db/open! {:path path})]
            (try
              (is (= "Committed in WAL" (:name (store/session reopened sid))))
              (finally (store-db/close! reopened)))))
        (with-open [files (Files/list (util/path directory))]
          (let [backup (first (filter #(re-find #"\.schema4-.*\.backup$" (str %))
                                      (iterator-seq (.iterator files))))]
            (is backup)
            (when backup
              (with-open [connection (java.sql.DriverManager/getConnection
                                      (str "jdbc:sqlite:" (.toUri backup) "?mode=ro"))
                          statement (.createStatement connection)
                          result (.executeQuery statement "SELECT name FROM sessions")]
                (.next result)
                (is (= "Committed in WAL" (.getString result 1))))))))
      (finally
        (store-db/close! database)
        (remove-directory! directory)))))

(deftest unmarked-artifact-root-with-unknown-file-stays-unclaimed
  (let [directory (temp-directory)
        path (str directory "/sessions.sqlite")
        database (store-db/open! {:path path})
        sid (:id (new-session database))
        artifact (artifacts/put! database sid "Retained" {})
        root (util/path (str path ".artifacts"))
        extra (.resolve root "not-an-artifact")]
    (store-db/close! database)
    (try
      (Files/delete (.resolve root ".arrodes-owner"))
      (Files/writeString extra "Unrelated" (make-array java.nio.file.OpenOption 0))
      (let [error (try (store-db/open! {:path path}) nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
        (is (= "artifact-owner-unknown" (:error/code (ex-data error))))
        (is (= "Unrelated" (Files/readString extra)))
        (is (Files/exists (util/path (str root "/" (subs (:sha256 artifact) 0 2)
                                          "/" (:sha256 artifact)))
                          (make-array java.nio.file.LinkOption 0))))
      (finally (remove-directory! directory)))))
