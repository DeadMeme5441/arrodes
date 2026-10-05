(ns arrodes.repl-ergonomics-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.help :as help]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- evaluate [rt sid & forms]
  (runtime/evaluate! rt sid (binding [*print-meta* true] (str/join "\n" (map pr-str forms)))))

(deftest discovered-functions-compose-with-native-paths-and-search-records
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (evaluate rt sid '(write {:path "src/one.clj" :content "before\nneedle *e\nafter\n"})
                '(write {:path "src/two.clj" :content "second"}))
      (let [result (evaluate rt sid
                            '(def files (find {:path "src" :pattern "*.clj"}))
                            '(def hits (grep {:path "src" :pattern "*e" :literal true :context 1}))
                            '{:texts (mapv #(read {:path (:path %)}) (:entries files))
                              :hits hits :files files :listing (ls {:path "src" :limit 1})
                              :page (read {:path "src/one.clj" :offset 2 :limit 1 :detailed true})
                              :overview (help) :page-of-help (help {:group "coding" :query "grep"})
                              :help (help "grep") :full-help (help 'grep {:detailed? true})
                              :agent-help (help 'agents/start!) :job-help (help "jobs/start!")
                              :workspace-help (help 'workspace)})
            {:keys [texts hits files listing page overview page-of-help help full-help
                    agent-help job-help workspace-help]} (:value result)
            match (first (:matches hits))]
        (is (false? (:error? result)))
        (is (= ["before\nneedle *e\nafter" "second"] texts))
        (is (= 2 (:line match)))
        (is (= :match (:kind match)))
        (is (= [1 3] (mapv :line (:context match))))
        (is (= "one.clj" (last (str/split (:path match) #"/"))))
        (is (:complete? hits))
        (is (:complete? files))
        (is (false? (:complete? listing)))
        (is (= 2 (:total-count listing)))
        (is (= {:text "needle *e" :offset 2 :lines 1 :next-offset 3 :eof? false}
               (dissoc page :path)))
        (is (< (count (pr-str overview)) 1600))
        (is (pos? (:count (first (:groups overview)))))
        (is (= ["grep"] (mapv :name (:entries page-of-help))))
        (is (<= (count (pr-str page-of-help)) 6000))
        (is (= ["[arguments]"] (:arities help)))
        (is (seq (:examples help)))
        (is (contains? full-help :parameters))
        (is (= "agents/start!" (:name agent-help)))
        (is (some #(= "[opts]" %) (:arities agent-help)))
        (is (some #(= "[opts f]" %) (:arities job-help)))
        (is (= "workspace" (:name workspace-help)))
        (is (<= (count (pr-str agent-help)) 6000))
        (is (nil? (:value (evaluate rt sid '(resolve 'registered-tools))))))
      (is (:error? (evaluate rt sid '(find {:pattern "*" :format "legacy"})))))))

(deftest help-pages-live-selected-tools-and-bounds-extension-descriptors
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (runtime/configure! rt sid {:config {:tools ["grep"]}})
      (let [overview (:value (evaluate rt sid '(help)))
            coding (:value (evaluate rt sid '(help {:group "coding"})))
            hidden (evaluate rt sid '(help "read"))]
        (is (= 1 (:count (some #(when (= "coding" (:group %)) %) (:groups overview)))))
        (is (= ["grep"] (mapv :name (:entries coding))))
        (is (:error? hidden)))
      (evaluate rt sid '(defn echo [{:keys [value]}] value)
                '(register-tool! #'echo {:name "echo" :description "Return the supplied value"
                                         :parameters {:type "object" :properties {:value {:type "integer"}}
                                                      :required ["value"]}
                                         :returns {:description "The supplied integer"}}))
      (is (:error? (evaluate rt sid '(help "echo"))))
      (runtime/configure! rt sid {:config {:tools ["grep" "echo"]}})
      (is (= "echo" (:name (:value (evaluate rt sid '(help "echo"))))))
      (is (= {:type "integer"} (get-in (:value (evaluate rt sid '(help "echo" {:detailed? true})))
                                       [:parameters :properties :value])))
      (is (= "jobs/result" (:name (:value (evaluate rt sid '(help 'jobs/result))))))
      (is (= "agents/resume!" (:name (:value (evaluate rt sid '(help 'agents/resume! {:detailed? true}))))))
      (is (= ["[target]"] (:arities (:value (evaluate rt sid '(help 'agents/resume!))))))))
  (let [huge (apply str (repeat 30000 "x"))
        descriptor {:name "ext" :symbol "ext" :description huge
                    :parameters {:properties (into {} (map #(vector (str "field" %)
                                                             {:type "string" :description huge})
                                                           (range 50)))}
                    :returns {:description huge} :examples [{:source huge} {:source huge} {:source huge}]}
        selection #(into [descriptor] (map (fn [n] (assoc descriptor :name (str "ext" n)
                                                         :symbol (str "ext" n))) (range 25)))
        overview (help/help 'clojure.core selection)
        page (help/help 'clojure.core selection {:group "coding"})
        named (help/help 'clojure.core selection "ext")
        full (help/help 'clojure.core selection "ext" {:detailed? true})]
    (is (< (count (pr-str overview)) 1600))
    (is (= 8 (count (:entries page))))
    (is (= 8 (:next-offset page)))
    (is (<= (count (pr-str page)) 6000))
    (is (<= (count (pr-str (help/help 'clojure.core selection {:group "coding" :limit 20}))) 6000))
    (is (<= (count (pr-str named)) 6000))
    (is (= 42 (:omitted-arguments (:parameters named))))
    (is (= 1 (:omitted-examples named)))
    (is (= huge (:description full)))))

(deftest workflow-help-is-bounded-selected-and-isolated
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          root (:value (evaluate rt sid '(help)))]
      (is (= #{"coding" "background" "delegation" "failure" "results"}
             (set (map :workflow (:workflows root)))))
      (is (every? #(and (string? (:purpose %)) (not (contains? % :steps)))
                  (:workflows root)))
      (is (= ["[id]"] (:arities (:value (evaluate rt sid '(help 'agents/submission))))))))
  (let [selection (constantly [])
        root (help/help 'clojure.core selection)]
    (is (empty? (:workflows root)))
    (is (= "unavailable-workflow"
           (:error/code (ex-data (try (help/help 'clojure.core selection {:workflow "background"})
                                      (catch clojure.lang.ExceptionInfo error error))))))
    (doseq [options [{:workflow nil}
                     {:workflow 4}
                     {:workflow "background" :group "jobs"}
                     {:workflow "background" :limit 8}]]
      (is (= "invalid-arguments"
             (:error/code (ex-data (try (help/help 'clojure.core selection options)
                                        (catch clojure.lang.ExceptionInfo error error)))))))
    (is (= "unknown-workflow"
           (:error/code (ex-data (try (help/help 'clojure.core selection {:workflow "not-installed"})
                                      (catch clojure.lang.ExceptionInfo error error))))))))

(deftest coding-workflow-follows-selected-file-capabilities
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          available #(set (map :workflow (:workflows (:value (evaluate rt sid '(help))))))]
      (runtime/configure! rt sid {:config {:tools ["read" "write" "bash"]}})
      (is (not (contains? (available) "coding")))
      (runtime/configure! rt sid {:config {:tools ["read" "write" "edit" "bash"]}})
      (is (contains? (available) "coding")))))

(deftest registered-tools-do-not-masquerade-as-native-workflows
  (let [tool-names ["jobs/start!" "jobs/inspect" "jobs/wait" "jobs/output" "jobs/result" "jobs/cancel!"]
        selected #(mapv (fn [name] {:name name :symbol name :description "Extension, not a native job helper"})
                        tool-names)]
    (is (empty? (:workflows (help/help 'clojure.core selected))))
    (is (= "unavailable-workflow"
           (:error/code (ex-data (try (help/help 'clojure.core selected {:workflow "background"})
                                      (catch clojure.lang.ExceptionInfo error error))))))))

(deftest workspace-describes-bindings-without-running-their-values
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (evaluate rt sid
                '(def ^{:label "Baseline"} records "Records before edits." [1 2 3])
                '(def touched (atom 0))
                '(def delayed (lazy-seq (swap! touched inc) (range)))
                '(defn helper "Local helper." [x] (inc x)))
      (let [first-view (:value (evaluate rt sid '(workspace)))
            records (some #(when (= "records" (:name %)) %) (:bindings first-view))]
        (is (= "Records before edits." (:doc records)))
        (is (= "Baseline" (:label records)))
        (is (= 3 (:count records)))
        (is (= 0 (:value (evaluate rt sid '@touched))))
        (is (= #{"records" "touched" "delayed" "helper"} (set (map :name (:bindings first-view)))))
        (is (= 1 (count (:bindings (:value (evaluate rt sid '(workspace {:limit 1})))))))
        (runtime/run! rt sid "First discussion")
        (runtime/run! rt sid "Second discussion")
        (runtime/compact! rt sid {:config {:settings {:compaction-keep-entries 1}}})
        (is (= (:generation first-view) (:generation (:value (evaluate rt sid '(workspace))))))
        (is (= [1 2 3] (:value (evaluate rt sid 'records))))
        (runtime/reload! rt sid)
        (let [after (:value (evaluate rt sid '(workspace)))]
          (is (not= (:generation first-view) (:generation after)))
          (is (empty? (:bindings after))))))))

(deftest failure-details-remain-inspectable-after-another-error-and-reload
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          failed (evaluate rt sid '(def effects (atom [])) '(swap! effects conj :before)
                           '(throw (ex-info "original failure" {:probe 42}))
                           '(swap! effects conj :after))
          id (get-in failed [:result :id])]
      (is (:error? failed))
      (is (= [:before] (:value (evaluate rt sid '@effects))))
      (evaluate rt sid '(/ 1 0))
      (runtime/reload! rt sid)
      (let [info (:value (evaluate rt sid (list 'result-info id)))
            data (get-in info [:details :data])]
        (is (= 42 (:probe data)))
        (is (= 2 (:completed-forms data)))
        (is (= 3 (:form-index data)))
        (is (= :evaluating (:phase data)))
        (is (not (contains? info :value)))))))

(deftest result-pages-are-stable-and-artifacts-are-readable-without-reexecution
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (doseq [n (range 5)] (evaluate rt sid n))
      (let [page (:value (evaluate rt sid '(results {:limit 2})))
            next-page (:value (evaluate rt sid (list 'results {:limit 2 :before-id (:next-before-id page)})))]
        (is (= 2 (count (:items page))))
        (is (every? #(< (:id %) (:next-before-id page)) (:items next-page))))
      (let [artifact (artifacts/put! (:store rt) sid "abcdefgh" {:kind :text})
            page (:value (evaluate rt sid (list 'artifact-page (:id artifact) {:offset 3 :limit 2})))]
        (is (= "cd" (:content page)))
        (is (= 5 (:next-offset page))))
      (let [result (evaluate rt sid "line one\nline two")]
        (is (= "=> line one\nline two" (:content result)))))))

(deftest empty-resource-catalogs-explain-their-scope
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (doseq [tool ['skill 'prompt]]
        (let [catalog (:value (evaluate rt sid (list tool {:action "catalog"})))]
          (is (= [] (:items catalog)))
          (is (string? (get-in catalog [:scope :global-directory])))
          (is (false? (get-in catalog [:scope :repository-auto-discovery?]))))))))

(deftest reader-failure-preserves-completed-form-accounting
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          failed (runtime/evaluate! rt sid "(def earlier 42)\n[")]
      (is (:error? failed))
      (is (= :reading (get-in failed [:details :data :phase])))
      (is (= 1 (get-in failed [:details :data :completed-forms])))
      (is (= 2 (get-in failed [:details :data :form-index])))
      (is (= 42 (:value (evaluate rt sid 'earlier)))))))
