(ns arrodes.provider-cache-test
  (:require [arrodes.provider :as provider]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as session-fixtures]
            [arrodes.run :as run]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- sse-handler [requests label]
  (reify HttpHandler
    (handle [_ exchange]
      (with-open [body (.getRequestBody exchange)]
        (slurp body))
      (swap! requests conj {:path (-> exchange .getRequestURI .getPath)
                            :method (.getRequestMethod exchange)
                            :authorization (-> exchange .getRequestHeaders
                                               (.getFirst "Authorization"))})
      (let [payload (str "data: {\"id\":\"local-" label
                         "\",\"model\":\"local-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                         label "\"},\"finish_reason\":null}]}\n\n"
                         "data: {\"id\":\"local-" label
                         "\",\"model\":\"local-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                         "data: [DONE]\n\n")
            bytes (.getBytes payload StandardCharsets/UTF_8)]
        (doto (.getResponseHeaders exchange)
          (.set "Content-Type" "text/event-stream"))
        (.sendResponseHeaders exchange 200 (alength bytes))
        (with-open [output (.getResponseBody exchange)]
          (.write output bytes))))))

(defn- provider-ids [manager]
  (set (keys @(:profiles manager))))

(deftest explicit-cache-controls-survive-the-stable-session-scope
  (let [requests (atom [])
        cache-options {:enabled? false
                       :strategy :none
                       :ttl "1h"
                       :breakpoints 2
                       :tools-cache? false
                       :scope-id "caller-supplied-scope"}
        complete (fn [request _]
                   (swap! requests conj request)
                   (fixtures/answer "Recorded"))]
    (fixtures/with-runtime [rt complete]
      (let [session (runtime/create-session!
                     rt {:name "Cache controls"
                         :config (assoc session-fixtures/config
                                        :settings {:cache cache-options})})
            sid (:id session)]
        (runtime/run! rt sid "First request" {})
        (runtime/run! rt sid "Second request" {})
        (let [[first-request second-request] @requests
              expected-cache (assoc cache-options :scope-id sid)
              prefix (:request/messages first-request)]
          (is (= expected-cache (:request/cache first-request)))
          (is (= expected-cache (:request/cache second-request)))
          (is (= prefix
                 (subvec (:request/messages second-request) 0 (count prefix)))))))))

(deftest session-provider-views-route-to-independent-project-endpoints
  (let [directory (session-fixtures/temp-directory)
        requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (try
      (.createContext server "/alpha/chat/completions" (sse-handler requests "alpha"))
      (.createContext server "/beta/chat/completions" (sse-handler requests "beta"))
      (.start server)
      (let [port (-> server .getAddress .getPort)
            root (provider/create! {:home (str directory "/home") :settings {}})
            profile (fn [path]
                      {:type :openai-compatible
                       :provider :openai
                       :base-url (str "http://127.0.0.1:" port "/" path)
                       :auth-strategy :none
                       :models [{:id "local-model"}]})
            alpha (provider/for-session root {:providers {:project-api (profile "alpha")}})
            beta (provider/for-session root {:providers {:project-api (profile "beta")}})
            request {:request/model "local-model"
                     :request/messages [{:message/role :user :message/content "Route locally"}]}]
        (try
          (is (not (contains? (provider-ids root) :project-api)))
          (is (= "alpha" (-> (provider/complete! alpha request {:provider :project-api})
                              :response/parts first :text)))
          (is (= {:closed? true} (provider/close! alpha)))
          (is (= "beta" (-> (provider/complete! beta request {:provider :project-api})
                             :response/parts first :text)))
          (is (= [{:path "/alpha/chat/completions" :method "POST"
                   :authorization nil}
                  {:path "/beta/chat/completions" :method "POST"
                   :authorization nil}]
                 @requests))
          (is (not (contains? (provider-ids root) :project-api)))
          (finally
            (provider/close! alpha)
            (provider/close! beta)
            (provider/close! root))))
      (finally
        (.stop server 0)
        (session-fixtures/remove-directory! directory)))))

(deftest wire-cache-prefix-and-reported-usage-survive-evaluator-reload
  (let [directory (session-fixtures/temp-directory)
        requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (try
      (.createContext server "/v1/chat/completions"
        (reify HttpHandler
          (handle [_ exchange]
            (let [request (with-open [body (.getRequestBody exchange)]
                            (json/read-str (slurp body) :key-fn keyword))
                  n (count (swap! requests conj request))
                  chunks [{:id (str "cache-" n) :model "cache-fixture"
                           :choices [{:index 0 :delta {:content "Measured reply"} :finish_reason nil}]}
                          {:id (str "cache-" n) :model "cache-fixture"
                           :choices [{:index 0 :delta {} :finish_reason "stop"}]}
                          {:id (str "cache-" n) :model "cache-fixture" :choices []
                           :usage {:prompt_tokens 1000 :completion_tokens 20 :total_tokens 1020
                                   :prompt_tokens_details {:cached_tokens (if (= n 1) 0 900)}}}]
                  payload (str (apply str (map #(str "data: " (json/write-str %) "\n\n") chunks))
                               "data: [DONE]\n\n")
                  bytes (.getBytes payload StandardCharsets/UTF_8)]
              (.set (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [body (.getResponseBody exchange)] (.write body bytes))))))
      (.start server)
      (let [rt (runtime/open!
                {:cwd directory :home (str directory "/home") :trust false
                 :settings {:providers
                            {:fixture {:type :openai-compatible :provider :openai
                                       :auth-strategy :none
                                       :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
                                       :models [{:id "cache-fixture" :thinking-levels [:none]}]}}}})]
        (try
          (let [sid (:id (runtime/create-session!
                          rt {:name "Wire caching"
                              :config {:provider :fixture :model "cache-fixture" :thinking :none}}))]
            (runtime/run! rt sid "First task")
            (runtime/run! rt sid "Second task")
            (runtime/reload! rt sid)
            (runtime/run! rt sid "Continue after reload")
            (let [[first-request second-request third-request] @requests
                  report (run/usage-report (runtime/active-path rt sid))]
              (is (= [sid sid sid] (mapv :prompt_cache_key @requests)))
              (doseq [[earlier later] [[first-request second-request] [second-request third-request]]]
                (is (= (:messages earlier)
                       (subvec (:messages later) 0 (count (:messages earlier)))))
                (is (= (:tools earlier) (:tools later))))
              (is (= 900 (get-in report [:latest-usage :usage/cached-input-tokens])))
              (is (= 100 (get-in report [:latest-usage :usage/input-tokens])))
              (is (= 1800 (get-in report [:totals :usage/cached-input-tokens])))
              (is (= 1200 (get-in report [:totals :usage/input-tokens])))
              (is (= 60 (get-in report [:totals :usage/output-tokens])))
              (is (= 3 (get-in report [:missing :usage/cache-write-tokens])))))
          (finally (runtime/close! rt))))
      (finally
        (.stop server 0)
        (session-fixtures/remove-directory! directory)))))
