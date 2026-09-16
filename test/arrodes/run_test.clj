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
