(ns arrodes.web-provider-test
  (:require [arrodes.auth :as auth]
            [arrodes.provider :as provider]
            [arrodes.session-test :as fixtures]
            [arrodes.web.data :as web]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors TimeUnit]))

(def ^:private url "https://sources.example/one")
(def ^:private url-two "https://sources.example/two")
(def ^:private query "grounded fixture question")
(def ^:private answer "Grounded fixture answer.")
(def ^:private source-row {:url url :title "First source" :snippet "Actual search snippet"})
(def ^:private annotation {:type "url_citation" :url url :title "First source"
                          :start_index 0 :end_index 8})

(def ^:private responses-result
  {:id "response-fixture" :model "fixture-model" :status "completed"
   :output [{:type "web_search_call" :status "completed"
             :action {:type "search" :queries [query]
                      :sources [source-row {:url url-two :title "Second source"}]}}
            {:type "message" :status "completed" :role "assistant"
             :content [{:type "output_text" :text answer :annotations [annotation]}]}]
   :usage {:input_tokens 10 :output_tokens 3 :total_tokens 13}})

(def ^:private gemini-result
  {:candidates [{:finishReason "STOP"
                 :content {:role "model" :parts [{:text answer}]}
                 :groundingMetadata
                 {:webSearchQueries [query]
                  :groundingChunks [{:web {:uri url :title "First source"}}
                                    {:web {:uri url-two :title "Second source"}}]
                  :groundingSupports [{:segment {:text "Grounded" :startIndex 0 :endIndex 8}
                                       :groundingChunkIndices [0]}]}}]
   :usageMetadata {:promptTokenCount 10 :candidatesTokenCount 3 :totalTokenCount 13}})

(def ^:private router-result
  {:id "router-fixture" :model "fixture-model"
   :choices [{:index 0 :finish_reason "stop"
              :message {:role "assistant" :content answer
                        :annotations [{:type "url_citation" :url_citation annotation}
                                      {:type "url_citation"
                                       :url_citation {:url url-two :title "Second source"}}]}}]
   :usage {:prompt_tokens 10 :completion_tokens 3 :total_tokens 13 :cost 0.001}})

(def ^:private perplexity-result
  {:id "agent-fixture" :model "perplexity/fixture-model" :status "completed"
   :output [{:type "search_results"
             :results [(assoc source-row :id 1 :date "2026-10-01")
                       {:id 2 :url url-two :title "Second source"}]}
            {:type "message" :role "assistant" :status "completed"
             :content [{:type "output_text" :text answer :annotations [annotation]}]}]
   :usage {:input_tokens 10 :output_tokens 3 :total_tokens 13}})

(def ^:private anthropic-result
  {:id "messages-fixture" :type "message" :role "assistant" :model "claude-fixture"
   :stop_reason "end_turn"
   :content [{:type "server_tool_use" :id "srvtoolu_1" :name "web_search"
              :input {:query query}}
             {:type "web_search_tool_result" :tool_use_id "srvtoolu_1"
              :content [{:type "web_search_result" :url url :title "First source"
                         :encrypted_content "encrypted-source"}
                        {:type "web_search_result" :url url-two :title "Second source"}]}
             {:type "text" :text answer
              :citations [{:type "web_search_result_location" :url url
                           :title "First source" :cited_text "Grounded"}]}]
   :usage {:input_tokens 10 :output_tokens 3 :server_tool_use {:web_search_requests 1}}})

(def ^:private families
  [{:id :search-openai :sdk-id :openai :path "/openai" :model "fixture-model"
    :route "/openai/responses" :result responses-result}
   {:id :search-responses :sdk-id :codex :path "/responses" :model "fixture-model"
    :route "/responses/responses" :result responses-result}
   {:id :search-oauth :sdk-id :codex-backend :path "/oauth" :model "fixture-model"
    :route "/oauth/responses" :result responses-result :sse? true}
   {:id :search-gemini :sdk-id :gemini-native :path "/gemini" :model "gemini-fixture"
    :route "/gemini/models/gemini-fixture:generateContent" :result gemini-result}
   {:id :search-router :sdk-id :openrouter :path "/router" :model "fixture-model"
    :route "/router/chat/completions" :result router-result}
   {:id :search-perplexity :sdk-id :perplexity :path "/perplexity"
    :model "perplexity/fixture-model" :route "/perplexity/v1/agent" :result perplexity-result}
   {:id :search-anthropic :sdk-id :anthropic :path "/anthropic" :model "claude-fixture"
    :route "/anthropic/messages" :result anthropic-result}])

(defn- sse [data]
  (str "data: " (json/write-str data) "\n\n"))

(defn- reply-for [{:keys [result sse?]}]
  {:status 200 :content-type (if sse? "text/event-stream" "application/json")
   :payload (if sse? (sse {:type "response.completed" :response result})
                (json/write-str result))})

(defn- serve [f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)
        requests (atom [])
        replies (atom (into {} (map (fn [family] [(:route family) (reply-for family)])) families))
        directory (fixtures/temp-directory)]
    (try
      (.setExecutor server executor)
      (.createContext
       server "/"
       (reify HttpHandler
         (handle [_ exchange]
           (try
             (let [path (-> exchange .getRequestURI .getPath)
                   body (with-open [input (.getRequestBody exchange)]
                          (json/read-str (slurp input) :key-fn keyword))
                   headers (.getRequestHeaders exchange)
                   {:keys [status content-type payload hold-ms]} (get @replies path)
                   bytes (.getBytes (or payload "") StandardCharsets/UTF_8)]
               (swap! requests conj {:path path :body body
                                     :authorization (.getFirst headers "Authorization")
                                     :api-key (.getFirst headers "x-api-key")
                                     :google-key (.getFirst headers "x-goog-api-key")
                                     :account-id (.getFirst headers "ChatGPT-Account-ID")
                                     :anthropic-version (.getFirst headers "anthropic-version")})
               (.set (.getResponseHeaders exchange) "Content-Type" (or content-type "application/json"))
               (.sendResponseHeaders exchange (int (or status 404))
                                     (if hold-ms 0 (long (alength bytes))))
               (with-open [output (.getResponseBody exchange)]
                 (.write output bytes)
                 (.flush output)
                 (when hold-ms (Thread/sleep hold-ms))))
             (catch Exception _)
             (finally (.close exchange))))))
      (.start server)
      (let [base (str "http://127.0.0.1:" (-> server .getAddress .getPort))
            manager (provider/create! {:home (str directory "/home") :settings {}})]
        (try
          (doseq [{:keys [id sdk-id path model]} families]
            (provider/register! manager id
                                {:type :profile-alias :provider sdk-id :base-url (str base path)
                                 :models [{:id model :tools? true}]})
            ;; A different underlying profile key catches cross-profile fallback.
            (auth/put-credential! (:auth manager) sdk-id
                                  {:type :api-key :secret (str "underlying-" (name sdk-id))})
            (if (= sdk-id :codex-backend)
              (auth/put-credential! (:auth manager) id
                                    {:type :oauth :access-token (str "owned-" (name id))
                                     :account-id "fixture-account" :expires-at Long/MAX_VALUE})
              (provider/login! manager id {:type :api-key :api-key (str "owned-" (name id))})))
          (f manager requests replies)
          (finally (provider/close! manager))))
      (finally
        (.stop server 0)
        (.shutdownNow executor)
        (.awaitTermination executor 2 TimeUnit/SECONDS)
        (fixtures/remove-directory! directory)))))

(defn- search [manager family options]
  (provider/web-search! manager {:provider (:id family) :model (:model family)
                                :query query :limit 1 :max-tokens 128 :timeout-ms 3000} options))

(defn- failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest native-search-protocols-produce-grounded-qualified-results
  (serve
   (fn [manager requests _]
     (doseq [{:keys [id sdk-id route model] :as family} families]
       (testing (name id)
         (let [result (search manager family {})
               wire (last @requests)
               body (:body wire)]
           (is (s/valid? ::web/search-result result))
           (is (= :hosted (::web/backend result)))
           (is (= id (::web/provider result)))
           (is (= model (::web/model result)))
           (is (= answer (::web/answer result)))
           (is (= [url] (mapv ::web/url (::web/sources result))))
           (is (= [url] (mapv ::web/url (::web/citations result))))
           (is (= 3 (get-in result [::web/usage :usage/output-tokens])))
           (if (= sdk-id :openrouter)
             (do (is (= 0.001 (get-in result [::web/cost :cost/usd])))
                 (is (= false (get-in result [::web/cost :cost/estimated?]))))
             (is (nil? (::web/cost result))))
           (is (= route (:path wire)))
           (is (= (if (= sdk-id :anthropic) nil (when-not (= sdk-id :gemini-native)
                                                  (str "Bearer owned-" (name id))))
                  (:authorization wire)))
           (case sdk-id
             :gemini-native (do (is (= (str "owned-" (name id)) (:google-key wire)))
                                (is (= [{:googleSearch {}}] (:tools body))))
             :anthropic (do (is (= (str "owned-" (name id)) (:api-key wire)))
                            (is (= "2023-06-01" (:anthropic-version wire)))
                            (is (= [{:type "web_search_20250305" :name "web_search" :max_uses 1}]
                                   (:tools body))))
             :openrouter (do (is (= [{:id "web" :max_results 1}] (:plugins body)))
                             (is (nil? (:tools body))))
             :perplexity (is (= [{:type "web_search" :max_results 1}] (:tools body)))
             (:openai :codex :codex-backend)
             (do (is (= [{:type "web_search"}] (:tools body)))
                 (is (= "required" (:tool_choice body)))
                 (when (= sdk-id :codex-backend)
                   (is (= "fixture-account" (:account-id wire)))
                   (is (= true (:stream body)))
                   (is (nil? (:max_output_tokens body))))))
           (when-not (= sdk-id :gemini-native) (is (= model (:model body))))))))))

(deftest normal-completion-and-search-share-auth-without-sharing-tools-or-routes
  (serve
   (fn [manager requests replies]
     (swap! replies assoc "/openai/chat/completions"
            {:status 200 :content-type "text/event-stream"
             :payload (str (sse {:choices [{:index 0 :delta {:content "Coding completion"}
                                            :finish_reason "stop"}]}) "data: [DONE]\n\n")})
     (let [response (provider/complete!
                     manager {:request/model "fixture-model"
                              :request/messages [{:message/role :user :message/content "Code"}]
                              :request/tools [{:type :function :function {:name "repl"
                                                                         :parameters {:type "object"}}}]}
                     {:provider :search-openai})
           result (search manager (first families) {})
           [coding research] @requests]
       (is (= "Coding completion" (-> response :response/parts first :text)))
       (is (= answer (::web/answer result)))
       (is (= "/openai/chat/completions" (:path coding)))
       (is (= "/openai/responses" (:path research)))
       (is (= "repl" (get-in coding [:body :tools 0 :function :name])))
       (is (= "web_search" (get-in research [:body :tools 0 :type])))
       (is (= "Bearer owned-search-openai" (:authorization coding) (:authorization research)))))))

(deftest filters-and-source-caps-are-honest
  (serve
   (fn [manager requests _]
     (doseq [family (remove #(= :perplexity (:sdk-id %)) families)]
       (is (= "provider/unsupported-option"
              (:error/code (failure #(provider/web-search! manager
                                                           {:provider (:id family) :model (:model family)
                                                            :query query :recency :week} {}))))))
     (is (empty? @requests))
     (let [family (first (filter #(= :perplexity (:sdk-id %)) families))]
       (provider/web-search! manager {:provider (:id family) :model (:model family)
                                     :query query :limit 2 :recency :week} {})
       (is (= {:type "web_search" :max_results 2 :filters {:search_recency_filter "week"}}
              (get-in (last @requests) [:body :tools 0])))))))

(defn- without-evidence [{:keys [sdk-id result]}]
  (case sdk-id
    (:openai :codex :codex-backend) (assoc result :output [{:type "message" :status "completed"
                                                         :content [{:type "output_text" :text "Memory only"}]}])
    :gemini-native (update-in result [:candidates 0] dissoc :groundingMetadata)
    :openrouter (update-in result [:choices 0 :message] dissoc :annotations)
    :perplexity (assoc result :output [{:type "message" :status "completed"
                                       :content [{:type "output_text" :text "Memory only"}]}])
    :anthropic (assoc result :content [{:type "text" :text "Memory only"}])))

(defn- unfinished [{:keys [sdk-id result]}]
  (case sdk-id
    (:openai :codex :codex-backend :perplexity) (assoc result :status "incomplete")
    :gemini-native (assoc-in result [:candidates 0 :finishReason] "MAX_TOKENS")
    :openrouter (assoc-in result [:choices 0 :finish_reason] "length")
    :anthropic (assoc result :stop_reason "max_tokens")))

(defn- function-continuation [{:keys [sdk-id result]}]
  (case sdk-id
    (:openai :codex :codex-backend :perplexity)
    (update result :output conj {:type "function_call" :id "fc_bad" :call_id "call_bad"
                                :name "repl" :arguments "{}" :status "completed"})
    :gemini-native (update-in result [:candidates 0 :content :parts]
                             conj {:functionCall {:name "repl" :args {}}})
    :openrouter (assoc-in result [:choices 0 :message :tool_calls]
                         [{:id "bad" :type "function" :function {:name "repl" :arguments "{}"}}])
    :anthropic (update result :content conj {:type "tool_use" :id "bad" :name "repl" :input {}})))

(deftest answer-only-errors-and-unfinished-functions-never-become-search-results
  (serve
   (fn [manager requests replies]
     (doseq [family families]
       (testing (name (:id family))
         (doseq [[change expected] [[without-evidence "provider/no-search-evidence"]
                                    [unfinished nil] [function-continuation nil]]]
           (swap! replies assoc (:route family) (reply-for (assoc family :result (change family))))
           (let [before (count @requests)
                 error (failure #(search manager family {}))]
             (is (some? error))
             (when expected (is (= expected (:error/code error))))
             (is (false? (:retryable? error)))
             (is (= (inc before) (count @requests)))))))
     (let [family (last families)]
       (swap! replies assoc (:route family)
              (reply-for (assoc family :result
                                (assoc anthropic-result :content
                                       [{:type "web_search_tool_result" :tool_use_id "srvtoolu_1"
                                         :content {:type "web_search_tool_result_error"
                                                   :error_code "too_many_requests"}}]))))
       (is (= "provider/search-failed" (:error/code (failure #(search manager family {})))))))))

(deftest malformed-wire-and-http-errors-are-closed-and-sanitized
  (serve
   (fn [manager requests replies]
     (doseq [family families]
       (swap! replies assoc (:route family)
              {:status 200 :payload (if (:sse? family)
                                     (sse {:type "response.output_text.delta" :delta "unfinished"})
                                     "{\"output\":")})
       (is (= "provider/incomplete-stream" (:error/code (failure #(search manager family {})))))
       (swap! replies assoc (:route family)
              {:status 401 :payload "{\"error\":{\"message\":\"owned-search-openai\"}}"})
       (let [error (failure #(search manager family {}))]
         (is (= "provider/auth" (:error/code error)))
         (is (not (str/includes? (pr-str error) "owned-search-openai")))))
     (is (= (* 2 (count families)) (count @requests))))))

(deftest cancellation-and-total-deadline-stop-silent-bodies-without-replay
  (serve
   (fn [manager requests replies]
     (let [family (first families)
           cancelled (atom false)
           received (promise)]
       (is (= "provider/cancelled" (:error/code (failure #(search manager family
                                                                                {:cancelled? (constantly true)})))))
       (is (empty? @requests))
       (add-watch requests ::request-started (fn [_ _ _ rows] (when (seq rows) (deliver received true))))
       (swap! replies assoc (:route family) {:status 200 :payload "{" :hold-ms 10000})
       (let [pending (future (failure #(search manager family {:cancelled? (fn [] @cancelled)})))]
         (is (= true (deref received 2000 :timed-out)))
         (reset! cancelled true)
         (is (= "provider/cancelled" (:error/code (deref pending 1000 {:error/code "test/timeout"})))))
       (is (= 1 (count @requests)))
       (remove-watch requests ::request-started)
       (let [start (System/nanoTime)
             error (failure #(provider/web-search! manager {:provider (:id family) :model (:model family)
                                                             :query query :timeout-ms 250} {}))]
         (is (= "provider/timeout" (:error/code error)))
         (is (< (/ (- (System/nanoTime) start) 1000000) 1500)))
       (is (= 2 (count @requests)))))))

(deftest codex-oauth-cancellation-abandons-the-native-stream-without-a-replay
  (serve
   (fn [manager requests replies]
     (let [family (first (filter :sse? families))
           cancelled (atom false)]
       (swap! replies assoc (:route family)
              {:status 200 :content-type "text/event-stream"
               :payload (str (sse {:type "response.output_text.delta" :delta "Grounded"})
                             (sse {:type "response.completed" :response responses-result}))})
       (is (= "provider/cancelled"
              (:error/code (failure #(search manager family
                                               {:cancelled? (fn [] @cancelled)
                                                :on-event (fn [event]
                                                            (when (= :stream/content-delta (:event/type event))
                                                              (reset! cancelled true)))})))))
       (is (= 1 (count @requests)))))))
