(ns arrodes.repl-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.capabilities :as capabilities]
            [arrodes.runtime-test :as fixtures]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest failed-form-preserves-prior-effects-and-repl-history-is-session-local
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          other (:id (fixtures/create-session rt))
          failure (runtime/evaluate! rt sid
                                     "(def retained 41) 7 (throw (ex-info \"Failure with native data\" {:object (Object.)}))")]
      (is (true? (:error? failure)))
      (is (= 7 (:value (runtime/evaluate! rt sid "*1"))))
      (is (= 42 (:value (runtime/evaluate! rt sid "(inc retained)"))))
      (is (= [nil nil] (:value (runtime/evaluate! rt other "[*1 (resolve 'retained)]"))))
      (is (= 42 (:value (runtime/evaluate! rt sid "*1")))))))

(deftest wrapped-capability-failure-exposes-its-cause-without-losing-evaluation-progress
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (capabilities/register! (runtime/registry rt sid)
        {:name "reject-research" :description "Reject missing provider evidence"
         :parameters {:type "object"} :permission :read :execution :parallel
         :fn (fn [_]
               (throw (ex-info "Hosted search returned no genuine source URLs"
                               {:error/code "provider/no-search-evidence" :provider :codex-backend})))})
      (let [failed (runtime/evaluate! rt sid
                     "(def effects (atom 0))\n(swap! effects inc)\n(def research (reject-research {}))")
            id (get-in failed [:result :id])
            data (get-in failed [:details :data])]
        (is (:error? failed))
        (is (= "evaluation-failed" (get-in failed [:details :code])))
        (is (str/includes? (:content failed) "provider/no-search-evidence"))
        (is (= "provider/no-search-evidence" (get-in data [:evaluation/cause :code])))
        (is (str/includes? (get-in data [:evaluation/cause :message]) "Hosted search returned no genuine source URLs"))
        (is (= {:completed-forms 2 :form-index 3 :phase :evaluating}
               (select-keys data [:completed-forms :form-index :phase])))
        (is (= 1 (:value (runtime/evaluate! rt sid "@effects"))))
        (runtime/reload! rt sid)
        (let [info (:value (runtime/evaluate! rt sid (str "(result-info " id ")")))]
          (is (= "provider/no-search-evidence" (get-in info [:details :data :evaluation/cause :code])))))
      (let [syntax (runtime/evaluate! rt sid "(missing_research_function {})")]
        (is (:error? syntax))
        (is (nil? (get-in syntax [:details :data :evaluation/cause])))
        (is (= :compile-syntax-check (get-in syntax [:details :data :clojure.error/phase])))))))

(deftest nested-function-events-replay-with-native-result-references
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          observed (atom [])
          result (runtime/evaluate! rt sid
                                    "(defn answer [x] (+ x 1)) (write {:path \"answer.txt\" :content (str (answer 41))}) (read {:path \"answer.txt\"})"
                                    {:on-event #(swap! observed conj %)})
          events (runtime/events-since rt {:session-id sid :after 0})
          evaluation (first (filter #(= :evaluation/started (:type %)) events))
          children (filter #(= :capability/completed (:type %)) events)]
      (is (= "42" (:value result)))
      (is (= ["write" "read"] (mapv #(get-in % [:data :name]) children)))
      (is (every? #(= (get-in evaluation [:data :call-id])
                      (get-in % [:data :parent-call-id])) children))
      (is (= (:operation-id evaluation) (:operation-id (last children))))
      (is (= "42" (:value (runtime/evaluate! rt sid (str "(result " (get-in result [:result :id]) ")")))))
      (is (= [:evaluation/started :capability/started :capability/completed
              :capability/started :capability/completed :evaluation/completed]
             (mapv :type @observed))))))

(deftest provider-cannot-bypass-evaluation-by-naming-a-function
  (fixtures/with-runtime
    [rt (fn [_ _]
          (fixtures/calls {:tool-call/id "bypass" :tool-call/name "write"
                           :tool-call/arguments {:path "must-not-exist.txt" :content "bad"}}))]
    (let [sid (:id (fixtures/create-session rt))]
      (is (thrown? clojure.lang.ExceptionInfo (runtime/run! rt sid "Attempt direct dispatch")))
      (is (= false (:value (runtime/evaluate! rt sid
                                             "(.exists (java.io.File. cwd \"must-not-exist.txt\"))")))))))

(deftest provider-result-references-follow-fork-remapping
  (let [requests (atom [])
        turn (atom 0)
        provider (fn [request _]
                   (swap! requests conj request)
                   (if (= 1 (swap! turn inc))
                     (fixtures/calls
                      (fixtures/tool-call "retain"
                                          "(write {:path \"result.txt\" :content \"42\"}) (read {:path \"result.txt\"})"))
                     (fixtures/answer "Done")))]
    (fixtures/with-runtime [rt provider]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "Retain a result")
        (let [original (->> (runtime/entries rt sid)
                            (keep #(get-in % [:data :message/result :id])) last)
              fork-id (:id (runtime/fork! rt sid {}))]
          (runtime/continue! rt fork-id)
          (let [content (->> (:request/messages (last @requests))
                             (filter #(= :tool (:message/role %))) last :message/content)
                source (re-find #"\(result \d+\)" content)
                fork-result (runtime/evaluate! rt fork-id source)]
            (is (not= (str "(result " original ")") source))
            (is (= "42" (:value fork-result)))))))))

(deftest observer-output-does-not-reenter-repl-output-capture
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          host-output (java.io.StringWriter.)
          result (binding [*out* host-output]
                   (runtime/evaluate! rt sid "(println \"program\") 42"
                                      {:on-event (fn [_] (println "host"))}))]
      (is (= 42 (:value result)))
      (is (= ["program"] (str/split-lines (get-in result [:details :stdout]))))
      (is (.contains (str host-output) "host")))))

(deftest deeply-nested-durable-values-use-an-envelope-safe-result
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          result (runtime/evaluate! rt sid "(nth (iterate vector 0) 30)")
          result-id (get-in result [:result :id])]
      (is (false? (:error? result)))
      (is (= :artifact (get-in result [:result :kind])))
      (is (some #(and (= :evaluation/completed (:type %))
                      (= result-id (get-in % [:data :result :id])))
                (runtime/events-since rt {:session-id sid :after 0})))
      (runtime/reload! rt sid)
      (is (true? (:value
                  (runtime/evaluate!
                   rt sid
                   (str "(= (nth (iterate vector 0) 30) (result " result-id "))"))))))))

(deftest later-reader-and-compiler-failures-show-captured-output-and-source-location
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (doseq [[source phase]
              [["(println \"prior stdout\")\n(binding [*out* *err*] (println \"prior stderr\"))\n("
                :reading]
               ["(println \"prior stdout\")\n(binding [*out* *err*] (println \"prior stderr\"))\n(missing-function)"
                :evaluating]]]
        (let [failure (runtime/evaluate! rt sid source)
              data (get-in failure [:details :data])]
          (is (:error? failure))
          (is (= "evaluation-failed" (get-in failure [:details :code])))
          (is (= {:completed-forms 2 :form-index 3 :phase phase}
                 (select-keys data [:completed-forms :form-index :phase])))
          (is (= "prior stdout\n" (:stdout data)))
          (is (= "prior stderr\n" (:stderr data)))
          (is (str/includes? (:content failure) "stdout:\nprior stdout"))
          (is (str/includes? (:content failure) "stderr:\nprior stderr"))
          (is (str/includes? (:content failure) "Form 3"))
          (is (pos-int? (or (:clojure.error/line data) (:line data))))
          (is (pos-int? (or (:clojure.error/column data) (:column data))))
          (is (string? (:exception data))))))))

(deftest native-exception-metadata-cannot-replace-captured-output-or-source-progress
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (doseq [[stdout stderr line column]
              [[42 {:not "a stream"} {:not "a line"} ["not a column"]]
               [{:not "a stream"} 42 "not a line" -1]
               [42 42 999 999]]]
        (let [metadata {:stdout stdout :stderr stderr
                        :stdout-truncated? true :stderr-truncated? true
                        :form-index 999 :completed-forms 999 :phase "not a phase"
                        :line "not a line" :column {}
                        :clojure.error/line line :clojure.error/column column
                        :exception "not the exception class"
                        :error/code "primary/native-failure"
                        :reason {:kind :original :values [1 2 3]}}
              source (str "(def retained-effects (atom 0))\n"
                          "(swap! retained-effects inc)\n"
                          "(println \"prior stdout\")\n"
                          "(binding [*out* *err*] (println \"prior stderr\"))\n"
                          "(def primary-exception (ex-info \"primary failure\" "
                          (pr-str metadata) "))\n"
                          "(throw primary-exception)")
              failure (runtime/evaluate! rt sid source)
              data (get-in failure [:details :data])]
          (is (:error? failure))
          (is (= "primary/native-failure" (get-in failure [:details :code])))
          (is (str/includes? (:content failure) "primary failure"))
          (is (= "primary/native-failure" (get-in data [:evaluation/cause :code])))
          (is (= {:completed-forms 5 :form-index 6 :phase :evaluating :line 6 :column 1}
                 (select-keys data [:completed-forms :form-index :phase :line :column])))
          (is (= "prior stdout\n" (:stdout data)))
          (is (= "prior stderr\n" (:stderr data)))
          (is (false? (:stdout-truncated? data)))
          (is (false? (:stderr-truncated? data)))
          (is (= "clojure.lang.ExceptionInfo" (:exception data)))
          (is (= (:reason metadata) (:reason data)))
          (is (str/includes? (:content failure) "Form 6"))
          (is (str/includes? (:content failure) "line 6"))
          (is (str/includes? (:content failure) "column 1"))
          (is (str/includes? (:content failure) "stdout:\nprior stdout"))
          (is (str/includes? (:content failure) "stderr:\nprior stderr"))
          (is (= [true "primary failure" metadata 1]
                 (:value (runtime/evaluate! rt sid
                           "[(identical? *e primary-exception) (ex-message *e) (ex-data *e) @retained-effects]")))))))))

(deftest failure-stream-previews-remain-bounded-with-full-retained-diagnostics
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          failure (runtime/evaluate! rt sid
                    "(print (apply str (repeat 10000 \"x\"))) (throw (ex-info \"primary failure\" {:reason :original :stdout 42 :stderr {:not \"a stream\"} :stdout-truncated? true}))")
          result-id (get-in failure [:result :id])]
      (is (str/includes? (:content failure) "primary failure"))
      (is (str/includes? (:content failure) "Preview truncated"))
      (is (< (count (:content failure)) 5000))
      (is (= (apply str (repeat 10000 "x")) (get-in failure [:details :data :stdout])))
      (is (= :original (get-in failure [:details :data :reason])))
      (is (false? (get-in failure [:details :data :stdout-truncated?])))
      (is (= "" (get-in failure [:details :data :stderr])))
      (runtime/reload! rt sid)
      (let [retained (:value (runtime/evaluate! rt sid (str "(result-info " result-id ")")))]
        (is (= (get-in failure [:details :data :stdout]) (get-in retained [:details :data :stdout])))
        (is (= (get-in failure [:details :data :exception]) (get-in retained [:details :data :exception])))))))

(deftest native-exception-values-do-not-collapse-owned-failure-diagnostics
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          failure (runtime/evaluate! rt sid
                    "(def native-object (Object.))\n(println \"before native failure\")\n(throw (ex-info \"native failure\" {:object native-object :reason {:kind :original}}))")
          data (get-in failure [:details :data])
          result-id (get-in failure [:result :id])]
      (is (:error? failure))
      (is (str/includes? (:content failure) "native failure"))
      (is (= "before native failure\n" (:stdout data)))
      (is (= {:completed-forms 2 :form-index 3 :phase :evaluating :line 3}
             (select-keys data [:completed-forms :form-index :phase :line])))
      (is (= {:kind :original} (:reason data)))
      (is (true? (get-in data [:object :live-only?])))
      (is (true? (:value (runtime/evaluate! rt sid
                           "(identical? native-object (:object (ex-data *e)))"))))
      (runtime/reload! rt sid)
      (is (= data (get-in (:value (runtime/evaluate! rt sid (str "(result-info " result-id ")")))
                          [:details :data]))))))

(deftest oversized-captured-failure-output-is-paged-with-honest-capture-limits
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          failure (runtime/evaluate! rt sid
                    "(println \"prior effect\")\n(print (apply str (repeat 1100000 \"x\")))\n(binding [*out* *err*] (println \"captured stderr\"))\n(throw (ex-info (str \"primary failure \" (apply str (repeat 100000 \"m\"))) {:object (Object.) :oversized (apply str (repeat 1100000 \"z\")) :reason :original}))")
          data (get-in failure [:details :data])
          artifact-id (get-in failure [:details :artifact :id])
          result-id (get-in failure [:result :id])]
      (is (:error? failure))
      (is (str/includes? (:content failure) "primary failure"))
      (is (str/includes? (:content failure) "Exception message truncated"))
      (is (< (count (:content failure)) 10000))
      (is (some? artifact-id))
      (is (str/includes? (:content failure) (str artifact-id)))
      (is (true? (get-in failure [:details :output-hard-truncated?])))
      (is (true? (:stdout-truncated? data)))
      (is (false? (:stderr-truncated? data)))
      (is (true? (:stdout-preview? data)))
      (is (= 4096 (count (:stdout data))))
      (is (= "captured stderr\n" (:stderr data)))
      (is (= :original (:reason data)))
      (is (true? (get-in data [:object :live-only?])))
      (is (= {:completed-forms 3 :form-index 4 :phase :evaluating :line 4}
             (select-keys data [:completed-forms :form-index :phase :line])))
      (is (= 100016 (:value (runtime/evaluate! rt sid "(count (ex-message *e))"))))
      (runtime/reload! rt sid)
      (let [retained (:value (runtime/evaluate! rt sid (str "(result-info " result-id ")")))
            paged (:value (runtime/evaluate! rt sid
                           (str "(artifact-page " (pr-str artifact-id) " {:limit 4096})")))
            inspected (:value (runtime/evaluate! rt sid
                               (str "(let [text (artifact " (pr-str artifact-id) ")] "
                                    "[(clojure.string/starts-with? text \"stdout:\\nprior effect\\n\") "
                                    "(clojure.string/ends-with? text \"\\n[stdout truncated at 1048576 characters]\\nstderr:\\ncaptured stderr\\n\")])")))]
        (is (= data (get-in retained [:details :data])))
        (is (= artifact-id (get-in retained [:details :artifact :id])))
        (is (= 4096 (count (:content paged))))
        (is (= [true true] inspected))))))

(deftest primary-cause-survives-foreign-metadata-budget-and-reload
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          source (str "(println \"before primary cause\")\n"
                      (pr-str '(throw
                                 (ex-info "primary-cause"
                                          (into {:error/code "fixture/primary"}
                                                (map (fn [i] [(keyword (str "field-" i)) i])
                                                     (range 2000)))))))
          failure (runtime/evaluate! rt sid source)
          result-id (get-in failure [:result :id])
          data (get-in failure [:details :data])]
      (is (= {:code "fixture/primary" :message "primary-cause"} (:evaluation/cause data)))
      (is (= "before primary cause\n" (:stdout data)))
      (is (= 1 (:completed-forms data)))
      (is (pos? (:exception-data-omitted data)))
      (runtime/reload! rt sid)
      (is (= (:evaluation/cause data)
             (get-in (:value (runtime/evaluate! rt sid (str "(result-info " result-id ")")))
                     [:details :data :evaluation/cause]))))))
