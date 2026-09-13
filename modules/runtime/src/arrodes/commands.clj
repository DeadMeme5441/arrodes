(ns arrodes.commands
  "Transport-independent commands. Every interface uses this command surface."
  (:refer-clojure :exclude [methods])
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.capabilities :as capabilities]
            [arrodes.packages :as packages]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.runtime :as runtime]
            [arrodes.util :as u]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util.concurrent TimeUnit)))

(def version "0.1.0")
(def protocol-version 1)
(def methods
  ["runtime.inspect" "session.list" "session.create" "session.inspect" "session.state"
   "session.entries" "session.tree" "session.configure" "session.name" "session.label"
   "session.rewind" "session.fork" "session.clone" "session.delete" "session.run"
   "session.continue" "session.compact" "session.steer" "session.follow-up" "session.cancel"
   "session.queue" "session.reload" "session.evaluate" "session.invoke" "session.command"
   "session.export" "session.import" "session.share" "operation.list" "operation.inspect"
   "operation.wait" "operation.cancel" "operation.steer" "operation.follow-up"
   "capability.list" "capability.set" "capability.attach" "capability.detach"
   "result.list" "result.inspect" "artifact.list" "artifact.read" "artifact.inspect" "artifact.write"
   "resource.list" "skill.read" "prompt.render" "prompt.run" "model.list" "model.refresh"
   "auth.status" "auth.login" "auth.logout" "settings.get" "settings.update" "project.trust"
   "package.list" "package.install" "package.remove" "package.update" "event.replay"])

(defn public-value
  "Project ordinary data without serializing runtime handles or executable functions."
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value) (number? value) (keyword? value)) value
    (symbol? value) (str value)
    (instance? Throwable value) (public-value (u/redact (u/error-map value)))
    (fn? value) {:type :function :available? false}
    (map? value) (into {} (keep (fn [[k v]]
                                (when-not (contains? #{:fn :implementation :namespace :thread :future :executor :connection} k)
                                  [k (public-value v)]))) value)
    (set? value) (mapv public-value (sort-by pr-str value))
    (sequential? value) (mapv public-value value)
    (bytes? value) {:type :binary :encoding :base64 :data (.encodeToString (java.util.Base64/getEncoder) value)}
    (instance? java.util.Date value) (.getTime ^java.util.Date value)
    (instance? java.time.Instant value) (str value)
    :else {:type (.getName (class value)) :available? false}))

(defn- required-string [params key]
  (let [value (get params key)]
    (u/check! (and (string? value) (not (str/blank? value))) :invalid-params
              (str "Expected a non-empty " (name key)) {:parameter key})
    value))
(defn- sid [params] (required-string params :session-id))
(defn- oid [params] (required-string params :operation-id))
(defn- keyword-value [value] (if (string? value) (keyword value) value))
(defn- required-provider [params]
  (let [value (:provider params)]
    (u/check! (or (keyword? value) (and (string? value) (not (str/blank? value))))
              :invalid-params "Provider must be a name" {:parameter :provider})
    (keyword-value value)))
(defn normalize-config [config]
  (u/check! (map? config) :invalid-params "Configuration must be a map" {})
  (cond-> config
    (:provider config) (update :provider keyword-value)
    (:thinking config) (update :thinking #(let [level (keyword-value %)] (if (= :off level) :none level)))
    (= "all" (:tools config)) (assoc :tools :all)))
(defn- run-options [params]
  (cond-> (or (:options params) {})
    (:config params) (assoc :config (normalize-config (:config params)))
    (:instructions params) (assoc :instructions (:instructions params))))
(defn- content [params]
  (let [value (:prompt params)]
    (u/check! (or (and (string? value) (not (str/blank? value)))
                  (and (vector? value) (seq value))) :invalid-params "Prompt must contain text or content parts" {})
    value))
(defn- positive-int [value field default maximum]
  (let [n (or value default)]
    (u/check! (and (integer? n) (<= 1 n maximum)) :invalid-params
              (str (name field) " is outside its supported range") {:parameter field :maximum maximum})
    n))
(defn- resource-manager [rt params]
  (if (:session-id params) (runtime/resource-manager rt (sid params)) (:resources rt)))
(defn- provider-manager [rt params]
  (if (:session-id params) (runtime/provider-manager rt (sid params)) (:provider rt)))
(defn- home [rt] (or (:home rt) (u/home-dir {})))
(defn- cwd [rt] (or (:cwd rt) (System/getProperty "user.dir")))
(defn- invocation-view [result]
  (public-value (dissoc result :value)))
(defn- import-file [file]
  (with-open [in (io/input-stream file)]
    (let [limit (* 32 1024 1024)
          bytes (.readNBytes in (inc limit))]
      (u/check! (<= (alength bytes) limit) :invalid-import "Session import exceeds 32 MiB" {})
      (String. bytes java.nio.charset.StandardCharsets/UTF_8))))

(defn export-jsonl
  "A versioned JSONL envelope preserves EDN values without lossy keyword coercion."
  [{:keys [entries] :as packet}]
  (str (json/write-str {:type "session" :format (:format packet) :version (:version packet)
                       :encoding "edn" :data (pr-str (dissoc packet :entries))}) "\n"
       (apply str (map #(str (json/write-str {:type "entry" :data (pr-str %)}) "\n") entries))))
(defn import-content [text]
  (u/check! (and (string? text) (<= (count text) (* 32 1024 1024)))
            :invalid-import "Session import must be text of at most 32 MiB" {})
  (try
    (let [text (str/trim text)]
      (u/check! (seq text) :invalid-import "Session import is empty" {})
      (if (str/starts-with? text "{\"")
        (let [lines (remove str/blank? (str/split text #"\n"))
              header (json/read-str (first lines) :key-fn keyword)]
          (u/check! (and (= "session" (:type header)) (= "arrodes-session" (:format header))
                        (= 1 (:version header)) (= "edn" (:encoding header)))
                    :invalid-import "Unsupported JSONL session format" {})
          (let [metadata (edn/read-string (:data header))]
            (u/check! (map? metadata) :invalid-import "Session header data must be a map" {})
            (assoc metadata :entries
                   (mapv (fn [line]
                           (let [entry (json/read-str line :key-fn keyword)]
                             (u/check! (= "entry" (:type entry)) :invalid-import "Invalid session record" {})
                             (edn/read-string (:data entry))))
                         (rest lines)))))
        (edn/read-string text)))
    (catch Exception error
      (if (:error/code (ex-data error))
        (throw error)
        (u/fail! :invalid-import "Session data could not be decoded" {:reason (ex-message error)})))))
(defn- escape-html [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))
(defn export-html [{:keys [session entries]}]
  (str "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (escape-html (:name session)) " — Arrodes</title>"
       "<style>body{max-width:960px;margin:3rem auto;padding:0 1.5rem;background:#13171b;color:#e2e7eb;font:16px/1.65 system-ui}h1{font-size:2rem}article{border-top:1px solid #35404a;padding:1.2rem 0}header{color:#98b7c8;font:12px monospace}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.65 ui-monospace,monospace}.meta{color:#a3adb6}details{margin-top:.6rem}summary{cursor:pointer}a{color:#98cbea}</style>"
       "<h1>" (escape-html (or (:name session) "Arrodes session")) "</h1><p class=\"meta\">Session "
       (escape-html (:id session)) " · " (count entries) " entries</p>"
       (apply str
              (map (fn [entry]
                     (let [data (:data entry)
                           role (if (= :message (:kind entry)) (:message/role data) (:kind entry))
                           text (cond
                                  (= :message (:kind entry)) (u/text-content (:message/content data))
                                  (:summary data) (:summary data)
                                  :else (u/bounded-string data 200000))]
                       (str "<article id=\"" (escape-html (:id entry)) "\"><header>"
                            (escape-html role) " · " (escape-html (:id entry)) "</header><pre>"
                            (escape-html text) "</pre>"
                            (when (seq (:message/tool-calls data))
                              (str "<details open><summary>Tool calls</summary><pre>"
                                   (escape-html (pr-str (:message/tool-calls data))) "</pre></details>"))
                            "</article>"))) entries))
       "</html>"))
(defn- exported [rt params]
  (let [packet (runtime/export! rt (sid params))
        format (keyword-value (or (:format params) :jsonl))
        text (case format
               :jsonl (export-jsonl packet)
               :edn (str (pr-str packet) "\n")
               :html (export-html packet)
               (u/fail! :invalid-params "Export format must be jsonl, edn, or html" {:format format}))]
    (cond-> {:content text :format format}
      (:path params) (assoc :path (u/atomic-write! (:path params) text {:private? true})))))
(defn- share! [rt params]
  (let [html (:content (exported rt (assoc params :format :html)))
        process (try
                  (.start (doto (ProcessBuilder. ^java.util.List ["gh" "gist" "create" "--desc" "Arrodes session" "--filename" "session.html" "-"])
                            (.redirectErrorStream true)))
                  (catch java.io.IOException error
                    (u/fail! :share-unavailable "GitHub CLI is required for explicit session sharing" {:cause (ex-message error)})))
        output (future (slurp (.getInputStream process)))]
    (try
      (with-open [writer (io/writer (.getOutputStream process))] (.write writer html))
      (when-not (.waitFor process 60 TimeUnit/SECONDS)
        (.destroyForcibly process)
        (u/fail! :timeout "Session sharing timed out" {}))
      (let [text (str/trim @output)]
        (u/check! (zero? (.exitValue process)) :share-failed "GitHub rejected session sharing" {:detail text})
        (u/check! (re-find #"https://gist\.github\.com/\S+" text) :share-failed "GitHub did not return a gist URL" {:detail text})
        {:url text})
      (finally
        (when (.isAlive process) (.destroyForcibly process))
        (.close (.getInputStream process))
        (future-cancel output)))))

(defn dispatch!
  "Execute one session command. Transport framing, authentication prompts and display stay outside."
  [rt method params]
  (u/check! (map? params) :invalid-params "Command parameters must be an object" {})
  (case method
    "runtime.inspect" {:version version :protocol protocol-version :cwd (cwd rt)
                       :sessions (count (runtime/list-sessions rt {}))
                       :operations (count (runtime/operations rt {})) :methods methods}
    "session.list" {:sessions (runtime/list-sessions rt (select-keys params [:cwd]))}
    "session.create" (runtime/create-session! rt (cond-> params (:config params) (update :config normalize-config)))
    "session.inspect" {:session (runtime/session rt (sid params)) :state (runtime/state rt (sid params))
                       :usage (runtime/usage rt (sid params))}
    "session.state" (runtime/state rt (sid params))
    "session.entries" {:entries (if (:branch? params) (runtime/active-path rt (sid params)) (runtime/entries rt (sid params)))}
    "session.tree" {:entries (runtime/entries rt (sid params)) :head (:head (runtime/session rt (sid params)))}
    "session.configure" (runtime/configure! rt (sid params)
                                            (cond-> (select-keys params [:name :metadata :expected-revision])
                                              (:config params) (assoc :config (normalize-config (:config params)))))
    "session.name" (runtime/configure! rt (sid params) {:name (required-string params :name)})
    "session.label" (runtime/label! rt (sid params) (required-string params :entry-id) (:label params) params)
    "session.rewind" (runtime/branch! rt (sid params) (:entry-id params) params)
    "session.fork" (runtime/fork! rt (sid params) (cond-> params (:position params) (update :position keyword-value)))
    "session.clone" (runtime/clone! rt (sid params) params)
    "session.delete" (runtime/delete! rt (sid params))
    "session.run" (runtime/start! rt (sid params) (content params) (run-options params))
    "session.continue" (runtime/start-continue! rt (sid params) (run-options params))
    "session.compact" (runtime/start-compact! rt (sid params) (run-options params))
    "session.steer" (runtime/steer! rt (sid params) (content params) (run-options params))
    "session.follow-up" (runtime/follow-up! rt (sid params) (content params) (run-options params))
    "session.cancel" (runtime/cancel! rt (sid params))
    "session.queue" {:items (if (:clear? params) (runtime/clear-queue! rt (sid params)) (runtime/pending rt (sid params)))}
    "session.reload" (do (runtime/reload! rt (sid params)) {:session-id (sid params) :status :reloaded})
    "session.evaluate" (invocation-view (runtime/evaluate! rt (sid params) (required-string params :source) (run-options params)))
    "session.invoke" (invocation-view (runtime/invoke! rt (sid params) (required-string params :name)
                                                       (or (:arguments params) {}) (run-options params)))
    "session.command" (resources/command! (resource-manager rt params) (runtime/registry rt (sid params))
                                          {:session-id (sid params) :get-session #(runtime/session rt (sid params))
                                           :command! #(dispatch! rt %1 %2) :ui! #(runtime/ui! rt %)}
                                          (required-string params :name) (or (:arguments params) ""))
    "session.export" (exported rt params)
    "session.import" (do
                       (u/check! (not (and (:path params) (:content params))) :invalid-params "Supply either path or content" {})
                       (let [text (if (:path params) (import-file (:path params)) (:content params))]
                         (runtime/import! rt (import-content text) (select-keys params [:cwd :name]))))
    "session.share" (share! rt params)
    "operation.list" {:operations (runtime/operations rt params)}
    "operation.inspect" (runtime/operation rt (oid params))
    "operation.wait" (runtime/wait! rt (oid params) (positive-int (:timeout-ms params) :timeout-ms 30000 300000))
    "operation.cancel" (runtime/cancel-operation! rt (oid params))
    "operation.steer" (runtime/steer-operation! rt (oid params) (content params) (run-options params))
    "operation.follow-up" (runtime/follow-up-operation! rt (oid params) (content params) (run-options params))
    "capability.list" {:capabilities (mapv #(dissoc % :fn :validate :mutation-key) (capabilities/catalog (runtime/registry rt (sid params))))}
    "capability.set" (runtime/configure! rt (sid params) {:config (normalize-config {:tools (:tools params)})})
    "capability.attach" (let [name (required-string params :name)
                              registry (runtime/registry rt (sid params))
                              descriptor {:name name :description (or (:description params) "Host capability")
                                          :parameters (or (:parameters params) {:type "object" :properties {}})
                                          :execution (keyword-value (or (:execution params) :sequential))
                                          :owner (or (:connection-id params) "host")
                                          :permission :execute
                                          :fn (fn [arguments]
                                                (runtime/ui! rt {:kind :capability :name name :arguments arguments
                                                                 :session-id (sid params)}))}]
                          (capabilities/register! registry descriptor)
                          (dissoc descriptor :fn))
    "capability.detach" (let [name (required-string params :name)
                             registry (runtime/registry rt (sid params))
                             descriptor (some #(when (= name (:name %)) %) (capabilities/catalog registry))
                             owner (or (:connection-id params) "host")]
                         (u/check! (and descriptor (= owner (:owner descriptor)))
                                   :capability-not-owned "This connection does not own the capability" {:name name})
                         (capabilities/unregister! registry name)
                         {:removed name})
    "result.list" {:results (artifacts/results (:store rt) (sid params))}
    "result.inspect" (artifacts/result (:store rt) (sid params) (positive-int (:result-id params) :result-id nil Long/MAX_VALUE))
    "artifact.list" {:artifacts (artifacts/list-artifacts (:store rt) (sid params))}
    "artifact.inspect" (artifacts/get-artifact (:store rt) (sid params) (required-string params :artifact-id))
    "artifact.read" (artifacts/read! (:store rt) (sid params) (required-string params :artifact-id) (select-keys params [:offset :limit]))
    "artifact.write" (artifacts/put! (:store rt) (sid params) (or (:content params) "") (select-keys params [:name]))
    "resource.list" (resources/catalog (resource-manager rt params))
    "skill.read" {:name (required-string params :name)
                  :content (if (:path params)
                             (resources/read-skill (resource-manager rt params) (:name params) (:path params))
                             (resources/read-skill (resource-manager rt params) (:name params)))}
    "prompt.render" {:content (resources/render-prompt (resource-manager rt params) (required-string params :name) (or (:arguments params) {}))}
    "prompt.run" (runtime/start! rt (sid params)
                                  (resources/render-prompt (resource-manager rt params) (required-string params :name) (or (:arguments params) {}))
                                  (run-options params))
    "model.list" {:models (provider/catalog (provider-manager rt params))}
    "model.refresh" {:models (if (:provider params) (provider/refresh! (provider-manager rt params) (keyword-value (:provider params))) (provider/refresh! (provider-manager rt params)))}
    "auth.status" (u/redact (provider/status (provider-manager rt params)))
    "auth.login" (u/redact (provider/login! (provider-manager rt params) (required-provider params)
                                           (assoc params
                                                  :input (fn [prompt] (runtime/ui! rt {:kind :input :prompt prompt :secret? true}))
                                                  :on-event (fn [event] (runtime/ui! rt (assoc event :kind :notify))))))
    "auth.logout" (provider/logout! (provider-manager rt params) (required-provider params))
    "settings.get" {:settings (u/redact (resources/settings (resource-manager rt params)))}
    "settings.update" (u/redact (resources/update-settings! (resource-manager rt params) (or (:changes params) {})
                                                           {:scope (keyword-value (or (:scope params) :project))}))
    "project.trust" (do (u/check! (boolean? (:trusted? params)) :invalid-params "trusted? must be a boolean" {})
                         (resources/trust! (:resources rt) (or (:cwd params) (cwd rt)) (:trusted? params)))
    "package.list" {:packages (packages/list-packages (home rt) (cwd rt))}
    "package.install" (packages/install! (home rt) (cwd rt) (required-string params :source)
                                         (update params :scope #(keyword-value (or % :global))))
    "package.remove" (packages/remove! (home rt) (cwd rt) (required-string params :name)
                                       (update params :scope #(keyword-value (or % :global))))
    "package.update" (packages/update! (home rt) (cwd rt) (:name params)
                                       (update params :scope #(keyword-value (or % :global))))
    "event.replay" (let [events (runtime/events-since rt (assoc params :limit (positive-int (:limit params) :limit 100 500)))]
                     {:events events :cursor (or (:seq (peek events)) (:after params) 0)})
    (u/fail! :method-not-found "Unknown command" {:method method :methods methods})))
