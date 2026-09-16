(ns arrodes.context-recovery-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as sessions]
            [arrodes.run :as run]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (com.sun.net.httpserver HttpServer HttpHandler)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- summary? [request]
  (str/starts-with? (str (:message/content (last (:request/messages request))))
                    "Summarize the supplied conversation faithfully"))

(defn- overflow []
  (ex-info "Provider rejected input" {:status 400 :retryable? true
                                     :body {:error {:code "context_length_exceeded"
                                                    :message "Maximum context length exceeded"}}}))

(deftest identifies-context-rejections-without-confusing-rate-or-payload-limits
  (doseq [error [(overflow)
                 (ex-info "prompt is too long: 200 > 100 tokens" {:status 400})
                 (ex-info "Rejected" {:status 400 :body "{\"error\":{\"code\":\"context_length_exceeded\"}}"})
                 (ex-info "Provider streaming API error" {:status 400 :error {:error/message "Maximum context length exceeded"}})
                 (ex-info "input token count exceeds the maximum allowed" {:status 422})]]
    (is (run/context-overflow? error)))
  (doseq [error [(ex-info "Rate limit for long context" {:status 429})
                 (ex-info "Payload too large" {:status 413 :error {:error/should-compress true}})
                 (ex-info "max_output_tokens exceeds the limit" {:status 400})
                 (ex-info "Maximum context length unavailable during server outage" {:status 503})
                 (ex-info "Authentication failed" {:status 401})]]
    (is (not (run/context-overflow? error)))))

(deftest overflow-retries-only-the-provider-after-settled-repl-effects
  (let [normal (atom 0) summaries (atom 0) requests (atom []) phases (atom [])
        provider (fn [request _]
                   (swap! requests conj request)
                   (if (summary? request)
                     (do (swap! summaries inc) (fixtures/answer "Earlier context summarized"))
                     (case (swap! normal inc)
                       1 (fixtures/answer "Ready")
                       2 (fixtures/calls (fixtures/tool-call "write-once"
                                           "(def retained 42) (spit (str cwd \"/effects.txt\") \"effect\\n\" :append true) retained"))
                       3 (throw (overflow))
                       (fixtures/answer "Recovered"))))]
    (fixtures/with-runtime [rt provider]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "Initial context")
        (runtime/run! rt sid "Perform the effect"
                      {:on-event #(when (= :operation/phase (:type %))
                                    (swap! phases conj (get-in % [:data :phase])))})
        (is (= 4 @normal))
        (is (= 1 @summaries))
        (is (= [:compacting :provider] @phases))
        (is (= "effect\n" (slurp (str (:cwd (runtime/session rt sid)) "/effects.txt"))))
        (is (= 42 (:value (runtime/evaluate! rt sid "retained"))))
        (is (= 2 (count (filter #(= :user (get-in % [:data :message/role])) (runtime/entries rt sid)))))
        (is (= 1 (count (filter #(= "write-once" (get-in % [:data :message/tool-call-id])) (runtime/entries rt sid)))))
        (is (some #(str/includes? (str (:message/content %)) "Earlier context summarized")
                  (:request/messages (last @requests))))))))

(deftest repeated-overflow-stops-after-one-recovery-attempt
  (let [normal (atom 0) summaries (atom 0)
        provider (fn [request _]
                   (if (summary? request)
                     (do (swap! summaries inc) (fixtures/answer "Summary"))
                     (if (= 1 (swap! normal inc)) (fixtures/answer "Ready") (throw (overflow)))))]
    (fixtures/with-runtime [rt provider]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "First turn")
        (let [error (try (runtime/run! rt sid "Oversized latest turn") nil
                         (catch clojure.lang.ExceptionInfo e e))]
          (is (= "context-limit-unresolved" (:error/code (ex-data error))))
          (is (str/includes? (ex-message error) "one compaction retry")))
        (is (= 3 @normal))
        (is (= 1 @summaries))))))

(deftest missing-boundary-and-partial-output-do-not-loop-or-repeat-work
  (doseq [partial? [false true]]
    (let [calls (atom 0)
          provider (fn [_ options]
                     (swap! calls inc)
                     (when partial?
                       ((:on-event options) {:event/type :stream/content-delta :event/delta "Partial reply"}))
                     (throw (overflow)))]
      (fixtures/with-runtime [rt provider]
        (let [sid (:id (fixtures/create-session rt))
              error (try (runtime/run! rt sid "Only one turn") nil
                         (catch clojure.lang.ExceptionInfo e e))]
          (is (= 1 @calls))
          (if partial?
            (is (true? (:provider/output-started? (ex-data error))))
            (is (str/includes? (ex-message error) "no earlier turn")))
          (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid))))))))

(deftest failed-compaction-keeps-history-and-does-not-resubmit
  (let [normal (atom 0) summaries (atom 0)
        provider (fn [request _]
                   (if (summary? request)
                     (do (swap! summaries inc) (throw (ex-info "Summary unavailable" {:error/code "summary-unavailable" :retryable? false})))
                     (if (= 1 (swap! normal inc)) (fixtures/answer "Ready") (throw (overflow)))))]
    (fixtures/with-runtime [rt provider]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "Keep this history")
        (let [error (try (runtime/run! rt sid "Second turn") nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message error) "compaction failed")))
        (is (= 2 @normal))
        (is (= 1 @summaries))
        (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid)))))))

(deftest http-context-rejection-survives-sdk-classification-and-recovers
  (let [directory (sessions/temp-directory)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        normal (atom 0) summaries (atom 0)]
    (try
      (.createContext server "/v1/chat/completions"
        (reify HttpHandler
          (handle [_ exchange]
            (let [body (with-open [input (.getRequestBody exchange)] (slurp input))
                  summary? (str/includes? body "Summarize the supplied conversation")
                  overflow? (and (not summary?) (= 2 (swap! normal inc)))
                  _ (when summary? (swap! summaries inc))
                  text (if summary? "Earlier context" "Answer")
                  payload (if overflow?
                            (json/write-str {:error {:code "context_length_exceeded" :message "Maximum context length exceeded"}})
                            (str "data: " (json/write-str {:id "fixture" :model "offline"
                                                           :choices [{:index 0 :delta {:content text} :finish_reason nil}]})
                                 "\n\ndata: " (json/write-str {:id "fixture" :model "offline"
                                                               :choices [{:index 0 :delta {} :finish_reason "stop"}]})
                                 "\n\ndata: [DONE]\n\n"))
                  bytes (.getBytes payload StandardCharsets/UTF_8)]
              (.set (.getResponseHeaders exchange) "Content-Type" (if overflow? "application/json" "text/event-stream"))
              (.sendResponseHeaders exchange (if overflow? 400 200) (alength bytes))
              (with-open [output (.getResponseBody exchange)] (.write output bytes))))))
      (.start server)
      (let [rt (runtime/open! {:cwd directory :home (str directory "/home")
                               :settings {:providers {:fixture {:type :openai-compatible :provider :openai
                                                                 :auth-strategy :none
                                                                 :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
                                                                 :models [{:id "offline"}]}}}})]
        (try
          (let [sid (:id (runtime/create-session! rt {:name "HTTP recovery"
                                                     :config {:provider :fixture :model "offline" :thinking :none}}))]
            (runtime/run! rt sid "Initial turn")
            (runtime/run! rt sid "Trigger rejection")
            (is (= 3 @normal))
            (is (= 1 @summaries))
            (is (= :idle (:status (runtime/session rt sid)))))
          (finally (runtime/close! rt))))
      (finally (.stop server 0) (sessions/remove-directory! directory)))))

(deftest cancellation-during-recovery-prevents-summary-commit-and-retry
  (let [normal (atom 0) rt* (atom nil) sid* (atom nil)
        provider (fn [request _]
                   (if (summary? request)
                     (do (runtime/cancel! @rt* @sid*) (fixtures/answer "Cancelled summary"))
                     (if (= 1 (swap! normal inc)) (fixtures/answer "Ready") (throw (overflow)))))]
    (fixtures/with-runtime [rt provider]
      (let [sid (:id (fixtures/create-session rt))]
        (reset! rt* rt) (reset! sid* sid)
        (runtime/run! rt sid "First turn")
        (is (thrown? clojure.lang.ExceptionInfo (runtime/run! rt sid "Second turn")))
        (is (= 2 @normal))
        (is (= :interrupted (:status (runtime/session rt sid))))
        (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid)))))))
