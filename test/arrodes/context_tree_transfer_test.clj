(ns arrodes.context-tree-transfer-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.capabilities :as capabilities]
            [arrodes.context-tree :as context-tree]
            [arrodes.history :as history]
            [arrodes.platform :as util]
            [arrodes.session :as session-model]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.context-tree :as tree-store]
            [arrodes.store.db :as store-db]
            [arrodes.store.transfer :as transfer]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Base64 Date UUID)))

(def ^:private config
  {:provider :openai :model "fixture-model" :thinking :medium :tools :all
   :instructions "Offline contextual transfer test" :settings {}})

(defmacro with-memory-store [[binding] & body]
  `(let [~binding (store-db/open! {:memory? true})]
     (try ~@body (finally (store-db/close! ~binding)))))

(defmacro with-file-store [[binding] & body]
  `(let [directory# (Files/createTempDirectory "arrodes-context-transfer-"
                                              (make-array FileAttribute 0))
         ~binding (store-db/open! {:path (str directory# "/sessions.sqlite")})]
     (try
       ~@body
       (finally
         (store-db/close! ~binding)
         (with-open [walk# (Files/walk directory# (make-array java.nio.file.FileVisitOption 0))]
           (doseq [file# (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator walk#)))]
             (Files/deleteIfExists file#)))))))

(defn- new-session [database]
  (store/create-session! database
                         {:name "Context transfer" :cwd (System/getProperty "java.io.tmpdir")
                          :config config}))

(defn- message-entry [role text]
  {:kind :message :data {:message/role role :message/content text}})

(defn- native-result [database sid id]
  (let [registry (capabilities/create! {:store database :session-id sid
                                        :cwd (:cwd (store/session database sid)) :config config})]
    (try
      (capabilities/result-value registry id)
      (finally (capabilities/close! registry)))))

(defn- read-original [database sid entry-id]
  (let [registry (capabilities/create! {:store database :session-id sid
                                        :cwd (:cwd (store/session database sid)) :config config})]
    (try
      (binding [capabilities/*invocation-context* {:registry registry :session-id sid}]
        (history/read entry-id))
      (finally (capabilities/close! registry)))))

(defn- unrelated-native [sid entry-id]
  {:id entry-id :entry-id entry-id :from-id entry-id :session-id sid
   :context-entry-ids [entry-id] :source-entry-ids [entry-id]
   :operation-id (util/id) :call-id "literal-call"
   :result {:id 99} :message/result {:id 99}
   :artifact-id entry-id :artifact {:id entry-id :session-id sid}
   :ratio 8/13 :symbols '(a b) :set #{:one :two} :character \λ
   :uuid (UUID/randomUUID) :date (Date. 1234) :nil nil})

(defn- retain! [database sid native details storage]
  (artifacts/put-result!
   database sid
   (merge {:content "Historical printed result remains verbatim" :details details}
          (case storage
            :inline {:kind :inline :value native}
            :artifact (let [artifact (artifacts/put! database sid (pr-str native)
                                                     {:kind :edn :name "native-retrieval.edn"})]
                        {:kind :artifact :artifact-id (:id artifact)})))))

(defn- commit-native! [database sid descriptor]
  (store/commit! database sid
                 {::command/entries
                  [{:kind :evaluation
                    :data {:source "retained native value"
                           :result {:id "retained-native" :content "Native value"
                                    :details {} :error? false :result descriptor}}}]}))

(defn- record-retrieval! [database sid receipt native storage]
  (let [details {:history/retrieval receipt :history/retrievals [receipt]}
        descriptor (retain! database sid native details storage)
        source (str "(history/read \"" (first (:source-entry-ids receipt)) "\") (+ 20 22)")
        committed
        (store/commit!
         database sid
         {::command/entries
          [{:kind :evaluation
            :data {:source source
                   :result {:id "historical-evaluation" :content "New calculation: 42"
                            :details details :error? false :result descriptor}}}
           {:kind :message
            :data {:message/role :assistant
                   :message/content (str "Quoted original references: " (pr-str receipt))
                   :history/retrievals [receipt]
                   :message/details {:history/retrieval receipt}}}]})]
    {:descriptor descriptor :source source :entries (:entries committed)}))

(defn- fixture! [database storage]
  (let [sid (:id (new-session database))
        committed (store/commit! database sid
                                 {::command/entries [(message-entry :user "Original evidence")
                                                     (message-entry :assistant "Original answer")
                                                     (message-entry :user "Current question")]})
        [first-entry last-entry context-entry] (:entries committed)
        receipt {:session-id sid :context-entry-ids [(:id context-entry)]
                 :context-head-entry-id (:id context-entry)
                 :source-entry-ids [(:id first-entry) (:id last-entry)]
                 :source-first-entry-id (:id first-entry) :source-last-entry-id (:id last-entry)
                 :source-count 2 :query "why?" :mode :read
                 :operation-id (util/id) :call-id "originating-call" :available? true}
        marked {:history/retrieval receipt :original "Quoted original evidence"}
        unrelated (unrelated-native sid (:id first-entry))
        native {:lookups [marked] :list (list marked 'literal)
                :set #{marked :literal} :unrelated unrelated :new-computation 42}
        recorded (record-retrieval! database sid receipt native storage)]
    (merge recorded {:sid sid :receipt receipt :native native :unrelated unrelated})))

(defn- copied-descriptor [database sid]
  (get-in (first (filter #(= :evaluation (:kind %)) (store/entries database sid)))
          [:data :result :result]))

(defn- assert-local-navigation [database sid receipt]
  (let [entries (store/entries database sid)
        by-id (into {} (map (juxt :id identity)) entries)
        path (session-model/active-path entries (:head (store/session database sid)))]
    (is (= sid (:session-id receipt)))
    (is (every? #(= sid (:session-id (get by-id %)))
                (concat (:context-entry-ids receipt) (:source-entry-ids receipt))))
    (is (= (first (:context-entry-ids receipt)) (:context-head-entry-id receipt)))
    (is (= ["Original evidence" "Original answer"]
           (mapv #(get-in (get by-id %) [:data :message/content]) (:source-entry-ids receipt))))
    (is (= ["Current question"]
           (mapv #(get-in (get by-id %) [:data :message/content]) (:context-entry-ids receipt))))
    (is (= (mapv :id entries) (mapv :id path)))
    (is (= (first (:source-entry-ids receipt)) (:source-first-entry-id receipt)))
    (is (= (last (:source-entry-ids receipt)) (:source-last-entry-id receipt)))
    (is (= 2 (:source-count receipt)))
    (is (true? (:available? receipt)))
    (is (not (contains? receipt :operation-id)))
    (is (not (contains? receipt :call-id)))))

(defn- assert-roundtrip [database storage]
  (let [{:keys [sid receipt native unrelated source descriptor]} (fixture! database storage)
        entries (store/entries database sid)
        sources (context-tree/source-entries entries)
        cached (context-tree/leaf-node sources 0 "Cached original evidence")
        _ (tree-store/put-node! database sid cached)
        _ (store/commit! database sid
                         {::command/session
                          {:metadata (assoc (:metadata (store/session database sid))
                                            :context/view-node-ids [(:id cached)])}})
        original (transfer/export-session database sid)
        cloned (transfer/clone! database sid {})
        forked (transfer/fork! database sid {})
        imported (transfer/import-session! database original {})
        reimported (transfer/import-session! database
                                             (transfer/export-session database (:id cloned)) {})]
    (is (= 2 (:version original)))
    (is (not (contains? original :context-nodes)))
    (is (not (contains? (get-in original [:session :metadata]) :context/view-node-ids)))
    (is (not (contains? original :jobs)))
    (doseq [copied [cloned forked imported reimported]]
      (let [copied-id (:id copied)
            retained (copied-descriptor database copied-id)
            value (native-result database copied-id (:id retained))
            reference (get-in value [:lookups 0 :history/retrieval])
            copied-entries (store/entries database copied-id)
            evaluation (first (filter #(= :evaluation (:kind %)) copied-entries))
            message (last copied-entries)]
        (assert-local-navigation database copied-id reference)
        (is (= receipt (:source-reference reference)))
        (is (= unrelated (:unrelated value)))
        (is (= 42 (:new-computation value)))
        (is (list? (:list value)))
        (is (set? (:set value)))
        (is (= reference (:history/retrieval (first (:list value)))))
        (is (= #{:literal {:history/retrieval reference :original "Quoted original evidence"}}
               (:set value)))
        (is (= reference (get-in retained [:details :history/retrieval])))
        (is (= [reference] (get-in retained [:details :history/retrievals])))
        (is (= [reference] (get-in evaluation [:data :result :details :history/retrievals])))
        (is (= [reference] (get-in message [:data :history/retrievals])))
        (is (= reference (get-in message [:data :message/details :history/retrieval])))
        (is (= source (get-in evaluation [:data :source])))
        (is (= (get-in (last entries) [:data :message/content])
               (get-in message [:data :message/content])))
        (is (= (:content descriptor) (:content retained)))
        (is (= :idle (:status copied)))
        (is (not (contains? (:metadata copied) :context/view-node-ids)))
        (is (empty? (tree-store/nodes database copied-id)))))
    (is (= original (transfer/export-session database sid)))
    (is (= entries (store/entries database sid)))
    (is (= native (native-result database sid (:id descriptor))))
    (is (= [(:id cached)] (get-in (store/session database sid) [:metadata :context/view-node-ids])))
    (is (= cached (select-keys (tree-store/node database sid (:id cached)) (keys cached))))))

(deftest typed-retrievals-roundtrip-through-real-native-consumers
  (with-memory-store [database]
    (doseq [storage [:inline :artifact]]
      (testing (name storage) (assert-roundtrip database storage)))))

(deftest remapped-native-artifacts-survive-file-backed-transfer
  (with-file-store [database]
    (assert-roundtrip database :artifact)))

(deftest partial-forks-mark-omitted-originals-and-context-as-unavailable
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          committed (store/commit! database sid
                                   {::command/entries [(message-entry :user "Shared evidence")
                                                       (message-entry :assistant "Omitted original")
                                                       (message-entry :user "Omitted context")]})
          [shared omitted omitted-context] (:entries committed)
          _ (store/branch! database sid (:id shared) {})
          current (first (:entries (store/commit! database sid
                                                  {::command/entries [(message-entry :user "Local question")]})))
          receipt {:session-id sid :context-entry-ids [(:id current) (:id omitted-context)]
                   :context-head-entry-id (:id omitted-context)
                   :source-entry-ids [(:id shared) (:id omitted)]
                   :source-first-entry-id (:id shared) :source-last-entry-id (:id omitted)
                   :source-count 2 :mode :zoom :operation-id (util/id)
                   :call-id "outside-branch" :available? true}
          native {:history/retrieval receipt :new-computation 42}
          recorded (record-retrieval! database sid receipt native :artifact)
          original (transfer/export-session database sid)
          forked (transfer/fork! database sid {:entry-id (:id (last (:entries recorded)))})
          cloned (transfer/clone! database sid {})
          imported (transfer/import-session! database (transfer/export-session database (:id forked)) {})]
      (doseq [copied [forked cloned imported]]
        (let [copied-id (:id copied)
              value (native-result database copied-id (:id (copied-descriptor database copied-id)))
              reference (:history/retrieval value)
              by-id (into {} (map (juxt :id identity)) (store/entries database copied-id))]
          (is (false? (:available? reference)))
          (is (= copied-id (:session-id reference)))
          (is (= receipt (:source-reference reference)))
          (is (= 1 (count (:source-entry-ids reference))))
          (is (= "Shared evidence"
                 (get-in (get by-id (first (:source-entry-ids reference))) [:data :message/content])))
          (is (= 1 (count (:context-entry-ids reference))))
          (is (= "Local question"
                 (get-in (get by-id (first (:context-entry-ids reference))) [:data :message/content])))
          (is (not-any? #(contains? reference %)
                        [:context-head-entry-id :source-first-entry-id :source-last-entry-id
                         :source-count :operation-id :call-id]))
          (is (not-any? #(contains? by-id %) [(:id omitted) (:id omitted-context)]))
          (is (= 42 (:new-computation value)))))
      (is (= original (transfer/export-session database sid)))
      (is (= native (native-result database sid (get-in recorded [:descriptor :id])))))))

(defn- import-error [database packet]
  (try
    (transfer/import-session! database packet {})
    nil
    (catch clojure.lang.ExceptionInfo error (:error/code (ex-data error)))))

(deftest corrupt-typed-references-are-rejected-before-import-mutation
  (with-file-store [database]
    (let [{:keys [sid]} (fixture! database :inline)
          original (transfer/export-session database sid)
          before (store/list-sessions database {})
          reference-path [:results 0 :details :history/retrieval]
          corruptions [{:session-id (util/id)} {:source-entry-ids [(util/id)]}
                       {:context-entry-ids [(util/id)]} {:source-entry-ids ["not-a-uuid"]}
                       {:context-entry-ids '()} {:available? :yes} {:operation-id "not-a-uuid"}
                       {:call-id ""} {:query 42} {:mode :execute} {:source-count 3}
                       {:context-head-entry-id "not-a-uuid"} {:context-head-entry-id (util/id)}
                       {:source-first-entry-id (util/id)}
                       {:source-reference {:session-id "forged"}}]]
      (doseq [changes corruptions]
        (is (= "invalid-import" (import-error database (update-in original reference-path merge changes))))
        (is (= before (store/list-sessions database {}))))
      (is (= "invalid-import"
             (import-error database (update-in original reference-path dissoc :source-last-entry-id))))
      (is (= "invalid-import"
             (import-error database (assoc-in original [:entries 3 :data :result :details :history/retrievals]
                                              [{:session-id sid}]))))
      (is (= "invalid-import"
             (import-error database (assoc-in original [:results 0 :value :lookups 0 :history/retrieval :available?]
                                              nil))))
      (doseq [version [nil 0 3 "2" 1]]
        (is (= "invalid-import" (import-error database (assoc original :version version)))))
      (is (= before (store/list-sessions database {})))
      (is (= original (transfer/export-session database sid))))))

(deftest typed-references-in-native-edn-artifacts-are-validated
  (with-memory-store [database]
    (let [{:keys [sid native]} (fixture! database :artifact)
          original (transfer/export-session database sid)
          corrupt (assoc-in native [:lookups 0 :history/retrieval :source-entry-ids] [(util/id)])
          bytes (.getBytes ^String (pr-str corrupt) StandardCharsets/UTF_8)
          packet (-> original
                     (assoc-in [:artifacts 0 :content] (.encodeToString (Base64/getEncoder) bytes))
                     (assoc-in [:artifacts 0 :descriptor :bytes] (alength bytes))
                     (assoc-in [:artifacts 0 :descriptor :sha256] (util/sha256 bytes)))
          before (store/list-sessions database {})]
      (is (= "invalid-import" (import-error database packet)))
      (is (= before (store/list-sessions database {})))
      (is (= original (transfer/export-session database sid))))))

(deftest artifact-only-retrieval-markers-require-version-two
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          original-entry (first (:entries (store/commit! database sid
                                                          {::command/entries
                                                           [(message-entry :user "Artifact-only original")]})))
          receipt {:session-id sid :context-entry-ids [(:id original-entry)]
                   :source-entry-ids [(:id original-entry)] :mode :read :available? true}
          native {:history/retrieval receipt :answer 42}
          descriptor (retain! database sid native {} :artifact)
          _ (store/commit! database sid
                           {::command/entries
                            [{:kind :evaluation
                              :data {:source "artifact-only"
                                     :result {:id "artifact-only" :content "42" :details {}
                                              :error? false :result descriptor}}}]})
          original (transfer/export-session database sid)
          imported (transfer/import-session! database original {})
          imported-id (:id imported)
          value (native-result database imported-id (:id (copied-descriptor database imported-id)))
          reference (:history/retrieval value)
          copied-entry (first (store/entries database imported-id))
          malformed (.getBytes "{" StandardCharsets/UTF_8)
          invalid-packet (-> original
                             (assoc-in [:artifacts 0 :content]
                                       (.encodeToString (Base64/getEncoder) malformed))
                             (assoc-in [:artifacts 0 :descriptor :bytes] (alength malformed))
                             (assoc-in [:artifacts 0 :descriptor :sha256] (util/sha256 malformed)))
          before (store/list-sessions database {})]
      (is (= 2 (:version original)))
      (is (= {:session-id imported-id :context-entry-ids [(:id copied-entry)]
              :source-entry-ids [(:id copied-entry)] :mode :read :available? true
              :source-reference receipt}
             reference))
      (is (= 42 (:answer value)))
      (is (= "invalid-import" (import-error database (assoc original :version 1))))
      (is (= "invalid-import" (import-error database invalid-packet)))
      (is (= before (store/list-sessions database {})))
      (is (= original (transfer/export-session database sid))))))

(deftest ordinary-version-one-packets-and-unrelated-edn-remain-unchanged
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          entry (first (:entries (store/commit! database sid
                                                {::command/entries [(message-entry :user "Ordinary history")]})))
          native (unrelated-native sid (:id entry))
          printed (str " \n" (pr-str native) "\n")
          artifact (artifacts/put! database sid printed {:kind :edn})
          descriptor (artifacts/put-result! database sid
                                             {:kind :artifact :artifact-id (:id artifact)
                                              :content "Literal :history/retrieval {:session-id \"quoted\"}"
                                              :details {}})
          _ (store/commit! database sid
                           {::command/entries [{:kind :evaluation
                                               :data {:source "'{:history/retrieval {:id :literal}}"
                                                      :result {:id "ordinary" :content "ordinary"
                                                               :details {} :error? false :result descriptor}}}]})
          original (transfer/export-session database sid)
          cloned (transfer/clone! database sid {})
          forked (transfer/fork! database sid {})
          imported (transfer/import-session! database original {})]
      (is (= 1 (:version original)))
      (doseq [copied [cloned forked imported]]
        (let [copied-id (:id copied)
              retained (copied-descriptor database copied-id)]
          (is (= native (native-result database copied-id (:id retained))))
          (is (= printed (:content (artifacts/read! database copied-id (:artifact-id retained) {:limit 8192}))))
          (is (= (:content descriptor) (:content retained)))
          (is (= "'{:history/retrieval {:id :literal}}"
                 (get-in (last (store/entries database copied-id)) [:data :source])))
          (is (= 1 (:version (transfer/export-session database copied-id))))))
      (is (= original (transfer/export-session database sid))))))

(defn- with-native-artifact [packet native]
  (let [bytes (.getBytes ^String (pr-str native) StandardCharsets/UTF_8)]
    (-> packet
        (assoc-in [:artifacts 0 :content] (.encodeToString (Base64/getEncoder) bytes))
        (assoc-in [:artifacts 0 :descriptor :bytes] (alength bytes))
        (assoc-in [:artifacts 0 :descriptor :sha256] (util/sha256 bytes)))))

(deftest map-key-only-receipts-remain-navigable-through-native-consumers
  (with-memory-store [database]
    (doseq [storage [:inline :artifact]]
      (testing (name storage)
        (let [sid (:id (new-session database))
              [original-entry context-entry]
              (:entries (store/commit! database sid
                                       {::command/entries
                                        [(message-entry :assistant "Map-key evidence")
                                         (message-entry :user "Map-key question")]}))
              original-key (read-original database sid (:id original-entry))
              receipt (:history/retrieval original-key)
              unrelated (unrelated-native sid (:id original-entry))
              native {original-key :saved unrelated :literal}
              descriptor (retain! database sid native {} storage)
              _ (commit-native! database sid descriptor)
              packet (transfer/export-session database sid)
              before (store/list-sessions database {})
              bad-key (assoc-in original-key [:history/retrieval :source-entry-ids] [(util/id)])
              corrupt-native {bad-key :saved unrelated :literal}
              corrupt-packet (if (= storage :artifact)
                               (with-native-artifact packet corrupt-native)
                               (assoc-in packet [:results 0 :value] corrupt-native))]
          (is (= [(:id context-entry)] (:context-entry-ids receipt)))
          (is (= (:id context-entry) (:context-head-entry-id receipt)))
          (is (= 2 (:version packet)))
          (is (= "invalid-import" (import-error database (assoc packet :version 1))))
          (is (= "invalid-import" (import-error database corrupt-packet)))
          (is (= before (store/list-sessions database {})))
          (doseq [copied [(transfer/clone! database sid {})
                          (transfer/fork! database sid {})
                          (transfer/import-session! database packet {})]]
            (let [copied-id (:id copied)
                  retained (copied-descriptor database copied-id)
                  value (native-result database copied-id (:id retained))
                  copied-key (first (keep (fn [[key saved]] (when (= :saved saved) key)) value))
                  ref (:history/retrieval copied-key)
                  copied-original (read-original database copied-id (first (:source-entry-ids ref)))]
              (is (map? value))
              (is (= 2 (count value)))
              (is (= :literal (get value unrelated)))
              (is (= (dissoc original-key :history/retrieval)
                     (dissoc copied-key :history/retrieval)))
              (is (= copied-id (:session-id ref)))
              (is (true? (:available? ref)))
              (is (= receipt (:source-reference ref)))
              (is (= (first (:context-entry-ids ref)) (:context-head-entry-id ref)))
              (is (not= (:id context-entry) (:context-head-entry-id ref)))
              (is (not= (:id original-entry) (first (:source-entry-ids ref))))
              (is (= "Map-key evidence" (:content copied-original)))
              (is (= 2 (:version (transfer/export-session database copied-id))))))
          (is (= native (native-result database sid (:id descriptor))))
          (is (= packet (transfer/export-session database sid))))))))

(deftest ordinary-artifact-byte-limits-do-not-impose-inline-shape-limits
  (with-memory-store [database]
    (doseq [native [(vec (repeat 100000 0))
                    (nth (iterate vector {:ordinary #{:value} :list '(a b)}) 64)]]
      (let [sid (:id (new-session database))
            _ (store/commit! database sid {::command/entries [(message-entry :user "Ordinary artifact")]})
            printed (str " \n" (pr-str native) "\n")
            artifact (artifacts/put! database sid printed {:kind :edn})
            descriptor (artifacts/put-result! database sid
                                               {:kind :artifact :artifact-id (:id artifact)
                                                :content "Ordinary native artifact" :details {}})
            _ (commit-native! database sid descriptor)
            packet (transfer/export-session database sid)]
        (is (= native (native-result database sid (:id descriptor))))
        (is (= 1 (:version packet)))
        (doseq [copied [(transfer/clone! database sid {})
                        (transfer/fork! database sid {})
                        (transfer/import-session! database packet {})]]
          (let [copied-id (:id copied)
                retained (copied-descriptor database copied-id)
                copied-packet (transfer/export-session database copied-id)
                copied-artifact (get-in copied-packet [:artifacts 0 :descriptor])]
            (is (= native (native-result database copied-id (:id retained))))
            (is (= 1 (:version copied-packet)))
            (is (= (:sha256 artifact) (:sha256 copied-artifact)))
            (is (= (:bytes artifact) (:bytes copied-artifact)))
            (is (= (get-in packet [:artifacts 0 :content])
                   (get-in copied-packet [:artifacts 0 :content])))))
        (is (= packet (transfer/export-session database sid)))
        (is (= native (native-result database sid (:id descriptor))))))))

(deftest markers-beyond-inline-shape-limits-are-still-checked-and-remapped
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          entry (first (:entries (store/commit! database sid
                                                {::command/entries
                                                 [(message-entry :user "Deep original")]})))
          marked (read-original database sid (:id entry))
          native {:ordinary (vec (repeat 100000 0))
                  :deep (nth (iterate vector marked) 64)}
          descriptor (retain! database sid native {} :artifact)
          _ (commit-native! database sid descriptor)
          packet (transfer/export-session database sid)
          bad-marked (assoc-in marked [:history/retrieval :source-entry-ids] [(util/id)])
          bad-native (assoc native :deep (nth (iterate vector bad-marked) 64))
          before (store/list-sessions database {})]
      (is (= 2 (:version packet)))
      (is (= "invalid-import" (import-error database (assoc packet :version 1))))
      (is (= "invalid-import" (import-error database (with-native-artifact packet bad-native))))
      (is (= before (store/list-sessions database {})))
      (doseq [copied [(transfer/clone! database sid {})
                      (transfer/fork! database sid {})
                      (transfer/import-session! database packet {})]]
        (let [copied-id (:id copied)
              value (native-result database copied-id (:id (copied-descriptor database copied-id)))
              copied-marked (get-in value (into [:deep] (repeat 64 0)))
              ref (:history/retrieval copied-marked)]
          (is (= (:ordinary native) (:ordinary value)))
          (is (= copied-id (:session-id ref)))
          (is (true? (:available? ref)))
          (is (= (:history/retrieval marked) (:source-reference ref)))
          (is (= "Deep original"
                 (:content (read-original database copied-id (first (:source-entry-ids ref))))))))
      (is (= packet (transfer/export-session database sid)))
      (is (= native (native-result database sid (:id descriptor)))))))

(deftest omitted-pinned-head-alone-makes-a-retrieval-unavailable
  (with-memory-store [database]
    (let [sid (:id (new-session database))
          [shared omitted]
          (:entries (store/commit! database sid
                                   {::command/entries [(message-entry :user "Shared original")
                                                       (message-entry :assistant "Omitted head")]}))
          _ (store/branch! database sid (:id shared) {})
          receipt {:session-id sid :context-entry-ids [(:id shared)]
                   :context-head-entry-id (:id omitted) :source-entry-ids [(:id shared)]
                   :mode :read :available? true}
          descriptor (retain! database sid {:history/retrieval receipt} {} :inline)
          _ (commit-native! database sid descriptor)
          packet (transfer/export-session database sid)]
      (doseq [copied [(transfer/clone! database sid {}) (transfer/fork! database sid {})]]
        (let [copied-id (:id copied)
              value (native-result database copied-id (:id (copied-descriptor database copied-id)))
              ref (:history/retrieval value)
              imported (transfer/import-session! database (transfer/export-session database copied-id) {})
              imported-ref (:history/retrieval
                            (native-result database (:id imported)
                                           (:id (copied-descriptor database (:id imported)))))]
          (is (= copied-id (:session-id ref)))
          (is (= 1 (count (:context-entry-ids ref))))
          (is (= 1 (count (:source-entry-ids ref))))
          (is (false? (:available? ref)))
          (is (not (contains? ref :context-head-entry-id)))
          (is (= receipt (:source-reference ref)))
          (is (false? (:available? imported-ref)))
          (is (not (contains? imported-ref :context-head-entry-id)))
          (is (= receipt (:source-reference imported-ref)))))
      (is (= packet (transfer/export-session database sid))))))
