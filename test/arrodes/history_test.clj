(ns arrodes.history-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.capabilities :as capabilities]
            [arrodes.context-tree :as tree]
            [arrodes.history :as history]
            [arrodes.platform :as util]
            [arrodes.provider-repl :as provider-repl]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.context-tree :as tree-store]
            [arrodes.store.db :as db]
            [arrodes.store.transfer :as transfer]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private config
  {:provider :openai :model "fixture-model" :thinking :none :tools :all
   :instructions "Offline native history fixture" :settings {}})

(defmacro with-history [[database registry sid] & body]
  `(let [~database (db/open! {:memory? true})
         session# (store/create-session! ~database {:name "History" :cwd (System/getProperty "java.io.tmpdir")
                                                   :config config})
         ~sid (:id session#)
         ~registry (history/install! (capabilities/create! {:store ~database :session-id ~sid
                                                           :cwd (:cwd session#) :config config}))]
     (try ~@body (finally (capabilities/close! ~registry) (db/close! ~database)))))

(defn- messages! [database sid texts]
  (:entries (store/commit! database sid
                          {::command/entries (mapv (fn [[role text]]
                                                     {:kind :message :data {:message/role role :message/content text}})
                                                   texts)})))

(defn- evaluate [registry source]
  (capabilities/evaluate! registry source {}))

(defn- read-source [entry opts]
  (str "(history/read " (pr-str (:id entry)) " " (pr-str opts) ")"))

(defn- cache! [database sid]
  (let [entries (tree/source-entries (store/active-path database sid))]
    (doseq [{:keys [start count left-id right-id]} (tree/ready-nodes entries {})]
      (let [node (if (= count 1)
                   (tree/leaf-node entries start (tree/source-text (nth entries start)))
                   (tree/parent-node (tree-store/node database sid left-id)
                                     (tree-store/node database sid right-id)
                                     (str "Completed summary " start "/" count)))]
        (tree-store/put-node! database sid node)))
    entries))

(defn- commit-evaluation! [database sid source result]
  (store/commit! database sid
                 {::command/entries [{:kind :evaluation
                                      :data {:source source :result (dissoc result :value)}}
                                     {:kind :message :data (provider-repl/result-message result)}]}))

(deftest native-exact-originals-and-contextual-receipts
  (with-history [database registry sid]
    (let [[old _ input] (messages! database sid [[:user "The exact original: λ."]
                                                [:assistant "Prior answer"] [:user "Why did we decide?"]])
          operation (util/id)
          result (capabilities/evaluate! registry (read-source old {:query "decision reason"})
                                         {:id "lookup-call" :context {:operation-id operation
                                                                      :history/context-entry-ids [(:id input)]
                                                                      :history/head (:id old)}})
          native (:value result)
          receipt (:history/retrieval native)]
      (is (map? native))
      (is (= "The exact original: λ." (:content native)))
      (is (= :user (:role native)))
      (is (= :message (:kind native)))
      (is (= (:created-at old) (:created-at native)))
      (is (:complete? native))
      (is (= sid (:session-id receipt)))
      (is (= [(:id input)] (:context-entry-ids receipt)))
      (is (= [(:id old)] (:source-entry-ids receipt)))
      (is (= (:id old) (:context-head-entry-id receipt)))
      (is (= "decision reason" (:query receipt)))
      (is (= :read (:mode receipt)))
      (is (= operation (:operation-id receipt)))
      (is (= "lookup-call" (:call-id receipt)))
      (is (= [receipt] (get-in result [:details :history/retrievals])))
      (is (= {:history/retrieval receipt} (get-in result [:details :history/quoted-return])))
      (is (= [receipt] (:history/retrievals (provider-repl/result-message result))))
      (is (= (get-in result [:details :history/quoted-return])
             (:history/quoted-return (provider-repl/result-message result))))
      (is (= receipt (get-in (artifacts/result database sid (get-in result [:result :id]))
                            [:details :history/retrievals 0])))
      (is (= {:created-at (:created-at old) :time-unit :epoch-milliseconds}
             (dissoc (:value (evaluate registry (str "(history/date " (pr-str (:id old)) ")")))
                     :history/retrieval)))
      (is (= [(:id input)] (get-in (evaluate registry (read-source old {}))
                                   [:details :history/retrievals 0 :context-entry-ids])))
      (is (= (:id input) (get-in (evaluate registry (read-source old {}))
                                [:details :history/retrievals 0 :context-head-entry-id])))
      (let [help (:value (evaluate registry "(help {:group \"history\"})"))]
        (is (= #{"history/view" "history/zoom" "history/read" "history/date"}
               (set (map :name (:entries help))))))
      (is (= ["[entry-id]" "[entry-id opts]"]
             (:arities (:value (evaluate registry "(help 'history/read)")))))
      (is (= ["repl"] (mapv #(get-in % [:function :name])
                            (:request/tools (provider-repl/request {}))))))))

(deftest parent-children-original-traversal-and-bounded-frontier
  (with-history [database registry sid]
    (let [entries (messages! database sid [[:user "One"] [:assistant "Two"] [:user "Three"] [:assistant "Four"]])
          before (evaluate registry "(history/view)")]
      (is (= [] (get-in before [:value :nodes])))
      (is (false? (get-in before [:value :ready?])))
      (is (= :pending-leaf (get-in before [:value :reason])))
      (is (empty? (tree-store/nodes database sid)))
      (cache! database sid)
      (let [parent (:value (evaluate registry "(history/zoom 0 4)"))
            left (first (:children parent))
            second-level (:value (evaluate registry (str "(history/zoom " (pr-str (:id left)) ")")))
            leaf (first (:children second-level))
            original (:value (evaluate registry (str "(history/zoom " (pr-str (:id leaf)) ")")))
            page (:value (evaluate registry "(history/view {:limit 1})"))
            next-page (:value (evaluate registry (get-in page [:next :view])))]
        (is (= [2 2] (mapv :count (:children parent))))
        (is (= [0 2] (mapv :start (:children parent))))
        (is (= 4 (get-in parent [:history/retrieval :source-count])))
        (is (= [] (get-in parent [:history/retrieval :source-entry-ids])))
        (is (= (:id (first entries)) (get-in parent [:history/retrieval :source-first-entry-id])))
        (is (= (:id (last entries)) (get-in parent [:history/retrieval :source-last-entry-id])))
        (is (= [1 1] (mapv :count (:children second-level))))
        (is (= "One" (:content original)))
        (is (= [(:id (first entries))] (get-in original [:history/retrieval :source-entry-ids])))
        (is (:ready? page))
        (is (:fits? page))
        (is (= 1 (:next-offset page)))
        (is (= [1] (mapv :start (:nodes next-page))))
        (is (:error? (evaluate registry "(history/zoom 1 2)")))
        (is (:error? (evaluate registry "(history/zoom 0 8)")))))))

(deftest original-and-summary-pages-preserve-unicode-and-explicit-continuation
  (with-history [database registry sid]
    (let [[old] (messages! database sid [[:user "α😀β😀γ"]])
          first-page (:value (evaluate registry (read-source old {:limit 4})))
          pages (loop [page first-page chunks []]
                  (let [chunks (conj chunks (:content page))]
                    (if (:complete? page) chunks
                        (recur (:value (evaluate registry (get-in page [:next :read]))) chunks))))]
      (is (= "α😀β😀γ" (apply str pages)))
      (is (false? (:complete? first-page)))
      (is (= 1 (:next-offset first-page)))
      (is (<= (:bytes first-page) 4))
      (is (:error? (evaluate registry (read-source old {:offset 2 :limit 4}))))
      (is (:error? (evaluate registry (read-source old {:offset 100}))))
      (is (:error? (evaluate registry (read-source old {:limit 0}))))
      (is (:error? (evaluate registry (read-source old {:query 42}))))
      (let [long-text (apply str (repeat 2000 "λ"))
            [second-entry] (messages! database sid [[:assistant long-text]])
            entries (tree/source-entries (store/active-path database sid))
            left (tree-store/put-node! database sid (tree/leaf-node entries 0 "Short summary"))
            right (tree-store/put-node! database sid (tree/leaf-node entries 1 long-text))
            parent (tree-store/put-node! database sid (tree/parent-node left right long-text))
            page (:value (evaluate registry (str "(history/zoom " (pr-str (:id parent)) " {:limit 4})")))]
        (is (= "λλ" (get-in page [:node :text])))
        (let [singleton-page (:value (evaluate registry (get-in page [:children 1 :next :summary])))]
          (is (= :zoom (get-in singleton-page [:history/retrieval :mode])))
          (is (= 1 (get-in singleton-page [:history/retrieval :source-count])))
          (is (= [] (:children singleton-page)))
          (is (= 512 (get-in singleton-page [:node :offset])))
          (is (= (apply str (repeat 512 "λ")) (get-in singleton-page [:node :text]))))
        (is (= 2 (get-in page [:node :next-offset])))
        (is (false? (get-in page [:node :complete?])))
        (is (<= (get-in page [:children 1 :bytes]) 1024))
        (is (= long-text (:content (:value (evaluate registry (read-source second-entry {}))))))
        (is (= "λλ" (get-in (:value (evaluate registry (get-in page [:node :next :summary])))
                              [:node :text])))))))

(deftest cancellation-session-scope-and-branch-membership-are-enforced
  (with-history [database registry sid]
    (let [[first-entry removed] (messages! database sid [[:user "Kept"] [:assistant "Not active"]])
          _ (cache! database sid)
          old-node (tree/node-key (tree/source-entries (store/active-path database sid)) 0 2)
          other (store/create-session! database {:name "Other" :cwd (:cwd registry) :config config})
          [foreign] (messages! database (:id other) [[:user "Private"]])]
      (is (:error? (evaluate registry (read-source foreign {}))))
      (is (= "history-unavailable"
             (:error/code (ex-data (try (history/view) (catch clojure.lang.ExceptionInfo e e))))))
      (is (= "history-unavailable"
             (:error/code (ex-data (try (binding [capabilities/*invocation-context* {:registry registry :session-id (:id other)}]
                                         (history/view))
                                       (catch clojure.lang.ExceptionInfo e e))))))
      (is (:error? (capabilities/evaluate! registry (read-source first-entry {}) {:cancelled? (atom true)})))
      (store/branch! database sid (:id first-entry) {})
      (messages! database sid [[:assistant "Replacement"]])
      (is (:error? (evaluate registry (read-source removed {}))))
      (is (:error? (evaluate registry (str "(history/zoom " (pr-str old-node) ")"))))
      (is (:error? (capabilities/evaluate! registry "(history/view)" {:context {:history/head (:id removed)}})))
      (is (= [] (get-in (capabilities/evaluate! registry "(history/view)" {:context {:history/head nil}})
                        [:value :nodes])))
      (is (not (contains? (get-in (capabilities/evaluate! registry "(history/view)"
                                                        {:context {:history/head nil}})
                                  [:value :history/retrieval])
                           :context-head-entry-id)))
      (is (:error? (capabilities/evaluate! registry "(history/view)"
                                           {:context {:history/context-entry-ids [(:id foreign)]}})))
      (is (:error? (capabilities/evaluate! registry "(history/view)" {:context {:operation-id "not-a-uuid"}})))
      (is (:error? (capabilities/evaluate! registry "(history/view)" {:id " "})))
      (is (:error? (capabilities/evaluate! registry "(history/view)" {:id 42}))))))

(deftest mixed-work-failures-and-joined-lookups-retain-all-evidence
  (with-history [database registry sid]
    (let [[old input] (messages! database sid [[:assistant "Quoted old output"] [:user "New work"]])
          source (str "(def effects (atom 0))\n(let [old " (read-source old {:query "old output"})
                      "] (swap! effects inc) (prn \"new stdout\")"
                      " (binding [*out* *err*] (prn \"new stderr\")) {:old old :answer 42})")
          result (evaluate registry source)]
      (is (= 42 (get-in result [:value :answer])))
      (is (= "Quoted old output" (get-in result [:value :old :content])))
      (is (str/includes? (get-in result [:details :stdout]) "new stdout"))
      (is (str/includes? (get-in result [:details :stderr]) "new stderr"))
      (is (= 1 (count (get-in result [:details :history/retrievals]))))
      (is (nil? (get-in result [:details :history/quoted-return])))
      (is (= 1 (:value (evaluate registry "@effects"))))
      (let [direct (evaluate registry (str "(swap! effects inc) (prn \"also new\") " (read-source old {})))]
        (is (get-in direct [:details :history/quoted-return]))
        (is (str/includes? (get-in direct [:details :stdout]) "also new"))
        (is (= 3 (get-in direct [:details :forms])))
        (is (= 2 (:value (evaluate registry "@effects")))))
      (let [failure (evaluate registry (str (read-source old {})
                                           " (def completed 42) (prn \"before failure\")"
                                           " (throw (ex-info \"expected failure\" {:specific :kept}))"))]
        (is (:error? failure))
        (is (= :kept (get-in failure [:details :data :specific])))
        (is (= 3 (get-in failure [:details :data :completed-forms])))
        (is (str/includes? (get-in failure [:details :data :stdout]) "before failure"))
        (is (= 1 (count (get-in failure [:details :history/retrievals]))))
        (is (nil? (get-in failure [:details :history/quoted-return])))
        (is (= 42 (:value (evaluate registry "completed")))))
      (let [joined (evaluate registry (str "(let [a (future " (read-source old {})
                                          ") b (future " (read-source input {}) ")] [@a @b])"))]
        (is (= 2 (count (get-in joined [:details :history/retrievals]))))
        (is (= #{(:id old) (:id input)}
               (set (mapcat :source-entry-ids (get-in joined [:details :history/retrievals]))))))
      (is (nil? (get-in (evaluate registry "(+ 1 2)") [:details :history/retrievals])))
      (let [isolated (evaluate registry
                               (str "(binding [arrodes.capabilities/*invocation-context*"
                                    " (assoc arrodes.capabilities/*invocation-context* :job-id \"other-job\")] "
                                    (read-source old {}) ")"))]
        (is (map? (:value isolated)))
        (is (nil? (get-in isolated [:details :history/retrievals])))))))

(deftest native-history-does-not-replay-code-reset-or-consume-owned-work
  (with-history [database registry sid]
    (evaluate registry "(def live (atom 17))")
    (let [descriptor (artifacts/put-result! database sid
                                             {:kind :inline :value 42 :content "Original descriptor"
                                              :details {} :available? true})
          entry (first (:entries (store/commit! database sid
                                               {::command/entries [{:kind :evaluation
                                                                    :data {:source "(swap! live inc) (throw (Exception.))"
                                                                           :result {:content "Recorded original result"
                                                                                    :result descriptor}}}]})))
          entries-before (store/entries database sid)
          results-before (:items (artifacts/result-page database sid {}))
          original (:value (evaluate registry (read-source entry {})))]
      (is (str/includes? (:content original) "Recorded original result"))
      (is (str/includes? (:content original) "(swap! live inc)"))
      (is (= {:source-session-id sid :source-entry-id (:id entry)
              :descriptor (select-keys descriptor [:id :kind :available? :artifact-id])}
             (:result-reference original)))
      (is (= 17 (:value (evaluate registry "@live"))))
      (is (= entries-before (store/entries database sid)))
      (is (empty? (tree-store/nodes database sid)))
      (is (< (count results-before) (count (:items (artifacts/result-page database sid {}))))))))

(deftest invocation-owned-retrievals-use-the-existing-capability-retention
  (with-history [database registry sid]
    (let [[old] (messages! database sid [[:user "Original"]])]
      (capabilities/register! registry {:name "lookup" :owner "fixture" :permission :read :execution :parallel
                                        :description "Native lookup composition"
                                        :parameters {:type "object" :properties {}}
                                        :fn (fn [_] (history/read (:id old)))})
      (let [result (capabilities/invoke! registry {:id "direct-call" :name "lookup" :arguments {}} {})]
        (is (= "Original" (get-in result [:value :content])))
        (is (= "direct-call" (get-in result [:details :history/retrievals 0 :call-id])))
        (is (= 1 (count (get-in result [:details :history/retrievals]))))))))

(deftest bounded-receipts-survive-real-retention-and-session-transfer
  (with-history [database registry sid]
    (let [[old _] (messages! database sid [[:assistant "Original bounded evidence"] [:user "New bounded work"]])
          repeated-source (str "(def repetition-effects (atom 0)) "
                               "(dotimes [_ 10001] (history/date " (pr-str (:id old))
                               ") (swap! repetition-effects inc)) "
                               "(prn \"new repeated output\") {:answer 42 :effects @repetition-effects}")
          cardinal-source (str "(dotimes [n 256] (history/read " (pr-str (:id old))
                               " {:query (str n)})) (prn \"new cardinal output\") :cardinal-done")
          byte-source (str "(dotimes [n 16] (history/read " (pr-str (:id old))
                           " {:query (str (apply str (repeat 4000 \"λ\")) n)})) :byte-done")
          oversized-source (str "(history/date " (pr-str (:id old)) ")")
          repeated (evaluate registry repeated-source)
          cardinal (evaluate registry cardinal-source)
          byte-bounded (evaluate registry byte-source)
          oversized (capabilities/evaluate! registry oversized-source {:id (apply str (repeat 50000 "c"))})
          results [repeated cardinal byte-bounded oversized]
          sources [repeated-source cardinal-source byte-source oversized-source]]
      (is (= {:answer 42 :effects 10001} (:value repeated)))
      (is (= 10001 (:value (evaluate registry "@repetition-effects"))))
      (is (str/includes? (:content repeated) "new repeated output"))
      (is (= :cardinal-done (:value cardinal)))
      (is (str/includes? (:content cardinal) "new cardinal output"))
      (is (= :byte-done (:value byte-bounded)))
      (is (= (:created-at old) (get-in oversized [:value :created-at])))
      (is (= 50000 (count (get-in oversized [:value :history/retrieval :call-id]))))
      (is (nil? (meta (:value oversized))))
      (is (nil? (get-in oversized [:details :history/quoted-return])))
      (let [summary (get-in repeated [:details :history/retrieval-summary])]
        (is (= 10001 (:calls summary)))
        (is (= 1 (:retained-associations summary)))
        (is (= 10000 (:coalesced-calls summary)))
        (is (zero? (:omitted-calls summary)))
        (is (false? (:all-calls-recorded? summary))))
      (doseq [result results]
        (let [refs (get-in result [:details :history/retrievals])
              summary (get-in result [:details :history/retrieval-summary])
              retained (artifacts/result database sid (get-in result [:result :id]))
              message (provider-repl/result-message result)]
          (is (false? (:error? result)))
          (is (vector? refs))
          (is (= refs (get-in retained [:details :history/retrievals])))
          (is (= refs (:history/retrievals message)))
          (is (= summary (:history/retrieval-summary message)))
          (is (= (:calls summary) (+ (count refs) (:coalesced-calls summary) (:omitted-calls summary))))
          (is (= (:retained-bytes summary) (tree/utf8-bytes (pr-str refs))))
          (is (<= (count refs) (:max-associations summary) 64))
          (is (<= (:retained-bytes summary) (:max-bytes summary) 32768))))
      (is (pos? (get-in cardinal [:details :history/retrieval-summary :omitted-calls])))
      (is (pos? (get-in byte-bounded [:details :history/retrieval-summary :omitted-calls])))
      (is (< (get-in byte-bounded [:details :history/retrieval-summary :retained-associations]) 16))
      (is (= [] (get-in oversized [:details :history/retrievals])))
      (is (= 1 (get-in oversized [:details :history/retrieval-summary :omitted-calls])))
      (is (= 1 (get-in oversized [:details :history/retrieval-summary :oversized-calls])))
      (doseq [[source result] (map vector sources results)]
        (commit-evaluation! database sid source result))
      (let [exported (transfer/export-session database sid)
            copies [(transfer/fork! database sid {}) (transfer/clone! database sid {})
                    (transfer/import-session! database exported {})]]
        (is (= 2 (:version exported)))
        (doseq [copy copies]
          (let [copy-id (:id copy)
                copied-registry (history/install! (capabilities/create! {:store database :session-id copy-id
                                                                        :cwd (:cwd copy) :config config}))]
            (try
              (let [evaluations (filter #(= :evaluation (:kind %)) (store/entries database copy-id))
                    copied-messages (filter #(get-in % [:data :history/retrieval-summary])
                                            (filter #(= :message (:kind %)) (store/entries database copy-id)))]
                (is (= 4 (count evaluations)))
                (is (= 4 (count copied-messages)))
                (doseq [[original evaluation] (map vector results evaluations)]
                  (let [result (get-in evaluation [:data :result])
                        refs (get-in result [:details :history/retrievals])
                        summary (get-in result [:details :history/retrieval-summary])
                        retained (artifacts/result database copy-id (get-in result [:result :id]))
                        native (capabilities/result-value copied-registry (get-in result [:result :id]))
                        ref (or (first refs) (:history/retrieval native))]
                    (is (vector? refs))
                    (is (= refs (get-in retained [:details :history/retrievals])))
                    (is (= (:content original) (:content result)))
                    (is (= (get-in original [:details :history/retrieval-summary]) summary))
                    (is (= (if (:history/retrieval native)
                             (dissoc (:value original) :history/retrieval) (:value original))
                           (if (:history/retrieval native) (dissoc native :history/retrieval) native)))
                    (is (= copy-id (:session-id ref)))
                    (is (not= (:id old) (first (:source-entry-ids ref))))
                    (is (= "Original bounded evidence"
                           (get-in (evaluate copied-registry
                                             (str "(history/read " (pr-str (first (:source-entry-ids ref))) ")"))
                                   [:value :content])))))
                (doseq [entry copied-messages]
                  (is (vector? (get-in entry [:data :history/retrievals])))
                  (is (false? (get-in entry [:data :history/retrieval-summary :all-calls-recorded?]))))
                (doseq [entry (concat evaluations copied-messages)]
                  (let [past (:value (evaluate copied-registry (read-source entry {})))
                        refs (:history/retrievals past)
                        page (:history/retrieval-page past)
                        recorded (or (get-in entry [:data :history/retrievals])
                                     (get-in entry [:data :result :details :history/retrievals]))
                        summary (or (get-in entry [:data :history/retrieval-summary])
                                    (get-in entry [:data :result :details :history/retrieval-summary]))]
                    (is (vector? refs))
                    (is (= summary (:history/retrieval-summary past)))
                    (is (= [(:id entry)] (get-in past [:history/retrieval :source-entry-ids])))
                    (is (= (count recorded) (:total-associations page)))
                    (is (= (- (count recorded) (count refs)) (:omitted-associations page)))
                    (is (<= (tree/utf8-bytes (pr-str refs)) 32768))
                    (when (= 16 (:calls summary))
                      (is (pos? (:omitted-associations page))))
                    (when-let [source-id (first (:source-entry-ids (first refs)))]
                      (is (= "Original bounded evidence"
                             (get-in (evaluate copied-registry (str "(history/read " (pr-str source-id) ")"))
                                     [:value :content]))))))
                (is (nil? (:value (evaluate copied-registry "(resolve 'repetition-effects)")))))
              (finally (capabilities/close! copied-registry)))))))))

(deftest bounded-identity-coverage-never-suppresses-uncertified-native-output
  (with-history [database registry sid]
    (let [[old] (messages! database sid [[:user "Exact old text stays visible when uncertified"]])
          fill (str "(dotimes [n 256] (history/read " (pr-str (:id old)) " {:query (str n)})) ")
          earlier (evaluate registry
                            (str fill "(let [old " (read-source old {:query "untracked bound value"})
                                 "] " (read-source old {:query "later observed return"})
                                 " (prn \"still new output\") old)"))
          direct (evaluate registry (str fill "(prn \"new before direct return\") "
                                         (read-source old {:query "final direct return"})))]
      (is (= "Exact old text stays visible when uncertified" (get-in earlier [:value :content])))
      (is (= "untracked bound value" (get-in earlier [:value :history/retrieval :query])))
      (is (nil? (meta (:value earlier))))
      (is (nil? (get-in earlier [:details :history/quoted-return])))
      (is (false? (get-in earlier [:details :history/retrieval-summary :return-identities-complete?])))
      (is (str/includes? (:content earlier) "still new output"))
      (is (str/includes? (:content earlier) "Exact old text stays visible when uncertified"))
      (is (= (get-in direct [:value :history/retrieval])
             (get-in direct [:details :history/quoted-return :history/retrieval])))
      (is (not-any? #(= "final direct return" (:query %)) (get-in direct [:details :history/retrievals])))
      (is (str/includes? (:content direct) "new before direct return"))
      (is (str/includes? (tree/source-text {:id (util/id) :kind :message
                                           :data (provider-repl/result-message earlier)})
                         "Exact old text stays visible when uncertified"))
      (is (str/includes? (tree/source-text {:id (util/id) :kind :message
                                           :data (provider-repl/result-message direct)})
                         "new before direct return")))))

(deftest bounds-preserve-partial-failures-and-joined-invocation-isolation
  (with-history [database registry sid]
    (let [[old] (messages! database sid [[:user "Joined bounded evidence"]])
          fill (str "(dotimes [n 256] (history/read " (pr-str (:id old)) " {:query (str n)})) ")
          failure (evaluate registry (str fill "(def bounded-completed 42) (prn \"before bounded failure\") "
                                          "(throw (ex-info \"bounded failure\" {:specific :kept}))"))
          joined (evaluate registry
                           (str "(let [a (future (dotimes [n 128] (history/read " (pr-str (:id old))
                                " {:query (str \"a\" n)})) :a)"
                                " b (future (dotimes [n 128] (history/read " (pr-str (:id old))
                                " {:query (str \"b\" n)})) :b)] [@a @b])"))]
      (is (:error? failure))
      (is (= :kept (get-in failure [:details :data :specific])))
      (is (= 3 (get-in failure [:details :data :completed-forms])))
      (is (= 256 (get-in failure [:details :history/retrieval-summary :calls])))
      (is (vector? (get-in failure [:details :history/retrievals])))
      (is (pos? (get-in failure [:details :history/retrieval-summary :omitted-calls])))
      (is (str/includes? (:content failure) "bounded failure"))
      (is (str/includes? (get-in failure [:details :data :stdout]) "before bounded failure"))
      (is (nil? (get-in failure [:details :history/quoted-return])))
      (is (= 42 (:value (evaluate registry "bounded-completed"))))
      (is (= [:a :b] (:value joined)))
      (is (= 256 (get-in joined [:details :history/retrieval-summary :calls])))
      (is (= 256 (+ (count (get-in joined [:details :history/retrievals]))
                    (get-in joined [:details :history/retrieval-summary :omitted-calls]))))
      (is (vector? (get-in joined [:details :history/retrievals])))
      (is (<= (tree/utf8-bytes (pr-str (get-in joined [:details :history/retrievals]))) 32768))
      (is (nil? (get-in (evaluate registry "(+ 1 2)") [:details :history/retrieval-summary]))))))

(deftest observed-receipts-remain-navigable-after-fork-clone-and-import
  (with-history [database registry sid]
    (let [[old input] (messages! database sid [[:assistant "Original transferred evidence"] [:user "Current question"]])
          source (read-source old {:query "transfer evidence"})
          result (evaluate registry source)
          _ (commit-evaluation! database sid source result)
          exported (transfer/export-session database sid)
          copies [(transfer/fork! database sid {}) (transfer/clone! database sid {})
                  (transfer/import-session! database exported {})]]
      (is (= 2 (:version exported)))
      (doseq [copy copies]
        (testing (:id copy)
          (let [copy-id (:id copy)
                copied-registry (history/install! (capabilities/create! {:store database :session-id copy-id
                                                                          :cwd (:cwd copy) :config config}))]
            (try
              (let [evaluation (first (filter #(= :evaluation (:kind %)) (store/entries database copy-id)))
                    descriptor (get-in evaluation [:data :result :result])
                    native (capabilities/result-value copied-registry (:id descriptor))
                    ref (:history/retrieval native)
                    actual-id (first (:source-entry-ids ref))
                    read-result (evaluate copied-registry (str "(history/read " (pr-str actual-id) ")"))
                    hint (get-in evaluation [:data :result :details :history/quoted-return])
                    past-result (evaluate copied-registry (read-source evaluation {}))
                    past (:value past-result)
                    past-ref (first (:history/retrievals past))
                    followed (evaluate copied-registry
                                       (str "(history/read " (pr-str (first (:source-entry-ids past-ref))) ")"))]
                (is (= copy-id (:session-id ref)))
                (is (:available? ref))
                (is (= "Original transferred evidence" (get-in read-result [:value :content])))
                (is (not= (:id old) actual-id))
                (is (not= [(:id input)] (:context-entry-ids ref)))
                (is (= (first (:context-entry-ids ref)) (:context-head-entry-id ref)))
                (is (not= (:id input) (:context-head-entry-id ref)))
                (is (= ref (:history/retrieval hint)))
                (is (= (:history/retrieval (:value result)) (:source-reference ref)))
                (is (= [ref] (:history/retrievals past)))
                (is (= (get-in evaluation [:data :result :details :history/retrieval-summary])
                       (:history/retrieval-summary past)))
                (is (zero? (get-in past [:history/retrieval-page :omitted-associations])))
                (is (= [(:id evaluation)] (get-in past [:history/retrieval :source-entry-ids])))
                (is (= 1 (get-in past-result [:details :history/retrieval-summary :calls])))
                (is (= [(get-in past [:history/retrieval])] (get-in past-result [:details :history/retrievals])))
                (is (str/includes? (:content past) (:id old)))
                (is (str/includes? (:content past) (:content result)))
                (is (= "Original transferred evidence" (get-in followed [:value :content])))
                (is (not (contains? ref :call-id)))
                (is (not (contains? ref :operation-id)))
                (is (empty? (tree-store/nodes database copy-id)))
                (is (:error? (evaluate copied-registry (read-source old {})))))
              (finally (capabilities/close! copied-registry)))))))))
