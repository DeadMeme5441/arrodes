(ns arrodes.provider
  "Manager-local provider catalog, authentication, and real llm.sdk request routing."
  (:require [clojure.string :as str]
            [arrodes.auth :as auth]
            [llm.sdk :as sdk]
            [llm.sdk.errors :as sdk-errors]
            [llm.sdk.http :as sdk-http]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.stream :as sdk-stream]
            [llm.sdk.transport :as sdk-transport]
            [llm.sdk.providers.anthropic.chat :as anthropic]
            [llm.sdk.providers.codex.responses :as codex]
            [llm.sdk.providers.openai.chat :as openai]))

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
   :github-copilot "GitHub Copilot"
   :ollama-native "Ollama"})

(def ^:private copilot-default-headers
  {"User-Agent" "GitHubCopilotChat/0.35.0"
   "Editor-Version" "vscode/1.107.0"
   "Editor-Plugin-Version" "copilot-chat/0.35.0"
   "Copilot-Integration-Id" "vscode-chat"
   "X-GitHub-Api-Version" "2026-06-01"})

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
      (contains? #{:codex-backend :openai-codex :github-copilot} id) :oauth
      (contains? #{:bedrock :amazon-bedrock :vertex-gemini :google-vertex
                   :vertex-anthropic} id) :ambient
      (= :ollama-native (:sdk-id profile)) :none
      :else :api-key)))

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
     :refreshable? (boolean (:profile/supports-model-listing p))
     :built-in? true}))

(defn- built-in-profiles []
  (let [sdk-profiles
        (into {}
              (keep (fn [id]
                      (let [profile (sdk/provider-profile id)]
                        (when (and profile
                                   (contains? (set (:profile/capabilities profile)) :chat)
                                   (not= id :fake))
                          [id (sdk-profile->local id)]))))
              (sdk/list-providers))
        aliased (into {}
                      (keep (fn [[id sdk-id]]
                              (when-let [base (get sdk-profiles sdk-id)]
                                [id (assoc base :id id :sdk-id sdk-id
                                           :name (get display-names id (:name base)))])))
                      aliases)
        copilot {:id :github-copilot :sdk-id nil :kind :copilot
                 :name "GitHub Copilot" :capabilities #{:chat :streaming :tools :reasoning}
                 :env-var-names ["COPILOT_GITHUB_TOKEN"]
                 :base-url "https://api.individual.githubcopilot.com"
                 :refreshable? true :built-in? true}
        profiles (assoc (merge sdk-profiles aliased) :github-copilot copilot)]
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
    (when-not (every? all-thinking-levels levels)
      (fail! :provider/thinking "Model descriptor contains an unsupported thinking level"
             {:provider provider-id :model id}))
    {:provider provider-id
     :id id
     :name (or (:name m) (:model/display-name m) id)
     :context-window (or (:context-window m) (:model/context-length m))
     :thinking-levels levels
     :input (vec (or (:input m) [:text]))
     :cost (or (:cost m) (:model/cost m) :unknown)
     :source (or (:source m) :configured)}))

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
  [{:keys [home settings complete-fn] :as options}]
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

(defn- stored-credential [manager provider-id p]
  (or (auth/credential (:auth manager) provider-id)
      (when (not= provider-id (:sdk-id p))
        (auth/credential (:auth manager) (:sdk-id p)))))

(defn- codex-auth [manager provider-id p refresh? options]
  (let [stored (if refresh?
                 (or (auth/ensure-fresh! (:auth manager) provider-id options)
                     (when (not= provider-id (:sdk-id p))
                       (auth/ensure-fresh! (:auth manager) (:sdk-id p) options)))
                 (stored-credential manager provider-id p))]
    (cond
      (and stored (= :oauth (:type stored)))
      {:source :stored-oauth
       :type :oauth
       :headers (cond-> {"Authorization" (str "Bearer " (:access-token stored))
                         "User-Agent" "codex_cli_rs/0.0.0 (arrodes-mono)"
                         "originator" "codex_cli_rs"}
                  (:account-id stored) (assoc "ChatGPT-Account-ID" (:account-id stored)))}
      (codex/codex-backend-available?)
      {:source :codex-cli :type :oauth :headers (codex/codex-backend-auth-headers)}
      :else nil)))

(defn- auth-resolution [manager provider-id p refresh? options]
  (let [stored (stored-credential manager provider-id p)
        c (if (and refresh? (= :oauth (:type stored)))
            (or (auth/ensure-fresh! (:auth manager) provider-id options) stored)
            stored)
        env-token (env-value (:env-var-names p))
        sdk-id (:sdk-id p)]
    (cond
      (contains? #{:codex-backend :openai-codex} provider-id)
      (codex-auth manager provider-id p refresh? options)

      (= provider-id :github-copilot)
      (let [token (or (:access-token c) (:secret c) env-token)]
        (when token
          {:source (if c (:source c) :environment) :type (or (:type c) :api-key)
           :token token :credential c
           :base-url (auth/copilot-base-url token (:enterprise-domain c))}))

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
    (contains? capabilities :file-attachments) (conj :file)))

(defn- sdk-model->descriptor [provider-id p entry]
  (let [capabilities (set (concat (:capabilities p) (:model/capabilities entry)))]
    {:provider provider-id
     :id (:model/id entry)
     :name (or (:model/display-name entry) (:model/id entry))
     :context-window (:model/context-length entry)
     :thinking-levels (reasoning-levels provider-id (:model/id entry) capabilities nil)
     :input (input-types capabilities)
     :cost (or (:model/cost entry) :unknown)
     :source (:model/source entry)}))

(defn- profile-models [manager provider-id p]
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
        :else []))))

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

(defn- copilot-models [provider-id p base-url items]
  (let [eligible (filterv
                  (fn [item]
                    (not= false (get-in item [:capabilities :supports :tool_calls])))
                  items)
        picker (filterv
                (fn [item]
                  (and (true? (:model_picker_enabled item))
                       (not= "disabled" (get-in item [:policy :state]))))
                eligible)
        visible (if (and (empty? picker)
                         (= base-url "https://api.individual.githubcopilot.com"))
                  (filterv #(= "enabled" (get-in % [:policy :state])) eligible)
                  picker)]
    (->> visible
         (keep #(model-from-openai provider-id p %))
         vec)))

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

(defn- refresh-one! [manager provider-id]
  (let [provider-id (keyword provider-id)
        p (profile manager provider-id)
        resolution (require-auth! manager provider-id p true {})
        result
        (cond
          (contains? #{:codex-backend :openai-codex} provider-id)
          (let [body (auth/request! {:url "https://chatgpt.com/backend-api/codex/models?client_version=99.99.99"
                                     :headers (:headers resolution)})]
            (->> (or (:models body) (:data body))
                 (keep #(codex-model provider-id p %)) vec))

          (= provider-id :github-copilot)
          (let [body (auth/request! {:url (str (:base-url resolution) "/models")
                                     :headers (merge copilot-default-headers
                                                     {"Authorization" (str "Bearer " (:token resolution))})})]
            (copilot-models provider-id p (:base-url resolution) (:data body)))

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
                headers (merge (:headers p)
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
                    (get @(:last-refresh manager) id)
                    (assoc :last-refresh (get @(:last-refresh manager) id)))))))} )

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
    (auth/login! (:auth manager) provider-id options)))

(defn refresh-auth!
  "Explicitly refresh one stored OAuth credential."
  ([manager provider-id]
   (refresh-auth! manager provider-id {}))
  ([manager provider-id options]
   (open-manager! manager)
   (auth/refresh! (:auth manager) (keyword provider-id) options)))

(defn logout!
  "Remove only the selected provider's private stored credential."
  [manager provider-id]
  (open-manager! manager)
  (let [provider-id (keyword provider-id)]
    (profile manager provider-id)
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
  (merge (select-keys (:settings manager) [:connect-timeout-ms :timeout-ms :http-client])
         (select-keys (get-in (:settings manager) [:provider-options provider-id])
                      [:connect-timeout-ms :timeout-ms :http-client])))

(defn- direct-sse-complete! [provider-id model-id profile transport request-map options]
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
            events
            (loop [lines (seq (sdk-http/line-seq-closeable body))
                   out [start] terminal? false]
              (if-let [line (first lines)]
                (let [parsed (event-list (sdk-transport/parse-stream-event transport profile line))
                      _ (doseq [event parsed] (emit-event! options event))
                      terminal? (or terminal? (some #(= :stream/end (:event/type %)) parsed))]
                  (recur (next lines) (into out parsed) terminal?))
                (if terminal?
                  out
                  (let [end (sdk-stream/end-event)]
                    (emit-event! options end)
                    (conj out end)))))
            response (sdk-stream/events->response events provider-id model-id)]
        (-> response
            (assoc :response/provider provider-id :response/model model-id)
            (pricing/stamp-response-cost-and-cache provider-id model-id)))
      (finally
        (when (instance? java.io.Closeable body)
          (.close ^java.io.Closeable body))))))

(defn- codex-complete! [manager provider-id p request options]
  (let [resolution (require-auth! manager provider-id p true options)
        model-id (:request/model request)
        transient-profile {:profile/id :arrodes-codex-request
                           :profile/base-url "https://example.invalid/v1"
                           :profile/auth-strategy :none
                           :profile/default-headers {}
                           :profile/capabilities (:capabilities p)}
        base (codex/build-request-codex transient-profile
                                        (assoc request :request/stream? true))
        cache-key (get-in request [:request/cache :scope-id])
        headers (merge (:headers resolution)
                       {"Accept" "text/event-stream"}
                       (when cache-key {"session_id" cache-key
                                        "x-client-request-id" cache-key}))
        body (cond-> (-> (:body base)
                         (assoc :stream true)
                         (dissoc :max_output_tokens))
               (nil? (get-in base [:body :instructions]))
               (assoc :instructions "You are a helpful assistant."))
        req (merge base
                   (direct-http-options manager provider-id)
                   {:url "https://chatgpt.com/backend-api/codex/responses"
                    :headers headers :body body})]
    (direct-sse-complete! provider-id model-id
                          (assoc transient-profile :profile/id provider-id)
                          (codex/make-transport) req options)))

(defn- copilot-complete! [manager provider-id p request options]
  (let [resolution (require-auth! manager provider-id p true options)
        model-id (:request/model request)
        claude? (str/starts-with? (str/lower-case model-id) "claude")
        base-url (:base-url resolution)
        profile (if claude?
                  {:profile/id provider-id :profile/base-url base-url
                   :profile/auth-strategy :bearer :profile/auth-token (:token resolution)
                   :profile/default-headers copilot-default-headers
                   :profile/capabilities (:capabilities p)}
                  {:profile/id provider-id :profile/base-url base-url
                   :profile/auth-strategy :bearer :profile/auth-token (:token resolution)
                   :profile/default-headers copilot-default-headers
                   :profile/capabilities (:capabilities p)})
        transport (if claude? (anthropic/make-transport) (codex/make-transport))
        req (merge
             (if claude?
               (anthropic/build-request-anthropic profile (assoc request :request/stream? true))
               (codex/build-request-codex profile (assoc request :request/stream? true)))
             (direct-http-options manager provider-id))]
    (direct-sse-complete! provider-id model-id profile transport req options)))

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

(defn- sdk-complete! [manager provider-id p request options]
  (let [resolution (require-auth! manager provider-id p true options)
        sdk-id (:sdk-id p)
        custom? (= :openai-compatible (:kind p))
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
        config (cond-> (merge (select-keys (:settings manager)
                                           [:connect-timeout-ms :timeout-ms :transport :incremental?])
                              (select-keys (get-in (:settings manager) [:provider-options provider-id])
                                           [:connect-timeout-ms :timeout-ms :transport :incremental?]))
                 (and (:token resolution) (not vertex?)) (assoc :api-key (:token resolution))
                 custom? (assoc :base-url (:base-url p))
                 (seq (:headers p)) (assoc :headers (:headers p)))
        callback (fn [event] (emit-event! options event))]
    (when (auth/cancelled? options) (throw (cancelled-ex)))
    (let [response (sdk/complete sdk-id request :stream? true :on-event callback
                                 :retry false :config config)]
      (when (auth/cancelled? options) (throw (cancelled-ex)))
      (assoc response :response/provider provider-id))))

(defn- validate-request! [provider-id request]
  (when-not (and (map? request)
                 (string? (:request/model request))
                 (not (str/blank? (:request/model request)))
                 (vector? (:request/messages request)))
    (fail! :provider/request "Invalid canonical provider request"
           {:provider provider-id}))
  (when-let [effort (get-in request [:request/reasoning :effort])]
    (when-not (contains? all-thinking-levels effort)
      (fail! :provider/thinking "Unsupported reasoning effort"
             {:provider provider-id :effort effort})))
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
                                                      :provider provider-id))
            reason (:error/reason classified :unknown)]
        (ex-info (or (ex-message error) "Provider request failed")
                 (merge data
                        {:error/code (str "provider/" (name reason))
                         :error/type reason
                         :retryable? (boolean (:error/retryable classified))
                         :provider provider-id
                         :error classified
                         :attempts 1})
                 error)))))

(defn complete!
  "Complete one logical provider attempt. Selected provider is opts :provider."
  [manager canonical-request {:keys [provider] :as options}]
  (open-manager! manager)
  (when-not provider
    (fail! :provider/selection "Provider selection is required in opts :provider" {}))
  (let [provider-id (keyword provider)
        p (profile manager provider-id)]
    (validate-request! provider-id canonical-request)
    (when (auth/cancelled? options) (throw (cancelled-ex)))
    (try
      (if-let [complete-fn (:complete-fn manager)]
        (complete-fn canonical-request options)
        (case (:kind p)
          :copilot (copilot-complete! manager provider-id p canonical-request options)
          :azure-openai (azure-complete! manager provider-id p canonical-request options)
          :profile-alias (sdk-complete! manager provider-id p canonical-request options)
          :openai-compatible (sdk-complete! manager provider-id
                                            (assoc p :sdk-id :openai)
                                            canonical-request options)
          (if (contains? #{:codex-backend :openai-codex} provider-id)
            (codex-complete! manager provider-id p canonical-request options)
            (sdk-complete! manager provider-id p canonical-request options))))
      (catch clojure.lang.ExceptionInfo e
        (case (:error/type (ex-data e))
          :provider/cancelled (throw e)
          :auth/cancelled (throw (cancelled-ex))
          (throw (classified-ex provider-id e))))
      (catch Exception e
        (throw (classified-ex provider-id e))))))

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
