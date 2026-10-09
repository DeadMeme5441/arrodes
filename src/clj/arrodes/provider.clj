(ns arrodes.provider
  "Manager-local provider catalog, authentication, and real llm.sdk request routing."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [arrodes.auth :as auth]
            [arrodes.anthropic-oauth :as anthropic-oauth]
            [arrodes.platform :as u]
            [arrodes.run :as run]
            [arrodes.web.data :as web]
            [arrodes.web.hosted :as hosted-web]
            [llm.sdk :as sdk]
            [llm.sdk.errors :as sdk-errors]
            [llm.sdk.http :as sdk-http]
            [llm.sdk.models-dev :as models-dev]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.sse :as sdk-sse]
            [llm.sdk.stream :as sdk-stream]
            [llm.sdk.transport :as sdk-transport]
            [llm.sdk.provider.auth :as sdk-auth]
            [llm.sdk.providers.codex.auth :as codex-auth]
            [llm.sdk.providers.codex.responses :as codex]
            [llm.sdk.providers.openai.chat :as openai]
            [llm.sdk.providers.anthropic.chat :as anthropic]))

(defrecord ProviderManager [home settings auth profiles live-models last-refresh
                            complete-fn closed? parent owns-auth?])

(def ^:private all-thinking-levels
  #{:none :minimal :low :medium :high :xhigh :max})

(def ^:private aliases
  {:google :gemini-native
   :google-vertex :vertex-gemini
   :amazon-bedrock :bedrock
   :openai-codex :codex-backend})

(def ^:private display-names
  {:openai "OpenAI"
   :anthropic "Anthropic"
   :gemini-native "Google Gemini"
   :google "Google Gemini"
   :vertex-gemini "Google Vertex Gemini"
   :google-vertex "Google Vertex Gemini"
   :vertex-anthropic "Anthropic on Vertex AI"
   :bedrock "Amazon Bedrock"
   :amazon-bedrock "Amazon Bedrock"
   :codex "OpenAI Responses"
   :codex-backend "OpenAI ChatGPT / Codex"
   :openai-codex "OpenAI ChatGPT / Codex"
   :ollama-native "Ollama"})
(def ^:private secret-keys
  #{:api-key :auth-token :secret :token :access-token :refresh-token
    :password :aws-access-key-id :aws-secret-access-key})

(defn- fail! [code message data]
  (throw (ex-info message (assoc data :error/code (name code) :error/type code))))

(defn- open-manager! [manager]
  (when @(:closed? manager)
    (fail! :provider/closed "Provider manager is closed" {}))
  manager)

(defn- secret-key? [key]
  (or (contains? secret-keys key)
      (contains? #{"authorization" "proxy-authorization" "api-key" "x-api-key"
                   "access-token" "refresh-token" "password" "secret"}
                 (str/lower-case (if (keyword? key) (name key) (str key))))))

(defn- contains-secret? [value]
  (cond
    (map? value) (or (some secret-key? (keys value))
                     (some contains-secret? (vals value)))
    (coll? value) (some contains-secret? value)
    :else false))

(defn- profile-auth-mode [profile]
  (let [id (:id profile)]
    (cond
      (contains? #{:codex-backend :openai-codex} id) :oauth
      (contains? #{:bedrock :amazon-bedrock :vertex-gemini :google-vertex
                   :vertex-anthropic} id) :ambient
      (= :ollama-native (:sdk-id profile)) :none
      :else :api-key)))

(defn- anthropic-profile? [profile]
  (= :anthropic (:sdk-id profile)))

(defn- sdk-profile->local [id]
  (let [p (sdk/provider-profile id)]
    {:id id
     :sdk-id id
     :kind :sdk
     :name (or (get display-names id)
               (-> (name id) (str/replace #"[-_]" " ") str/capitalize))
     :capabilities (set (:profile/capabilities p))
     :env-var-names (vec (:profile/env-var-names p))
     :base-url (:profile/base-url p)
     :auth-strategy (:profile/auth-strategy p)
     :auth-header-name (:profile/auth-header-name p)
     :headers (:profile/default-headers p)
     :refreshable? (boolean (:profile/supports-model-listing p))
     :built-in? true}))

(defn- built-in-profiles []
  (let [sdk-profiles
        (into {}
              (keep (fn [id]
                      (let [profile (sdk/provider-profile id)]
                        (when (and profile
                                   (contains? (set (:profile/capabilities profile)) :chat)
                                   (not (contains? #{:fake :github-copilot :copilot} id)))
                          [id (sdk-profile->local id)]))))
              (sdk/list-providers))
        aliased (into {}
                      (keep (fn [[id sdk-id]]
                              (when-let [base (get sdk-profiles sdk-id)]
                                [id (assoc base :id id :sdk-id sdk-id
                                           :name (get display-names id (:name base)))])))
                      aliases)
        profiles (merge sdk-profiles aliased)]
    (-> profiles
        (assoc-in [:codex-backend :refreshable?] true)
        (assoc-in [:openai-codex :refreshable?] true)
        (assoc-in [:vertex-gemini :refreshable?] false)
        (assoc-in [:google-vertex :refreshable?] false))))

(defn- normalize-model-spec [provider-id value]
  (let [m (if (string? value) {:id value} value)
        id (or (:id m) (:model/id m))
        levels (vec (or (:thinking-levels m) [:none]))]
    (when-not (and (string? id) (not (str/blank? id)))
      (fail! :provider/model "Provider model descriptor requires a non-empty :id"
             {:provider provider-id}))
    (when-not (and (seq levels) (every? all-thinking-levels levels))
      (fail! :provider/thinking "Model descriptor contains an unsupported thinking level"
             {:provider provider-id :model id}))
    (cond-> {:provider provider-id
             :id id
             :name (or (:name m) (:model/display-name m) id)
             :context-window (or (:context-window m) (:model/context-length m))
             :thinking-levels levels
             :input (vec (or (:input m) [:text]))
             :cost (or (:cost m) (:model/cost m) :unknown)
             :source (or (:source m) :configured)}
      (contains? m :tools?) (assoc :tools? (:tools? m))
      (or (:max-output-tokens m) (:model/max-output-tokens m))
      (assoc :max-output-tokens (or (:max-output-tokens m)
                                    (:model/max-output-tokens m))))))

(defn- normalize-custom-profile [id descriptor]
  (when (contains-secret? descriptor)
    (fail! :provider/secret-config
           "Provider registration may not contain credentials; use login! or environment variables"
           {:provider id}))
  (let [kind (keyword (or (:type descriptor) (:kind descriptor) :openai-compatible))
        models (mapv #(normalize-model-spec id %) (or (:models descriptor) []))]
    (when-not (contains? #{:openai-compatible :azure-openai :profile-alias} kind)
      (fail! :provider/type "Unsupported custom provider type" {:provider id :type kind}))
    (when (and (= kind :profile-alias)
               (nil? (sdk/provider-profile (some-> (:provider descriptor) keyword))))
      (fail! :provider/alias "Profile alias requires a registered SDK :provider"
             {:provider id}))
    (when (and (not= kind :profile-alias)
               (str/blank? (str (or (:base-url descriptor) (:endpoint descriptor)))))
      (fail! :provider/base-url "Custom provider requires :base-url or :endpoint"
             {:provider id}))
    (when (and (= kind :azure-openai)
               (not-every? some? [(:endpoint descriptor) (:deployment descriptor)
                                  (:api-version descriptor)]))
      (fail! :provider/azure
             "Azure OpenAI provider requires :endpoint, :deployment, and :api-version"
             {:provider id}))
    {:id id
     :sdk-id (some-> (:provider descriptor) keyword)
     :kind kind
     :name (or (:name descriptor) (name id))
     :base-url (or (:base-url descriptor) (:endpoint descriptor))
     :endpoint (:endpoint descriptor)
     :deployment (:deployment descriptor)
     :api-version (:api-version descriptor)
     :auth-strategy (keyword (or (:auth-strategy descriptor)
                                 (when (= kind :azure-openai) :api-key-header)
                                 :bearer))
     :auth-header-name (or (:auth-header-name descriptor) "api-key")
     :headers (or (:headers descriptor) {})
     :env-var-names (vec (or (:env-var-names descriptor) []))
     :capabilities (set (or (:capabilities descriptor)
                            #{:chat :streaming :tools :reasoning}))
     :models models
     :refreshable? (boolean (get descriptor :refreshable? (= kind :openai-compatible)))
     :owner (:owner descriptor)
     :built-in? false}))

(declare register!)

(defn- register-initial! [manager configured]
  (doseq [[id descriptor]
          (cond
            (map? configured) configured
            (sequential? configured) (map (juxt (comp keyword :id) identity) configured)
            :else [])]
    (register! manager (keyword id) descriptor))
  manager)

(defn create!
  "Create a manager with a private credential store and manager-local profiles."
  [{:keys [home settings complete-fn]}]
  (when-not (and (string? home) (not (str/blank? home)))
    (fail! :provider/home "Provider manager requires a non-empty :home" {}))
  (when (contains-secret? settings)
    (fail! :provider/secret-config "Settings may not contain provider credentials" {}))
  (let [manager (->ProviderManager home (or settings {}) (auth/open! home)
                                   (atom (built-in-profiles)) (atom {}) (atom {})
                                   complete-fn (atom false) nil true)]
    (register-initial! manager (or (:providers settings) (:provider-profiles settings)))
    manager))

(defn for-session
  "Create an isolated provider view for one session's effective settings.
   Credentials and injected completion belong to the root manager; profile,
   live-catalog, and refresh mutations remain local to the view."
  [root effective-settings]
  (open-manager! root)
  (when (contains-secret? effective-settings)
    (fail! :provider/secret-config "Session settings may not contain provider credentials" {}))
  (let [settings (merge (:settings root) (or effective-settings {}))
        child (->ProviderManager (:home root) settings (:auth root)
                                 (atom @(:profiles root)) (atom {}) (atom {})
                                 (:complete-fn root) (atom false) root false)
        configured (or (:providers effective-settings)
                       (:provider-profiles effective-settings))]
    (doseq [[id descriptor]
            (cond
              (map? configured) configured
              (sequential? configured) (map (juxt (comp keyword :id) identity) configured)
              :else [])]
      (let [id (keyword id)]
        (swap! (:profiles child) assoc id (normalize-custom-profile id descriptor))
        (swap! (:live-models child) dissoc id)))
    child))

(defn register!
  "Register a manager-local provider profile or model profile."
  [manager id descriptor]
  (open-manager! manager)
  (let [id (keyword id)
        current (get @(:profiles manager) id)
        replace? (true? (:replace? descriptor))]
    (when (and current (not replace?))
      (fail! :provider/duplicate "Provider is already registered" {:provider id}))
    (when (and current (:built-in? current))
      (fail! :provider/built-in "Built-in provider profiles cannot be replaced"
             {:provider id}))
    (let [profile (normalize-custom-profile id descriptor)]
      (swap! (:live-models manager) dissoc id)
      (swap! (:last-refresh manager) dissoc id)
      (swap! (:profiles manager) assoc id profile)
      profile)))

(defn unregister!
  "Remove a manager-local provider. Built-ins cannot be removed."
  [manager id]
  (open-manager! manager)
  (let [id (keyword id) profile (get @(:profiles manager) id)]
    (when (and profile (:built-in? profile))
      (fail! :provider/built-in "Built-in provider profiles cannot be unregistered"
             {:provider id}))
    (swap! (:profiles manager) dissoc id)
    (swap! (:live-models manager) dissoc id)
    (swap! (:last-refresh manager) dissoc id)
    {:removed id}))

(defn withdraw!
  "Remove every manager-local provider registered by an extension owner."
  [manager owner]
  (open-manager! manager)
  (let [ids (->> @(:profiles manager)
                 (keep (fn [[id p]] (when (and (not (:built-in? p)) (= owner (:owner p))) id)))
                 vec)]
    (doseq [id ids] (unregister! manager id))
    {:owner owner :removed ids}))

(defn- profile [manager provider-id]
  (or (get @(:profiles manager) (keyword provider-id))
      (fail! :provider/unknown "Unknown provider" {:provider provider-id})))

(defn- env-value [names]
  (some (fn [n] (let [v (System/getenv n)] (when-not (str/blank? v) v))) names))

(defn- stored-credential-entry [manager provider-id p]
  (if-let [credential (auth/credential (:auth manager) provider-id)]
    {:provider-id provider-id :credential credential}
    (when-let [sdk-id (when (not= provider-id (:sdk-id p)) (:sdk-id p))]
      (when-let [credential (auth/credential (:auth manager) sdk-id)]
        {:provider-id sdk-id :credential credential}))))

(defn- current-credential [manager entry refresh? options]
  (let [credential (:credential entry)]
    (if (and refresh? (= :oauth (:type credential)))
      (auth/ensure-fresh! (:auth manager) (:provider-id entry) options)
      credential)))

(defn- codex-auth [stored]
  (let [token (or (:access-token stored) (:secret stored))]
    (cond
      token
      {:source (:source stored)
       :type (:type stored)
       :token token
       :account-id (:account-id stored)
       :credential stored}

      (codex-auth/codex-backend-available?)
      {:source :codex-cli :type :oauth}

      :else nil)))

(defn- auth-resolution [manager provider-id p refresh? options]
  (let [entry (stored-credential-entry manager provider-id p)
        anthropic? (anthropic-profile? p)
        c (when-not (and anthropic?
                         (= :api-key (get-in entry [:credential :type]))
                         (auth/anthropic-oauth-token? (get-in entry [:credential :secret])))
            (current-credential manager entry refresh? options))
        env-token (let [token (env-value (:env-var-names p))]
                    (when-not (and anthropic?
                                   (auth/anthropic-oauth-token? token))
                      token))
        sdk-id (:sdk-id p)]
    (cond
      (or (contains? #{:codex-backend :openai-codex} provider-id)
          (= :codex-backend sdk-id))
      (codex-auth c)

      (contains? #{:bedrock :amazon-bedrock} provider-id)
      (when (or (and (System/getenv "AWS_ACCESS_KEY_ID")
                     (System/getenv "AWS_SECRET_ACCESS_KEY"))
                (System/getenv "AWS_PROFILE")
                (System/getenv "AWS_WEB_IDENTITY_TOKEN_FILE")
                (System/getenv "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
                (System/getenv "AWS_CONTAINER_CREDENTIALS_FULL_URI"))
        {:source :aws-credential-chain :type :ambient})

      (contains? #{:vertex-gemini :google-vertex :vertex-anthropic} provider-id)
      (let [token (or (:secret c) (System/getenv "GOOGLE_OAUTH_ACCESS_TOKEN"))
            adc (or (System/getenv "GOOGLE_APPLICATION_CREDENTIALS")
                    (str (System/getProperty "user.home")
                         "/.config/gcloud/application_default_credentials.json"))]
        (when (or token
                  (and adc (.isFile (java.io.File. adc))))
          {:source (cond c (:source c) token :environment :else :application-default-credentials)
           :type (if c (:type c) :ambient) :token token :credential c}))

      (or (= sdk-id :ollama-native)
          (= :none (:auth-strategy p)))
      {:source :local :type :none}

      :else
      (let [token (or (:secret c) (:access-token c) env-token)]
        (when token
          {:source (if c (:source c) :environment)
           :type (or (:type c) :api-key) :token token :credential c})))))

(defn- reasoning-levels [provider-id model-id capabilities entry]
  (let [m (str/lower-case (or model-id ""))
        reported (:thinking-levels entry)]
    (cond
      (seq reported) (vec (distinct reported))
      (not (contains? capabilities :reasoning)) [:none]
      (contains? #{:codex :codex-backend :openai-codex :openai} provider-id)
      (cond-> [:none :minimal :low :medium :high]
        (or (str/includes? m "gpt-5") (str/includes? m "gpt-6")) (conj :xhigh))
      (contains? #{:anthropic :vertex-anthropic} provider-id)
      (cond-> [:none :low :medium :high]
        (or (str/includes? m "opus-4-7") (str/includes? m "opus-4-8")
            (str/includes? m "sonnet-5")) (conj :xhigh))
      :else [:none :low :medium :high])))

(defn- input-types [capabilities]
  (cond-> [:text]
    (or (contains? capabilities :multimodal)
        (contains? capabilities :vision)) (conj :image)
    (or (contains? capabilities :file-attachments)
        (contains? capabilities :pdf)) (conj :file)))

(defn- sdk-model->descriptor [provider-id p entry]
  (let [capabilities (or (:model/capabilities entry) (:capabilities p))]
    {:provider provider-id
     :id (:model/id entry)
     :name (or (:model/display-name entry) (:model/id entry))
     :context-window (:model/context-length entry)
     :max-output-tokens (:model/max-output-tokens entry)
     :tools? (contains? capabilities :tools)
     :thinking-levels (reasoning-levels provider-id (:model/id entry) capabilities nil)
     :input (input-types capabilities)
     :cost (or (:model/cost entry) :unknown)
     :source (:model/source entry)}))

(defn- profile-models [manager provider-id p]
  (binding [models-dev/*cache-dir* (u/resolve-path (:home manager) "cache/models")]
    (if (contains? @(:live-models manager) provider-id)
      (get @(:live-models manager) provider-id)
      (if-let [parent (:parent manager)]
        (let [parent-profile (get @(:profiles parent) provider-id)]
          (if (= p parent-profile)
            (profile-models parent provider-id parent-profile)
            (cond
              (seq (:models p)) (:models p)
              (:sdk-id p) (mapv #(sdk-model->descriptor provider-id p %)
                                (sdk/list-models (:sdk-id p)))
              :else [])))
        (cond
          (seq (:models p)) (:models p)
          (:sdk-id p) (mapv #(sdk-model->descriptor provider-id p %)
                            (sdk/list-models (:sdk-id p)))
          :else [])))))

(defn catalog
  "Return the manager-local model catalog without network access."
  [manager]
  (open-manager! manager)
  (->> @(:profiles manager)
       (sort-by (comp str key))
       (mapcat (fn [[id p]] (profile-models manager id p)))
       vec))

(defn model
  "Look up a model descriptor by provider and exact model id."
  [manager provider-id model-id]
  (let [provider-id (keyword provider-id)
        p (get @(:profiles manager) provider-id)]
    (when p
      (some #(when (= model-id (:id %)) %) (profile-models manager provider-id p)))))

(defn- model-from-openai [provider-id p item]
  (let [id (or (:id item) (:slug item))]
    (when (string? id)
      {:provider provider-id :id id :name (or (:name item) (:display_name item) id)
       :context-window (or (:context_window item) (:context_length item))
       :thinking-levels (reasoning-levels provider-id id (:capabilities p) nil)
       :input (input-types (:capabilities p)) :cost :unknown :source :live})))

(defn- codex-model [provider-id p item]
  (let [id (or (:slug item) (:id item))
        reported (->> (:supported_reasoning_levels item)
                      (keep (fn [x]
                              (let [value (keyword (or (:effort x) x))]
                                (case value :off :none :ultra :max value))))
                      (filter all-thinking-levels)
                      distinct vec)
        levels (vec (distinct (concat [:none] reported)))]
    (when (string? id)
      {:provider provider-id :id id :name (or (:display_name item) id)
       :context-window (or (:context_window item) (:max_context_window item))
       :thinking-levels (if (> (count levels) 1) levels
                            (reasoning-levels provider-id id (:capabilities p) nil))
       :input (let [xs (set (:input_modalities item))]
                (cond-> [:text] (contains? xs "image") (conj :image)))
       :cost :unknown :source :live
       :metadata (select-keys item [:description :default_reasoning_level
                                    :supports_parallel_tool_calls :supported_in_api
                                    :visibility])})))

(defn- require-auth! [manager provider-id p refresh? options]
  (or (auth-resolution manager provider-id p refresh? options)
      (fail! :provider/auth "Provider credentials are not configured"
             {:provider provider-id :available? false})))

(defn- openai-model-url [p]
  (str (str/replace (:base-url p) #"/$" "") "/models"))

(defn- sdk-runtime-config [manager provider-id p resolution]
  (cond-> (merge
           {:connect-timeout-ms 15000 :timeout-ms 60000}
           (select-keys (:settings manager)
                        [:connect-timeout-ms :timeout-ms :transport :incremental?])
           (select-keys (get-in (:settings manager) [:provider-options provider-id])
                        [:base-url :connect-timeout-ms :timeout-ms :transport :incremental?]))
    (:token resolution) (assoc :auth-token (:token resolution))
    (:account-id resolution) (assoc :account-id (:account-id resolution))
    (and (= :profile-alias (:kind p)) (:base-url p))
    (assoc :base-url (:base-url p))
    (seq (:headers p)) (assoc :headers (:headers p))))

(defn- refresh-one! [manager provider-id]
  (let [provider-id (keyword provider-id)
        p (profile manager provider-id)
        resolution (require-auth! manager provider-id p true {})
        result
        (cond
          (contains? #{:codex-backend :openai-codex} provider-id)
          (let [sdk-profile (-> (sdk/provider-profile (:sdk-id p))
                                (sdk-auth/apply-runtime-config
                                 (sdk-runtime-config manager provider-id p resolution)))
                request-auth (codex-auth/request-auth sdk-profile)
                body (auth/request! {:url "https://chatgpt.com/backend-api/codex/models?client_version=99.99.99"
                                     :headers (:headers request-auth)})]
            (->> (or (:models body) (:data body))
                 (keep #(codex-model provider-id p %)) vec))

          (and (anthropic-profile? p) (= :oauth (:type resolution)))
          (let [sdk-profile (-> (sdk/provider-profile :anthropic)
                                (sdk-auth/apply-runtime-config
                                 (sdk-runtime-config manager provider-id p resolution)))
                req (anthropic-oauth/build-request sdk-profile
                                                   {:request/model "discovery"
                                                    :request/messages []}
                                                   (:token resolution))
                body (auth/request! {:url "https://api.anthropic.com/v1/models"
                                     :headers (:headers req)})]
            (->> (:data body) (keep #(model-from-openai provider-id p %)) vec))

          (= (:sdk-id p) :gemini-native)
          (let [body (auth/request! {:url "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
                                     :headers {"x-goog-api-key" (:token resolution)}})]
            (->> (:models body)
                 (keep (fn [item]
                         (model-from-openai provider-id p
                                            (assoc item :id (some-> (:name item)
                                                                    (str/replace #"^models/" ""))
                                                   :name (:displayName item)
                                                   :context_length (:inputTokenLimit item)))))
                 vec))

          (or (= (:kind p) :openai-compatible)
              (and (= :sdk (:kind p)) (:refreshable? p)))
          (let [url (openai-model-url p)
                headers (sdk-auth/merge-headers
                         (:headers p)
                         (when-let [token (:token resolution)]
                           (case (:auth-strategy p)
                             :api-key-header {(:auth-header-name p) token}
                             {"Authorization" (str "Bearer " token)})))
                body (auth/request! {:url url :headers headers})]
            (->> (:data body) (keep #(model-from-openai provider-id p %)) vec))

          :else (profile-models manager provider-id p))]
    (swap! (:live-models manager) assoc provider-id result)
    (swap! (:last-refresh manager) assoc provider-id
           {:at (System/currentTimeMillis) :count (count result)})
    result))

(defn refresh!
  "Explicitly refresh one provider or every configured refreshable provider."
  ([manager]
   (open-manager! manager)
   (doseq [[id p] @(:profiles manager) :when (:refreshable? p)]
     (try (refresh-one! manager id)
          (catch Exception e
            (swap! (:last-refresh manager) assoc id
                   {:at (System/currentTimeMillis) :error (ex-message e)}))))
   (catalog manager))
  ([manager provider-id]
   (open-manager! manager)
   (refresh-one! manager (keyword provider-id))
   (catalog manager)))

(defn- redact-auth-status [resolution]
  (when resolution
    {:configured? true :type (:type resolution) :source (:source resolution)}))

(defn status
  "Return provider/auth availability without credentials or credential paths."
  [manager]
  (open-manager! manager)
  {:providers
   (->> @(:profiles manager)
        (sort-by (comp str key))
        (mapv (fn [[id p]]
                (let [resolution (try (auth-resolution manager id p false {})
                                      (catch Exception _ nil))]
                  (cond-> {:provider id
                           :name (:name p)
                           :available? (boolean resolution)
                           :auth (or (redact-auth-status resolution)
                                     {:configured? false :type (profile-auth-mode p)})
                           :models (count (profile-models manager id p))
                           :refreshable? (:refreshable? p)}
                    (anthropic-profile? p) (assoc :auth-modes [:api-key :oauth])
                    (get @(:last-refresh manager) id)
                    (assoc :last-refresh (get @(:last-refresh manager) id)))))))})

(defn login!
  "Authenticate a provider explicitly. Credentials remain under manager :home."
  [manager provider-id options]
  (open-manager! manager)
  (let [provider-id (keyword provider-id)
        p (profile manager provider-id)]
    (when (contains? #{:bedrock :amazon-bedrock} provider-id)
      (fail! :auth/ambient
             "Amazon Bedrock uses the SDK's native AWS credential chain; configure AWS credentials in the process environment or shared AWS files"
             {:provider provider-id}))
    (when (and (contains? #{:vertex-gemini :google-vertex :vertex-anthropic} provider-id)
               (nil? (:api-key options)))
      (fail! :auth/ambient
             "Vertex uses Application Default Credentials; pass an explicit OAuth access token as :api-key or configure ADC"
             {:provider provider-id}))
    (auth/login! (:auth manager) provider-id
                 (assoc options :anthropic-profile? (anthropic-profile? p)))))

(defn refresh-auth!
  "Explicitly refresh one stored OAuth credential."
  ([manager provider-id]
   (refresh-auth! manager provider-id {}))
  ([manager provider-id options]
   (open-manager! manager)
   (let [provider-id (keyword provider-id)
         p (profile manager provider-id)
         entry (stored-credential-entry manager provider-id p)
         credential-provider (or (:provider-id entry) provider-id)]
     (assoc (auth/refresh! (:auth manager) credential-provider options)
            :provider provider-id))))

(defn logout!
  "Remove only the selected provider's private stored credential."
  [manager provider-id]
  (open-manager! manager)
  (let [provider-id (keyword provider-id)]
    (when-not (or (contains? @(:profiles manager) provider-id)
                  (auth/credential (:auth manager) provider-id))
      (profile manager provider-id))
    (auth/delete-credential! (:auth manager) provider-id)
    {:provider provider-id :status :logged-out}))

(defn- cancelled-ex []
  (ex-info "Provider request cancelled"
           {:error/code "provider/cancelled" :error/type :provider/cancelled
            :retryable? false}))

(defn- event-list [event]
  (cond (nil? event) [] (sequential? event) event :else [event]))

(defn- emit-event! [options event]
  (when (auth/cancelled? options) (throw (cancelled-ex)))
  (when-let [f (:on-event options)] (f event))
  (when (auth/cancelled? options) (throw (cancelled-ex))))

(defn- direct-http-options [manager provider-id]
  (merge {:connect-timeout-ms 15000 :timeout-ms 60000}
         (select-keys (:settings manager) [:connect-timeout-ms :timeout-ms :http-client])
         (select-keys (get-in (:settings manager) [:provider-options provider-id])
                      [:connect-timeout-ms :timeout-ms :http-client])))

(defn- incomplete! [provider-id model-id response]
  (fail! :provider/incomplete-stream
         "Provider stream ended before a complete response; no tool calls were executed"
         {:provider provider-id :model model-id :retryable? false
          :partial-response response}))

(defn- complete-stream! [response provider-id model-id]
  (when (= :incomplete (:response/finish-reason response))
    (incomplete! provider-id model-id response))
  (when (some (fn [call]
                (or (str/blank? (:tool-call/name call))
                    (not (string? (:tool-call/arguments call)))
                    (not (map? (try
                                 (json/read-str (:tool-call/arguments call))
                                 (catch Exception _ nil))))))
              (:response/tool-calls response))
    (incomplete! provider-id model-id response))
  response)

(defn- direct-sse-complete! [provider-id model-id profile transport request-map options]
  (when (auth/cancelled? options) (throw (cancelled-ex)))
  (let [response (sdk-http/sse-response request-map)
        body (:body response)]
    (try
      (when (and (number? (:status response)) (>= (:status response) 400))
        (let [classified (sdk-transport/parse-error transport profile (:status response) body)]
          (throw (ex-info "Provider streaming API error"
                          {:error classified :status (:status response)
                           :provider provider-id :attempts 1}))))
      (let [start (sdk-stream/start-event)
            _ (emit-event! options start)
            accumulated
            (loop [records (seq (sdk-sse/event-seq
                                 (sdk-http/line-seq-closeable body)))
                   acc (sdk-stream/reduce-event (sdk-stream/empty-accumulator) start)
                   terminal? false
                   done? false]
              (if-let [record (first records)]
                (let [payload (sdk-sse/data-payload record)
                      data (sdk-sse/parse-json-data record)
                      anthropic-stop? (and (= :anthropic (:sse-protocol options))
                                           (= "message_stop" (:type data)))
                      done-record? (boolean (re-find #"(?m)^data:\s*\[DONE\]\s*$" record))
                      _ (when (and payload (nil? data))
                          (incomplete! provider-id model-id
                                       (sdk-stream/acc->response acc provider-id model-id)))
                      parsed (mapv (or (:normalize-event options) identity)
                                   (event-list
                                    (sdk-transport/parse-stream-event transport profile record)))
                      acc (reduce (fn [current event]
                                    (emit-event! options event)
                                    (sdk-stream/reduce-event current event))
                                  acc parsed)]
                  (recur (when-not anthropic-stop? (next records)) acc
                         (or terminal? (some #(= :stream/end (:event/type %)) parsed))
                         (or done? (if (= :anthropic (:sse-protocol options))
                                     anthropic-stop?
                                     done-record?))))
                (if (and terminal? done?)
                  acc
                  (incomplete! provider-id model-id
                               (sdk-stream/acc->response acc provider-id model-id)))))
            response (sdk-stream/acc->response accumulated provider-id model-id)]
        (when (and (= :anthropic (:sse-protocol options))
                   (not (contains? #{:stop :tool-calls :length :content-filter}
                                   (:response/finish-reason response))))
          (incomplete! provider-id model-id response))
        (-> response
            (complete-stream! provider-id model-id)
            (pricing/stamp-response-cost-and-cache provider-id model-id)))
      (finally
        (when (instance? java.io.Closeable body)
          (.close ^java.io.Closeable body))))))

(defn- custom-openai-complete! [manager provider-id p request options]
  (let [resolution (require-auth! manager provider-id p true options)
        profile (cond-> {:profile/id provider-id
                         :profile/protocol-family :openai-chat
                         :profile/base-url (:base-url p)
                         :profile/auth-strategy (:auth-strategy p)
                         :profile/env-var-names []
                         :profile/default-headers (:headers p)
                         :profile/capabilities (:capabilities p)}
                  (:token resolution)
                  (assoc :profile/auth-token (:token resolution))
                  (= :api-key-header (:auth-strategy p))
                  (assoc :profile/auth-header-name (:auth-header-name p)))
        transport (openai/make-transport)
        req (merge
             (openai/build-request-openai
              profile (assoc request :request/stream? true))
             (direct-http-options manager provider-id))]
    (direct-sse-complete! provider-id (:request/model request)
                          profile transport req options)))

(defn- azure-complete! [manager provider-id p request options]
  (let [resolution (require-auth! manager provider-id p true options)
        profile (cond-> {:profile/id provider-id :profile/protocol-family :openai-chat
                         :profile/base-url (:endpoint p)
                         :profile/auth-strategy (:auth-strategy p)
                         :profile/auth-token (:token resolution)
                         :profile/auth-header-name (:auth-header-name p)
                         :profile/default-headers (:headers p)
                         :profile/capabilities (:capabilities p)
                         :profile/url-builder openai/azure-url-builder
                         :azure/deployment (:deployment p)
                         :azure/api-version (:api-version p)}
                  (= :bearer (:auth-strategy p))
                  (dissoc :profile/auth-header-name))
        transport (openai/make-transport)
        req (merge (openai/build-request-openai profile (assoc request :request/stream? true))
                   (direct-http-options manager provider-id))]
    (direct-sse-complete! provider-id (:request/model request) profile transport req options)))

(defn- anthropic-oauth-complete! [manager provider-id p request options resolution]
  (let [config (sdk-runtime-config manager provider-id p resolution)
        profile (-> (sdk/provider-profile :anthropic)
                    (assoc :profile/env-var-names [])
                    (sdk-auth/apply-runtime-config config))
        req (merge (anthropic-oauth/build-request profile
                                                  (assoc request :request/stream? true)
                                                  (:token resolution))
                   (direct-http-options manager provider-id))]
    (direct-sse-complete! provider-id (:request/model request) profile
                          (anthropic/make-transport) req
                          (assoc options :sse-protocol :anthropic
                                 :normalize-event anthropic-oauth/normalize-event))))

(defn- sdk-complete! [manager provider-id p request options]
  (let [resolution (require-auth! manager provider-id p true options)
        sdk-id (:sdk-id p)
        c (:credential resolution)
        vertex? (contains? #{:vertex-gemini :google-vertex :vertex-anthropic} provider-id)
        request (if (and vertex? (:token resolution))
                  (cond-> (assoc-in request
                                    [:request/provider-options :vertex :access-token]
                                    (:token resolution))
                    (:project c)
                    (assoc-in [:request/provider-options :vertex :project] (:project c))
                    (:location c)
                    (assoc-in [:request/provider-options :vertex :location] (:location c)))
                  request)
        config (sdk-runtime-config manager provider-id p resolution)
        callback (fn [event] (emit-event! options event))]
    (when (auth/cancelled? options) (throw (cancelled-ex)))
    (let [response (if (and (anthropic-profile? p) (= :oauth (:type resolution)))
                     (anthropic-oauth-complete! manager provider-id p request options resolution)
                     (sdk/complete sdk-id request :stream? true :on-event callback
                                 :retry false :config config))]
      (when (auth/cancelled? options) (throw (cancelled-ex)))
      (-> response
          (assoc :response/provider provider-id)
          (complete-stream! provider-id (:request/model request))))))

(defn- validate-request! [manager provider-id request]
  (when-not (and (map? request)
                 (string? (:request/model request))
                 (not (str/blank? (:request/model request)))
                 (vector? (:request/messages request)))
    (fail! :provider/request "Invalid canonical provider request"
           {:provider provider-id}))
  (when-not (:complete-fn manager)
    (let [model-id (:request/model request)
          available (profile-models manager provider-id (profile manager provider-id))
          descriptor (some #(when (= model-id (:id %)) %) available)
          effort (get-in request [:request/reasoning :effort])
        input (set (:input descriptor))
        requested (into #{}
                        (comp (mapcat (fn [message]
                                        (let [content (:message/content message)]
                                          (if (vector? content) content []))))
                              (keep (fn [part]
                                      (case (:part/type part)
                                        :image :image
                                        :file :file
                                        nil))))
                        (:request/messages request))]
    (when (and (seq available) (nil? descriptor))
      (fail! :provider/model
             "Model is not listed for this provider; configure its exact ID or refresh this provider's catalog"
             {:provider provider-id :model model-id}))
    (when (and effort (not (contains? all-thinking-levels effort)))
      (fail! :provider/thinking "Unsupported reasoning effort"
             {:provider provider-id :model model-id :effort effort}))
    (when (and descriptor effort
               (not (contains? (set (:thinking-levels descriptor)) effort)))
      (fail! :provider/thinking "Selected model does not support this reasoning effort"
             {:provider provider-id :model model-id :effort effort
              :supported (:thinking-levels descriptor)}))
    (when (and (seq (:request/tools request)) (= false (:tools? descriptor)))
      (fail! :provider/tools "Selected model does not support tools"
             {:provider provider-id :model model-id}))
    (when-let [missing (and descriptor (seq (remove input requested)))]
      (fail! :provider/input "Selected model does not support the requested input"
             {:provider provider-id :model model-id :unsupported (vec missing)}))
    (when (and (number? (:max-output-tokens descriptor))
               (number? (:request/max-tokens request))
               (> (:request/max-tokens request) (:max-output-tokens descriptor)))
      (fail! :provider/output "Requested output exceeds the selected model's output limit"
             {:provider provider-id :model model-id
              :limit (:max-output-tokens descriptor)}))))
  request)

(defn- classified-ex [provider-id error]
  (let [data (ex-data error)]
    (if (:error/code data)
      (ex-info (or (ex-message error) "Provider request failed")
               (assoc data :provider provider-id :attempts 1)
               error)
      (let [classified (or (:error data)
                           (sdk-errors/classify-error error
                                                      :status (:status data)
                                                      :body (:body data)
                                                      :provider provider-id))
            reason (:error/reason classified :unknown)
            context? (run/context-overflow? error)
            code (if context? :context-overflow reason)]
        (ex-info (str (case code
                        :auth "Provider authentication failed; check credentials or sign in again"
                        :rate-limit "Provider rate limited; wait before retrying"
                        :context-overflow "Provider context limit exceeded; reduce input or compact the session"
                        :timeout "Provider timed out; check connection or retry"
                        :server "Provider server error; retry later"
                        :overloaded "Provider overloaded; retry later"
                        (str "Provider " (name code)))
                      (when-let [status (:status data)] (str " (HTTP " status ")")))
                 {:error/code (str "provider/" (name code))
                  :error/type code
                  :status (:status data)
                  :retryable? (and (not context?)
                                   (boolean (:error/retryable classified)))
                  :provider provider-id
                  :error (select-keys classified
                                      [:error/reason :error/retryable
                                       :error/should-compress])
                  :partial-response (:partial-response data)
                  :stream/error (:stream/error data)
                  :attempts 1}
                 error)))))

(defn complete!
  "Complete one logical provider attempt. Selected provider is opts :provider."
  [manager canonical-request {:keys [provider] :as options}]
  (binding [models-dev/*cache-dir* (u/resolve-path (:home manager) "cache/models")]
    (open-manager! manager)
    (when-not provider
      (fail! :provider/selection "Provider selection is required in opts :provider" {}))
    (let [provider-id (keyword provider)
          p (profile manager provider-id)]
      (validate-request! manager provider-id canonical-request)
      (when (auth/cancelled? options) (throw (cancelled-ex)))
      (try
        (if-let [complete-fn (:complete-fn manager)]
          (complete-fn canonical-request options)
          (case (:kind p)
            :azure-openai (azure-complete! manager provider-id p canonical-request options)
            :profile-alias (sdk-complete! manager provider-id p canonical-request options)
            :openai-compatible (custom-openai-complete!
                                manager provider-id p canonical-request options)
            (sdk-complete! manager provider-id p canonical-request options)))
        (catch clojure.lang.ExceptionInfo e
          (case (:error/type (ex-data e))
            :provider/cancelled (throw e)
            :auth/cancelled (throw (cancelled-ex))
            (throw (classified-ex provider-id e))))
        (catch Exception e
          (if (auth/cancelled? options)
            (throw (cancelled-ex))
            (throw (classified-ex provider-id e))))))))

(defn web-search!
  "Perform one bounded hosted search with the selected provider's credentials.
   This search-only request never exposes REPL/function tools or changes a model."
  [manager params options]
  (binding [models-dev/*cache-dir* (u/resolve-path (:home manager) "cache/models")]
    (open-manager! manager)
    (let [{:keys [provider model query limit timeout-ms max-tokens recency] :as params}
          (merge {:limit 5 :timeout-ms 30000 :max-tokens 2048} params)
          provider-id (when provider (keyword provider))]
      (when-not (and provider-id (string? model) (not (str/blank? model))
                     (string? query) (not (str/blank? query))
                     (integer? limit) (<= 1 limit 20)
                     (integer? timeout-ms) (<= 1 timeout-ms 120000)
                     (integer? max-tokens) (<= 1 max-tokens 8192)
                     (or (nil? recency) (contains? #{:hour :day :week :month :year} recency)))
        (fail! :provider/request "Invalid hosted web-search parameters" {}))
      (let [p (profile manager provider-id)
            family (hosted-web/family (:sdk-id p))
            params (assoc params :provider provider-id)
            canonical (hosted-web/request family params)]
        (validate-request! manager provider-id canonical)
        (when (auth/cancelled? options) (throw (cancelled-ex)))
        (try
          (hosted-web/bounded!
           timeout-ms options
           (fn [options body-owner]
             (if-let [complete-fn (:complete-fn manager)]
               (let [response (complete-fn canonical
                                           (assoc options :provider provider-id :web-search? true))]
                 (when (auth/cancelled? options) (throw (cancelled-ex)))
                 (if (::web/backend response)
                   (let [response (web/validate! ::web/search-result response)]
                     (when (empty? (::web/sources response))
                       (fail! :provider/no-search-evidence
                              "Hosted search returned no genuine source URLs" {}))
                     (let [sources (vec (take limit (::web/sources response)))
                           urls (set (map ::web/url sources))]
                       (assoc response ::web/backend :hosted ::web/provider provider-id
                              ::web/model model ::web/query query ::web/sources sources
                              ::web/citations (vec (filter #(contains? urls (::web/url %))
                                                         (::web/citations response))))))
                   (hosted-web/normalize family params (:response/raw response) response)))
               (let [resolution (require-auth! manager provider-id p true options)
                     _ (when (and (anthropic-profile? p) (= :oauth (:type resolution)))
                         (fail! :provider/unsupported-search
                                "Hosted Anthropic web search requires an API key; Claude OAuth currently supports chat only"
                                {:provider provider-id}))
                     sdk-id (if (= :openai (:sdk-id p)) :codex (:sdk-id p))
                     config (-> (merge {:base-url (:base-url p)}
                                       (sdk-runtime-config manager provider-id p resolution))
                                (assoc :timeout-ms timeout-ms))
                     sdk-profile (-> (sdk/provider-profile sdk-id)
                                     (assoc :profile/env-var-names [])
                                     (sdk-auth/apply-runtime-config config))
                     http-options (assoc (direct-http-options manager provider-id)
                                         :timeout-ms timeout-ms)]
                 (when (auth/cancelled? options) (throw (cancelled-ex)))
                 (let [result (hosted-web/complete! family sdk-profile canonical params
                                                   http-options options body-owner)]
                   (cond-> result
                     (= sdk-id :codex-backend)
                     (update ::web/notes conj
                             "Codex OAuth does not accept a token cap; the total deadline and response-size bound still apply.")))))))
          (catch clojure.lang.ExceptionInfo e
            (case (:error/type (ex-data e))
              :provider/cancelled (throw e)
              :auth/cancelled (throw (cancelled-ex))
              (throw (classified-ex provider-id e))))
          (catch Exception e
            (if (auth/cancelled? options)
              (throw (cancelled-ex))
              (throw (classified-ex provider-id e)))))))))

(defn close!
  "Close a provider manager or session view. Idempotent.
   Session views never close shared credentials or process-wide SDK resources."
  [manager]
  (if (compare-and-set! (:closed? manager) false true)
    (do
      (when (:owns-auth? manager)
        (auth/close! (:auth manager)))
      {:closed? true})
    {:closed? true :already-closed? true}))
