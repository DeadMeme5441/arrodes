(ns arrodes.web-runtime-test
  (:require [arrodes.provider :as provider]
            [arrodes.runtime :as runtime]
            [arrodes.session-test :as fixtures]
            [arrodes.web.data :as web]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (com.sun.net.httpserver HttpServer HttpHandler)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- respond! [exchange type content]
  (let [bytes (.getBytes content StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" type)
    (.sendResponseHeaders exchange 200 (long (alength bytes)))
    (with-open [output (.getResponseBody exchange)] (.write output bytes))))

(defn- sse [packet] (str "data: " (json/write-str packet) "\n\n"))

(defn- evaluate! [rt sid source]
  (let [result (runtime/evaluate! rt sid source)]
    (when (:error? result) (throw (ex-info (:content result) (:details result))))
    result))

(deftest model-research-keeps-coding-identity-and-retains-sources-without-refetch
  (let [directory (fixtures/temp-directory)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        requests (atom [])
        turns (atom 0)
        live (atom nil)
        base (str "http://127.0.0.1:" (-> server .getAddress .getPort))
        url (str base "/guide")
        options {:cwd directory :home (str directory "/home") :data-dir (str directory "/data")
                 :settings {:web {:provider :search :model "research-model"}
                            :providers {:coding {:type :profile-alias :provider :openai :base-url (str base "/v1")
                                                 :models [{:id "coding-model" :tools? true :thinking-levels [:none]}]}
                                        :search {:type :profile-alias :provider :openrouter :base-url (str base "/v1")
                                                 :models [{:id "research-model" :tools? true :thinking-levels [:none]}]}}}}]
    (try
      (.createContext server "/guide"
        (reify HttpHandler
          (handle [_ exchange]
            (swap! requests conj {:read-page true})
            (respond! exchange "text/html; charset=utf-8"
                      "<html><title>Primary guide</title><main><h1>Evidence</h1><p>Original source facts.</p><pre>(+ 20 22)</pre></main></html>"))))
      (.createContext server "/v1/chat/completions"
        (reify HttpHandler
          (handle [_ exchange]
            (let [request (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)]
              (swap! requests conj request)
              (if (seq (:plugins request))
                (respond! exchange "application/json"
                          (json/write-str {:id "searched" :model "research-model"
                                           :choices [{:message {:role "assistant" :content "A grounded explanation."
                                                                :annotations [{:type "url_citation" :url_citation {:url url :title "Primary guide" :content "Original source facts."}}]}
                                                      :finish_reason "stop"}]}))
                (let [first? (= 1 (swap! turns inc))
                      source (str "(def research (web-search {:query \"Find primary guide\"})) "
                                  "(def page (web-read {:url " (pr-str url) "})) "
                                  "(assoc research ::web/content (::web/content page))")
                      delta (if first?
                              {:tool_calls [{:index 0 :id "research-call" :type "function"
                                             :function {:name "repl" :arguments (json/write-str {:source source})}}]}
                              {:content "Research complete"})]
                  (respond! exchange "text/event-stream"
                            (str (sse {:choices [{:index 0 :delta delta}]})
                                 (sse {:choices [{:index 0 :delta {} :finish_reason (if first? "tool_calls" "stop")}]})
                                 "data: [DONE]\n\n"))))))))
      (.start server)
      (reset! live (runtime/open! options))
      (let [rt @live sid (:id (runtime/create-session! rt {:name "Research lifecycle"
                                                         :config {:provider :coding :model "coding-model" :thinking :none}}))]
        (doseq [id [:coding :search]] (provider/login! (:provider rt) id {:type :api-key :api-key "fixture-secret"}))
        (runtime/run! rt sid "Research the primary guide")
        (is (= [url] (:value (evaluate! rt sid "(mapv ::web/url (::web/sources research))"))))
        (is (= "coding-model" (get-in (runtime/session rt sid) [:config :model])))
        (is (= :coding (get-in (runtime/session rt sid) [:config :provider])))
        (let [ordinary (filter #(= "coding-model" (:model %)) @requests)
              search (first (filter #(= "research-model" (:model %)) @requests))]
          (is (= 2 (count ordinary)))
          (is (every? #(and (not (seq (:plugins %))) (= ["repl"] (mapv (comp :name :function) (:tools %)))) ordinary))
          (is (= [] (vec (:tools search)))))
        (let [before @requests
              failure (:value (evaluate! rt sid
                               "(try (web-search {:query \"Find guide\" :provider \"unconfigured\"}) (catch clojure.lang.ExceptionInfo error (ex-data error)))"))]
          (is (= "model" (:error/code failure)))
          (is (= before @requests)))
        (let [descriptor (some #(when (= "research-call" (get-in % [:data :message/tool-call-id]))
                                  (get-in % [:data :message/result])) (runtime/entries rt sid))
              id (:id descriptor)
              before @requests]
          (is (str/includes? (:value (evaluate! rt sid "(::web/content page)")) "Original source facts."))
          (is (= :closed (:status (runtime/close! rt))))
          (reset! live nil)
          (reset! live (runtime/open! options))
          (let [retained (:value (evaluate! @live sid (str "(result " id ")")))]
            (is (= :search (::web/provider retained)))
            (is (= [url] (mapv ::web/url (::web/sources retained))))
            (is (str/includes? (::web/content retained) "Original source facts.")))
          (is (= before @requests))))
      (finally
        (when @live (runtime/close! @live))
        (.stop server 0)
        (fixtures/remove-directory! directory)))))

(deftest explicit-mcp-research-preserves-native-content-without-inventing-sources
  (let [directory (fixtures/temp-directory)
        script (str directory "/web-server.sh")
        call-file (str directory "/calls")
        text "Title: Report\nURL: https://example.test/report\nThis text is not a structured source row."
        packet {:structuredContent {:sources [{:url "https://example.test/report" :score 0.75}]}
                :content [{:type "text" :text text} {:type "image" :mimeType "image/png" :data "AA=="}]}
        source (str/join "\n"
                 ["while IFS= read -r line; do"
                  "  id=$(printf '%s' \"$line\" | sed -E 's/.*\"id\":(\"[^\"]*\"|[0-9]+).*/\\1/')"
                  "  case \"$line\" in"
                  "    *'\"method\":\"initialize\"'*)"
                  "      version=$(printf '%s' \"$line\" | sed -E 's/.*\"protocolVersion\":\"([^\"]+)\".*/\\1/')"
                  "      printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"%s\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"web\",\"version\":\"1\"}}}\\n' \"$id\" \"$version\" ;;"
                  "    *'tools'*'list'*)"
                  "      printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"tools\":[{\"name\":\"probe\",\"inputSchema\":{\"type\":\"object\"}}]}}\\n' \"$id\" ;;"
                  "    *'tools'*'call'*)"
                  (str "      printf '%s\\n' \"$line\" >> " (pr-str call-file))
                  (str "      printf '%s%s%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":' \"$id\" ',\"result\":" (json/write-str packet) "}' ;; ")
                  "  esac"
                  "done"])]
    (try
      (spit script source)
      (let [rt (runtime/open! {:cwd directory :home (str directory "/home") :memory? true
                               :settings {:mcp/servers {:research {:transport :stdio :command "/bin/sh" :args [script]}}}})]
        (try
          (let [sid (:id (runtime/create-session! rt {:name "MCP research"}))
                result (:value (evaluate! rt sid "(web-search {:query \"Find report\" :backend \"mcp\" :server \"research\" :tool \"probe\" :arguments {:objective \"Find primary evidence\"}})"))
                page (:value (evaluate! rt sid "(web-read {:url \"https://example.test/report\" :backend \"mcp\" :server \"research\" :tool \"probe\"})"))]
            (is (= [] (::web/sources result)))
            (is (= [] (::web/citations result)))
            (is (= 0.75 (get-in result [::web/structured-content :sources 0 :score])))
            (is (nil? (::web/final-url page)))
            (let [before (slurp call-file)
                  rejected (runtime/evaluate! rt sid "(web-search {:query \"Find report\" :backend \"mcp\" :server \"research\" :tool \"probe\" :recency \"day\"})")]
              (is (:error? rejected))
              (is (= before (slurp call-file)))))
          (finally (runtime/close! rt))))
      (finally (fixtures/remove-directory! directory)))))

(deftest native-recency-option-reaches-the-provider-filter
  (let [directory (fixtures/temp-directory)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        request (atom nil)
        base (str "http://127.0.0.1:" (-> server .getAddress .getPort))]
    (try
      (.createContext server "/v1/agent"
        (reify HttpHandler
          (handle [_ exchange]
            (reset! request (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword))
            (respond! exchange "application/json"
              (json/write-str {:id "filtered" :status "completed" :model "perplexity/sonar"
                               :output [{:type "search_results"
                                         :results [{:id 1 :url "https://example.test/recent" :title "Recent report"}]}]})))))
      (.start server)
      (let [rt (runtime/open! {:cwd directory :home (str directory "/home") :memory? true
                              :settings {:providers {:research {:type :profile-alias :provider :perplexity
                                                                 :base-url base
                                                                 :models [{:id "perplexity/sonar" :tools? true}]}}}})]
        (try
          (provider/login! (:provider rt) :research {:type :api-key :api-key "fixture-secret"})
          (let [sid (:id (runtime/create-session! rt {:name "Recency boundary"}))
                result (:value (evaluate! rt sid
                                "(web-search {:query \"Recent report\" :provider \"research\" :model \"perplexity/sonar\" :recency \"month\"})"))]
            (is (= ["https://example.test/recent"] (mapv ::web/url (::web/sources result))))
            (is (= "month" (get-in @request [:tools 0 :filters :search_recency_filter]))))
          (finally (runtime/close! rt))))
      (finally
        (.stop server 0)
        (fixtures/remove-directory! directory)))))
