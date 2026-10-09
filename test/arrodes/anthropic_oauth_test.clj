(ns arrodes.anthropic-oauth-test
  (:require [arrodes.anthropic-oauth :as oauth]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.providers.anthropic.chat :as anthropic]
            [llm.sdk.stream :as stream]))

(def request
  {:request/model "claude-sonnet-4-6"
   :request/stream? true
   :request/messages [{:message/role :system :message/content "Hermes Agent / Nous Research remain unchanged"}
                      {:message/role :user :message/content "Use the REPL"}]
   :request/tools [{:type :function :function {:name "repl" :description "Evaluate"
                                              :parameters {:type "object" :properties {}}}}]
   :request/tool-choice {:type :function :function {:name "repl"}}
   :request/cache {:enabled? true :strategy :system-and-3 :tools-cache? true}})

(deftest explicit-oauth-is-bearer-only-and-request-local
  (let [original (sdk/provider-profile :anthropic)
        profile (assoc original :profile/auth-token "sk-ant-api-do-not-send"
                       :profile/runtime-headers {"X-API-Key" "do-not-send"
                                                 "Authorization" "old"
                                                 "Anthropic-Beta" "fixture-beta"}
                       :profile/connect-timeout-ms 1234 :profile/timeout-ms 5678)
        built (oauth/build-request profile request "opaque-access-token")
        headers (:headers built)]
    (is (= "https://api.anthropic.com/v1/messages?beta=true" (:url built)))
    (is (= "Bearer opaque-access-token" (get headers "authorization")))
    (is (not-any? #(= "x-api-key" (str/lower-case (name %))) (keys headers)))
    (is (not (str/includes? (pr-str built) "do-not-send")))
    (is (str/includes? (get headers "anthropic-beta") "oauth-2025-04-20"))
    (is (str/includes? (get headers "anthropic-beta") "fixture-beta"))
    (is (= "2023-06-01" (get headers "anthropic-version")))
    (is (= 1234 (:connect-timeout-ms built)))
    (is (= 5678 (:timeout-ms built)))
    (is (= "_repl" (get-in built [:body :tools 0 :name])))
    (is (= "_repl" (get-in built [:body :tool_choice :name])))
    (is (= "You are Claude Code, Anthropic's official CLI for Claude."
           (get-in built [:body :system 0 :text])))
    (is (= "Hermes Agent / Nous Research remain unchanged"
           (get-in built [:body :system 1 :text])))
    (is (= original (sdk/provider-profile :anthropic)))
    (is (= "repl" (get-in request [:request/tools 0 :function :name])))
    (is (some :cache_control (get-in built [:body :system])))
    (is (some :cache_control (get-in built [:body :tools])))))

(deftest oauth-tool-names-roundtrip-with-native-replay
  (doseq [name ["repl" "_private" "mcp_tool"]]
    (let [wire-name (str "_" name)
          event (stream/tool-call-start 0 "call" wire-name
                                        :provider-data {:anthropic/input {:untouched wire-name}
                                                        :anthropic/content-block
                                                        {:type "tool_use" :id "call" :name wire-name
                                                         :input {:untouched wire-name}}})
          normalized (oauth/normalize-event event)
          call (assoc (select-keys normalized [:tool-call/name :tool-call/id :tool-call/provider-data])
                      :tool-call/arguments "{}")
          replay (update request :request/messages conj
                         {:message/role :assistant :message/content [] :message/tool-calls [call]}
                         {:message/role :tool :message/tool-call-id "call" :message/content "done"})
          built (oauth/build-request (sdk/provider-profile :anthropic) replay "opaque")]
      (is (= name (:tool-call/name normalized)))
      (is (= name (get-in normalized [:tool-call/provider-data :anthropic/content-block :name])))
      (is (= wire-name (get-in normalized [:tool-call/provider-data :anthropic/input :untouched])))
      (is (= wire-name (get-in built [:body :messages 1 :content 0 :name])))
      (is (= "call" (get-in built [:body :messages 2 :content 0 :tool_use_id])))))
  (let [reasoning {:event/type :stream/reasoning-delta :reasoning/signature "_opaque"}]
    (is (= reasoning (oauth/normalize-event reasoning)))))

(deftest oauth-preserves-thinking-images-and-explicit-cache-off
  (let [input (-> request
                  (assoc :request/reasoning {:enabled true :effort :high}
                         :request/cache {:enabled? false})
                  (update :request/messages conj
                          {:message/role :user
                           :message/content [{:part/type :image :image/data "AAAA"
                                              :image/mime-type "image/png"}]}))
        built (oauth/build-request (sdk/provider-profile :anthropic) input "opaque")]
    (is (= "adaptive" (get-in built [:body :thinking :type])))
    (is (= "high" (get-in built [:body :output_config :effort])))
    (is (= "AAAA" (get-in built [:body :messages 1 :content 0 :source :data])))
    (is (not-any? :cache_control (get-in built [:body :system])))
    (is (not-any? :cache_control (get-in built [:body :tools])))))

(deftest oauth-rejects-custom-or-ambiguous-endpoints
  (doseq [url ["http://api.anthropic.com/v1" "https://proxy.invalid/v1"
               "https://api.anthropic.com.evil/v1" "https://api.anthropic.com/v1?redirect=evil"
               "https://api.anthropic.com/v1/" nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"official Anthropic endpoint"
                         (oauth/build-request (assoc (sdk/provider-profile :anthropic) :profile/base-url url)
                                              request "opaque"))))
  (doseq [token [nil "" " " 123]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (oauth/build-request (sdk/provider-profile :anthropic) request token)))))

(deftest api-key-sdk-path-remains-unchanged
  (let [built (anthropic/build-request-anthropic
               (assoc (sdk/provider-profile :anthropic) :profile/auth-token "sk-ant-api-fixture") request)]
    (is (= "sk-ant-api-fixture" (get-in built [:headers "x-api-key"])))
    (is (= "repl" (get-in built [:body :tools 0 :name])))
    (is (= "Hermes Agent / Nous Research remain unchanged" (get-in built [:body :system 0 :text])))))
