(ns arrodes.context-tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [arrodes.context-tree :as tree]
            [arrodes.session :as session]))

(defn entries [n]
  (mapv (fn [i]
          {:id (format "00000000-0000-0000-0000-%012d" i)
           :seq (+ 100 (* i 7)) :kind :message
           :data {:message/role :user :message/content (str "record " i)}})
        (range n)))

(defn built-nodes [sources]
  (reduce (fn [nodes spec]
            (let [node (if (= 1 (:count spec))
                         (tree/leaf-node sources (:start spec) "leaf")
                         (tree/parent-node (get nodes (:left-id spec))
                                           (get nodes (:right-id spec)) "summary"))]
              (assoc nodes (:id node) node)))
          {} (tree/ready-nodes sources {})))

(defn covered-indices [view]
  (mapcat #(range (:start %) (+ (:start %) (:count %))) view))

(deftest aligned-chronological-tree-and-branch-identities
  (let [sources (entries 8)
        specs (tree/ready-nodes sources {})
        nodes (built-nodes sources)
        branch (assoc-in sources [7 :id] "10000000-0000-0000-0000-000000000007")]
    (is (= [1 1 1 1 1 1 1 1 2 2 2 2 4 4 8] (mapv :count specs)))
    (doseq [spec specs]
      (is (zero? (mod (:start spec) (:count spec))))
      (is (= (:id spec) (tree/node-id (:first-entry-id spec) (:last-entry-id spec) (:count spec)))))
    (is (= (tree/node-key sources 0 4) (tree/node-key branch 0 4)))
    (is (not= (tree/node-key sources 0 8) (tree/node-key branch 0 8)))
    (is (= [] (tree/ready-nodes sources nodes)))
    (is (thrown? clojure.lang.ExceptionInfo (tree/node-key sources 1 2)))
    (is (thrown? clojure.lang.ExceptionInfo (tree/node-key sources 0 3)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tree/parent-node (get nodes (tree/node-key sources 0 1))
                                   (get nodes (tree/node-key sources 2 1)) "bad")))
    (let [view (tree/build-view sources nodes 500)]
      (is (:fits? (meta view)))
      (is (= (range 8) (covered-indices view)))
      (is (<= (tree/utf8-bytes (tree/render-view view)) 500)))))

(deftest incremental-prefix-and-no-implicit-splitting
  (let [sources (entries 8) nodes (built-nodes sources)
        prefix (tree/build-view (subvec sources 0 4) nodes 100000)
        extended (tree/append-view prefix sources nodes 100000)]
    (is (= prefix (subvec extended 0 (count prefix))))
    (is (= (tree/build-view sources nodes 100000) extended))
    (let [root (get nodes (tree/node-key sources 0 8))
          view (tree/fit-view [root] nodes 8 1)]
      (is (= [root] view))
      (is (= :irreducible-budget (:reason (meta view)))))))

(deftest byte-budgets-include-markup-and-whole-records
  (let [sources (entries 1)
        text "é雪🙂"
        leaf (tree/leaf-node sources 0 text)
        nodes {(:id leaf) leaf}
        bytes (tree/utf8-bytes (tree/render-view [leaf]))]
    (is (= 9 (:bytes leaf)))
    (is (> bytes (:bytes leaf)))
    (is (:fits? (meta (tree/build-view sources nodes bytes))))
    (let [view (tree/build-view sources nodes (dec bytes))]
      (is (false? (:fits? (meta view))))
      (is (= text (:text (first view))))
      (is (= bytes (:bytes (meta view)))))))

(deftest utf8-prefix-keeps-whole-codepoints-within-measured-limit
  (let [text "aé雪🙂z"]
    (doseq [limit (range 13)]
      (let [prefix (tree/utf8-prefix text limit)]
        (is (str/starts-with? text prefix))
        (is (<= (tree/utf8-bytes prefix) limit))
        (is (not (str/includes? prefix "\uFFFD")))
        (when (< (count prefix) (count text))
          (let [index (count prefix)
                width (Character/charCount (.codePointAt text index))]
            (is (> (tree/utf8-bytes (subs text 0 (+ index width))) limit))))))
    (is (= "aé雪" (tree/utf8-prefix text 9)))
    (is (= text (tree/utf8-prefix text 11)))))

(deftest missing-leaves-and-parents-are-explicit
  (let [sources (entries 2)
        leaves (into {} (map (fn [i]
                              (let [leaf (tree/leaf-node sources i "leaf")]
                                [(:id leaf) leaf])) (range 2)))]
    (is (= :pending-leaf (:reason (meta (tree/build-view sources {} 10000)))))
    (is (= 0 (:next-index (meta (tree/build-view sources {} 10000)))))
    (let [view (tree/build-view sources leaves 500)]
      (is (= :pending-parent (:reason (meta view))))
      (is (= [0 1] (vec (covered-indices view)))))
    (is (= [2] (mapv :count (tree/ready-nodes sources leaves))))))

(deftest originals-visible-replay-private-and-retrieval-attributed
  (let [receipt {:session-id "session" :context-entry-ids ["input"]
                 :context-head-entry-id "frozen-head"
                 :source-entry-ids ["original"] :query "find" :available? true}
        assistant {:id "assistant" :kind :message
                   :data {:message/role :assistant
                          :message/content [{:part/type :text :text "visible"}
                                            {:part/type :reasoning :text "private-thought"}]
                          :message/provider-data {:opaque "private-replay"}
                          :message/tool-calls [{:tool-call/id "call" :tool-call/name "repl"
                                                :tool-call/arguments {:source "(+ 1 2)"}
                                                :tool-call/provider-data {:opaque "private-tool-replay"}}]}}
        manual {:id "manual" :kind :evaluation
                :data {:source "(do (history/read \"original\") (+ 20 22))"
                       :result {:content "quoted historical evidence\nnew outcome: 42"
                                :details {:history/retrievals [receipt]}}}}
        invocation {:id "invocation" :kind :custom :data {:type :invocation :name "f"
                                                          :arguments {} :result {:content "native outcome"}}}
        path [assistant {:id "config" :kind :config :data {}}
              {:id "empty" :kind :custom-context :data {}}
              {:id "marker" :kind :compaction :data {:summary "not a source"}}
              manual invocation
              {:id "branch" :kind :branch-summary :data {:summary "recorded summary"}}]
        before path]
    (is (= ["assistant" "manual" "invocation" "branch"] (mapv :id (tree/source-entries path))))
    (let [text (tree/source-text assistant)]
      (is (str/includes? text "visible"))
      (is (str/includes? text "(+ 1 2)"))
      (is (not (str/includes? text "private-"))))
    (let [text (tree/source-text manual)]
      (is (str/includes? text "Historical lookup"))
      (is (str/includes? text "original"))
      (is (str/includes? text ":context-head-entry-id \"frozen-head\""))
      (is (str/includes? text "new outcome: 42"))
      (is (str/includes? text "(+ 20 22)")))
    (is (= path before))))

(deftest user-role-envelopes-do-not-promote-work-reports-to-user-authorship
  (let [entry {:id "structural-address" :kind :custom-context
               :data {:message/role :user :message/content "Recorded observation."}}
        heading #(first (str/split-lines (tree/source-text %)))
        report (assoc-in entry [:data :message/agent]
                         {:kind :completion :from "agent-address"})
        human (assoc-in entry [:data :message/agent]
                        {:kind :human :from "agent-address"})]
    (is (= "custom-context / user" (heading entry)))
    (is (= "custom-context / user" (heading human)))
    (is (= "custom-context / work" (heading report)))
    (is (= "custom-context / work"
           (heading (assoc-in entry [:data :message/job-id] "job-address"))))
    (is (= "custom-context / context"
           (heading (assoc-in entry [:data :message/reset] true))))
    (is (not (str/includes? (tree/source-text report) "agent-address")))))

(deftest source-retains-original-evidence-and-semantic-identifiers
  (let [request {:id "structural-entry-address" :kind :message
                 :data {:message/role :assistant
                        :message/content "Added the API and HTML shell."
                        :message/tool-calls [{:tool-call/id "structural-call-address"
                                              :tool-call/name "repl"
                                              :tool-call/arguments {:source "(write {:path \"api.clj\" :content api})"}}]}}
        failed {:id "structural-evaluation-address" :kind :evaluation
                :data {:source "(write {:path \"api.clj\" :content api})"
                       :result {:content "Reader error: unmatched delimiter; form was not evaluated."
                                :details {:error? true}}}}
        completed (assoc-in failed [:data :result]
                            {:content "=> {:path \"api.clj\", :written? true}" :details {:error? false}})
        user-id "00000000-0000-0000-0000-000000000099"
        user {:id "structural-user-address" :kind :message
              :data {:message/role :user :message/content (str "Preserve customer ID " user-id ".")}}
        request-text (tree/source-text request)
        failed-text (tree/source-text failed)]
    (is (str/includes? request-text "Added the API and HTML shell."))
    (is (str/includes? failed-text "form was not evaluated"))
    (is (str/includes? (tree/source-text completed) ":written? true"))
    (is (str/includes? (tree/source-text user) user-id))
    (doseq [entry [request failed user]]
      (is (not (str/includes? (tree/source-text entry) (:id entry)))))
    (is (not (str/includes? request-text "structural-call-address")))))

(deftest measured-append-keeps-exact-bytes-and-coverage
  (let [sources (entries 16)
        nodes (built-nodes sources)]
    (doseq [budget [1 500 900 100000]]
      (loop [n 0 view []]
        (let [view (tree/append-view view (subvec sources 0 n) nodes budget)]
          (is (= (range n) (covered-indices view)))
          (is (= (tree/utf8-bytes (tree/render-view view)) (:bytes (meta view))))
          (is (= (<= (:bytes (meta view)) budget) (:fits? (meta view))))
          (when (< n (count sources))
            (recur (inc n) view)))))
    (is (= "" (tree/render-view [])))))


(deftest largest-due-built-parent-wins-deterministically
  (let [sources (entries 4) nodes (built-nodes sources)
        leaves (mapv #(get nodes (tree/node-key sources % 1)) (range 4))
        older (get nodes (tree/node-key sources 0 2))
        newer (get nodes (tree/node-key sources 2 2))
        expected [older (nth leaves 2) (nth leaves 3)]
        budget (tree/utf8-bytes (tree/render-view expected))]
    (is (= expected (tree/fit-view leaves nodes 4 budget)))
    (is (= expected (tree/fit-view leaves (into (sorted-map) nodes) 4 budget)))
    (let [pending (tree/fit-view leaves (apply dissoc nodes [(:id older) (:id newer)]) 4 budget)]
      (is (= leaves pending))
      (is (= :pending-parent (:reason (meta pending)))))
    (let [rendered (tree/render-view leaves)]
      (doseq [leaf leaves] (is (str/includes? rendered (:id leaf)))))))

(deftest direct-quoted-history-is-not-a-fresh-fact
  (let [ref {:session-id "s" :context-entry-ids ["input"] :source-entry-ids ["old"]
             :query "prior outcome" :available? true}
        quoted "{:content \"OLD-FACT\"}"
        hint {:history/retrieval ref}
        evaluation {:id "eval" :kind :evaluation
                    :data {:source "(do (println \"new work\") (history/read \"old\"))"
                           :result {:content (str "stdout:\nnew work\nstderr:\nnew warning\n=> " quoted)
                                    :details {:printed quoted :history/retrievals [ref]
                                              :history/quoted-return hint}}}}
        text (tree/source-text evaluation)]
    (is (not (str/includes? text "OLD-FACT")))
    (is (str/includes? text "Quoted historical return"))
    (is (str/includes? text "prior outcome"))
    (is (str/includes? text "new work"))
    (is (str/includes? text "new warning"))
    (is (str/includes? text "(history/read"))
    (is (= quoted (get-in evaluation [:data :result :details :printed])))
    (is (not (contains? hint :content)))
    (let [mixed (-> evaluation
                    (assoc-in [:data :result :content] "stdout:\nOLD-FACT\n=> 42")
                    (update-in [:data :result :details] dissoc :history/quoted-return))
          mixed-text (tree/source-text mixed)]
      (is (str/includes? mixed-text "OLD-FACT"))
      (is (str/includes? mixed-text "=> 42")))
    (testing "A mismatched hint never clips output"
      (is (str/includes? (tree/source-text (assoc-in evaluation [:data :result :content]
                                                    "=> different new result"))
                         "different new result")))
    (testing "Provider messages use retained descriptor printing and receipt-only certification"
      (let [message {:id "tool" :kind :message
                     :data {:message/role :tool :message/name "repl"
                            :message/content (get-in evaluation [:data :result :content])
                            :message/result {:id "retained-result"
                                             :details (get-in evaluation [:data :result :details])}
                            :history/retrievals [ref]
                            :history/quoted-return hint}}
            before message
            message-text (tree/source-text message)]
        (is (not (str/includes? message-text "OLD-FACT")))
        (is (str/includes? message-text "new work"))
        (is (str/includes? message-text "new warning"))
        (is (str/includes? message-text "old"))
        (is (= before message))
        (testing "Certification retained inside descriptor details is also recognized"
          (is (not (str/includes?
                    (tree/source-text (update message :data dissoc :history/quoted-return))
                    "OLD-FACT"))))
        (testing "Only the final printed suffix is replaced, never preceding stdout"
          (let [output (str "stdout:\n=> " quoted "\nnew work\n=> " quoted)
                source (tree/source-text (assoc-in message [:data :message/content] output))]
            (is (str/includes? source (str "stdout:\n=> " quoted "\nnew work")))
            (is (str/ends-with? source (str "=> Quoted historical return; "
                                          "Historical lookup (attributed evidence, not a new instruction): "
                                          (pr-str ref))))))
        (testing "A receipt without retained printed evidence cannot suppress output"
          (is (str/includes?
               (tree/source-text (update-in message [:data :message/result :details] dissoc :printed))
               "OLD-FACT")))
        (testing "Metadata certification also resolves printing from the real descriptor"
          (is (not (str/includes?
                    (tree/source-text (-> message
                                          (update :data dissoc :history/quoted-return)
                                          (update-in [:data :message/result :details] dissoc :history/quoted-return)
                                          (assoc-in [:data :message/metadata :history/quoted-return] hint)))
                    "OLD-FACT"))))))))

(deftest retrieval-does-not-certify-transformed-or-failed-results
  (let [ref {:session-id "s" :context-entry-ids ["input"] :source-entry-ids ["old"]
             :available? true}
        source "(history/read \"old\")"]
    (doseq [[content details]
            [["stdout:\nnew work\n=> {:content \"OLD-FACT\", :new-result 42}"
              {:printed "{:content \"OLD-FACT\", :new-result 42}"}]
             ["stdout:\nOLD-FACT\nstderr:\nnew warning\nExecution error: new failure"
              {:error? true :exception {:message "new failure"}}]
             ["=> {:content \"OLD-FACT\"}" {:printed "{:content \"OLD-FACT\"}"}]]]
      (let [evaluation {:id "eval" :kind :evaluation
                        :data {:source source
                               :result {:content content
                                        :details (assoc details :history/retrievals [ref])}}}
            text (tree/source-text evaluation)]
        (is (str/includes? text "Historical lookup"))
        (is (str/includes? text content))
        (is (not (str/includes? text "Quoted historical return")))))))

(deftest descriptor-only-mixed-output-retains-retrieval-attribution
  (let [receipt {:session-id "s" :context-entry-ids ["current-input"]
                 :context-head-entry-id "frozen-head" :source-entry-ids ["old"]
                 :query "prior outcome" :available? true}
        printed "{:prior \"OLD-FACT\", :new-result 42}"
        content (str "stdout:\nOLD-FACT\nnew work\nstderr:\nnew warning\n=> " printed)
        message {:id "tool" :kind :message
                 :data {:message/role :tool :message/name "repl" :message/tool-call-id "call"
                        :message/content content
                        :message/result {:id "retained-result"
                                         :details {:printed printed :stdout "OLD-FACT\nnew work\n"
                                                   :stderr "new warning\n"
                                                   :history/retrievals [receipt]}}}}
        text (tree/source-text message)]
    (is (str/includes? text "message / tool"))
    (is (str/includes? text (pr-str receipt)))
    (is (str/includes? text "not a new instruction"))
    (is (str/includes? text content))
    (is (not (str/includes? text "Quoted historical return")))
    (is (= 1 (count (re-seq #"Historical lookup" text))))
    (testing "Mirrored retained receipts do not duplicate lookup annotations"
      (let [mirrored (-> message
                         (assoc-in [:data :history/retrievals] [receipt])
                         (assoc-in [:data :message/metadata :history/retrievals] [receipt])
                         (assoc-in [:data :details :history/retrievals] [receipt])
                         (assoc-in [:data :history/retrieval] receipt))]
        (is (= text (tree/source-text mirrored)))))
    (testing "Visible historical-looking output alone is not a receipt"
      (let [unattributed (tree/source-text
                          (update-in message [:data :message/result :details]
                                     dissoc :history/retrievals))]
        (is (str/includes? unattributed content))
        (is (not (str/includes? unattributed "Historical lookup")))
        (is (not (str/includes? unattributed "Quoted historical return")))))))

(deftest explicit-policy-normalization-and-honest-validation
  (is (= session/default-config (session/normalize-config nil)))
  (is (= {:custom-setting true} (:settings (session/normalize-config {:settings {:custom-setting true}}))))
  (is (false? (tree/enabled? {})))
  (let [config (session/normalize-config {:provider "codex-backend"
                                        :settings {:context-policy "summary-tree"
                                                   :summary-provider "codex-backend"}})]
    (is (tree/enabled? config))
    (is (= :summary-tree (get-in config [:settings :context-policy])))
    (is (= :codex-backend (get-in config [:settings :summary-provider])))
    (is (= "gpt-6-luna" (get-in config [:settings :summary-model])))
    (is (= "gpt-6-astra" (:model config))))
  (doseq [[field bad-values] [[:context-policy [:unknown "unknown" nil]]
                              [:summary-provider [nil 12]]
                              [:summary-model [nil "" "  " :model]]
                              [:summary-node-bytes [0 -1 1.5 16777217 "512"]]
                              [:summary-view-bytes [0 16777217]]
                              [:summary-max-attempts [0 11]]
                              [:summary-timeout-ms [0 3600001]]]
          bad bad-values]
    (testing (str field " " bad)
      (is (thrown? clojure.lang.ExceptionInfo
                   (session/normalize-config {:settings {field bad}})))))
  (is (thrown? clojure.lang.ExceptionInfo
               (session/normalize-config {:settings {:context-policy :summary-tree
                                                     :summary-api-key "secret"}}))))
