(ns arrodes.provider-regression-test
  (:require [arrodes.auth :as auth]
            [arrodes.provider :as provider]
            [arrodes.session-test :as fixtures]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as sdk-http])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.io ByteArrayInputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def request
  {:request/model "test-model"
   :request/messages [{:message/role :user :message/content "Hello"}]})

(defn- with-manager [f]
  (let [directory (fixtures/temp-directory)
        manager (provider/create! {:home (str directory "/home") :settings {}})]
    (try
      (f manager)
      (finally
        (provider/close! manager)
        (fixtures/remove-directory! directory)))))

(defn- response-handler [requests label]
  (reify HttpHandler
    (handle [_ exchange]
      (with-open [body (.getRequestBody exchange)]
        (slurp body))
      (swap! requests conj
             {:path (-> exchange .getRequestURI .getPath)
              :authorization (-> exchange .getRequestHeaders
                                 (.getFirst "Authorization"))
              :api-key (-> exchange .getRequestHeaders
                           (.getFirst "x-api-key"))})
      (let [payload (str "data: {\"id\":\"local\",\"model\":\"test-model\","
                         "\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                         label "\"},\"finish_reason\":null}]}\n\n"
                         "data: {\"id\":\"local\",\"model\":\"test-model\","
                         "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                         "data: [DONE]\n\n")
            bytes (.getBytes payload StandardCharsets/UTF_8)]
        (.set (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
        (.sendResponseHeaders exchange 200 (alength bytes))
        (with-open [output (.getResponseBody exchange)]
          (.write output bytes))))))

(deftest custom-profiles-use-only-their-declared-authentication
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (try
      (.createContext server "/none/chat/completions"
                      (response-handler requests "none"))
      (.createContext server "/header/chat/completions"
                      (response-handler requests "header"))
      (.start server)
      (let [base (str "http://127.0.0.1:" (-> server .getAddress .getPort))]
        (with-manager
          (fn [manager]
            (provider/register! manager :github-copilot
                                {:type :openai-compatible
                                 :base-url (str base "/none")
                                 :auth-strategy :none
                                 :models [{:id "test-model"}]})
            (provider/register! manager :header-auth
                                {:type :openai-compatible
                                 :base-url (str base "/header")
                                 :auth-strategy :api-key-header
                                 :auth-header-name "x-api-key"
                                 :models [{:id "test-model"}]})
            (provider/login! manager :header-auth
                             {:type :api-key :api-key "dummy-custom-key"})
            (is (= "none" (-> (provider/complete! manager request
                                                  {:provider :github-copilot})
                              :response/parts first :text)))
            (is (= "header" (-> (provider/complete! manager request
                                                    {:provider :header-auth})
                                :response/parts first :text)))
            (is (= [{:path "/none/chat/completions"
                     :authorization nil :api-key nil}
                    {:path "/header/chat/completions"
                     :authorization nil :api-key "dummy-custom-key"}]
                   @requests)))))
      (finally
        (.stop server 0)))))

(deftest anthropic-model-discovery-preserves-sdk-authentication-metadata
  (with-manager
    (fn [manager]
      (let [captured (atom nil)]
        (provider/login! manager :anthropic
                         {:type :api-key :api-key "console-key-without-a-speculative-prefix"})
        (with-redefs [auth/request! (fn [request]
                                      (reset! captured request)
                                      {:data []})]
          (provider/refresh! manager :anthropic))
        (is (= "https://api.anthropic.com/v1/models" (:url @captured)))
        (is (= "console-key-without-a-speculative-prefix"
               (get-in @captured [:headers "x-api-key"])))
        (is (= "2023-06-01" (get-in @captured [:headers "anthropic-version"])))
        (is (nil? (get-in @captured [:headers "Authorization"])))))))

(deftest replacing-profile-invalidates-live-catalog-and-refresh-metadata
  (with-manager
    (fn [manager]
      (provider/register! manager :replaceable
                          {:type :openai-compatible
                           :base-url "https://old.invalid/v1"
                           :auth-strategy :none
                           :models [{:id "configured-old"}]})
      (with-redefs [auth/request! (fn [_] {:data [{:id "live-old"}]})]
        (provider/refresh! manager :replaceable))
      (is (provider/model manager :replaceable "live-old"))
      (provider/register! manager :replaceable
                          {:type :openai-compatible
                           :base-url "https://new.invalid/v1"
                           :auth-strategy :none
                           :refreshable? false
                           :replace? true
                           :models [{:id "configured-new"}]})
      (is (nil? (provider/model manager :replaceable "live-old")))
      (is (= "configured-new"
             (:id (provider/model manager :replaceable "configured-new"))))
      (is (not (contains? @(:last-refresh manager) :replaceable))))))

(defn- anthropic-stream [stop?]
  (apply str
         (map #(str "data: " (json/write-str %) "\n\n")
              (cond-> [{:type "message_start" :message {:id "fixture" :model "test-model"
                                                          :usage {:input_tokens 10}}}
                       {:type "content_block_start" :index 0
                        :content_block {:type "tool_use" :id "call-1" :name "_repl" :input {}}}
                       {:type "content_block_delta" :index 0
                        :delta {:type "input_json_delta" :partial_json "{\"source\":\"(+ 1 2)\"}"}}
                       {:type "content_block_stop" :index 0}
                       {:type "message_delta" :delta {:stop_reason "tool_use"}
                        :usage {:output_tokens 5}}]
                stop? (conj {:type "message_stop"})))))

(deftest anthropic-oauth-streams-through-alias-without-leaking-api-key
  (with-manager
    (fn [manager]
      (let [captured (atom nil) events (atom []) closed? (atom false)
            credential {:type :oauth :access-token "opaque-oauth-token"
                        :refresh-token "owner-refresh-token"
                        :expires-at Long/MAX_VALUE :source :stored-oauth}]
        (auth/put-credential! (:auth manager) :anthropic credential)
        (provider/register! manager :claude-alias {:type :profile-alias :provider :anthropic})
        (with-redefs [sdk/list-models (fn [_] [{:model/id "test-model"
                                              :model/capabilities #{:chat :streaming :tools}}])
                      sdk-http/sse-response
                      (fn [req]
                        (reset! captured req)
                        {:status 200 :headers {}
                         :body (proxy [ByteArrayInputStream]
                                      [(.getBytes (anthropic-stream true) StandardCharsets/UTF_8)]
                                 (close [] (reset! closed? true) (proxy-super close)))})]
          (let [response (provider/complete! manager
                                             (assoc request :request/tools
                                                    [{:type :function :function {:name "repl" :description "Evaluate"
                                                                                :parameters {:type "object"}}}])
                                             {:provider :claude-alias :on-event #(swap! events conj %)})]
            (is (= :claude-alias (:response/provider response)))
            (is (= "repl" (-> response :response/tool-calls first :tool-call/name)))
            (is (= :tool-calls (:response/finish-reason response)))
            (is (= "Bearer opaque-oauth-token" (get-in @captured [:headers "authorization"])))
            (is (not-any? #(= "x-api-key" (clojure.string/lower-case (name %)))
                          (keys (:headers @captured))))
            (is (= "_repl" (get-in @captured [:body :tools 0 :name])))
            (is @closed?)
            (is (some #(= "repl" (:tool-call/name %)) @events))))
        (is (= credential (auth/credential (:auth manager) :anthropic)))
        (is (nil? (auth/credential (:auth manager) :claude-alias)))
        (is (= [:api-key :oauth]
               (:auth-modes (some #(when (= :anthropic (:provider %)) %)
                                  (:providers (provider/status manager))))))))))

(deftest anthropic-oauth-requires-message-stop-not-just-stop-reason
  (with-manager
    (fn [manager]
      (auth/put-credential! (:auth manager) :anthropic
                            {:type :oauth :access-token "opaque" :expires-at Long/MAX_VALUE})
      (let [closed? (atom false)]
        (with-redefs [sdk/list-models (constantly [])
                      sdk-http/sse-response
                      (fn [_] {:status 200
                               :body (proxy [ByteArrayInputStream]
                                            [(.getBytes (anthropic-stream false) StandardCharsets/UTF_8)]
                                       (close [] (reset! closed? true) (proxy-super close)))})]
          (is (thrown? clojure.lang.ExceptionInfo
                       (provider/complete! manager request {:provider :anthropic}))))
        (is @closed?)))))

(deftest anthropic-oauth-discovery-and-refresh-use-credential-owner
  (with-manager
    (fn [manager]
      (auth/put-credential! (:auth manager) :anthropic
                            {:type :oauth :access-token "old" :refresh-token "owner-refresh"
                             :expires-at Long/MAX_VALUE :source :stored-oauth})
      (provider/register! manager :claude-alias {:type :profile-alias :provider :anthropic})
      (let [captured (atom [])]
        (with-redefs [auth/request! (fn [req]
                                     (swap! captured conj req)
                                     (if (= :post (:method req))
                                       {:access_token "fresh" :expires_in 3600}
                                       {:data [{:id "test-model"}]}))]
          (is (= :refreshed (:status (provider/refresh-auth! manager :claude-alias))))
          (provider/refresh! manager :anthropic))
        (is (= "owner-refresh" (get-in @captured [0 :body :refresh_token])))
        (is (= "Bearer fresh" (get-in @captured [1 :headers "authorization"])))
        (is (nil? (get-in @captured [1 :headers "x-api-key"])))
        (is (= "2023-06-01" (get-in @captured [1 :headers "anthropic-version"])))
        (is (nil? (auth/credential (:auth manager) :claude-alias)))))))

(deftest anthropic-oauth-refuses-custom-endpoints-and-hosted-search
  (with-manager
    (fn [manager]
      (auth/put-credential! (:auth manager) :anthropic
                            {:type :oauth :access-token "opaque" :expires-at Long/MAX_VALUE})
      (provider/register! manager :claude-proxy
                          {:type :profile-alias :provider :anthropic :base-url "https://proxy.invalid/v1"})
      (with-redefs [sdk/list-models (constantly [])
                    sdk-http/sse-response (fn [_] (throw (AssertionError. "Unexpected inference")))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (provider/complete! manager request {:provider :claude-proxy})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (provider/web-search! manager {:provider :anthropic :model "test-model"
                                                    :query "fixture"} {})))))))

(deftest explicit-logout-removes-a-legacy-copilot-credential
  (with-manager
    (fn [manager]
      (auth/put-credential! (:auth manager) :github-copilot
                            {:type :oauth
                             :access-token "legacy"
                             :refresh-token "legacy-refresh"
                             :source :stored-oauth})
      (is (nil? (get @(:profiles manager) :github-copilot)))
      (is (= {:provider :github-copilot :status :logged-out}
             (provider/logout! manager :github-copilot)))
      (is (nil? (auth/credential (:auth manager) :github-copilot))))))

(deftest provider-stream-errors-fail-and-close-the-sdk-stream
  (with-manager
    (fn [manager]
      (let [closed? (atom false)
            payload (str "data: {\"type\":\"response.output_text.delta\","
                         "\"delta\":\"partial\"}\n\n"
                         "data: {\"type\":\"response.failed\","
                         "\"response\":{\"model\":\"test-model\","
                         "\"error\":{\"message\":\"provider exploded\"}}}\n\n")
            body (proxy [ByteArrayInputStream]
                        [(.getBytes payload StandardCharsets/UTF_8)]
                   (close []
                     (reset! closed? true)
                     (proxy-super close)))
            captured (atom nil)
            error (atom nil)]
        (auth/put-credential! (:auth manager) :codex-backend
                              {:type :oauth
                               :access-token "dummy-caller-token"
                               :refresh-token "dummy-refresh-token"
                               :expires-at Long/MAX_VALUE
                               :source :stored-oauth})
        (with-redefs [sdk/list-models (fn [_] [{:model/id "test-model"
                                                :model/capabilities #{:chat :streaming :tools}}])
                      sdk-http/sse-response (fn [request]
                                              (reset! captured request)
                                              {:status 200 :headers {} :body body})]
          (try
            (provider/complete! manager request {:provider :codex-backend})
            (catch clojure.lang.ExceptionInfo e
              (reset! error e))))
        (is (= "Bearer dummy-caller-token"
               (get-in @captured [:headers "Authorization"])))
        (is (instance? clojure.lang.ExceptionInfo @error))
        (is (= "partial"
               (-> @error ex-data :partial-response :response/parts first :text)))
        (is (= "provider exploded"
               (-> @error ex-data :stream/error :error/message)))
        (is @closed?)))))
