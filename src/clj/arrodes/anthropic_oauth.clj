(ns arrodes.anthropic-oauth
  "Explicit, request-local Claude OAuth adaptation of the SDK Anthropic codec.
   This is a minimal experimental wire contract, not a full Claude CLI fingerprint."
  (:require [clojure.string :as str]
            [llm.sdk.provider.auth :as sdk-auth]
            [llm.sdk.providers.anthropic.chat :as anthropic]))

(def ^:private official-base-url "https://api.anthropic.com/v1")
(def ^:private system-prefix
  "You are Claude Code, Anthropic's official CLI for Claude.")
(def ^:private oauth-betas
  ["claude-code-20250219" "oauth-2025-04-20"
   "interleaved-thinking-2025-05-14" "fine-grained-tool-streaming-2025-05-14"])

(defn- prefix-name [name] (str "_" name))

(defn- canonical-name [name]
  (if (and (string? name) (str/starts-with? name "_"))
    (subs name 1)
    name))

(defn- map-tool-block [f block]
  (if (and (= "tool_use" (:type block)) (string? (:name block)))
    (update block :name f)
    block))

(defn- oauth-body [body]
  ;; Transform the SDK's final wire representation, including native replay,
  ;; rather than independently reproducing its canonical content conversion.
  (cond-> (update body :messages
                  (fn [messages]
                    (mapv #(update % :content
                                   (fn [blocks]
                                     (mapv (partial map-tool-block prefix-name) blocks)))
                          messages)))
    (:tools body) (update :tools #(mapv (fn [tool] (update tool :name prefix-name)) %))
    (= "tool" (get-in body [:tool_choice :type]))
    (update-in [:tool_choice :name] prefix-name)))

(defn- final-headers [headers token]
  (let [headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])) headers)
        betas (->> (str/split (get headers "anthropic-beta" "") #",")
                   (map str/trim) (remove str/blank?)
                   (concat oauth-betas) distinct (str/join ","))]
    (-> headers
        (dissoc "x-api-key" "authorization")
        (assoc "authorization" (str "Bearer " token)
               "anthropic-version" "2023-06-01"
               "anthropic-beta" betas
               "anthropic-dangerous-direct-browser-access" "true"
               "content-type" "application/json"
               "accept" "application/json"
               "user-agent" "claude-cli/2.1.280 (external, cli)"
               "x-app" "cli"))))

(defn build-request
  "Build a finalized HTTP request for explicit OAuth, including HTTP options.
   Call after all runtime profile overrides. Only the official endpoint is
   allowed; do not run SDK apply-http-options again on the returned request.
   Token shape never determines authentication mode."
  [profile canonical-request token]
  (when-not (= official-base-url (:profile/base-url profile))
    (throw (ex-info "Claude OAuth requires the official Anthropic endpoint"
                    {:error/code "provider/oauth-endpoint"
                     :error/type :anthropic/oauth-endpoint :retryable? false})))
  (when (or (not (string? token)) (str/blank? token))
    (throw (ex-info "Claude OAuth requires an access token"
                    {:error/code "provider/oauth-token"
                     :error/type :anthropic/oauth-token :retryable? false})))
  ;; The synthetic token deliberately bypasses the SDK's token sniffing,
  ;; mcp_ naming and unrelated prompt sanitization. Nothing global is mutated.
  (let [codec-profile (assoc profile
                             :profile/auth-token "arrodes-anthropic-codec-only"
                             :profile/env-var-names []
                             :profile/auth-strategy :bearer)
        request (update canonical-request :request/messages
                        #(into [{:message/role :system
                                 :message/content system-prefix}] %))
        built (anthropic/build-request-anthropic codec-profile request)]
    (-> (sdk-auth/apply-http-options codec-profile built)
        (assoc :url (str official-base-url "/messages?beta=true"))
        (update :body oauth-body)
        (update :headers final-headers token))))

(defn normalize-event
  "Restore canonical names on SDK stream events before accumulation/replay.
   Exactly one underscore is removed, including from native tool-use metadata;
   tool inputs and opaque reasoning/provider state are not recursively rewritten."
  [event]
  (if (= :stream/tool-call-start (:event/type event))
    (cond-> (update event :tool-call/name canonical-name)
      (get-in event [:tool-call/provider-data :anthropic/content-block])
      (update-in [:tool-call/provider-data :anthropic/content-block]
                 (partial map-tool-block canonical-name)))
    event))
