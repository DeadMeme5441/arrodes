(ns arrodes.mcp
  "Session-owned, lazy Model Context Protocol clients and their REPL gateway."
  (:require [arrodes.capabilities :as capabilities]
            [arrodes.owned-process :as owned-process]
            [arrodes.platform :as u]
            [arrodes.value :as value]
            [clojure.string :as str]
            [plumcp.core.api.entity-gen :as entity]
            [plumcp.core.api.entity-support :as entity-support]
            [plumcp.core.api.mcp-client :as client]
            [plumcp.core.client.client-support :as client-support]
            [plumcp.core.client.http-client-transport :as http-transport]
            [plumcp.core.protocol :as protocol]
            [plumcp.core.support.http-client :as http-client]
            [plumcp.core.support.traffic-logger :as traffic]
            [plumcp.core.util :as plum-util])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter)
           (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption)))

(def ^:private default-timeout-ms 30000)
(def ^:private maximum-timeout-ms 300000)
(def ^:private maximum-pages 100)
(def ^:private server-pattern #"[A-Za-z0-9_.-]{1,100}")
(def ^:private environment-pattern #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")
(def ^:private supported-actions
  #{"catalog" "status" "describe" "call" "resources" "read-resource"
    "prompts" "get-prompt" "reconnect"})

(defrecord ClientPool [workspace configs states locks closed?])

(defn- fail! [code message data]
  (value/fail! code message data))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- server-name [value]
  (let [name (cond
               (keyword? value) (name value)
               (string? value) value
               :else nil)]
    (when-not (and name (re-matches server-pattern name))
      (fail! :mcp/invalid-server-name
             "MCP server name must contain only letters, digits, dot, underscore, or hyphen"
             {:server value}))
    name))

(defn- string-map [server field value]
  (when-not (and (map? value)
                 (every? #(or (keyword? %) (string? %)) (keys value))
                 (every? string? (vals value)))
    (fail! :mcp/invalid-config
           "MCP environment and headers must map names to string environment references"
           {:server server :field field}))
  (into {} (map (fn [[key item]] [(name key) item])) value))

(defn- assert-environment-references! [server field values]
  (doseq [[key value] values]
    (when-not (re-find environment-pattern value)
      (fail! :mcp/plaintext-secret-forbidden
             "MCP environment and header values must contain a ${VARIABLE} reference"
             {:server server :field field :name key})))
  values)

(defn- normalized-transport [server value]
  (let [value (or value :stdio)]
    (when-not (or (keyword? value) (string? value))
      (fail! :mcp/unsupported-transport
             "MCP transport must be :stdio or :streamable-http"
             {:server server :transport value}))
    (let [transport (keyword (name value))]
      (case transport
        :stdio :stdio
        (:http :streamable-http) :streamable-http
        (fail! :mcp/unsupported-transport
               "MCP transport must be :stdio or :streamable-http"
               {:server server :transport transport})))))

(defn- normalize-cwd [workspace server value]
  (let [path (u/path (or value workspace))
        resolved (if (.isAbsolute path)
                   (.normalize path)
                   (.normalize (.resolve (u/path workspace) path)))]
    (when-not (Files/isDirectory resolved (make-array LinkOption 0))
      (fail! :mcp/invalid-cwd "MCP server working directory does not exist"
             {:server server :cwd (str value)}))
    (str (.toRealPath resolved (make-array LinkOption 0)))))

(defn- normalize-config [workspace name config]
  (let [name (server-name name)]
    (when-not (map? config)
      (fail! :mcp/invalid-config "MCP server configuration must be a map"
             {:server name}))
    (let [transport (normalized-transport name (or (:transport config) (:type config)))
          timeout-value (or (:timeout-ms config) default-timeout-ms)
          enabled? (not= false (if (contains? config :enabled?)
                                 (:enabled? config)
                                 (:enabled config true)))
          env (some->> (:env config) (string-map name :env)
                       (assert-environment-references! name :env))
          headers (some->> (:headers config) (string-map name :headers)
                           (assert-environment-references! name :headers))]
      (when (and (or (contains? config :enabled?) (contains? config :enabled))
                 (not (instance? Boolean
                                 (if (contains? config :enabled?)
                                   (:enabled? config)
                                   (:enabled config)))))
        (fail! :mcp/invalid-config "MCP :enabled? must be boolean"
               {:server name}))
      (when-not (and (integer? timeout-value)
                     (<= 1 timeout-value maximum-timeout-ms))
        (fail! :mcp/invalid-timeout
               "MCP timeout must be an integer from 1 through 300000 milliseconds"
               {:server name :timeout-ms timeout-value}))
      (let [base {:transport transport :enabled? enabled?
                  :timeout-ms (long timeout-value)}]
        [name
         (case transport
           :stdio
           (let [command (:command config)
                 args (or (:args config) [])]
             (when-not (non-blank-string? command)
               (fail! :mcp/invalid-config "STDIO MCP server requires :command"
                      {:server name}))
             (when-not (and (vector? args) (every? string? args))
               (fail! :mcp/invalid-config "STDIO MCP :args must be a vector of strings"
                      {:server name}))
             (let [command-path (u/path command)]
               (when-not (.isAbsolute command-path)
                 (fail! :mcp/invalid-command "STDIO MCP :command must be an absolute path"
                        {:server name :command command}))
               (when-not (and (Files/isRegularFile command-path (make-array LinkOption 0))
                              (Files/isExecutable command-path))
                 (fail! :mcp/invalid-command
                        "STDIO MCP :command must identify an executable regular file"
                        {:server name :command command})))
             (cond-> (assoc base :command command :args args
                            :cwd (normalize-cwd workspace name (:cwd config)))
               env (assoc :env env)))

           :streamable-http
           (let [url (:url config)]
             (when-not (non-blank-string? url)
               (fail! :mcp/invalid-config "Streamable HTTP MCP server requires :url"
                      {:server name}))
             (let [uri (try (URI. url)
                            (catch Throwable error
                              (fail! :mcp/invalid-url "MCP server URL is invalid"
                                     {:server name :cause (ex-message error)})))
                   scheme (some-> uri .getScheme str/lower-case)
                   host (some-> uri .getHost str/lower-case)]
               (when-not (contains? #{"http" "https"} scheme)
                 (fail! :mcp/invalid-url "MCP server URL must use http or https"
                        {:server name}))
               (when (.getUserInfo uri)
                 (fail! :mcp/plaintext-secret-forbidden
                        "MCP server URL must not contain user information"
                        {:server name}))
               (when (and (= "http" scheme)
                          (not (contains? #{"localhost" "127.0.0.1" "::1"} host)))
                 (fail! :mcp/insecure-url
                        "Non-local Streamable HTTP MCP servers must use https"
                        {:server name})))
             (cond-> (assoc base :url url)
               headers (assoc :headers headers))))]))))

(defn- configured-servers [workspace settings]
  (let [servers (:mcp/servers settings {})]
    (when-not (map? servers)
      (fail! :mcp/invalid-config ":mcp/servers must be a map of server names to configurations"
             {}))
    (into (sorted-map)
          (map (fn [[name config]] (normalize-config workspace name config)))
          servers)))

(defn create!
  "Creates a closed-over session client pool without connecting any server."
  [workspace settings]
  (let [workspace (u/canonical-path workspace)
        configs (configured-servers workspace settings)]
    (->ClientPool workspace configs
                  (atom (into {} (map (fn [[name config]]
                                        [name {:name name
                                               :status (if (:enabled? config)
                                                         :disconnected :disabled)}]))
                              configs))
                  (atom {}) (atom false))))

(defn- ensure-open! [pool]
  (when @(:closed? pool)
    (fail! :mcp/closed "MCP client pool is closed" {})))

(defn- server-lock [pool name]
  (get (swap! (:locks pool)
              #(if (contains? % name) % (assoc % name (Object.))))
       name))

(defn- current-context []
  (or capabilities/*invocation-context* {}))

(defn- check-cancelled! []
  (u/check-cancelled! (:cancelled? (current-context))))

(defn- progress! [event]
  (when-let [callback (:on-progress (current-context))]
    (try (callback event) (catch Throwable _ nil))))

(defn- expand-environment [value]
  (str/replace
   value environment-pattern
   (fn [[token variable]]
     (or (System/getenv variable)
         (fail! :mcp/unset-environment-variable
                "MCP configuration references an unset environment variable"
                {:variable variable :reference token})))))

(defn- expand-map [values]
  (into {} (map (fn [[key value]] [key (expand-environment value)])) values))

(defn- update-attempt! [pool name token f]
  (swap! (:states pool) update name
         (fn [state]
           (if (= token (:attempt state)) (f state) state))))

(defn- daemon-thread [name f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.setName name)
    (.start)))

(defn- stdio-client-transport [options]
  (let [{:keys [command-tokens dir env on-server-exit on-stderr-text]} options
        owner (atom nil)
        writer (atom nil)
        closing? (atom false)
        start!
        (fn [on-message]
          (let [owned (owned-process/start! command-tokens {:cwd dir :env env})
                process ^Process (:process owned)
                stdin (OutputStreamWriter. (.getOutputStream process)
                                           StandardCharsets/UTF_8)]
            (reset! owner owned)
            (reset! writer stdin)
            (daemon-thread
             "arrodes-mcp-stdout"
             (fn []
               (with-open [reader (BufferedReader.
                                   (InputStreamReader. (.getInputStream process)
                                                       StandardCharsets/UTF_8))]
                 (loop []
                   (when-let [line (.readLine reader)]
                     (when-not @closing?
                       (try (on-message (plum-util/json-parse line))
                            (catch Throwable _ nil)))
                     (recur))))))
            (daemon-thread
             "arrodes-mcp-stderr"
             (fn []
               (with-open [reader (BufferedReader.
                                   (InputStreamReader. (.getErrorStream process)
                                                       StandardCharsets/UTF_8))]
                 (loop []
                   (when-let [line (.readLine reader)]
                     (when-not @closing? (on-stderr-text line))
                     (recur))))))
            (daemon-thread
             "arrodes-mcp-exit"
             (fn []
               (let [exit (.waitFor process)]
                 (when-not @closing?
                   (try
                     (owned-process/stop! owned)
                     (compare-and-set! owner owned nil)
                     (on-server-exit exit nil)
                     (catch Throwable cleanup-error
                       (on-server-exit exit cleanup-error)))))))))
        stop!
        (fn []
          (reset! closing? true)
          (when-let [owned @owner]
            (owned-process/stop! owned)
            (compare-and-set! owner owned nil))
          (reset! writer nil))]
    {:owner owner
     :transport
     (reify
       protocol/IClientTransport
       (client-transport-info [_] {:id :stdio :command-tokens command-tokens})
       (start-client-transport [_ on-message] (start! on-message))
       (stop-client-transport! [_ _] (stop!))
       (send-message-to-server [_ message]
         (if-let [output @writer]
           (locking output
             (.write ^OutputStreamWriter output (plum-util/json-write message))
             (.write ^OutputStreamWriter output "\n")
             (.flush ^OutputStreamWriter output))
           (throw (ex-info "MCP stdio transport is not running"
                           {:error/code "mcp/transport-closed"}))))
       (upon-handshake-success [_ _] nil))}))

(defn- make-transport [pool name config token]
  (case (:transport config)
    :stdio
    (stdio-client-transport
     {:command-tokens (into [(expand-environment (:command config))]
                            (map expand-environment (:args config)))
      :dir (:cwd config)
      :env (some-> (:env config) expand-map)
      :on-server-exit
      (fn [exit-code cleanup-error]
        (update-attempt!
         pool name token
         #(cond-> (-> %
                      (assoc :status (if cleanup-error :cleanup-failed :error)
                             :error (if cleanup-error
                                      (str "MCP server exited but cleanup failed: "
                                           (ex-message cleanup-error))
                                      (str "MCP server exited with code " exit-code)))
                      (dissoc :client :attempt))
            (nil? cleanup-error) (dissoc :owned))))
      :on-stderr-text
      (fn [text]
        (update-attempt! pool name token #(assoc % :last-stderr (str text))))})

    :streamable-http
    {:transport
     (let [headers (some-> (:headers config) expand-map)
           timeout-ms (:timeout-ms config)
           transport-client
           (http-client/make-http-client
            (expand-environment (:url config))
            :timeout-millis timeout-ms
            :request-middleware
            (fn [request]
              (cond-> (assoc request :timeout-millis timeout-ms)
                headers (update :headers merge headers))))]
       (http-transport/make-streamable-http-transport transport-client))
     :owner (atom nil)}))

(defn- make-client [pool name config token]
  (let [{:keys [transport owner]} (make-transport pool name config token)]
    (try
      (let [mcp-client
            (client/make-client
             {:info (entity-support/make-info "Arrodes" "0.1.2" "Arrodes headless runtime")
              :client-transport transport
              :traffic-logger traffic/nop-traffic-logger
              :print-banner? false
              :heartbeat-seconds 0})]
        {:client mcp-client :owned @owner})
      (catch Throwable error
        (when-let [owned @owner]
          (try
            (owned-process/stop! owned)
            (catch Throwable cleanup-error
              (swap! (:states pool) assoc name
                     {:name name :status :cleanup-failed :owned owned
                      :error (ex-message cleanup-error)})
              (throw (ex-info (str "Could not clean up failed MCP connection " name)
                              (merge (ex-data cleanup-error)
                                     {:error/code "mcp/cleanup-failed" :server name})
                              cleanup-error)))))
        (throw error)))))

(defn- remaining-ms [deadline server operation]
  (let [remaining (- deadline (System/currentTimeMillis))]
    (when-not (pos? remaining)
      (fail! :mcp/timeout "MCP operation timed out"
             {:server server :operation operation}))
    remaining))

(defn- initialize! [mcp-client timeout-ms server]
  (check-cancelled!)
  (try
    (let [result (client/initialize-and-notify!
                  mcp-client
                  :timeout-millis timeout-ms
                  :timeout-value ::timeout
                  :on-error (partial client-support/on-error-throw! "initialize")
                  :on-timeout (partial client-support/on-timeout-throw! "initialize")
                  :on-unknown (partial client-support/on-unknown-throw! "initialize"))]
      (when-not result
        (fail! :mcp/initialize-failed "MCP server returned no initialize result"
               {:server server}))
      (check-cancelled!)
      result)
    (catch InterruptedException error
      (.interrupt (Thread/currentThread))
      (fail! :cancelled "MCP initialization was cancelled"
             {:server server :cause (ex-message error)}))))

(defn- request! [state operation request timeout-ms]
  (check-cancelled!)
  (let [mcp-client (:client state)
        request-id (:id request)
        timeout-value (Object.)]
    (try
      (let [response (client/request->response request mcp-client
                                               :timeout-millis timeout-ms
                                               :timeout-value timeout-value)]
        (when (identical? timeout-value response)
          (client/cancel-sent-request mcp-client request-id :reason "Arrodes timeout")
          (fail! :mcp/timeout "MCP request timed out"
                 {:server (:name state) :operation operation
                  :request-id request-id :timeout-ms timeout-ms}))
        (check-cancelled!)
        (client-support/response->result-or-throw! response operation))
      (catch InterruptedException error
        (client/cancel-sent-request mcp-client request-id :reason "Arrodes cancellation")
        (.interrupt (Thread/currentThread))
        (fail! :cancelled "MCP request was cancelled"
               {:server (:name state) :operation operation
                :request-id request-id :cause (ex-message error)}))
      (catch Throwable error
        (if (u/cancelled? (:cancelled? (current-context)))
          (do
            (try (client/cancel-sent-request mcp-client request-id
                                             :reason "Arrodes cancellation")
                 (catch Throwable _ nil))
            (fail! :cancelled "MCP request was cancelled"
                   {:server (:name state) :operation operation
                    :request-id request-id}))
          (if (:error/code (ex-data error))
            (throw error)
            (throw (ex-info (str "MCP " operation " failed: "
                                 (or (ex-message error) (.getName (class error))))
                            (merge (ex-data error)
                                   {:error/code "mcp/protocol-error"
                                    :server (:name state)
                                    :operation operation
                                    :request-id request-id})
                            error))))))))

(defn- page-request [kind cursor]
  (case kind
    :tools (entity/make-list-tools-request :cursor cursor)
    :resources (entity/make-list-resources-request :cursor cursor)
    :resource-templates (entity/make-list-resource-templates-request :cursor cursor)
    :prompts (entity/make-list-prompts-request :cursor cursor)))

(defn- item-key [kind]
  (case kind
    :tools :tools
    :resources :resources
    :resource-templates :resourceTemplates
    :prompts :prompts))

(defn- list-items! [state kind deadline]
  (loop [cursor nil, page 0, items []]
    (when (>= page maximum-pages)
      (fail! :mcp/pagination-limit "MCP listing exceeded the page limit"
             {:server (:name state) :operation kind :maximum-pages maximum-pages}))
    (let [operation (str "list-" (name kind))
          result (request! state operation (page-request kind cursor)
                           (remaining-ms deadline (:name state) operation))
          accumulated (into items (or (get result (item-key kind)) []))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor (inc page) accumulated)
        (vec accumulated)))))

(defn- supports? [initialize-result capability]
  (let [declared (:capabilities initialize-result)]
    (or (contains? declared capability)
        (contains? declared (name capability)))))

(defn- disconnect-locked! [pool name status]
  (when-let [state (get @(:states pool) name)]
    (swap! (:states pool) update name #(-> % (assoc :status :disconnecting)
                                           (dissoc :attempt)))
    (let [client-error (when-let [mcp-client (:client state)]
                         (try (client/disconnect! mcp-client) nil
                              (catch Throwable error error)))
          owner-error (when-let [owned (:owned state)]
                        (try (owned-process/stop! owned) nil
                             (catch Throwable error error)))
          error (or owner-error client-error)]
      (if error
        (do
          (swap! (:states pool) assoc name
                 (cond-> (-> state
                             (dissoc :attempt)
                             (assoc :status :cleanup-failed :error (ex-message error)))
                   (nil? owner-error) (dissoc :owned :client)))
          (throw (ex-info (str "Could not clean up MCP server " name ": "
                               (or (ex-message error) (.getName (class error))))
                          (merge (ex-data error)
                                 {:error/code "mcp/cleanup-failed" :server name})
                          error)))
        (swap! (:states pool) update name
               #(when % (-> (select-keys % [:name])
                            (assoc :status status))))))))

(defn disconnect!
  "Disconnects one session-owned MCP client."
  [pool server]
  (let [name (server-name server)]
    (locking (server-lock pool name)
      (disconnect-locked! pool name
                          (if (get-in (:configs pool) [name :enabled?])
                            :disconnected :disabled)))))

(defn- connect-locked! [pool name]
  (ensure-open! pool)
  (let [config (get (:configs pool) name)]
    (when-not config
      (fail! :mcp/unknown-server "Unknown MCP server" {:server name}))
    (when-not (:enabled? config)
      (fail! :mcp/server-disabled "MCP server is disabled" {:server name}))
    (if (= :ready (get-in @(:states pool) [name :status]))
      (get @(:states pool) name)
      (let [token (u/id)
            candidate (atom nil)
            deadline (+ (System/currentTimeMillis) (:timeout-ms config))]
        (disconnect-locked! pool name :disconnected)
        (swap! (:states pool) assoc name {:name name :status :connecting :attempt token})
        (progress! {:type :mcp/connection :server name :status :connecting})
        (try
          (let [{:keys [client owned] :as connection} (make-client pool name config token)
                _ (reset! candidate connection)
                _ (update-attempt! pool name token
                                   #(assoc % :client client :owned owned))
                initialized (initialize! client
                                         (remaining-ms deadline name "initialize") name)
                provisional {:name name :status :connecting :attempt token
                             :client client :owned owned :initialize initialized}
                tools (if (supports? initialized :tools)
                        (list-items! provisional :tools deadline) [])
                resources (if (supports? initialized :resources)
                            (list-items! provisional :resources deadline) [])
                templates (if (supports? initialized :resources)
                            (list-items! provisional :resource-templates deadline) [])
                prompts (if (supports? initialized :prompts)
                          (list-items! provisional :prompts deadline) [])
                ready (assoc provisional :status :ready
                             :tools (vec (sort-by :name tools))
                             :resources (vec (sort-by :uri resources))
                             :resource-templates (vec (sort-by :uriTemplate templates))
                             :prompts (vec (sort-by :name prompts)))]
            (if (= token (get-in @(:states pool) [name :attempt]))
              (do
                (swap! (:states pool) assoc name ready)
                (progress! {:type :mcp/connection :server name :status :ready})
                ready)
              (do
                (disconnect-locked! pool name :disconnected)
                (fail! :mcp/connection-superseded
                       "MCP connection attempt was superseded" {:server name}))))
          (catch Throwable error
            (let [cleanup-error
                  (when-let [{mcp-client :client owned :owned} @candidate]
                    (when mcp-client
                      (try (client/disconnect! mcp-client) (catch Throwable _ nil)))
                    (when owned
                      (try (owned-process/stop! owned) nil
                           (catch Throwable cleanup-error cleanup-error))))]
              (update-attempt!
               pool name token
               #(if cleanup-error
                  (-> % (assoc :status :cleanup-failed
                               :error (str "Connection failed and cleanup was incomplete: "
                                           (ex-message cleanup-error))))
                  (-> (select-keys % [:name])
                      (assoc :status :error :error (ex-message error)))))
              (when cleanup-error
                (throw (ex-info (str "Could not clean up failed MCP connection " name)
                                (merge (ex-data cleanup-error)
                                       {:error/code "mcp/cleanup-failed" :server name})
                                cleanup-error))))
            (cond
              (= "mcp/cleanup-failed" (:error/code (ex-data error)))
              (throw error)

              (or (= "cancelled" (:error/code (ex-data error)))
                  (u/cancelled? (:cancelled? (current-context))))
              (fail! :cancelled "MCP connection was cancelled"
                     {:server name :cause (ex-message error)})

              :else
              (throw (ex-info (str "Could not connect to MCP server " name ": "
                                   (or (ex-message error) (.getName (class error))))
                              (merge (ex-data error)
                                     {:error/code "mcp/connect-failed" :server name})
                              error)))))))))

(defn connect!
  "Lazily initializes one configured server and discovers its functions and resources."
  [pool server]
  (let [name (server-name server)]
    (locking (server-lock pool name)
      (connect-locked! pool name))))

(defn- bounded-text [value]
  (when (some? value)
    (let [text (str value)]
      (subs text 0 (min 4096 (count text))))))

(defn- public-state [pool name]
  (let [state (get @(:states pool) name)
        config (get (:configs pool) name)]
    (cond-> {:server name :status (:status state)
             :transport (:transport config) :enabled? (:enabled? config)
             :timeout-ms (:timeout-ms config)}
      (:error state) (assoc :error (bounded-text (:error state)))
      (:initialize state) (assoc :server-info
                                 (select-keys (:serverInfo (:initialize state))
                                              [:name :version :title :description]))
      (:tools state) (assoc :tool-count (count (:tools state)))
      (:owned state) (assoc :process-ownership (:kind (:owned state)))
      (:resources state) (assoc :resource-count (count (:resources state)))
      (:prompts state) (assoc :prompt-count (count (:prompts state))))))

(defn status
  "Returns connection metadata without commands, URLs, headers, or resolved environment values."
  [pool]
  (ensure-open! pool)
  {:servers (mapv #(public-state pool %) (keys (:configs pool)))})

(defn- with-ready [pool server f]
  (let [name (server-name server)]
    (locking (server-lock pool name)
      (check-cancelled!)
      (let [state (connect-locked! pool name)]
        (f state)))))

(defn- discovery [state]
  {:server (:name state)
   :tools (mapv #(select-keys % [:name :title :description :annotations]) (:tools state))
   :resources (mapv #(select-keys % [:uri :name :title :description :mimeType :size])
                    (:resources state))
   :resource-templates
   (mapv #(select-keys % [:uriTemplate :name :title :description :mimeType])
         (:resource-templates state))
   :prompts (mapv #(select-keys % [:name :title :description :arguments]) (:prompts state))})

(defn catalog!
  "Discovers enabled servers on demand. An explicit server propagates connection errors."
  ([pool] (catalog! pool nil))
  ([pool server]
   (ensure-open! pool)
   (if server
     (with-ready pool server discovery)
     {:servers
      (mapv (fn [[name config]]
              (if-not (:enabled? config)
                (public-state pool name)
                (try
                  (with-ready pool name discovery)
                  (catch Throwable error
                    (if (= "cancelled" (:error/code (ex-data error)))
                      (throw error)
                      (public-state pool name))))))
            (:configs pool))})))

(defn- descriptor! [pool server tool-name]
  (when-not (non-blank-string? tool-name)
    (fail! :mcp/invalid-tool "MCP tool name must be a non-blank string" {}))
  (with-ready
    pool server
    (fn [state]
      (or (some #(when (= tool-name (:name %)) %) (:tools state))
          (fail! :mcp/unknown-tool "Unknown MCP tool"
                 {:server (:name state) :tool tool-name
                  :available (mapv :name (:tools state))})))))

(defn- call-tool! [pool server tool-name arguments]
  (when-not (map? arguments)
    (fail! :mcp/invalid-arguments "MCP tool arguments must be a map"
           {:tool tool-name}))
  (with-ready
    pool server
    (fn [state]
      (when-not (some #(= tool-name (:name %)) (:tools state))
        (fail! :mcp/unknown-tool "Unknown MCP tool"
               {:server (:name state) :tool tool-name
                :available (mapv :name (:tools state))}))
      (progress! {:type :mcp/request :server (:name state)
                  :operation :call :name tool-name :status :started})
      (let [result (request! state "call-tool"
                             (entity/make-call-tool-request tool-name arguments)
                             (get-in (:configs pool) [(:name state) :timeout-ms]))]
        (when (true? (:isError result))
          (let [remote-message (some #(when (= "text" (:type %)) (:text %))
                                     (:content result))]
            (fail! :mcp/tool-error
                   (str "MCP tool returned an error"
                        (when-not (str/blank? remote-message)
                          (str ": " (bounded-text remote-message))))
                   {:server (:name state) :tool tool-name :result result})))
        (progress! {:type :mcp/request :server (:name state)
                    :operation :call :name tool-name :status :completed})
        result))))

(defn- read-resource! [pool server uri]
  (when-not (non-blank-string? uri)
    (fail! :mcp/invalid-resource "MCP resource URI must be a non-blank string" {}))
  (with-ready pool server
    #(request! % "read-resource" (entity/make-read-resource-request uri)
               (get-in (:configs pool) [(:name %) :timeout-ms]))))

(defn- get-prompt! [pool server prompt-name arguments]
  (when-not (non-blank-string? prompt-name)
    (fail! :mcp/invalid-prompt "MCP prompt name must be a non-blank string" {}))
  (when-not (map? arguments)
    (fail! :mcp/invalid-arguments "MCP prompt arguments must be a map"
           {:prompt prompt-name}))
  (with-ready pool server
    #(request! % "get-prompt"
               (entity/make-get-prompt-request prompt-name :args arguments)
               (get-in (:configs pool) [(:name %) :timeout-ms]))))

(defn invoke!
  "Invokes one function-oriented MCP gateway operation and returns native Clojure data."
  [pool {:keys [action server name arguments uri] :as request}]
  (ensure-open! pool)
  (when-not (contains? supported-actions action)
    (fail! :mcp/unsupported-action "Unsupported MCP gateway action"
           {:action action :available (vec (sort supported-actions))}))
  (case action
    "status" (status pool)
    "catalog" (catalog! pool server)
    "describe" (descriptor! pool server name)
    "call" (call-tool! pool server name (or arguments {}))
    "resources" (with-ready pool server
                  #(select-keys % [:resources :resource-templates]))
    "read-resource" (read-resource! pool server uri)
    "prompts" (with-ready pool server :prompts)
    "get-prompt" (get-prompt! pool server name (or arguments {}))
    "reconnect" (let [server (server-name server)]
                  (locking (server-lock pool server)
                    (disconnect-locked! pool server :disconnected)
                    (discovery (connect-locked! pool server))))
    (fail! :mcp/unsupported-action "Unsupported MCP gateway action" {:request request})))

(defn- gateway-content [action value]
  (case action
    "call" (let [texts (keep #(when (= "text" (:type %)) (:text %)) (:content value))]
             (if (seq texts) (str/join "\n" texts) (pr-str value)))
    (binding [*print-length* 100 *print-level* 12] (pr-str value))))

(defn gateway-descriptor
  "Returns the capability descriptor for a session-owned MCP pool."
  [pool owner]
  {:name "mcp"
   :owner owner
   :description
   (str "Discover and use configured Model Context Protocol servers as native Clojure data. "
        "Start with {:action \"catalog\"}; use describe for a tool's complete input schema, "
        "call to invoke it, resources/read-resource for resources, and prompts/get-prompt for prompts. "
        "Remote tools remain behind this gateway and are never flattened into provider tools.")
   :parameters
   {:type "object"
    :properties
    {:action {:type "string" :enum (vec (sort supported-actions))}
     :server {:type "string" :description "Configured server name."}
     :name {:type "string" :description "Remote tool or prompt name."}
     :arguments {:type "object" :additionalProperties true}
     :uri {:type "string" :description "Remote resource URI."}}
    :required ["action"]
    :additionalProperties false}
   :execution :parallel
   :permission :execute
   :validate
   (fn [{:keys [action server name uri]}]
     (cond
       (and (not (contains? #{"catalog" "status"} action))
            (not (non-blank-string? server)))
       "This MCP action requires a non-blank server name"

       (and (contains? #{"describe" "call" "get-prompt"} action)
            (not (non-blank-string? name)))
       "This MCP action requires a non-blank tool or prompt name"

       (and (= "read-resource" action) (not (non-blank-string? uri)))
       "read-resource requires a non-blank URI"

       :else true))
   :fn (fn [{:keys [action] :as arguments}]
         (let [value (invoke! pool arguments)]
           {:value value
            :content (gateway-content action value)
            :details {:gateway :mcp :action action}}))})

(defn close!
  "Disconnects all session-owned clients and rejects subsequent operations.
   Cleanup failures are reported and retained so a later close can retry them."
  [pool]
  (let [first-close? (compare-and-set! (:closed? pool) false true)
        errors
        (reduce
         (fn [result name]
           (locking (server-lock pool name)
             (try
               (disconnect-locked! pool name :closed)
               result
               (catch Throwable error
                 (conj result {:server name
                               :code (:error/code (ex-data error))
                               :message (ex-message error)})))))
         [] (keys (:configs pool)))]
    {:closed? true
     :cleanup-complete? (empty? errors)
     :already-closed? (not first-close?)
     :errors errors}))
