(ns arrodes.setup
  "First-launch provider, model, and project-trust setup through the host UI channel."
  (:refer-clojure :exclude [run!])
  (:require [arrodes.platform :as platform]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.runtime :as runtime]
            [arrodes.value :as value]
            [clojure.string :as str]))

(def ^:private config-keys [:provider :model :thinking])

(defn- keyword-value [x]
  (if (string? x) (keyword x) x))

(defn- normalized-config [config]
  (cond-> (select-keys (or config {}) config-keys)
    (:provider config) (update :provider keyword-value)
    (:thinking config) (update :thinking keyword-value)))

(defn- settings-config [settings]
  (merge (normalized-config settings)
         (normalized-config (:session-defaults settings))
         (normalized-config (:session settings))
         (normalized-config (:session-config settings))))

(defn- provider-entry [providers provider-id]
  (some #(when (= (keyword-value (:provider %)) provider-id) %) providers))

(defn- configured? [{:keys [provider model]} providers]
  (and (keyword? provider)
       (string? model)
       (not (str/blank? model))
       (true? (:available? (provider-entry providers provider)))))

(defn project-info [rt]
  (let [info (or (:project rt) (platform/project-info (:home rt) (:cwd rt)))
        trust (resources/project-trust (:resources rt))]
    (assoc info :trust trust)))

(defn status
  "Return readiness without prompting or changing configuration; public model metadata may be cached."
  [rt params]
  (let [settings (resources/settings (:resources rt))
        requested (merge (normalized-config (select-keys params config-keys))
                         (normalized-config (:config params)))
        config (merge (settings-config settings) requested)
        providers (:providers (provider/status (:provider rt)))
        project (project-info rt)
        configuration-ready? (configured? config providers)
        project-trust (:trust project)
        trust-ready? (or (not (:required? project-trust))
                         (some? (:trust rt))
                         (true? (:configured? project-trust)))]
    {:ready? (and configuration-ready? trust-ready?)
     :configuration-ready? configuration-ready?
     :trust-ready? trust-ready?
     :config config
     :providers providers
     :project project
     :project-trust project-trust}))

(defn- choose! [rt title message items]
  (runtime/ui! rt {:kind :select :title title :message message :items items}))

(defn- credential-label [entry]
  (let [auth (:auth entry)
        source (:source auth)
        type (:type auth)]
    (str (case type :oauth "OAuth" :api-key "API key" :ambient "ambient credentials"
               :none "no credentials" (or (some-> type name) "credentials"))
         (when source (str " from " (str/replace (name source) "-" " "))))))

(defn auth-event! [rt event]
  (case (keyword (:type event))
    (:auth-url :device-code)
    (let [url (or (:url event) (:verification-uri event))
          description (str (:instructions event) "\n" url
                           (when-let [code (:user-code event)] (str "\nDevice code: " code)))
          choice (choose! rt "Authenticate in your browser" description
                          [{:label "Open browser" :description description :value :open-browser}
                           {:label "Continue manually" :description "Open the displayed URL yourself"
                            :value :continue}])]
      (when (= :open-browser (keyword-value choice))
        (try
          (.browse (java.awt.Desktop/getDesktop) (java.net.URI. url))
          (catch Exception _
            (runtime/ui! rt {:kind :render :title "Open this sign-in URL"
                             :content (str "A browser could not be opened. Copy this URL into your browser:\n"
                                           description)})))))
    (runtime/ui! rt {:kind :notify
                     :message (or (:message event) (pr-str (dissoc event :type)))})))

(defn- login! [rt manager entry]
  (let [auth-type (keyword-value (get-in entry [:auth :type]))]
    (when (= :ambient auth-type)
      (value/fail! :auth/ambient
                   "This provider uses ambient credentials. Configure them in the environment, then retry setup."
                   {:provider (:provider entry)}))
    (provider/login!
     manager (:provider entry)
     {:type auth-type
      :input (fn [prompt]
               (runtime/ui! rt {:kind :input
                                :title (or (:message prompt) "Provider authentication")
                                :prompt (not-empty (dissoc prompt :type :message))
                                :secret? (contains? #{:secret :manual-code} (keyword-value (:type prompt)))}))
      :on-event #(auth-event! rt %)})))

(defn- select-provider! [rt providers preferred]
  (or preferred
      (keyword-value
       (choose! rt "Choose a provider" "Select the provider Arrodes should use by default."
                (mapv (fn [entry]
                        {:label (or (:name entry) (name (:provider entry)))
                         :description (if (:available? entry)
                                        (str "Available: " (credential-label entry))
                                        (str "Sign-in required: " (credential-label entry)))
                         :value (:provider entry)})
                      providers)))))

(defn- ensure-auth! [rt manager entry]
  (let [auth-type (keyword-value (get-in entry [:auth :type]))]
    (cond
      (= :none auth-type) nil
      (:available? entry)
      (let [items (cond-> [{:label (str "Use existing " (credential-label entry))
                            :description "Reuse this credential explicitly" :value :reuse}]
                    (not= :ambient auth-type)
                    (conj {:label "Sign in again"
                           :description "Replace or refresh the stored credential"
                           :value :login}))
            choice (keyword-value
                    (choose! rt "Use provider credentials"
                             (str (or (:name entry) (name (:provider entry))) " is already available.")
                             items))]
        (when (= :login choice) (login! rt manager entry)))
      :else (login! rt manager entry))))

(defn- select-model! [rt models preferred]
  (if preferred
    (do
      (value/check! (some #(= preferred (:id %)) models) :setup/model-unavailable
                    "The selected model is not available from this provider"
                    {:model preferred})
      preferred)
    (choose! rt "Choose a model" "Only models discovered from the selected provider are shown."
             (mapv (fn [model]
                     {:label (or (:name model) (:id model))
                      :description (:id model)
                      :value (:id model)})
                   models))))

(defn- select-thinking! [rt model preferred]
  (let [levels (mapv keyword-value (or (seq (:thinking-levels model)) [:none]))]
    (if preferred
      (do
        (value/check! (some #{preferred} levels) :setup/thinking-unavailable
                      "The selected thinking level is not available for this model"
                      {:thinking preferred :available levels})
        preferred)
      (keyword-value
       (choose! rt "Choose a thinking level" "This becomes the global default for new sessions."
                (mapv (fn [level] {:label (name level)
                                   :description "Explicit reasoning level"
                                   :value level}) levels))))))

(defn- configure! [rt params current]
  (let [manager (:provider rt)
        providers (:providers current)
        requested (merge (normalized-config (select-keys params config-keys))
                         (normalized-config (:config params)))
        preferred-provider (or (:provider requested)
                               (when (and (not (:configuration-ready? current))
                                          (provider-entry providers (get-in current [:config :provider])))
                                 (get-in current [:config :provider])))
        provider-id (select-provider! rt providers preferred-provider)
        entry (or (provider-entry providers provider-id)
                  (value/fail! :setup/provider-unavailable "Provider is not available"
                               {:provider provider-id}))
        _ (ensure-auth! rt manager entry)
        models (->> (provider/refresh! manager provider-id)
                    (filter #(= provider-id (keyword-value (:provider %))))
                    vec)
        _ (value/check! (seq models) :setup/no-models
                        "The provider returned no usable models" {:provider provider-id})
        preferred-model (or (:model requested)
                            (when (and (not (:force? params))
                                       (= provider-id (get-in current [:config :provider])))
                              (get-in current [:config :model])))
        model-id (select-model! rt models preferred-model)
        model (some #(when (= model-id (:id %)) %) models)
        preferred-thinking (or (:thinking requested)
                               (when (and (not (:force? params))
                                          (= model-id (get-in current [:config :model])))
                                 (get-in current [:config :thinking])))
        thinking (select-thinking! rt model preferred-thinking)
        config {:provider provider-id :model model-id :thinking thinking}]
    (resources/update-settings!
     (:resources rt)
     (reduce (fn [changes scope]
               (if (map? (get (resources/settings (:resources rt)) scope))
                 (assoc changes scope (zipmap config-keys (repeat nil)))
                 changes))
             config [:session-defaults :session :session-config])
     {:scope :global})
    config))

(defn- record-trust! [rt current]
  (when-not (:trust-ready? current)
    (let [project (:project current)
          trusted? (= :trust
                      (keyword-value
                       (choose! rt "Trust this project?"
                                (str "Trust controls project-scoped settings and executable resources for "
                                     (:root project) ".")
                                [{:label "Do not trust" :description "Use global resources only" :value :untrusted}
                                 {:label "Trust project" :description "Allow this project's resources on future sessions"
                                  :value :trust}])))]
      (resources/trust! (:resources rt) (:root project) trusted?))))

(defn- validate-model-selection! [rt params scope sid]
  (let [manager (if (= :session scope) (runtime/provider-manager rt sid) (:provider rt))
        config (normalized-config params)
        entry (provider-entry (:providers (provider/status manager)) (:provider config))
        _ (value/check! entry :provider-unavailable
                        (if (= :default scope)
                          "Configure this provider globally before making it a default"
                          "This provider is not available in the conversation") {})
        _ (value/check! (:available? entry) :provider-not-connected
                        "Connect this provider before selecting a model" {})
        model (or (provider/model manager (:provider config) (:model config))
                  ;; Session discovery is cached in its own manager. A newly listed
                  ;; model may need discovery in the global catalog before saving it.
                  (when (and (= :default scope) (:refreshable? entry))
                    (provider/refresh! manager (:provider config))
                    (provider/model manager (:provider config) (:model config))))
        _ (value/check! model :model-unavailable "Refresh this provider to find an available model" {})
        levels (mapv keyword-value (or (seq (:thinking-levels model)) [:none]))
        _ (value/check! (some #{(:thinking config)} levels) :thinking-unavailable
                        "Select a reasoning level supported by this model" {:available levels})]
    config))

(defn apply-model!
  "Apply a model to this session, or save the default and apply it here too."
  [rt params]
  (let [scope (keyword-value (or (:scope params) :session))
        sid (:session-id params)
        current? (and (= :default scope) (some? sid))
        _ (value/check! (contains? #{:session :default} scope) :invalid-scope
                        "Choose session or default scope" {})
        _ (when (or (= :session scope) current?)
            (value/check! (and (string? sid) (not (str/blank? sid))) :invalid-session-id
                          "Select a conversation first" {}))
        config (validate-model-selection! rt params scope sid)
        _ (when current?
            (runtime/session rt sid)
            (validate-model-selection! rt params :session sid))]
    (if (= :session scope)
      {:scope scope :config config :session (runtime/configure! rt sid {:config config})}
      (do
        (resources/update-settings!
         (:resources rt)
         (reduce (fn [changes key]
                   (if (map? (get (resources/settings (:resources rt)) key))
                     (assoc changes key (zipmap config-keys (repeat nil))) changes))
                 config [:session-defaults :session :session-config])
         {:scope :global})
        (cond-> {:scope scope :config config}
          current?
          (assoc :session
                 (try (runtime/configure! rt sid {:config config})
                      (catch Exception e
                        (throw (ex-info "Default saved, but applying it to this session failed. Refresh the session before retrying."
                                        {:error/type :model-partially-applied :default-saved? true
                                         :session-id sid} e))))))))))

(defn run!
  "Complete missing setup interactively and return the resulting public status."
  [rt params]
  (let [current (status rt params)
        config (if (and (:configuration-ready? current) (not (:force? params)))
                 (:config current)
                 (configure! rt params current))
        after-config (assoc current :config config :configuration-ready? true)]
    (record-trust! rt after-config)
    (status rt params)))
