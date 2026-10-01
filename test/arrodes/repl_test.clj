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
