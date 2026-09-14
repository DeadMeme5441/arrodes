(ns arrodes.provider-regression-test
  (:require [arrodes.auth :as auth]
            [arrodes.provider :as provider]
            [arrodes.session-test :as fixtures]
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
            (provider/register! manager :no-auth
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
                                                   {:provider :no-auth})
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
                         {:type :api-key :api-key "dummy-anthropic-key"})
        (with-redefs [auth/request! (fn [request]
                                      (reset! captured request)
                                      {:data []})]
          (provider/refresh! manager :anthropic))
        (is (= "https://api.anthropic.com/v1/models" (:url @captured)))
        (is (= "dummy-anthropic-key" (get-in @captured [:headers "x-api-key"])))
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

(deftest profile-alias-refreshes-the-credential-owner
  (with-manager
    (fn [manager]
      (let [captured (atom nil)
            refreshes (atom 0)]
        (auth/put-credential! (:auth manager) :anthropic
                              {:type :oauth
                               :access-token "expired-token"
                               :refresh-token "owner-refresh-token"
                               :expires-at 0
                               :source :stored-oauth})
        (provider/register! manager :claude-alias
                            {:type :profile-alias :provider :anthropic})
        (with-redefs [auth/request! (fn [_]
                                      (swap! refreshes inc)
                                      {:access_token "fresh-token"
                                       :expires_in 3600})
                      sdk/complete (fn [provider-id canonical & options]
                                     (reset! captured
                                             {:provider provider-id
                                              :request canonical
                                              :options (apply hash-map options)})
                                     {:response/provider provider-id
                                      :response/model (:request/model canonical)
                                      :response/parts [{:part/type :text
                                                        :text "done"}]
                                      :response/finish-reason :stop})]
          (provider/complete! manager request {:provider :claude-alias}))
        (is (= 1 @refreshes))
        (is (= :anthropic (:provider @captured)))
        (is (= "fresh-token" (get-in @captured [:options :config :auth-token])))))))

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
        (with-redefs [sdk-http/sse-response (fn [request]
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
