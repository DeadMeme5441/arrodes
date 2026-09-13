(ns arrodes.auth
  "Private provider credential storage and interactive authentication flows."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException FileAlreadyExistsException Files LinkOption
            OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermission]
           [java.security MessageDigest SecureRandom]
           [java.time Duration]
           [java.util Base64]
           [java.util.concurrent.locks ReentrantLock]))

(defrecord AuthStore [^Path directory ^Path file ^Path lock-file ^ReentrantLock process-lock closed?])

(def ^:private store-version 1)
(def ^:private file-permissions
  #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
(def ^:private directory-permissions
  #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE
    PosixFilePermission/OWNER_EXECUTE})
(defonce ^:private process-locks (atom {}))
(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 15))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(def ^:private openai-client-id "app_EMoamEEZ73f0CkXaXp7hrann")
(def ^:private openai-token-url "https://auth.openai.com/oauth/token")
(def ^:private anthropic-client-id "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
(def ^:private anthropic-token-url "https://platform.claude.com/v1/oauth/token")
(def ^:private github-client-id "Iv1.b507a08c87ecfe98")
(def ^:private copilot-headers
  {"User-Agent" "GitHubCopilotChat/0.35.0"
   "Editor-Version" "vscode/1.107.0"
   "Editor-Plugin-Version" "copilot-chat/0.35.0"
   "Copilot-Integration-Id" "vscode-chat"})

(defn- fail! [code message data]
  (throw (ex-info message (assoc data :error/code (name code) :error/type code))))

(defn- check-open! [store]
  (when @(:closed? store)
    (fail! :auth/closed "Authentication store is closed" {})))

(defn- set-permissions! [^Path path permissions]
  (try
    (Files/setPosixFilePermissions path permissions)
    (catch UnsupportedOperationException _ nil))
  path)

(defn open!
  "Open private credential storage below the configured Arrodes home."
  [home]
  (let [directory (.resolve (Path/of (str home) (make-array String 0)) "auth")
        file (.resolve directory "credentials.edn")
        lock-file (.resolve directory ".credentials.lock")]
    (Files/createDirectories directory (make-array java.nio.file.attribute.FileAttribute 0))
    (when (Files/isSymbolicLink directory)
      (fail! :auth/unsafe-store "Credential directory may not be a symbolic link" {}))
    (set-permissions! directory directory-permissions)
    (when-not (Files/exists lock-file (make-array LinkOption 0))
      (try
        (Files/createFile lock-file (make-array java.nio.file.attribute.FileAttribute 0))
        (catch FileAlreadyExistsException _ nil)))
    (when (Files/isSymbolicLink lock-file)
      (fail! :auth/unsafe-store "Credential lock may not be a symbolic link" {}))
    (set-permissions! lock-file file-permissions)
    (->AuthStore directory file lock-file
                 (get (swap! process-locks
                             #(if (contains? % (str lock-file))
                                %
                                (assoc % (str lock-file) (ReentrantLock.))))
                      (str lock-file))
                 (atom false))))

(defn- with-store-lock [store f]
  (check-open! store)
  (let [^ReentrantLock local (:process-lock store)
        options (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE])]
    (.lock local)
    (try
      (with-open [channel (FileChannel/open (:lock-file store) options)
                  file-lock (.lock channel)]
        (f))
      (finally (.unlock local)))))

(defn- read-state* [store]
  (when (Files/isSymbolicLink (:file store))
    (fail! :auth/unsafe-store "Credential file may not be a symbolic link" {}))
  (if-not (Files/isRegularFile (:file store) (make-array LinkOption 0))
    {:version store-version :providers {}}
    (try
      (let [value (edn/read-string {:readers {} :default (fn [_ _] nil)}
                                   (Files/readString (:file store) StandardCharsets/UTF_8))]
        (if (and (= store-version (:version value)) (map? (:providers value)))
          value
          (fail! :auth/invalid-store "Credential file has an unsupported format" {})))
      (catch clojure.lang.ExceptionInfo e (throw e))
      (catch Exception e
        (throw (ex-info "Unable to read credential file"
                        {:error/code "auth/storage-read" :error/type :auth/storage-read}
                        e))))))

(defn- write-state* [store state]
  (let [tmp (Files/createTempFile (:directory store) ".credentials-" ".tmp"
                                  (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (set-permissions! tmp file-permissions)
      (Files/writeString tmp (pr-str state) StandardCharsets/UTF_8
                         (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING
                                                 StandardOpenOption/WRITE]))
      (try
        (Files/move tmp (:file store)
                    (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                    StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move tmp (:file store)
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (set-permissions! (:file store) file-permissions)
      (finally
        (Files/deleteIfExists tmp)))))

(defn credential [store provider-id]
  (with-store-lock store #(get-in (read-state* store) [:providers provider-id])))

(defn put-credential! [store provider-id value]
  (with-store-lock
   store
   (fn []
     (let [state (read-state* store)]
       (write-state* store (assoc-in state [:providers provider-id] value))
       value))))

(defn delete-credential! [store provider-id]
  (with-store-lock
   store
   (fn []
     (let [state (read-state* store)]
       (write-state* store (update state :providers dissoc provider-id))
       nil))))

(defn credential-info [store]
  (with-store-lock
   store
   (fn []
     (into {}
           (map (fn [[provider c]]
                  [provider (cond-> {:type (:type c)}
                              (:expires-at c) (assoc :expires-at (:expires-at c))
                              (:source c) (assoc :source (:source c)))])
                (:providers (read-state* store)))))))

(defn close! [store]
  (when (compare-and-set! (:closed? store) false true)
    true))

(defn cancelled? [options]
  (boolean (when-let [f (:cancelled? options)] (f))))

(defn- ensure-active! [options]
  (when (cancelled? options)
    (fail! :auth/cancelled "Authentication was cancelled" {})))

(defn notify! [options event]
  (ensure-active! options)
  (when-let [f (:on-event options)] (f event))
  nil)

(defn prompt! [options prompt]
  (ensure-active! options)
  (if-let [f (:input options)]
    (let [answer (f prompt)]
      (ensure-active! options)
      (str (or answer "")))
    (fail! :auth/input-required "Authentication requires an input callback"
           {:prompt/type (:type prompt)})))

(defn- encode [x]
  (URLEncoder/encode (str x) StandardCharsets/UTF_8))

(defn- form-body [values]
  (str/join "&" (map (fn [[k v]] (str (encode (name k)) "=" (encode v))) values)))

(defn request!
  "Perform an explicit authentication/catalog HTTP request and parse JSON."
  [{:keys [method url headers body form timeout-ms]
    :or {method :get timeout-ms 30000}}]
  (let [payload (cond
                  form (form-body form)
                  (some? body) (json/write-str body)
                  :else nil)
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis (long timeout-ms))))
        headers (cond-> (or headers {})
                  form (assoc "Content-Type" "application/x-www-form-urlencoded")
                  (and (some? body) (not form)) (assoc "Content-Type" "application/json")
                  true (assoc "Accept" "application/json"))]
    (doseq [[k v] headers] (.header builder (str k) (str v)))
    (case method
      :post (.POST builder (HttpRequest$BodyPublishers/ofString (or payload "")))
      :get (.GET builder)
      :delete (.DELETE builder)
      (fail! :auth/http-method "Unsupported authentication HTTP method" {:method method}))
    (let [response (.send ^HttpClient @http-client (.build builder)
                          (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
          status (.statusCode response)
          text (.body response)
          parsed (when-not (str/blank? text)
                   (try (json/read-str text :key-fn keyword)
                        (catch Exception _ nil)))]
      (if (<= 200 status 299)
        (or parsed {})
        (throw (ex-info (str "Authentication request failed with HTTP " status)
                        {:error/code "auth/http"
                         :error/type :auth/http
                         :status status}))))))

(defn- random-url-token [bytes]
  (let [value (byte-array bytes)]
    (.nextBytes (SecureRandom.) value)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value)))

(defn- pkce []
  (let [verifier (random-url-token 64)
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes verifier StandardCharsets/US_ASCII))]
    {:verifier verifier
     :challenge (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) digest)}))

(defn- query-map [raw]
  (when (seq raw)
    (into {}
          (map (fn [piece]
                 (let [[k v] (str/split piece #"=" 2)]
                   [(java.net.URLDecoder/decode k StandardCharsets/UTF_8)
                    (java.net.URLDecoder/decode (or v "") StandardCharsets/UTF_8)])))
          (str/split raw #"&"))))

(defn parse-authorization-input [input]
  (let [input (str/trim (str input))]
    (cond
      (str/blank? input) {}
      (str/includes? input "://")
      (let [uri (URI/create input) q (query-map (.getRawQuery uri))]
        {:code (get q "code") :state (get q "state")})
      (str/includes? input "#")
      (let [[code state] (str/split input #"#" 2)] {:code code :state state})
      (str/includes? input "code=")
      (let [q (query-map input)] {:code (get q "code") :state (get q "state")})
      :else {:code input})))

(defn- callback-server [host port path expected-state]
  (try
    (let [server (HttpServer/create (InetSocketAddress. host (int port)) 0)
          result (promise)]
      (.createContext
       server path
       (reify HttpHandler
         (handle [_ exchange]
           (try
             (let [q (query-map (some-> exchange .getRequestURI .getRawQuery))
                   code (get q "code") state (get q "state")
                   valid? (and (seq code) (= expected-state state))
                   response (if valid?
                              "Authentication complete. You may close this window."
                              "Authentication failed: missing code or invalid state.")
                   bytes (.getBytes response StandardCharsets/UTF_8)]
               (.sendResponseHeaders exchange (if valid? 200 400) (long (alength bytes)))
               (with-open [out (.getResponseBody exchange)] (.write out bytes))
               (when valid? (deliver result {:code code :state state})))
             (catch Exception _
               (try (.sendResponseHeaders exchange 500 -1) (catch Exception _ nil)))
             (finally (.close exchange))))))
      (.start server)
      {:server server :result result})
    (catch Exception _ nil)))

(defn- await-browser-code [options callback expected-state]
  (if-let [provided (:code options)]
    (let [parsed (parse-authorization-input provided)]
      (when (and (:state parsed) (not= expected-state (:state parsed)))
        (fail! :auth/state-mismatch "OAuth state did not match" {}))
      (:code parsed))
    (let [manual (when (:input options)
                   (future (prompt! options
                                    {:type :manual-code
                                     :message "Complete browser login, or paste the authorization code/redirect URL"
                                     :placeholder "http://localhost/callback"})))
          deadline (+ (System/currentTimeMillis) (long (or (:timeout-ms options) 600000)))]
      (try
        (loop []
          (ensure-active! options)
          (cond
            (and callback (realized? (:result callback))) (:code @(:result callback))
            (and manual (future-done? manual))
            (let [parsed (parse-authorization-input @manual)]
              (when (and (:state parsed) (not= expected-state (:state parsed)))
                (fail! :auth/state-mismatch "OAuth state did not match" {}))
              (:code parsed))
            (>= (System/currentTimeMillis) deadline)
            (fail! :auth/timeout "Timed out waiting for OAuth callback" {})
            :else (do (Thread/sleep 100) (recur))))
        (finally (when manual (future-cancel manual)))))))

(defn- token-credential [provider response skew-ms]
  (let [access (:access_token response)
        refresh (:refresh_token response)
        expires (:expires_in response)]
    (when-not (and (string? access) (seq access))
      (fail! :auth/token-response "OAuth token response did not include an access token"
             {:provider provider}))
    (cond-> {:type :oauth
             :access-token access
             :expires-at (+ (System/currentTimeMillis)
                            (* 1000 (long (or expires 3600)))
                            (- (long skew-ms)))
             :source :stored-oauth}
      (and (string? refresh) (seq refresh)) (assoc :refresh-token refresh))))

(defn- jwt-account-id [token]
  (try
    (let [parts (str/split token #"\.")
          payload (json/read-str
                   (String. (.decode (Base64/getUrlDecoder) (nth parts 1)) StandardCharsets/UTF_8))]
      (or (get-in payload ["https://api.openai.com/auth" "chatgpt_account_id"])
          (get payload "chatgpt_account_id")))
    (catch Exception _ nil)))

(defn- login-openai-browser [options]
  (let [{:keys [verifier challenge]} (pkce)
        state (random-url-token 24)
        redirect "http://localhost:1455/auth/callback"
        callback (callback-server (or (:callback-host options) "127.0.0.1") 1455 "/auth/callback" state)
        url (str "https://auth.openai.com/oauth/authorize?"
                 (form-body {:response_type "code" :client_id openai-client-id
                             :redirect_uri redirect
                             :scope "openid profile email offline_access"
                             :code_challenge challenge :code_challenge_method "S256"
                             :state state :id_token_add_organizations "true"
                             :codex_cli_simplified_flow "true" :originator "arrodes"}))]
    (notify! options {:type :auth-url :url url
                      :instructions "Complete ChatGPT login in a browser; the local callback or pasted code will finish login."})
    (try
      (let [code (await-browser-code options callback state)]
        (when-not (seq code) (fail! :auth/missing-code "No OAuth authorization code was received" {}))
        (let [c (token-credential
                 :codex-backend
                 (request! {:method :post :url openai-token-url
                            :form {:grant_type "authorization_code" :client_id openai-client-id
                                   :code code :code_verifier verifier :redirect_uri redirect}})
                 0)]
          (assoc c :account-id (jwt-account-id (:access-token c)))))
      (finally (when callback (.stop ^HttpServer (:server callback) 0))))))

(defn- poll! [options interval-seconds expires-seconds f]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 (long expires-seconds)))]
    (loop [interval (max 1 (long (or interval-seconds 5))) first? true]
      (ensure-active! options)
      (when (>= (System/currentTimeMillis) deadline)
        (fail! :auth/device-expired "Device authorization expired" {}))
      (when first? (Thread/sleep (* 1000 interval)))
      (let [result (f)]
        (case (:status result)
          :complete (:value result)
          :pending (do (Thread/sleep (* 1000 interval)) (recur interval false))
          :slow-down (let [next (+ interval 5)]
                       (Thread/sleep (* 1000 next))
                       (recur next false))
          (fail! :auth/device-failed (or (:message result) "Device authorization failed") {}))))))

(defn- login-openai-device [options]
  (let [device (request! {:method :post
                          :url "https://auth.openai.com/api/accounts/deviceauth/usercode"
                          :body {:client_id openai-client-id}})
        auth-id (:device_auth_id device)
        user-code (:user_code device)
        interval (long (or (:interval device) 5))]
    (when-not (and (seq auth-id) (seq user-code))
      (fail! :auth/device-response "Invalid ChatGPT device-code response" {}))
    (notify! options {:type :device-code :user-code user-code
                      :verification-uri "https://auth.openai.com/codex/device"
                      :interval-seconds interval :expires-in-seconds 900})
    (let [{:keys [authorization-code code-verifier]}
          (poll! options interval 900
                 (fn []
                   (try
                     (let [r (request! {:method :post
                                        :url "https://auth.openai.com/api/accounts/deviceauth/token"
                                        :body {:device_auth_id auth-id :user_code user-code}})]
                       (if (and (:authorization_code r) (:code_verifier r))
                         {:status :complete
                          :value {:authorization-code (:authorization_code r)
                                  :code-verifier (:code_verifier r)}}
                         {:status :pending}))
                     (catch clojure.lang.ExceptionInfo e
                       (if (contains? #{403 404} (:status (ex-data e)))
                         {:status :pending}
                         (throw e))))))
          c (token-credential
             :codex-backend
             (request! {:method :post :url openai-token-url
                        :form {:grant_type "authorization_code" :client_id openai-client-id
                               :code authorization-code :code_verifier code-verifier
                               :redirect_uri "https://auth.openai.com/deviceauth/callback"}})
             0)]
      (assoc c :account-id (jwt-account-id (:access-token c))))))

(defn- login-anthropic [options]
  (let [{:keys [verifier challenge]} (pkce)
        redirect "http://localhost:53692/callback"
        callback (callback-server (or (:callback-host options) "127.0.0.1") 53692 "/callback" verifier)
        scopes "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
        url (str "https://claude.ai/oauth/authorize?"
                 (form-body {:code "true" :client_id anthropic-client-id
                             :response_type "code" :redirect_uri redirect :scope scopes
                             :code_challenge challenge :code_challenge_method "S256"
                             :state verifier}))]
    (notify! options {:type :auth-url :url url
                      :instructions "Complete Anthropic login in a browser; the local callback or pasted code will finish login."})
    (try
      (let [code (await-browser-code options callback verifier)]
        (when-not (seq code) (fail! :auth/missing-code "No OAuth authorization code was received" {}))
        (token-credential
         :anthropic
         (request! {:method :post :url anthropic-token-url
                    :body {:grant_type "authorization_code" :client_id anthropic-client-id
                           :code code :state verifier :redirect_uri redirect
                           :code_verifier verifier}})
         300000))
      (finally (when callback (.stop ^HttpServer (:server callback) 0))))))

(defn- normalize-domain [value]
  (let [s (str/trim (str value))]
    (if (str/blank? s)
      "github.com"
      (try
        (.getHost (URI/create (if (str/includes? s "://") s (str "https://" s))))
        (catch Exception _ (fail! :auth/domain "Invalid GitHub Enterprise domain" {}))))))

(defn copilot-base-url [token enterprise-domain]
  (or (when-let [[_ host] (and token (re-find #"(?:^|;)proxy-ep=([^;]+)" token))]
        (str "https://" (str/replace host #"^proxy\." "api.")))
      (when (and enterprise-domain (not= enterprise-domain "github.com"))
        (str "https://copilot-api." enterprise-domain))
      "https://api.individual.githubcopilot.com"))

(defn copilot-token! [github-token enterprise-domain]
  (let [domain (or enterprise-domain "github.com")
        response (request! {:url (str "https://api." domain "/copilot_internal/v2/token")
                            :headers (merge copilot-headers
                                            {"Authorization" (str "Bearer " github-token)})})
        token (:token response)
        expires (:expires_at response)]
    (when-not (and (string? token) (number? expires))
      (fail! :auth/token-response "Invalid GitHub Copilot token response" {}))
    {:type :oauth :access-token token :refresh-token github-token
     :expires-at (- (* 1000 (long expires)) 300000)
     :enterprise-domain enterprise-domain :source :stored-oauth}))

(defn- login-copilot [options]
  (let [domain (normalize-domain
                (or (:enterprise-domain options)
                    (when (:input options)
                      (prompt! options {:type :text
                                        :message "GitHub Enterprise domain (blank for github.com)"
                                        :placeholder "github.com"}))))
        device (request! {:method :post :url (str "https://" domain "/login/device/code")
                          :headers {"User-Agent" "GitHubCopilotChat/0.35.0"}
                          :form {:client_id github-client-id :scope "read:user"}})
        device-code (:device_code device) user-code (:user_code device)
        verification (:verification_uri device) interval (long (or (:interval device) 5))
        expires (long (or (:expires_in device) 900))]
    (when-not (and (seq device-code) (seq user-code) (seq verification))
      (fail! :auth/device-response "Invalid GitHub device-code response" {}))
    (let [uri (URI/create verification)]
      (when-not (contains? #{"http" "https"} (.getScheme uri))
        (fail! :auth/device-response "GitHub returned an unsafe verification URL" {})))
    (notify! options {:type :device-code :user-code user-code
                      :verification-uri verification :interval-seconds interval
                      :expires-in-seconds expires})
    (let [github-token
          (poll! options interval expires
                 (fn []
                   (let [r (request! {:method :post
                                      :url (str "https://" domain "/login/oauth/access_token")
                                      :headers {"User-Agent" "GitHubCopilotChat/0.35.0"}
                                      :form {:client_id github-client-id :device_code device-code
                                             :grant_type "urn:ietf:params:oauth:grant-type:device_code"}})]
                     (cond
                       (:access_token r) {:status :complete :value (:access_token r)}
                       (= "authorization_pending" (:error r)) {:status :pending}
                       (= "slow_down" (:error r)) {:status :slow-down}
                       :else {:status :failed :message (or (:error_description r) (:error r))}))))]
      (copilot-token! github-token (when-not (= domain "github.com") domain)))))

(defn login!
  "Run explicit API-key or OAuth login and persist the resulting credential."
  [store provider-id options]
  (let [provider-id (keyword provider-id)
        api-key (:api-key options)
        mode (keyword (or (:type options)
                          (when (:oauth options) :oauth)
                          (when api-key :api-key)
                          (when (or (contains? #{:codex-backend :openai-codex :github-copilot} provider-id)
                                    (and (= :anthropic provider-id) (:code options)))
                            :oauth)
                          :api-key))
        credential
        (case mode
          :api-key
          (let [secret (or api-key
                           (prompt! options {:type :secret
                                             :message (str "Enter API key for " (name provider-id))}))]
            (when (str/blank? secret)
              (fail! :auth/missing-key "API key cannot be blank" {:provider provider-id}))
            (cond-> {:type :api-key :secret secret :source :stored-api-key}
              (:base-url options) (assoc :base-url (:base-url options))
              (:project options) (assoc :project (:project options))
              (:location options) (assoc :location (:location options))))
          :oauth
          (cond
            (contains? #{:codex-backend :openai-codex} provider-id)
            (if (= :device-code (keyword (or (:flow options) :browser)))
              (login-openai-device options)
              (login-openai-browser options))
            (= :anthropic provider-id) (login-anthropic options)
            (= :github-copilot provider-id) (login-copilot options)
            :else (fail! :auth/unsupported-oauth "Provider does not support OAuth login"
                         {:provider provider-id}))
          (fail! :auth/type "Unknown authentication type" {:type mode}))]
    (put-credential! store provider-id credential)
    {:provider provider-id :status :logged-in :type (:type credential)
     :expires-at (:expires-at credential)}))

(defn refresh!
  "Refresh one stored OAuth credential. API-key credentials are unchanged."
  [store provider-id options]
  (let [provider-id (keyword provider-id)
        c (credential store provider-id)]
    (when-not c (fail! :auth/not-configured "Provider has no stored credential" {:provider provider-id}))
    (if-not (= :oauth (:type c))
      {:provider provider-id :status :configured :type :api-key}
      (let [refresh-token (:refresh-token c)
            _ (when (str/blank? (str refresh-token))
                (fail! :auth/refresh-token "Stored OAuth credential has no refresh token"
                       {:provider provider-id}))
            updated
            (cond
              (contains? #{:codex-backend :openai-codex} provider-id)
              (let [x (token-credential
                       provider-id
                       (request! {:method :post :url openai-token-url
                                  :form {:grant_type "refresh_token"
                                         :refresh_token refresh-token
                                         :client_id openai-client-id}})
                       0)]
                (assoc x :account-id (jwt-account-id (:access-token x))))
              (= :anthropic provider-id)
              (token-credential
               provider-id
               (request! {:method :post :url anthropic-token-url
                          :body {:grant_type "refresh_token" :client_id anthropic-client-id
                                 :refresh_token refresh-token}})
               300000)
              (= :github-copilot provider-id)
              (copilot-token! refresh-token (:enterprise-domain c))
              :else
              (fail! :auth/unsupported-refresh "Provider does not support OAuth refresh"
                     {:provider provider-id}))]
        (ensure-active! options)
        (put-credential! store provider-id updated)
        {:provider provider-id :status :refreshed :type :oauth
         :expires-at (:expires-at updated)}))))

(defn ensure-fresh!
  "Refresh an OAuth credential only when its guarded expiry has passed."
  [store provider-id options]
  (when-let [c (credential store provider-id)]
    (if (and (= :oauth (:type c))
             (number? (:expires-at c))
             (<= (:expires-at c) (System/currentTimeMillis)))
      (do (refresh! store provider-id options) (credential store provider-id))
      c)))
