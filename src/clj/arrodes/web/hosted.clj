(ns arrodes.web.hosted
  "Search-only native tools at the SDK request boundary; never an agent loop."
  (:require [arrodes.auth :as auth]
            [arrodes.web.data :as web]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [llm.sdk.http :as http]
            [llm.sdk.provider.auth :as sdk-auth]
            [llm.sdk.providers.anthropic.chat :as anthropic]
            [llm.sdk.providers.codex.responses :as responses]
            [llm.sdk.providers.gemini.native :as gemini]
            [llm.sdk.providers.openrouter.chat :as openrouter]
            [llm.sdk.providers.perplexity.chat :as perplexity]
            [llm.sdk.sse :as sse]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as transport])
  (:import [java.io InputStream InputStreamReader]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Callable ExecutionException FutureTask TimeUnit TimeoutException]))

(defn- fail! [type message]
  (throw (ex-info message {:error/code (str "provider/" (name type))
                          :error/type type :retryable? false})))

(defn- active! [options]
  (when (auth/cancelled? options)
    (fail! :provider/cancelled "Provider request cancelled")))

(defn bounded!
  "Own authentication and one HTTP attempt under a total deadline. Interrupting
   the worker cancels the HTTP connect; closing its body unblocks stalled reads."
  [timeout-ms options f]
  (let [caller-options options
        deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))
        stopped? (atom false)
        body (atom nil)
        options (assoc options :cancelled?
                       #(or @stopped? (auth/cancelled? caller-options)
                            (>= (System/nanoTime) deadline)))
        task (FutureTask. ^Callable (bound-fn [] (f options body)))
        worker (doto (Thread. task "arrodes-web-search") (.setDaemon true))]
    (active! caller-options)
    (.start worker)
    (try
      (loop []
        (when (auth/cancelled? caller-options)
          (fail! :provider/cancelled "Provider request cancelled"))
        (let [remaining (- deadline (System/nanoTime))]
          (when (not (pos? remaining))
            (fail! :timeout "Provider web search exceeded its total deadline"))
          (let [result (try
                         [:done (.get task (long (min 25 (max 1 (quot remaining 1000000))))
                                      TimeUnit/MILLISECONDS)]
                         (catch TimeoutException _ [:pending]))]
            (if (= :done (first result)) (second result) (recur)))))
      (catch ExecutionException e
        (cond
          (auth/cancelled? caller-options)
          (fail! :provider/cancelled "Provider request cancelled")
          (>= (System/nanoTime) deadline)
          (fail! :timeout "Provider web search exceeded its total deadline")
          :else (throw (.getCause e))))
      (finally
        (reset! stopped? true)
        (when-let [input @body]
          (try (.close ^InputStream input) (catch Exception _)))
        (.cancel task true)
        ;; The caller continues to own this work until the worker has exited.
        (let [interrupted? (volatile! (Thread/interrupted))]
          (try
            (loop []
              (when-not (try (.join worker) true
                             (catch InterruptedException _
                               (vreset! interrupted? true)
                               false))
                (recur)))
            (finally
              (when @interrupted? (.interrupt (Thread/currentThread))))))))))

(defn family [sdk-id]
  (case sdk-id
    (:openai :codex :codex-backend) :responses
    :gemini-native :gemini
    :openrouter :openrouter
    :perplexity :perplexity
    :anthropic :anthropic
    (fail! :provider/unsupported-search "Selected provider has no hosted web-search protocol")))

(defn request
  [family {:keys [model query limit recency max-tokens]
           :or {limit 5 max-tokens 2048}}]
  (when (and recency (not= family :perplexity))
    (fail! :provider/unsupported-option
           "Selected hosted web-search protocol does not support an explicit recency filter; use Perplexity or MCP"))
  {:request/model model
   :request/messages [{:message/role :system
                       :message/content "Perform a web search for the user's query. Use the hosted search tool, cite actual sources, and give a concise grounded answer. Do not answer from memory."}
                      {:message/role :user :message/content query}]
   :request/max-tokens max-tokens
   :request/cache {:enabled? false}
   :request/provider-options
   (case family
     :responses {:extra_body {:tools [{:type "web_search"}]
                              :tool_choice "required"}}
     :gemini {:extra_body {:tools [{:googleSearch {}}]}}
     :openrouter {:extra_body {:plugins [{:id "web" :max_results limit}]}}
     :perplexity {:perplexity {:max-steps 3
                              :web-search (cond-> {:max-results limit}
                                            recency (assoc :filters {:search-recency-filter recency}))}}
     ;; SDK 0.6.2 only serializes function tools for Messages. Inject the
     ;; native tool after its builder has produced the API-key request.
     :anthropic {})})

(defn- adapter [family]
  (case family
    :responses (responses/make-transport)
    :gemini (gemini/make-transport)
    :openrouter (openrouter/make-transport)
    :perplexity (perplexity/make-transport)
    :anthropic (anthropic/make-transport)))

(def ^:private max-response-bytes (* 8 1024 1024))

(defn- limited-stream [^InputStream input options]
  (let [read-count (volatile! 0)
        eof? (volatile! false)
        record! (fn [n]
                  (active! options)
                  (when (neg? n) (vreset! eof? true))
                  (when (and (pos? n) (> (vswap! read-count + n) max-response-bytes))
                    (fail! :provider/response-limit "Hosted web-search response exceeded 8 MiB"))
                  n)
        single (byte-array 1)]
    (proxy [InputStream] []
      (read
        ([] (if @eof? -1
                (let [n (.read input single 0 1)]
                  (record! n)
                  (if (neg? n) -1 (bit-and 255 (aget single 0))))))
        ([target] (if @eof? -1 (record! (.read input ^bytes target))))
        ([target offset length]
         (cond (zero? length) 0
               @eof? -1
               :else (record! (.read input ^bytes target (int offset) (int length))))))
      (close [] (.close input)))))

(defn- responses-stream! [input options]
  (loop [records (seq (sse/event-seq (http/line-seq-closeable input)))]
    (active! options)
    (if-let [record (first records)]
      (let [data (sse/parse-json-data record)
            type (:type data)]
        (when (and (sse/data-payload record) (nil? data))
          (fail! :provider/incomplete-stream "Hosted search returned malformed SSE data"))
        (when (contains? #{"error" "response.error" "response.failed" "response.incomplete"} type)
          (fail! :provider/search-failed "Hosted web search failed or did not complete"))
        (when-let [delta (when (= "response.output_text.delta" type) (:delta data))]
          (when-let [on-event (:on-event options)] (on-event (stream/content-delta delta)))
          (active! options))
        (if (= "response.completed" type)
          (or (:response data)
              (fail! :provider/incomplete-stream "Hosted search completed without a response"))
          (recur (next records))))
      (fail! :provider/incomplete-stream "Hosted search stream ended before response.completed"))))

(defn- usable-url? [url]
  (web/http-url? url))

(defn- source [row]
  (when (usable-url? (:url row))
    (cond-> {::web/url (:url row)}
      (string? (:title row)) (assoc ::web/title (:title row))
      (string? (:snippet row)) (assoc ::web/snippet (:snippet row))
      (string? (:date row)) (assoc ::web/date (:date row)))))

(defn- citation [row]
  (when (usable-url? (:url row))
    (cond-> {::web/url (:url row)}
      (string? (:title row)) (assoc ::web/title (:title row))
      (string? (:cited_text row)) (assoc ::web/cited-text (:cited_text row)))))

(defn- unique-sources [rows limit]
  (:rows (reduce (fn [{:keys [seen rows] :as acc} row]
                   (if (or (nil? row) (contains? seen (::web/url row)) (>= (count rows) limit))
                     acc
                     {:seen (conj seen (::web/url row)) :rows (conj rows row)}))
                 {:seen #{} :rows []} rows)))

(defn- native-errors! [raw]
  (doseq [node (tree-seq coll? #(if (map? %) (vals %) %) raw)
          :when (map? node)]
    (when (or (:error node)
              (= "web_search_tool_result_error" (:type node))
              (contains? #{"failed" "incomplete" "in_progress" "queued" "cancelled"}
                         (:status node)))
      (fail! :provider/search-failed "Hosted search returned an error or unfinished response"))
    (when (or (contains? #{"function_call" "custom_tool_call" "tool_use"} (:type node))
              (:functionCall node) (seq (:tool_calls node))
              (and (= "server_tool_use" (:type node)) (not= "web_search" (:name node))))
      (fail! :provider/search-failed "Hosted search returned an unsupported function/tool continuation"))))

(defn- annotations [parts]
  (for [part parts annotation (:annotations part)
        :when (= "url_citation" (:type annotation))]
    (or (:url_citation annotation) annotation)))

(defn- native-evidence [family raw]
  (case family
    (:responses :perplexity)
    (let [items (:output raw)
          parts (mapcat :content (filter #(= "message" (:type %)) items))
          citations (annotations parts)
          sources (if (= family :responses)
                    (mapcat #(get-in % [:action :sources])
                            (filter #(= "web_search_call" (:type %)) items))
                    (mapcat :results (filter #(= "search_results" (:type %)) items)))]
      (when-not (= "completed" (:status raw))
        (fail! :provider/incomplete-stream "Hosted search response was not completed"))
      {:sources sources :citations citations
       :answer (apply str (keep #(when (= "output_text" (:type %)) (:text %)) parts))
       :queries (when (= family :responses)
                  (mapcat #(let [action (:action %)]
                             (or (:queries action) (when-let [q (:query action)] [q])))
                          (filter #(= "web_search_call" (:type %)) items)))})

    :gemini
    (let [candidate (first (:candidates raw))
          grounding (:groundingMetadata candidate)
          chunks (:groundingChunks grounding)
          parts (get-in candidate [:content :parts])
          sources (keep #(when-let [w (:web %)] {:url (:uri w) :title (:title w)}) chunks)
          citations (for [support (:groundingSupports grounding)
                          index (:groundingChunkIndices support)
                          :let [w (:web (get chunks index))]
                          :when w]
                      {:url (:uri w) :title (:title w)
                       :cited_text (get-in support [:segment :text])})]
      (when-not (= "STOP" (:finishReason candidate))
        (fail! :provider/incomplete-stream "Gemini hosted search did not finish normally"))
      {:sources sources :citations citations :queries (:webSearchQueries grounding)
       :answer (apply str (keep #(when-not (:thought %) (:text %)) parts))})

    :openrouter
    (let [choice (first (:choices raw))
          message (:message choice)
          citations (annotations [message])]
      (when-not (= "stop" (:finish_reason choice))
        (fail! :provider/incomplete-stream "OpenRouter hosted search did not finish normally"))
      {:sources citations :citations citations :answer (:content message)})

    :anthropic
    (let [blocks (:content raw)
          results (filter #(= "web_search_tool_result" (:type %)) blocks)
          sources (mapcat #(if (sequential? (:content %)) (:content %) []) results)
          citations (for [block blocks c (:citations block)
                          :when (= "web_search_result_location" (:type c))] c)]
      (when-not (= "end_turn" (:stop_reason raw))
        (fail! :provider/incomplete-stream "Anthropic hosted search did not finish its turn"))
      {:sources sources :citations citations
       :queries (keep #(when (and (= "server_tool_use" (:type %)) (= "web_search" (:name %)))
                         (get-in % [:input :query])) blocks)
       :answer (apply str (keep #(when (= "text" (:type %)) (:text %)) blocks))})))

(defn normalize
  [family {:keys [provider model query limit] :or {limit 5}} raw canonical]
  (native-errors! raw)
  (when (or (seq (:response/tool-calls canonical))
            (and (:response/finish-reason canonical)
                 (not= :stop (:response/finish-reason canonical))))
    (fail! :provider/search-failed "Hosted search returned an unfinished/function-tool completion"))
  (let [{:keys [sources citations answer queries]} (native-evidence family raw)
        citations (vec (keep citation citations))
        sources (unique-sources (concat (keep source sources)
                                        (map #(select-keys % [::web/url ::web/title]) citations)) limit)
        urls (set (map ::web/url sources))
        citations (vec (distinct (filter #(contains? urls (::web/url %)) citations)))
        notes (cond-> []
                (contains? #{:responses :gemini :anthropic} family)
                (conj "Source limit bounds returned sources; this protocol has no numeric search-result cap."))]
    (when (empty? sources)
      (fail! :provider/no-search-evidence "Hosted search returned no genuine source URLs; answer-only completion is not a search result"))
    (web/validate!
     ::web/search-result
     (cond-> {::web/backend :hosted ::web/provider provider ::web/model model
              ::web/query query ::web/sources sources ::web/citations citations
              ::web/fetched-at (System/currentTimeMillis) ::web/raw-response raw}
       (and (string? answer) (not (str/blank? answer))) (assoc ::web/answer answer)
       (seq queries) (assoc ::web/search-queries (vec (distinct (filter string? queries))))
       (seq notes) (assoc ::web/notes notes)
       (:response/usage canonical) (assoc ::web/usage (:response/usage canonical))
       (false? (get-in canonical [:response/cost :cost/estimated?]))
       (assoc ::web/cost (:response/cost canonical))))))

(defn complete!
  [family profile canonical params http-options options body-owner]
  (active! options)
  (let [adapter (adapter family)
        request (cond-> (transport/build-request adapter profile canonical)
                  (= family :responses)
                  (update-in [:body :include] (fnil conj []) "web_search_call.action.sources")
                  (= family :anthropic)
                  (assoc-in [:body :tools] [{:type "web_search_20250305"
                                            :name "web_search" :max_uses 1}]))
        request (cond-> request
                  (= family :anthropic) (assoc-in [:body :model] (:model params)))
        request (sdk-auth/apply-http-options profile
                                            (merge request http-options))
        _ (active! options)
        response (http/binary-stream-request
                  (update request :headers sdk-auth/merge-headers
                          {"Content-Type" "application/json"
                           "Accept" (if (get-in request [:body :stream])
                                      "text/event-stream" "application/json")}))
        input (:body response)]
    (reset! body-owner input)
    (try
      (active! options)
      (when (>= (:status response) 400)
        ;; HTTP status is sufficient for classification. Never retain an error
        ;; body containing reflected headers, credentials, or remote prose.
        (throw (ex-info "Hosted search HTTP error"
                        {:status (:status response)
                         :error (transport/parse-error adapter profile (:status response) {})})))
      (when-not (<= 200 (:status response) 299)
        (fail! :provider/search-failed "Hosted search returned an unexpected HTTP status"))
      (let [input (limited-stream input options)
            _ (when-let [f (:on-event options)] (f (stream/start-event)))
            raw (if (get-in request [:body :stream])
                  (responses-stream! input options)
                  (try (json/read (InputStreamReader. input StandardCharsets/UTF_8) :key-fn keyword)
                       (catch clojure.lang.ExceptionInfo e (throw e))
                       (catch Exception _
                         (active! options)
                         (fail! :provider/incomplete-stream "Hosted search returned malformed or truncated JSON"))))
            _ (active! options)
            result (normalize family params raw
                              (transport/parse-response adapter profile raw))]
        (when-let [f (:on-event options)] (f (stream/end-event :finish-reason :stop)))
        (active! options)
        result)
      (finally (.close ^InputStream input)))))
