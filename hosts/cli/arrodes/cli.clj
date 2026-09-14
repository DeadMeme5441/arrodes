(ns arrodes.cli
  "Command-line entrypoint and non-interactive modes for Arrodes."
  (:refer-clojure :exclude [run!])
  (:require [arrodes.rpc :as rpc]
            [arrodes.platform :as u]
            [arrodes.value :as value]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class)
  (:import (java.io Writer)
           (java.nio ByteBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)
           (java.nio.file Files LinkOption)
           (java.util Base64)))

(def version "0.1.0")
(def ^:private max-input-bytes (* 32 1024 1024))
(def ^:private terminal-operation-statuses
  #{:completed :failed :cancelled :interrupted "completed" "failed" "cancelled" "interrupted"})
(def ^:private successful-operation-statuses #{:completed "completed"})
(def ^:private thinking-levels #{:none :minimal :low :medium :high :xhigh :max})

(def usage
  (str
   "Arrodes - local JVM coding agent and persistent Clojure REPL\n\n"
   "Usage:\n"
   "  clojure -M:run [options] [--] [@files...] [prompt...]\n\n"
   "Modes:\n"
   "  --cli                       Interactive terminal mode (default)\n"
   "  --print, -p                 Run one prompt and print final assistant text\n"
   "  --mode <cli|text|json|rpc>  Select interactive, print, event JSONL, or RPC JSONL\n"
   "  --headless                  Alias for --mode rpc\n\n"
   "Session:\n"
   "  --session <id>              Resume an exact or unambiguous session ID prefix\n"
   "  --continue, -c              Resume the most recent project session; continue if no prompt\n"
   "  --resume, -r                Resume the most recent project session\n"
   "  --new                       Create a new session\n"
   "  --name <name>               Name a created, imported, forked, or resumed session\n"
   "  --fork <id>                 Fork an exact or unambiguous session ID prefix\n"
   "  --import <path>             Import a versioned JSONL or EDN session\n"
   "  --export <path|->           Export the selected session and exit; format follows extension\n"
   "  --no-session                Use an in-memory runtime for this process\n\n"
   "Runtime:\n"
   "  --cwd <directory>           Session/project working directory\n"
   "  --home <directory>          Arrodes home (else ARRODES_HOME or ~/.arrodes)\n"
   "  --data-dir <directory>      Persistent runtime data directory\n"
   "  --isolated                  Require an explicit dedicated --home\n"
   "  --trust | --no-trust        Override project resource trust for this process\n\n"
   "Model and capabilities:\n"
   "  --provider <id>             Provider ID\n"
   "  --model <id|provider/id>    Model ID, optionally qualified by provider\n"
   "  --thinking <level>          none/off, minimal, low, medium, high, xhigh, or max\n"
   "  --tools <a,b,...>           Enable only named tools\n"
   "  --exclude-tools <a,b,...>   Remove named tools from the selected/catalog tools\n"
   "  --no-tools                  Disable all tools\n"
   "  --config <EDN|JSON>         Merge a complete session configuration map\n"
   "  --settings <EDN|JSON>       Runtime settings overrides (including provider profiles)\n\n"
   "Input:\n"
   "  @path                       Attach an image or inline a UTF-8 text file\n"
   "  Piped stdin                 Combined with prompt input in --print or --mode json\n\n"
   "Other:\n"
   "  --help, -h                  Show this help without opening runtime or network resources\n"
   "  --version, -v               Show version without opening runtime or network resources\n"))

(defn- fail-usage! [message data]
  (value/fail! :usage message data))

(defn- option-value [flag inline remaining]
  (if (some? inline)
    [inline remaining]
    (if-let [value (first remaining)]
      (if (or (= value "--") (and (str/starts-with? value "-") (not= value "-")))
        (fail-usage! (str flag " requires a value") {:option flag})
        [value (next remaining)])
      (fail-usage! (str flag " requires a value") {:option flag}))))

(defn- comma-list [value]
  (let [items (->> (str/split value #",") (map str/trim) (remove str/blank?) vec)]
    (when-not (seq items)
      (fail-usage! "Tool lists must contain at least one name" {:value value}))
    items))


(defn- replace-option-token [options flag value]
  (case flag
    "--mode" (assoc options :mode (keyword value))
    "--cwd" (assoc options :cwd value)
    "--home" (assoc options :home value)
    "--data-dir" (assoc options :data-dir value)
    "--session" (assoc options :session value)
    "--name" (assoc options :name value)
    "--fork" (assoc options :fork value)
    "--model" (assoc options :model value)
    "--provider" (assoc options :provider value)
    "--thinking" (assoc options :thinking value)
    "--tools" (assoc options :tools (comma-list value))
    "--exclude-tools" (assoc options :exclude-tools (comma-list value))
    "--settings" (assoc options :settings-source value)
    "--config" (assoc options :config-source value)
    "--export" (assoc options :export value)
    "--import" (assoc options :import value)
    (fail-usage! (str "Unknown option: " flag) {:option flag})))

(defn parse-args
  "Parse CLI arguments without loading a provider, settings file, or runtime namespace."
  [arguments]
  (loop [remaining (seq arguments)
         options {:mode :cli :messages [] :files []}
         positional? false]
    (if-let [raw (first remaining)]
      (let [tail (next remaining)]
        (cond
          positional?
          (recur tail (if (str/starts-with? raw "@")
                        (update options :files conj (subs raw 1))
                        (update options :messages conj raw)) true)

          (= raw "--") (recur tail options true)
          (str/starts-with? raw "@") (recur tail (update options :files conj (subs raw 1)) false)

          (and (str/starts-with? raw "--") (str/includes? raw "="))
          (let [[flag value] (str/split raw #"=" 2)]
            (when (str/blank? value)
              (fail-usage! (str flag " requires a value") {:option flag}))
            (recur tail (replace-option-token options flag value) false))

          :else
          (case raw
            "--help" (recur tail (assoc options :help? true) false)
            "-h" (recur tail (assoc options :help? true) false)
            "--version" (recur tail (assoc options :version? true) false)
            "-v" (recur tail (assoc options :version? true) false)
            "--headless" (recur tail (assoc options :mode :rpc) false)
            "--cli" (recur tail (assoc options :mode :cli) false)
            "--print" (recur tail (assoc options :mode :print) false)
            "-p" (recur tail (assoc options :mode :print) false)
            "--continue" (recur tail (assoc options :continue? true) false)
            "-c" (recur tail (assoc options :continue? true) false)
            "--resume" (recur tail (assoc options :resume? true) false)
            "-r" (recur tail (assoc options :resume? true) false)
            "--new" (recur tail (assoc options :new? true) false)
            "--no-session" (recur tail (assoc options :no-session? true) false)
            "--isolated" (recur tail (assoc options :isolated? true) false)
            "--trust" (recur tail (-> options (assoc :trust true :trust-set? true) (update :trust-flags (fnil conj #{}) :trust)) false)
            "--no-trust" (recur tail (-> options (assoc :trust false :trust-set? true) (update :trust-flags (fnil conj #{}) :no-trust)) false)
            "--no-tools" (recur tail (assoc options :no-tools? true) false)
            (if (contains? #{"--mode" "--cwd" "--home" "--data-dir" "--session" "--name" "--fork"
                             "--model" "--provider" "--thinking" "--tools" "--exclude-tools" "--settings"
                             "--config" "--export" "--import"} raw)
              (let [[value more] (option-value raw nil tail)]
                (recur more (replace-option-token options raw value) false))
              (if (str/starts-with? raw "-")
                (fail-usage! (str "Unknown option: " raw) {:option raw})
                (recur tail (update options :messages conj raw) false))))))
      options)))

(defn- parse-map [label source]
  (when source
    (let [value (try
                  (if (str/starts-with? (str/trim source) "{")
                    (try
                      (json/read-str source :key-fn keyword)
                      (catch Throwable _ (edn/read-string source)))
                    (edn/read-string source))
                  (catch Throwable error
                    (fail-usage! (str label " must be an EDN or JSON object: " (ex-message error))
                                 {:option label})))]
      (when-not (map? value)
        (fail-usage! (str label " must be an object") {:option label}))
      value)))

(defn- validate-options [options]
  (when-not (contains? #{:cli :text :print :json :rpc} (:mode options))
    (fail-usage! "--mode must be cli, text, json, or rpc" {:mode (:mode options)}))
  (when (and (:isolated? options) (str/blank? (:home options)))
    (fail-usage! "--isolated requires an explicit --home" {}))
  (when (> (count (:trust-flags options)) 1)
    (fail-usage! "--trust cannot be combined with --no-trust" {}))
  (when (and (:no-tools? options) (:tools options))
    (fail-usage! "--no-tools cannot be combined with --tools" {}))
  (let [selectors (count (filter identity [(:session options) (:fork options) (:import options)]))]
    (when (> selectors 1)
      (fail-usage! "Use only one of --session, --fork, or --import" {})))
  (when (and (:new? options)
             (some identity [(:session options) (:fork options) (:import options)
                             (:continue? options) (:resume? options)]))
    (fail-usage! "--new cannot be combined with another session selector" {}))
  (when (and (:no-session? options)
             (some identity [(:session options) (:fork options) (:continue? options) (:resume? options)]))
    (fail-usage! "--no-session cannot resume or fork a stored session" {}))
  (when (and (= :rpc (:mode options))
             (some identity [(seq (:messages options)) (seq (:files options)) (:session options)
                             (:fork options) (:import options) (:export options) (:continue? options)
                             (:resume? options) (:new? options) (:name options) (:provider options)
                             (:model options) (:thinking options) (:tools options) (:exclude-tools options)
                             (:no-tools? options) (:settings-source options) (:config-source options)]))
    (fail-usage! "RPC mode accepts runtime paths and trust defaults; sessions and configuration are controlled after initialize" {}))
  (let [level (some-> (:thinking options) keyword)
        normalized (if (= :off level) :none level)]
    (when (and level (not (contains? thinking-levels normalized)))
      (fail-usage! "--thinking must be none/off, minimal, low, medium, high, xhigh, or max"
                   {:thinking (:thinking options)})))
  options)

(defn- resolve-directory [cwd value]
  (when value
    (u/resolve-path cwd value)))

(defn- runtime-options [options]
  (let [launch-cwd (or (not-empty (System/getenv "ARRODES_LAUNCH_CWD"))
                       (System/getProperty "user.dir"))
        cwd (u/resolve-path launch-cwd (or (:cwd options) "."))
        home (or (:home options) (not-empty (System/getenv "ARRODES_HOME")))
        directory (io/file cwd)]
    (value/check! (and (.exists directory) (.isDirectory directory)) :invalid-cwd
              "Working directory does not exist or is not a directory" {:cwd cwd})
    (cond-> {:cwd cwd
             :home (if home (u/resolve-path launch-cwd home) (u/home-dir {}))
             :settings (or (parse-map "--settings" (:settings-source options)) {})
             :memory? (boolean (:no-session? options))}
      (:data-dir options) (assoc :data-dir (resolve-directory cwd (:data-dir options)))
      (:trust-set? options) (assoc :trust (:trust options)))))

(defn- model-config [options]
  (let [base (or (parse-map "--config" (:config-source options)) {})
        [qualified-provider model]
        (when-let [value (:model options)]
          (if (and (str/includes? value "/")
                   (or (nil? (:provider options))
                       (str/starts-with? value (str (:provider options) "/"))))
            (str/split value #"/" 2)
            [nil value]))
        provider (or (:provider options) qualified-provider)
        level (some-> (:thinking options) keyword)
        level (if (= :off level) :none level)]
    (cond-> base
      provider (assoc :provider (keyword provider))
      model (assoc :model model)
      level (assoc :thinking level)
      (:no-tools? options) (assoc :tools [])
      (:tools options) (assoc :tools (vec (remove (set (:exclude-tools options)) (:tools options)))))))

(defn- xml-escape [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"}))

(defn- decode-text [bytes path]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
          text (str (.decode decoder (ByteBuffer/wrap bytes)))]
      (if (str/starts-with? text "\uFEFF") (subs text 1) text))
    (catch Throwable _
      (value/fail! :invalid-input "Attached non-image file is not valid UTF-8" {:path path}))))


(defn- byte-prefix? [bytes offset expected]
  (and (<= (+ offset (count expected)) (alength ^bytes bytes))
       (every? true?
               (map-indexed (fn [index value]
                              (= value (bit-and 0xff (aget ^bytes bytes (+ offset index)))))
                            expected))))

(defn- image-mime-type [bytes]
  (cond
    (byte-prefix? bytes 0 [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a]) "image/png"
    (byte-prefix? bytes 0 [0xff 0xd8 0xff]) "image/jpeg"
    (or (byte-prefix? bytes 0 [0x47 0x49 0x46 0x38 0x37 0x61])
        (byte-prefix? bytes 0 [0x47 0x49 0x46 0x38 0x39 0x61])) "image/gif"
    (and (byte-prefix? bytes 0 [0x52 0x49 0x46 0x46])
         (byte-prefix? bytes 8 [0x57 0x45 0x42 0x50])) "image/webp"
    :else nil))

(defn- attachment [cwd value]
  (let [path (u/path (u/resolve-path cwd value))]
    (value/check! (and (Files/exists path (make-array LinkOption 0))
                   (Files/isRegularFile path (make-array LinkOption 0)))
              :input-not-found "Attached file does not exist or is not a regular file" {:path (str path)})
    (let [size (Files/size path)]
      (value/check! (<= size max-input-bytes) :input-too-large
                "Attached file exceeds the 32 MiB limit" {:path (str path) :bytes size})
      (let [bytes (Files/readAllBytes path)
            mime (image-mime-type bytes)]
        (if mime
          {:bytes size
           :text (str "<file name=\"" (xml-escape value) "\"></file>\n")
           :part {:part/type :image
                  :image/mime-type mime
                  :image/data (.encodeToString (Base64/getEncoder) bytes)}}
          {:bytes size
           :text (str "<file name=\"" (xml-escape value) "\">\n"
                      (decode-text bytes (str path)) "\n</file>\n")})))))

(defn- read-piped-input []
  (when (nil? (System/console))
    (let [reader (io/reader System/in)
          buffer (char-array 8192)
          output (StringBuilder.)]
      (loop [total 0]
        (let [read (.read reader buffer 0 (alength buffer))]
          (if (neg? read)
            (not-empty (str output))
            (let [next-total (+ total read)]
              (value/check! (<= next-total max-input-bytes) :input-too-large
                        "Piped stdin exceeds the 32 MiB limit" {:bytes next-total})
              (.append output buffer 0 read)
              (recur next-total))))))))

(defn- prompt-value [options cwd]
  (let [attachments (mapv #(attachment cwd %) (:files options))
        attachment-bytes (reduce + 0 (map :bytes attachments))
        _ (value/check! (<= attachment-bytes max-input-bytes) :input-too-large
                    "Attached files exceed the combined 32 MiB limit" {:bytes attachment-bytes})
        explicit (not-empty (str/join " " (:messages options)))
        piped (when (contains? #{:print :text :json} (:mode options))
                (read-piped-input))
        text (apply str (concat (when piped [piped])
                                (map :text attachments)
                                (when explicit [explicit])))
        images (keep :part attachments)]
    (cond
      (and (seq images) (seq text)) (into [{:part/type :text :text text}] images)
      (seq images) (vec images)
      (seq text) text
      :else nil)))

(defn- resolve-api []
  {:open! (requiring-resolve 'arrodes.runtime/open!)
   :close! (requiring-resolve 'arrodes.runtime/close!)
   :subscribe! (requiring-resolve 'arrodes.runtime/subscribe!)
   :dispatch! (requiring-resolve 'arrodes.commands/dispatch!)
   :public-value (requiring-resolve 'arrodes.commands/public-value)})

(defn- dispatch! [api runtime method params]
  ((:dispatch! api) runtime method params))

(defn- resolve-session-id [api runtime candidate]
  (let [sessions (:sessions (dispatch! api runtime "session.list" {}))
        exact (some #(when (= candidate (:id %)) %) sessions)
        matches (filterv #(str/starts-with? (:id %) candidate) sessions)]
    (cond
      exact (:id exact)
      (= 1 (count matches)) (:id (first matches))
      (empty? matches) (value/fail! :session-not-found "No session matches the requested ID" {:session-id candidate})
      :else (value/fail! :ambiguous-session "Session ID prefix is ambiguous"
                     {:session-id candidate :matches (mapv :id matches)}))))

(defn- recent-session [api runtime cwd]
  (first (sort-by (juxt :updated-at :created-at) #(compare %2 %1)
                  (:sessions (dispatch! api runtime "session.list" {:cwd cwd})))))

(defn- select-session! [api runtime options cwd]
  (cond
    (:import options)
    (dispatch! api runtime "session.import"
               {:path (u/resolve-path cwd (:import options)) :cwd cwd})

    (:fork options)
    (dispatch! api runtime "session.fork"
               {:session-id (resolve-session-id api runtime (:fork options))
                :cwd cwd :position :at})

    (:session options)
    (let [id (resolve-session-id api runtime (:session options))]
      (:session (dispatch! api runtime "session.inspect" {:session-id id})))

    (or (:continue? options) (:resume? options) (and (:export options) (not (:new? options))))
    (or (recent-session api runtime cwd)
        (value/fail! :session-not-found "No session is available to resume in this project" {:cwd cwd}))

    :else
    (dispatch! api runtime "session.create" {:cwd cwd})))

(defn- apply-session-options! [api runtime snapshot options config]
  (let [id (:id snapshot)
        changes (cond-> {:session-id id}
                  (seq config) (assoc :config config)
                  (some-> (:name options) str/trim not-empty) (assoc :name (str/trim (:name options))))]
    (if (> (count changes) 1)
      (dispatch! api runtime "session.configure" changes)
      snapshot)))

(defn- apply-tool-exclusions! [api runtime session-id options]
  (when (and (seq (:exclude-tools options))
             (not (:no-tools? options))
             (not (:tools options)))
    (let [excluded (set (:exclude-tools options))
          selected (->> (:capabilities (dispatch! api runtime "capability.list" {:session-id session-id}))
                        (map :name)
                        (remove excluded)
                        vec)]
      (dispatch! api runtime "capability.set" {:session-id session-id :tools selected}))))

(defn- export-format [path]
  (let [lower (str/lower-case path)]
    (cond
      (str/ends-with? lower ".html") :html
      (str/ends-with? lower ".edn") :edn
      :else :jsonl)))

(defn- write-output! [^Writer writer text]
  (.write writer (str text))
  (.flush writer))

(defn- write-json! [api ^Writer writer lock value]
  (locking lock
    (.write writer (rpc/json-str (:public-value api) value))
    (.write writer "\n")
    (.flush writer)))

(defn- wait-operation! [api runtime receipt]
  (let [operation-id (:id receipt)]
    (value/check! (and (string? operation-id) (not (str/blank? operation-id))) :invalid-operation
              "Runtime did not return an operation ID" {:receipt receipt})
    (try
      (loop [operation receipt]
        (if (contains? terminal-operation-statuses (:status operation))
          operation
          (recur (dispatch! api runtime "operation.wait"
                            {:operation-id operation-id :timeout-ms 300000}))))
      (catch InterruptedException error
        (dispatch! api runtime "operation.cancel" {:operation-id operation-id})
        (.interrupt (Thread/currentThread))
        (throw error)))))

(defn- ensure-operation-success! [operation]
  (when-not (contains? successful-operation-statuses (:status operation))
    (let [failure (:error operation)]
      (throw (ex-info (or (:message failure) (str "Operation " (or (:status operation) "failed")))
                      (assoc (or (:data failure) {})
                             :error/code (or (:code failure) "operation-failed")
                             :operation-id (:id operation)
                             :status (:status operation))))))
  operation)

(defn- assistant-text [api runtime session-id operation]
  (or (not-empty (value/text-content (or (get-in operation [:result :message/content])
                                     (get-in operation [:result :response/parts])
                                     (get-in operation [:result :content])
                                     (when (string? (:result operation)) (:result operation)))))
      (->> (:entries (dispatch! api runtime "session.entries" {:session-id session-id :branch? true}))
           (filter #(and (= :message (:kind %)) (= :assistant (get-in % [:data :message/role]))))
           last :data :message/content value/text-content not-empty)
      ""))

(defn- run-noninteractive! [api runtime options snapshot prompt writer]
  (let [session-id (:id snapshot)
        lock (Object.)
        json? (= :json (:mode options))
        unsubscribe (when json?
                      ((:subscribe! api) runtime
                       #(write-json! api writer lock {:type "event" :event %})))]
    (try
      (let [receipt (if prompt
                      (dispatch! api runtime "session.run" {:session-id session-id :prompt prompt})
                      (if (:continue? options)
                        (dispatch! api runtime "session.continue" {:session-id session-id})
                        (fail-usage! "Non-interactive mode requires a prompt, piped stdin, @file, or --continue" {})))
            operation (->> receipt (wait-operation! api runtime) ensure-operation-success!)]
        (if json?
          (write-json! api writer lock {:type "result" :operation operation})
          (write-output! writer (str (assistant-text api runtime session-id operation) "\n")))
        0)
      (finally
        (when unsubscribe (unsubscribe))))))

(defn- run-export! [api runtime snapshot destination writer]
  (let [format (export-format destination)
        params (cond-> {:session-id (:id snapshot) :format format}
                 (not= "-" destination) (assoc :path destination))
        result (dispatch! api runtime "session.export" params)]
    (if (= "-" destination)
      (write-output! writer (:content result))
      (write-output! writer (str (:path result) "\n")))
    0))

(defn- run-runtime-mode! [options wire-out]
  (let [api (resolve-api)
        open-options (assoc (runtime-options options) :command! (:dispatch! api))
        runtime ((:open! api) open-options)]
    (try
      (let [cwd (:cwd open-options)
            config (model-config options)
            selected (select-session! api runtime options cwd)
            snapshot (apply-session-options! api runtime selected options config)
            _ (apply-tool-exclusions! api runtime (:id snapshot) options)
            prompt (prompt-value options cwd)]
        (cond
          (:export options) (run-export! api runtime snapshot
                                         (if (= "-" (:export options)) "-" (u/resolve-path cwd (:export options)))
                                         wire-out)
          (contains? #{:print :text :json} (:mode options))
          (run-noninteractive! api runtime options snapshot prompt wire-out)
          :else
          (let [terminal-run! (requiring-resolve 'arrodes.terminal/run!)]
            (terminal-run! runtime (assoc options
                                          :session-id (:id snapshot)
                                          :session snapshot
                                          :config config
                                          :prompt prompt
                                          :continue (and (:continue? options) (nil? prompt))
                                          :resume (:resume? options)))
            0)))
      (finally
        (let [report ((:close! api) runtime)]
          (when-not (= :closed (:status report))
            (value/fail! :shutdown-incomplete "Runtime did not finish shutting down"
                     {:cleanup report})))))))

(defn run!
  "Run parsed command-line arguments and return a process exit code."
  ([arguments] (run! arguments {}))
  ([arguments {:keys [out err] :or {out *out* err *err*}}]
   (let [options (-> arguments parse-args validate-options)
         writer (io/writer out)]
     (cond
       (:help? options) (do (write-output! writer usage) 0)
       (:version? options) (do (write-output! writer (str version "\n")) 0)
       (= :rpc (:mode options))
       (do
         (rpc/serve! (merge (select-keys (runtime-options options)
                                         [:cwd :home :data-dir :memory? :trust])
                            {:in *in* :out out :err err
                             :redirect-system-out? (identical? out *out*)}))
         0)
       :else
       (let [redirect? (contains? #{:print :text :json} (:mode options))
             original-system-out System/out]
         (try
           (when (and redirect? (identical? out *out*))
             (System/setOut System/err))
           (binding [*out* err]
             (run-runtime-mode! options writer))
           (finally
             (when (and redirect? (identical? out *out*))
               (System/setOut original-system-out)))))))))

(defn -main [& arguments]
  (let [json-mode? (or (some #{"--mode=json"} arguments)
                       (some (fn [[left right]] (and (= "--mode" left) (= "json" right)))
                             (partition 2 1 arguments)))]
    (try
      (let [exit-code (run! arguments)]
        (when-not (zero? exit-code) (System/exit exit-code)))
      (catch Throwable error
        (if json-mode?
          (try
            (println (json/write-str {:type "error" :error (value/redact (value/error-map error))}))
            (catch Throwable _ (binding [*out* *err*] (println (ex-message error)))))
          (binding [*out* *err*]
            (println (str "Error: " (or (ex-message error) (str error))))
            (when (= "usage" (:error/code (ex-data error)))
              (println "Use --help for usage."))))
        (System/exit 1))
      (finally (shutdown-agents)))))
