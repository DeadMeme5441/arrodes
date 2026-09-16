(ns arrodes.repl-ergonomics-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.provider-repl :as wire]
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
                              :catalog (registered-tools) :brief (registered-tools {:brief? true})
                              :help (registered-tools "grep")})
            {:keys [texts hits files listing page catalog brief help]} (:value result)
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
        (is (every? #(string? (get-in % [:returns :description])) catalog))
        (is (every? #(not (contains? % :parameters)) brief))
        (is (seq (:examples help))))
      (is (:error? (evaluate rt sid '(find {:pattern "*" :format "legacy"})))))))

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
          id (get-in failed [:result :id])
          rendered (:message/content (first (wire/messages [(wire/result-message failed)])))]
      (is (:error? failed))
      (is (= [:before] (:value (evaluate rt sid '@effects))))
      (is (str/includes? rendered (str "(result-info " id ")")))
      (is (not (str/includes? rendered (str "(result " id ")"))))
      (evaluate rt sid '(/ 1 0))
      (runtime/reload! rt sid)
      (let [info (:value (evaluate rt sid (list 'result-info id)))
            data (get-in info [:details :data])]
        (is (= 42 (:probe data)))
        (is (= 2 (:completed-forms data)))
        (is (= 3 (:form-index data)))
        (is (= :evaluating (:phase data)))
        (is (not (contains? info :value))))
      (let [nil-result (evaluate rt sid nil)
            text (:message/content (first (wire/messages [(wire/result-message nil-result)])))]
        (is (false? (:error? nil-result)))
        (is (str/includes? text "Use (result "))))))

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
