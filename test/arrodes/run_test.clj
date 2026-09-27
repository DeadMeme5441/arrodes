(ns arrodes.run-test
  (:require [arrodes.run :as run]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk.usage :as usage]))

(deftest context-usage-counts-cache-once-across-sdk-providers
  (doseq [normalized [(usage/normalize-codex-usage
                      {:input_tokens 50000 :output_tokens 200
                       :input_tokens_details {:cached_tokens 46000}})
                     (usage/normalize-openai-usage
                      {:prompt_tokens 50000 :completion_tokens 200
                       :prompt_tokens_details {:cached_tokens 45000 :cache_write_tokens 1000}})
                     (usage/normalize-anthropic-usage
                      {:input_tokens 4000 :cache_read_input_tokens 45000
                       :cache_creation_input_tokens 1000 :output_tokens 200})
                     (usage/normalize-gemini-usage
                      {:promptTokenCount 50000 :cachedContentTokenCount 46000
                       :candidatesTokenCount 200})]]
    (testing "Works with reported totals, sparse usage, and persisted prior-version maps"
      (is (= 50200 (run/context-tokens normalized)))
      (is (= 50200 (run/context-tokens (dissoc normalized :usage/total-tokens))))
      (is (= 50200 (run/context-tokens (edn/read-string (pr-str normalized)))))))
  (testing "Totals are authoritative; reasoning and modality counters are breakdowns"
    (is (= 53000 (run/context-tokens
                 {:usage/input-tokens 4000 :usage/cached-input-tokens 46000
                  :usage/output-tokens 200 :usage/reasoning-tokens 2800
                  :usage/total-tokens 53000})))
    (is (= 50200 (run/context-tokens
                 {:usage/input-tokens 4000 :usage/cached-input-tokens 46000
                  :usage/output-tokens 200 :usage/reasoning-tokens 100
                  :usage/image-tokens 1000 :usage/audio-tokens 500}))))
  (is (nil? (run/context-tokens nil)))
  (is (nil? (run/context-tokens {})))
  (is (= 0 (run/context-tokens {:usage/total-tokens 0})))
  (is (= 700 (run/context-tokens {:usage/cached-input-tokens 500
                                 :usage/cache-write-tokens 200}))))

(deftest usage-report-separates-cache-from-spend-and-preserves-unknowns
  (let [entry (fn [usage cost]
                {:kind :message :data {:message/role :assistant
                                       :message/provider-data {:response/usage usage
                                                               :response/cost cost}}})
        first-reply (entry {:usage/input-tokens 4000 :usage/cached-input-tokens 45000
                            :usage/cache-write-tokens 1000 :usage/output-tokens 200
                            :usage/reasoning-tokens 100 :usage/total-tokens 50200}
                           {:cost/usd 0.025 :cost/estimated? true})
        summary {:kind :compaction
                 :data {:usage {:usage/input-tokens 80 :usage/output-tokens 20
                                :usage/total-tokens 100}
                        :cost {:cost/usd :unknown}}}
        last-reply (entry {:usage/input-tokens 600 :usage/output-tokens 50
                           :usage/total-tokens 650}
                          {:cost/usd 0.005})
        report (run/usage-report
                [first-reply summary last-reply
                 {:kind :message :data {:message/role :user
                                        :message/content "A user message is not another request"}}])]
    (is (= (:response/usage (:message/provider-data (:data last-reply)))
           (:latest-usage report)))
    (is (= 3 (:requests report)))
    (is (= {:usage/input-tokens 4680 :usage/cached-input-tokens 45000
            :usage/cache-write-tokens 1000 :usage/output-tokens 270
            :usage/total-tokens 50950}
           (:totals report)))
    (is (= {:usage/cached-input-tokens 2 :usage/cache-write-tokens 2}
           (:missing report)))
    (is (< (Math/abs (- 0.03 (:known-cost-usd report))) 0.0000001))
    (is (= 1 (:unknown-cost-count report)))
    (is (= 650 (run/context-tokens (:latest-usage report))))
    (is (= 45000 (get-in (run/usage-report [first-reply]) [:totals :usage/cached-input-tokens]))))
  (let [report (run/usage-report [{:kind :branch-summary
                                   :data {:usage {:usage/cached-input-tokens 45
                                                  :usage/output-tokens 5
                                                  :usage/total-tokens 50}
                                          :cost {:cost/usd 0.003}}}])]
    (is (= 1 (:requests report)))
    (is (= 45 (get-in report [:totals :usage/cached-input-tokens])))
    (is (= 0.003 (:known-cost-usd report)))
    (is (nil? (:latest-usage report))))
  (let [report (run/usage-report [{:kind :message :data {:message/role :assistant
                                                         :message/content "No measurements"}}])]
    (is (= {} (:totals report)))
    (is (= 1 (:unknown-cost-count report)))
    (is (= 1 (get-in report [:missing :usage/cache-write-tokens])))
    (is (nil? (:known-cost-usd report)))))

(deftest context-measurements-expire-at-compaction
  (let [old {:kind :message :data {:message/role :assistant
                                 :message/provider-data {:response/usage {:usage/total-tokens 90000}}}}
        marker {:kind :compaction :data {:summary "Condensed history"
                                        :usage {:usage/total-tokens 95000}}}
        user {:kind :message :data {:message/role :user :message/content "Continue"}}
        fresh (assoc-in old [:data :message/provider-data :response/usage]
                        {:usage/total-tokens 1000})]
    (is (= {:usage/total-tokens 90000} (run/latest-usage [old user])))
    (is (nil? (run/latest-usage [old marker user])))
    (is (= {:usage/total-tokens 1000} (run/latest-usage [old marker user fresh])))
    (is (nil? (run/latest-usage
               [old {:kind :message :data {:message/role :assistant :message/content "No usage"}}])))
    (is (nil? (run/latest-usage [])))))

(deftest compaction-uses-current-measurement-not-session-spend
  (let [config {:settings {}} model {:context-window 100000}]
    (is (false? (run/auto-compact? config model {:usage/total-tokens 50000})))
    (is (true? (run/auto-compact? config model
                                {:usage/input-tokens 1000 :usage/cached-input-tokens 83000
                                 :usage/cache-write-tokens 500 :usage/output-tokens 500})))
    (is (false? (run/auto-compact? config model nil)))
    (is (false? (run/auto-compact? config model {})))
    (is (true? (run/auto-compact? config model {:usage/total-tokens 85000})))
    (is (false? (run/auto-compact? config model {:usage/total-tokens 84999})))
    (is (false? (run/auto-compact? {:settings {:auto-compact? false}} model
                                 {:usage/total-tokens 100000})))
    (is (false? (run/auto-compact? config {} {:usage/total-tokens 100000})))))
