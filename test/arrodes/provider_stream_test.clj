(ns arrodes.provider-stream-test
  (:require [arrodes.provider :as provider]
            [arrodes.session-test :as fixtures]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private request
  {:request/model "local-model"
   :request/messages [{:message/role :user :message/content "Hello"}]
   :request/tools [{:type :function
                    :function {:name "repl" :description "Evaluate"
                               :parameters {:type "object"}}}]})

(defn- sse-chunk [value]
  (str "data: " (json/write-str value) "\n\n"))

(def ^:private successful-stream
  (str (sse-chunk {:choices [{:index 0 :delta {:content "Working "}}]})
       (sse-chunk {:choices [{:index 0 :delta {:content "now"
                                          :tool_calls [{:index 0 :id "call_1" :type "function"
                                                        :function {:name "repl"
                                                                   :arguments "{\"source\":"}}]}}]})
       (sse-chunk {:choices [{:index 0 :delta {:tool_calls [{:index 0
                                                         :function {:arguments "\"(+ 1 1)\"}"}}]}
                          :finish_reason "tool_calls"}]})
       (sse-chunk {:choices [] :usage {:prompt_tokens 20 :completion_tokens 8
                                   :total_tokens 28
                                   :prompt_tokens_details {:cached_tokens 12}}})
       "data: [DONE]\n\n"))

(defn- serve [reply f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        requests (atom [])
        directory (fixtures/temp-directory)]
    (try
      (.createContext server "/v1/chat/completions"
                      (reify HttpHandler
                        (handle [_ exchange]
                          (let [text (with-open [body (.getRequestBody exchange)] (slurp body))
                                {:keys [status payload]} @reply
                                bytes (.getBytes (or payload "") StandardCharsets/UTF_8)]
                            (swap! requests conj (json/read-str text :key-fn keyword))
                            (.set (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
                            (.sendResponseHeaders exchange (int status) (long (alength bytes)))
                            (with-open [output (.getResponseBody exchange)]
                              (.write output bytes))))))
      (.start server)
      (let [url (str "http://127.0.0.1:" (-> server .getAddress .getPort) "/v1")
            manager (provider/create! {:home (str directory "/home")
                                       :settings {:provider-options {:openai {:base-url url}}}})]
        (try
          (provider/register! manager :local {:type :openai-compatible
                                              :base-url url :auth-strategy :none
                                              :models [{:id "local-model" :tools? true
                                                        :thinking-levels [:none :medium]
                                                        :input [:text :image]
                                                        :max-output-tokens 1024}]})
          (f manager requests)
          (finally (provider/close! manager))))
      (finally
        (.stop server 0)
        (fixtures/remove-directory! directory)))))

(defn- failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest local-stream-preserves-tool-text-usage-and-exact-model
  (serve (atom {:status 200 :payload successful-stream})
         (fn [manager requests]
           (let [response (provider/complete! manager request {:provider :local})]
             (is (= :local (:response/provider response)))
             (is (= "local-model" (:response/model response)))
             (is (= "Working now" (-> response :response/parts first :text)))
             (is (= "{\"source\":\"(+ 1 1)\"}"
                    (-> response :response/tool-calls first :tool-call/arguments)))
             (is (= 8 (get-in response [:response/usage :usage/input-tokens])))
             (is (= 12 (get-in response [:response/usage :usage/cached-input-tokens])))
             (is (= 28 (get-in response [:response/usage :usage/total-tokens])))
             (is (= "local-model" (:model (first @requests))))
             (is (= true (:stream (first @requests))))))))

(deftest invalid-model-capability-and-effort-never-reach-transport
  (serve (atom {:status 200 :payload successful-stream})
         (fn [manager requests]
           (doseq [[change code] [[{:request/model "LOCAL-model"} "model"]
                                  [{:request/reasoning {:enabled true :effort :max}} "thinking"]
                                  [{:request/max-tokens 1025} "output"]
                                  [{:request/messages [{:message/role :user
                                                        :message/content [{:part/type :file
                                                                           :file/name "file.txt"
                                                                           :file/content "a"}]}]} "input"]]]
             (is (= code (:error/code (failure #(provider/complete! manager
                                                                  (merge request change)
                                                                  {:provider :local}))))))
           (is (empty? @requests))
           (is (= "Working now" (-> (provider/complete! manager
                                                        (assoc request :request/reasoning
                                                               {:enabled true :effort :medium})
                                                        {:provider :local})
                                    :response/parts first :text))))))

(deftest http-and-malformed-streams-have-actionable-errors
  (let [reply (atom {:status 401 :payload "{\"error\":{\"message\":\"bad key\"}}"})]
    (serve reply
           (fn [manager _]
             (doseq [[status code] [[401 "provider/auth"] [429 "provider/rate-limit"]
                                    [500 "provider/server"] [503 "provider/overloaded"]]]
               (reset! reply {:status status :payload "{\"error\":{\"message\":\"fixture\"}}"})
               (let [data (failure #(provider/complete! manager request {:provider :local}))]
                 (is (= code (:error/code data)))
                 (is (= status (:status data)))))
             (doseq [payload [(str (sse-chunk {:choices [{:delta {:content "partial"}}]})
                                        "data: {bad json}\n\n")
                              (str (sse-chunk {:choices [{:delta {:content "partial"}}]})
                                   "data: [DONE]\n\n")
                              (str (sse-chunk {:choices [{:delta {:tool_calls
                                                              [{:index 0 :id "call_1"
                                                                :function {:name "repl"
                                                                           :arguments "{\"source\":"}}]}
                                                        :finish_reason "tool_calls"}]})
                                   "data: [DONE]\n\n")]]
               (reset! reply {:status 200 :payload payload})
               (let [data (failure #(provider/complete! manager request {:provider :local}))]
                 (is (= "incomplete-stream" (:error/code data)))
                 (is (not (:retryable? data)))))))))

(deftest cancellation-before-connect-and-during-stream
  (serve (atom {:status 200 :payload successful-stream})
         (fn [manager requests]
           (is (= "provider/cancelled" (:error/code (failure #(provider/complete!
                                                    manager request
                                                    {:provider :local :cancelled? (constantly true)})))))
           (is (empty? @requests))
           (let [cancelled (atom false)
                 data (failure #(provider/complete! manager request
                                                    {:provider :local
                                                     :cancelled? (fn [] @cancelled)
                                                     :on-event (fn [event]
                                                                 (when (= :stream/content-delta
                                                                          (:event/type event))
                                                                   (reset! cancelled true)))}))]
             (is (= :provider/cancelled (:error/type data)))
             (is (= 1 (count @requests)))))))

(deftest sdk-profile-alias-uses-local-transport-and-preserves-replay
  (serve (atom {:status 200 :payload successful-stream})
         (fn [manager requests]
           (provider/register! manager :local-sdk {:type :profile-alias :provider :openai
                                                   :base-url (get-in (:settings manager)
                                                                     [:provider-options :openai :base-url])
                                                   :models [{:id "local-model" :tools? true}]})
           (provider/login! manager :local-sdk {:type :api-key :api-key "fixture-secret"})
           (let [response (provider/complete! manager request {:provider :local-sdk})]
             (is (= :local-sdk (:response/provider response)))
             (is (= "local-model" (:response/model response)))
             (is (= "Working now" (-> response :response/parts first :text)))
             (is (= 12 (get-in response [:response/usage :usage/cached-input-tokens])))
             (provider/complete! manager
                                 (assoc request :request/messages
                                        (into (:request/messages request)
                                              [{:message/role :assistant
                                                :message/content [{:part/type :text
                                                                   :text "Working now"}]
                                                :message/tool-calls (:response/tool-calls response)}
                                               {:message/role :tool
                                                :message/tool-call-id "call_1"
                                                :message/name "repl"
                                                :message/content "2"}]))
                                 {:provider :local-sdk})
             (let [messages (:messages (last @requests))]
               (is (= "local-model" (:model (last @requests))))
               (is (= "call_1" (get-in messages [1 :tool_calls 0 :id])))
               (is (= "{\"source\":\"(+ 1 1)\"}"
                      (get-in messages [1 :tool_calls 0 :function :arguments])))
               (is (= "call_1" (get-in messages [2 :tool_call_id]))))))))
