(ns arrodes.run
  "Pure decisions and canonical value transforms used by the runtime loop."
  (:require [clojure.string :as str]
            [arrodes.value :as value]
            #?(:clj [clojure.data.json :as json])))

(def default-config
  {:provider :codex-backend
   :model "gpt-6-astra"
   :thinking :medium
   :tools :all
   :instructions ""
   :settings {}})

(def terminal-operation-statuses
  #{:completed :failed :cancelled :interrupted})

(def visible-stream-events
  #{:stream/content-delta :stream/reasoning-delta
    :stream/tool-call-start :stream/tool-call-delta :stream/tool-call-end})

(defn terminal-operation? [operation]
  (contains? terminal-operation-statuses (:status operation)))

(defn prompt-content [prompt]
  (cond
    (string? prompt) prompt
    (and (vector? prompt) (every? map? prompt)) prompt
    (sequential? prompt) (vec prompt)
    :else (value/fail! :invalid-prompt "Prompt must be a string or canonical content parts"
                       {:value-type (value/type-name prompt)})))

(defn user-message [prompt]
  {:message/role :user
   :message/content (prompt-content prompt)})

(defn effective-config [session overrides]
  (let [base (value/deep-merge default-config (:config session))
        config (value/deep-merge base (or overrides {}))
        thinking (:thinking config)]
    (value/check! (keyword? (:provider config)) :invalid-config
                 "Provider must be a keyword" {:provider (:provider config)})
    (value/check! (and (string? (:model config)) (not (str/blank? (:model config))))
                 :invalid-config "Model must be a non-empty string" {:model (:model config)})
    (value/check! (contains? #{:none :minimal :low :medium :high :xhigh :max} thinking)
                 :invalid-config "Unsupported thinking level" {:thinking thinking})
    (value/check! (or (= :all (:tools config))
                     (and (vector? (:tools config)) (every? string? (:tools config))))
                 :invalid-config "Tools must be :all or a vector of names" {:tools (:tools config)})
    config))

(defn response->assistant [response]
  (let [parts (vec (remove #(= :tool-call (:part/type %))
                           (or (:response/parts response) [])))
        tool-calls (vec (or (:response/tool-calls response) []))
        response-metadata
        (cond-> {}
          (:response/id response) (assoc :response/id (:response/id response))
          (:response/provider response) (assoc :response/provider (:response/provider response))
          (:response/model response) (assoc :response/model (:response/model response))
          (:response/finish-reason response) (assoc :response/finish-reason (:response/finish-reason response))
          (:response/usage response) (assoc :response/usage (:response/usage response))
          (:response/cost response) (assoc :response/cost (:response/cost response))
          (:response/cache response) (assoc :response/cache (:response/cache response)))
        provider-data (merge (or (:response/provider-data response) {})
                             response-metadata)]
    (cond-> {:message/role :assistant
             :message/content parts}
      (seq tool-calls) (assoc :message/tool-calls tool-calls)
      (seq provider-data) (assoc :message/provider-data provider-data))))

(defn validate-assistant! [assistant]
  (let [calls (vec (:message/tool-calls assistant))
        ids (mapv :tool-call/id calls)]
    (value/check! (or (seq (:message/content assistant)) (seq calls))
                 :empty-provider-response
                 "Provider returned neither content nor tool calls" {})
    (doseq [call calls]
      (value/check! (and (string? (:tool-call/id call))
                        (not (str/blank? (:tool-call/id call)))
                        (string? (:tool-call/name call))
                        (not (str/blank? (:tool-call/name call)))
                        (or (string? (:tool-call/arguments call))
                            (map? (:tool-call/arguments call))))
                   :invalid-tool-call "Provider returned an invalid tool call"
                   {:tool-call-id (:tool-call/id call)
                    :tool-call-name (:tool-call/name call)}))
    (value/check! (= (count ids) (count (set ids))) :invalid-tool-call
                 "Provider returned duplicate tool call IDs" {:tool-call-ids ids})
    assistant))

(defn- json-string [value]
  #?(:clj (json/write-str value)
     :cljs (js/JSON.stringify (clj->js value))))

(defn- provider-tool-call [call]
  (let [arguments (:tool-call/arguments call)
        arguments (cond
                    (string? arguments) arguments
                    (map? arguments) (json-string arguments)
                    (nil? arguments) "{}"
                    :else (value/fail! :invalid-tool-call
                                       "Tool call arguments must be a JSON string or map"
                                       {:tool-call-id (:tool-call/id call)}))]
    (cond-> {:part/type :tool-call
             :tool-call/id (:tool-call/id call)
             :tool-call/name (:tool-call/name call)
             :tool-call/arguments arguments}
      (:tool-call/provider-data call)
      (assoc :tool-call/provider-data (:tool-call/provider-data call)))))

(defn provider-message
  "Remove durable runtime annotations that are not part of llm.sdk's closed Message schema."
  [message]
  (cond-> (select-keys message
                       [:message/role :message/content :message/tool-call-id
                        :message/name :message/provider-data :message/phase])
    (seq (:message/tool-calls message))
    (assoc :message/tool-calls (mapv provider-tool-call (:message/tool-calls message)))))

(defn request
  [config messages tools]
  (let [settings (:settings config)
        thinking (:thinking config)]
    (cond-> {:request/model (:model config)
             :request/messages (mapv provider-message messages)}
      (seq tools) (assoc :request/tools (vec tools))
      (not= :none thinking)
      (assoc :request/reasoning {:enabled true :effort thinking})
      (number? (:temperature settings)) (assoc :request/temperature (:temperature settings))
      (number? (:top-p settings)) (assoc :request/top-p (:top-p settings))
      (int? (:max-output-tokens settings)) (assoc :request/max-tokens (:max-output-tokens settings))
      (:stop settings) (assoc :request/stop (:stop settings))
      (map? (:response-format settings)) (assoc :request/response-format (:response-format settings))
      (map? (:cache settings)) (assoc :request/cache (:cache settings))
      (map? (:provider-options settings)) (assoc :request/provider-options (:provider-options settings)))))

(defn event-visible? [event]
  (contains? visible-stream-events (:event/type event)))

(defn retryable-error? [error]
  (let [data (ex-data error)
        status (or (:status data) (get-in data [:error :status]))
        error-type (or (:error/type data) (get-in data [:error :type]))]
    (or (true? (:retryable? data))
        (contains? #{408 409 425 429} status)
        (and (integer? status) (<= 500 status 599))
        (contains? #{:timeout :connection :rate-limit :server-error
                     :transport/timeout :transport/connection} error-type))))

(defn context-overflow?
  "Recognize an explicit input-context rejection, not a rate limit, output cap
   or generic payload-size error. SDK should-compress hints alone are insufficient."
  [error]
  (let [data (ex-data error)
        field (fn [m k] (when (map? m) (or (get m k) (get m (name k)))))
        raw-body (or (:body data) (get-in data [:response :body]))
        body (if (string? raw-body)
               (try #?(:clj (json/read-str raw-body) :cljs (js->clj (js/JSON.parse raw-body)))
                    (catch #?(:clj Throwable :cljs :default) _ raw-body)) raw-body)
        remote (or (field body :error) body)
        codes (map #(last (str/split (if (keyword? %) (name %) (str %)) #"/"))
                   [(:error/code data) (:error/type data) (field remote :code) (field remote :type)])
        status (or (:status data) (get-in data [:response :status]))
        text (str/lower-case (str (ex-message error) " " (field remote :message) " "
                                 (get-in data [:error :error/message])
                                 (when (string? body) body)))]
    (boolean
     (and (or (nil? status) (contains? #{400 413 422} status))
          (not (some #{"rate-limit" "rate_limit_exceeded" "quota" "auth" "cancelled"} codes))
          (or (some #{"context_length_exceeded" "context_window_exceeded" "context-overflow"
                      "context-length-exceeded" "prompt_too_long" "input_too_long"} codes)
              (re-find #"maximum context length|context (?:length|window|size) (?:has been )?exceeded|(?:exceeds?|exceeded) (?:the )?(?:maximum |model.?s )?context (?:length|window)|prompt (?:is )?too long|input token(?:s| count)?.{0,50}exceeds? (?:the )?(?:maximum|limit)" text))))))

(defn suggested-session-name
  "A local title from the first user message. Never calls a model or includes
   non-text payloads; use only the first nonblank line and at most 72 code points."
  [content]
  (let [text (if (string? content) content
                (->> content (keep #(when (= :text (:part/type %)) (:text %)))
                     (str/join "\n")))
        clean (-> text
                  (str/replace #"\u001b\[[0-?]*[ -/]*[@-~]" "")
                  (str/replace #"[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]" ""))
        line (some #(not-empty (str/trim %)) (str/split-lines clean))
        line (some-> line (str/replace #"\s+" " "))
        points (when line #?(:clj (vec (.toArray (.codePoints ^String line)))
                            :cljs (vec (js/Array.from line))))]
    (when (seq points)
      (if (<= (count points) 72) line
          (str #?(:clj (String. (int-array (take 71 points)) 0 71)
                  :cljs (apply str (take 71 points))) "…")))))

(defn add-usage
  ([] {})
  ([a] (or a {}))
  ([a b]
   (merge-with (fn [x y]
                 (if (and (number? x) (number? y)) (+ x y) y))
               (or a {}) (or b {}))))

(defn usage-from-entries [entries]
  (reduce
   (fn [total entry]
     (if (= :message (:kind entry))
       (add-usage total (get-in entry [:data :message/provider-data :response/usage]))
       total))
   {}
   entries))

(defn context-tokens
  "Size of one completed model context, not cumulative session spend.
   SDK input, cache-read and cache-write counters are disjoint. Reported totals
   take precedence (some providers include additional reasoning tokens there);
   reasoning and modality breakdowns must not be added again. Missing usage is nil."
  [usage]
  (if (number? (:usage/total-tokens usage))
    (long (:usage/total-tokens usage))
    (let [counters (keep #(get usage %) [:usage/input-tokens :usage/cached-input-tokens
                                       :usage/cache-write-tokens :usage/output-tokens])]
      (when (seq counters)
        (reduce + 0 (map long counters))))))

(defn latest-usage
  "Usage from the latest completion in the current context. Missing usage stays
   unknown; compaction invalidates all preceding context measurements."
  [entries]
  (some->> (rseq (vec entries))
           (take-while #(not= :compaction (:kind %)))
           (filter #(and (= :message (:kind %))
                         (= :assistant (get-in % [:data :message/role]))))
           first
           :data
           :message/provider-data
           :response/usage))

(defn auto-compact?
  "Decide from the latest provider measurement only; never estimate new content."
  [config model usage]
  (let [settings (:settings config)
        enabled? (not= false (:auto-compact? settings))
        window (or (:context-window model) (:model/context-length model))
        threshold (double (or (:compaction-threshold settings) 0.85))
        observed (context-tokens usage)]
    (and enabled? (number? window) (pos? window) (number? observed)
         (<= (* (double window) threshold) (double observed)))))

(defn- message-entry? [entry]
  (= :message (:kind entry)))

(defn- safe-boundary? [entries index]
  (let [entry (nth entries index)
        previous (when (pos? index) (nth entries (dec index)))
        message (:data entry)]
    (and (message-entry? entry)
         (= :user (:message/role message))
         (not (and previous
                   (= :assistant (get-in previous [:data :message/role]))
                   (seq (get-in previous [:data :message/tool-calls])))))))

(defn- effective-compaction-entries [entries]
  (let [entries (vec entries)
        marker-index (last (keep-indexed
                            (fn [index entry]
                              (when (= :compaction (:kind entry)) index))
                            entries))]
    (if (nil? marker-index)
      entries
      (let [marker (nth entries marker-index)
            kept-id (get-in marker [:data :first-kept-entry-id])
            kept-index (if kept-id
                         (first (keep-indexed
                                 (fn [index entry]
                                   (when (= kept-id (:id entry)) index))
                                 entries))
                         marker-index)]
        (vec (concat [marker]
                     (subvec entries kept-index marker-index)
                     (subvec entries (inc marker-index))))))))

(defn compaction-plan
  "Choose a user-message boundary without separating an assistant tool call from its results."
  [entries config]
  (let [entries (effective-compaction-entries entries)
        keep-count (long (max 1 (or (get-in config [:settings :compaction-keep-entries]) 8)))
        target (max 1 (- (count entries) keep-count))
        candidates (filter #(safe-boundary? entries %) (range 1 (count entries)))
        index (last (filter #(<= % target) candidates))
        summary-entries (when index (subvec entries 0 index))]
    (when (and index (some message-entry? summary-entries))
      {:summary-entries summary-entries
       :kept-entries (subvec entries index)
       :first-kept-entry-id (:id (nth entries index))})))

(defn entry-messages [entries]
  (->> entries
       (keep (fn [entry]
               (case (:kind entry)
                 :message (:data entry)
                 :custom-context (:data entry)
                 :branch-summary {:message/role :developer
                                  :message/content (str "Branch summary:\n" (get-in entry [:data :summary]))}
                 :compaction {:message/role :user
                              :message/content (str "Conversation summary:\n"
                                                    (get-in entry [:data :summary]))}
                 nil)))
       vec))

(defn summary-request [config entries instructions]
  (let [instruction (str "Summarize the supplied conversation faithfully for continuation. "
                         "Preserve decisions, constraints, unresolved work, concrete identifiers, "
                         "tool outcomes, and user intent. Do not claim work not present in the history."
                         (when-not (str/blank? (str instructions))
                           (str "\n\nAdditional instructions:\n" instructions)))
        messages (conj (entry-messages entries)
                       {:message/role :user :message/content instruction})]
    (-> (request (assoc-in config [:settings :max-output-tokens]
                           (or (get-in config [:settings :compaction-max-output-tokens]) 4096))
                 messages [])
        (dissoc :request/tools))))

(defn response-text [response]
  (->> (:response/parts response)
       (keep (fn [part] (when (= :text (:part/type part)) (:text part))))
       (str/join "")
       str/trim))

(defn select-intents [items phase]
  (let [allowed (if (= phase :tool-boundary) #{:steering} #{:steering :follow-up})]
    (vec (filter #(contains? allowed (:kind %)) items))))

(defn intent-entries [items]
  (mapv (fn [item]
          {:kind :message
           :data {:message/role :user
                  :message/content (prompt-content (:content item))}})
        items))

(defn config-with-intents [config items]
  (reduce (fn [effective item]
            (if-let [overrides (get-in item [:options :config])]
              (effective-config {:config effective} overrides)
              effective))
          config items))

(defn unresolved-tool-calls [entries]
  (let [{:keys [order calls]}
        (reduce
         (fn [{:keys [order calls] :as state} entry]
           (if-not (= :message (:kind entry))
             state
             (let [message (:data entry)]
               (case (:message/role message)
                 :assistant
                 (reduce (fn [{:keys [order calls]} call]
                           (let [id (:tool-call/id call)]
                             {:order (if (contains? calls id) order (conj order id))
                              :calls (assoc calls id call)}))
                         state (:message/tool-calls message))
                 :tool
                 (let [id (:message/tool-call-id message)]
                   {:order (vec (remove #{id} order))
                    :calls (dissoc calls id)})
                 state))))
         {:order [] :calls {}} entries)]
    (mapv calls (filter #(contains? calls %) order))))
