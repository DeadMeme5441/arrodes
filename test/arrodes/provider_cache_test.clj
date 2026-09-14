(ns arrodes.provider-cache-test
  (:require [arrodes.provider :as provider]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as session-fixtures]
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
