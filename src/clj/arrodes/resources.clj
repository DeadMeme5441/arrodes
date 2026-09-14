(ns arrodes.resources
  "Trusted settings, project context, discoverable resources, session functions, and attributable extensions."
  (:require [arrodes.mcp :as mcp]
            [arrodes.packages :as packages]
            [arrodes.platform :as u]
            [arrodes.value :as value]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io StringReader)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption OpenOption Path StandardOpenOption)))

(declare reload! activate! deactivate! with-settings-lock)

(def ^:private context-names ["AGENTS.override.md" "AGENTS.md" "AGENTS.MD" "CLAUDE.md" "CLAUDE.MD"])
(def ^:private resource-kinds [:extensions :skills :prompts :themes])
(def ^:private provider-leases (atom {}))
(def ^:private settings-locks (atom {}))

(defn- fail! [code message data] (value/fail! code message data))

(defn- file? [path]
  (Files/isRegularFile (u/path path) (make-array LinkOption 0)))

(defn- directory? [path]
  (Files/isDirectory (u/path path) (make-array LinkOption 0)))

(defn- read-text! [path code]
  (try
    (String. (Files/readAllBytes (u/path path)) StandardCharsets/UTF_8)
    (catch Throwable error
      (fail! code "Resource file could not be read" {:path (str path) :cause (ex-message error)}))))

(defn- read-map-file [path default code]
  (if-not (file? path)
    default
    (let [value (try (edn/read-string (read-text! path code))
                     (catch Throwable error
                       (fail! code "EDN resource is malformed"
                              {:path (str path) :cause (ex-message error)})))]
      (when-not (map? value)
        (fail! code "EDN resource must contain a map" {:path (str path)}))
      value)))

(defn- patch-map [base patch]
  (reduce-kv (fn [result key value]
               (cond
                 (nil? value) (dissoc result key)
                 (and (map? (get result key)) (map? value))
                 (assoc result key (patch-map (get result key) value))
                 :else (assoc result key value)))
             (or base {}) (or patch {})))

(def ^:private secret-setting-key
  #"(?i)(?:^|[-_])(api[-_]?key|private[-_]?key|password|secret|token|credentials?|authorization)(?:$|[-_])")
(def ^:private environment-reference-key #"(?i)(?:env|environment|env[-_]?var)$")
(def ^:private environment-reference-value #"\$\{[A-Za-z_][A-Za-z0-9_]*\}")

(defn- assert-no-secrets! [value path]
  (cond
    (map? value)
    (doseq [[key child] value]
      (let [next-path (conj path key)
            key-name (if (or (keyword? key) (symbol? key) (string? key))
                       (name key)
                       (str key))]
        (when (and (re-find secret-setting-key key-name)
                   (not (re-find environment-reference-key key-name))
                   (not (and (string? child)
                             (re-find environment-reference-value child))))
          (fail! :settings/secret-forbidden
                 "Credentials belong in private storage or environment references, not settings"
                 {:path next-path}))
        (assert-no-secrets! child next-path)))

    (or (sequential? value) (set? value))
    (doseq [[index child] (map-indexed vector value)]
      (assert-no-secrets! child (conj path index))))
  value)

(defn- canonical-existing [path]
  (str (.toRealPath (u/path path) (make-array LinkOption 0))))

(defn- normalized-path [path]
  (str (.normalize (.toAbsolutePath (u/path path)))))

(defn- path-under? [root child]
  (let [r (.normalize (.toAbsolutePath (u/path root)))
        c (.normalize (.toAbsolutePath (u/path child)))]
    (or (= r c) (.startsWith c r))))

(defn- contained-path! [root relative]
  (when-not (and (string? relative) (not (str/blank? relative)))
    (fail! :resource/invalid-path "Resource path must be a non-empty string" {:path relative}))
  (let [raw (u/path relative)]
    (when (.isAbsolute raw)
      (fail! :resource/path-escape "Resource child path must be relative" {:path relative}))
    (let [candidate (.normalize (.resolve (u/path root) raw))]
      (when-not (path-under? root candidate)
        (fail! :resource/path-escape "Resource path escapes its root" {:root (str root) :path relative}))
      (when-not (Files/exists candidate (make-array LinkOption 0))
        (fail! :resource/not-found "Resource path does not exist" {:root (str root) :path relative}))
      (let [real (.toRealPath candidate (make-array LinkOption 0))
            real-root (.toRealPath (u/path root) (make-array LinkOption 0))]
        (when-not (path-under? real-root real)
          (fail! :resource/path-escape "Resource symbolic link escapes its root"
                 {:root (str root) :path relative}))
        (str real)))))

(defn- global-config-path [manager name]
  (u/resolve-path (u/resolve-path (:home manager) "config") name))
(defn- global-settings-path [manager] (global-config-path manager "settings.edn"))
(defn- project-settings-path [manager] (u/resolve-path (:project-dir manager) "settings.edn"))
(defn- trust-path [manager] (global-config-path manager "trust.edn"))

(defn- read-trust-store [manager]
  (let [value (read-map-file (trust-path manager) {:version 1 :projects {}} :trust/invalid-store)]
    (when-not (and (= 1 (:version value)) (map? (:projects value)))
      (fail! :trust/invalid-store "Trust store is malformed" {:path (trust-path manager)}))
    value))

(defn- ancestor-paths [root cwd]
  (let [root (u/path root)]
    (loop [path (u/path cwd) result []]
      (cond
        (= path root) (conj result (str path))
        (and (.getParent path) (.startsWith path root))
        (recur (.getParent path) (conj result (str path)))
        :else [(str root)]))))

(defn- persisted-trust [manager]
  (let [root (get-in manager [:project :root])
        projects (:projects (read-trust-store manager))
        entry (get projects root)]
    (when (contains? projects root)
      {:trusted? (boolean (if (map? entry) (:trusted? entry) entry))
       :configured? true
       :root root
       :updated-at (when (map? entry) (:updated-at entry))})))

(defn- trusted-project? [manager]
  (if (some? @(:trust-override manager))
    (boolean @(:trust-override manager))
    (boolean (:trusted? (persisted-trust manager)))))

(defn trust!
  "Persists a trust decision for the next session reload; it never activates project code immediately."
  [manager cwd trusted?]
  (with-settings-lock
    (trust-path manager)
    (fn []
      (let [path (u/project-root cwd)
            store (read-trust-store manager)
            updated (assoc-in store [:projects path]
                              {:trusted? (boolean trusted?) :updated-at (u/now)})]
        (u/write-edn! (trust-path manager) updated)
        {:cwd path
         :root path
         :trusted? (boolean trusted?)
         :configured? true
         :inherited? false
         :application :session-reload}))))

(defn- context-file [dir]
  (some (fn [name]
          (let [path (.resolve (u/path dir) name)]
            (when (file? path)
              {:path (canonical-existing path)
               :content (read-text! path :resource/context-read-failed)})))
        context-names))

(defn- context-present? [dir]
  (some #(file? (.resolve (u/path dir) %)) context-names))

(defn- project-context-files [root cwd]
  (->> (ancestor-paths root cwd)
       reverse
       (keep context-file)
       vec))

(defn- frontmatter [path]
  (let [raw (read-text! path :resource/read-failed)
        normalized (str/replace raw "\r\n" "\n")]
    (if-not (str/starts-with? normalized "---\n")
      {:attributes {} :body normalized :raw normalized}
      (let [matcher (re-matcher #"(?m)^---[ \t]*$" normalized)
            _ (.find matcher)
            closed? (.find matcher)]
        (when-not closed?
          (fail! :resource/invalid-frontmatter "Frontmatter is missing its closing delimiter"
                 {:path (str path)}))
        (let [close-start (.start matcher)
              close-end (.end matcher)
              body-start (if (and (< close-end (count normalized))
                                  (= \newline (.charAt normalized close-end)))
                           (inc close-end)
                           close-end)
              header (subs normalized 4 close-start)
              attributes (try (or (yaml/parse-string header :keywords true) {})
                              (catch Throwable error
                                (fail! :resource/invalid-frontmatter "Frontmatter YAML is malformed"
                                       {:path (str path) :cause (ex-message error)})))]
          (when-not (map? attributes)
            (fail! :resource/invalid-frontmatter "Frontmatter must be a map" {:path (str path)}))
          {:attributes attributes
           :body (subs normalized body-start)
           :raw normalized})))))

(defn- first-description [body]
  (when-let [line (some #(when-not (str/blank? %) (str/trim %)) (str/split-lines body))]
    (if (> (count line) 120) (str (subs line 0 117) "...") line)))

(defn- valid-skill-name! [name path]
  (when-not (and (string? name)
                 (<= 1 (count name) 64)
                 (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" name))
    (fail! :skill/invalid-name
           "Skill name must be 1-64 lowercase letters, digits, and single hyphen separators"
           {:name name :path path})))

(defn- skill-descriptor [path source scope]
  (let [{:keys [attributes body]} (frontmatter path)
        declared? (= "SKILL.md" (str (.getFileName (u/path path))))
        default-name (if declared?
                       (str (.getFileName (.getParent (u/path path))))
                       (str/replace (str (.getFileName (u/path path))) #"(?i)\.md$" ""))
        name (or (:name attributes) default-name)
        description (or (:description attributes) (when-not declared? (first-description body)))]
    (valid-skill-name! name (str path))
    (when-not (and (string? description) (not (str/blank? description)) (<= (count description) 1024))
      (fail! :skill/invalid-description "Skill description is required and may not exceed 1024 characters"
             {:name name :path (str path)}))
    {:name name
     :description description
     :path (canonical-existing path)
     :file-path (canonical-existing path)
     :base-dir (canonical-existing (.getParent (u/path path)))
     :source source
     :scope scope
     :disable-model-invocation (boolean (:disable-model-invocation attributes))
     :content body}))

(defn- sorted-children [dir]
  (with-open [stream (Files/list (u/path dir))]
    (sort-by #(str (.getFileName ^Path %)) (iterator-seq (.iterator stream)))))

(defn- scan-skills
  ([dir source scope] (scan-skills dir source scope true #{}))
  ([dir source scope include-root-files? visited]
   (if-not (directory? dir)
     []
     (let [real (canonical-existing dir)]
       (if (contains? visited real)
         []
         (let [children (sorted-children real)
               declared (some #(when (and (= "SKILL.md" (str (.getFileName ^Path %))) (file? %)) %) children)]
           (if declared
             [(skill-descriptor declared source scope)]
             (vec
              (mapcat (fn [entry]
                        (let [name (str (.getFileName ^Path entry))]
                          (cond
                            (or (str/starts-with? name ".") (= name "node_modules")) []
                            (Files/isSymbolicLink entry) []
                            (directory? entry) (scan-skills entry source scope false (conj visited real))
                            (and include-root-files? (file? entry) (str/ends-with? (str/lower-case name) ".md"))
                            [(skill-descriptor entry source scope)]
                            :else [])))
                      children)))))))))

(defn- prompt-descriptor [path source scope]
  (let [{:keys [attributes body]} (frontmatter path)
        name (or (:name attributes)
                 (str/replace (str (.getFileName (u/path path))) #"(?i)\.md$" ""))]
    (when-not (and (string? name) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" name))
      (fail! :prompt/invalid-name "Prompt name contains invalid characters" {:name name :path (str path)}))
    {:name name
     :description (or (:description attributes) (first-description body) "")
     :argument-hint (:argument-hint attributes)
     :arguments (:arguments attributes)
     :path (canonical-existing path)
     :source source
     :scope scope
     :content body}))

(defn- theme-descriptor [path source scope]
  (let [data (read-map-file path nil :theme/invalid)
        name (or (:name data)
                 (str/replace (str (.getFileName (u/path path))) #"(?i)\.edn$" ""))]
    (when-not (and (string? name) (not (str/blank? name)))
      (fail! :theme/invalid "Theme requires a non-empty :name" {:path (str path)}))
    {:name name :path (canonical-existing path) :source source :scope scope :data data}))

(defn- extension-descriptor [path source scope options]
  (let [name (or (:name options)
                 (str/replace (str (.getFileName (u/path path))) #"(?i)\.clj$" ""))]
    {:name name :path (canonical-existing path) :source source :scope scope
     :enabled? (not= false (:enabled? options))}))

(defn- extension-files [path]
  (cond
    (and (file? path) (str/ends-with? (str/lower-case (str path)) ".clj")) [(u/path path)]
    (directory? path)
    (let [root (u/path path)]
      (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
        (->> (iterator-seq (.iterator stream))
             (filter (fn [entry]
                       (let [relative (.relativize root ^Path entry)]
                         (and (file? entry)
                              (not (Files/isSymbolicLink ^Path entry))
                              (every? #(let [name (str %)]
                                         (and (not (str/starts-with? name "."))
                                              (not= name "node_modules")))
                                      (iterator-seq (.iterator relative)))
                              (str/ends-with? (str/lower-case (str entry)) ".clj")))))
             (sort-by str)
             vec)))
    :else []))

(defn- flat-files [path suffix]
  (cond
    (and (file? path) (str/ends-with? (str/lower-case (str path)) suffix)) [(u/path path)]
    (directory? path) (->> (sorted-children path)
                           (filter #(let [name (str (.getFileName ^Path %))]
                                      (and (not (str/starts-with? name "."))
                                           (file? %)
                                           (str/ends-with? (str/lower-case name) suffix))))
                           vec)
    :else []))

(defn- normalize-path-entry [entry]
  (cond
    (string? entry) {:path entry}
    (and (map? entry) (string? (:path entry))) entry
    :else (fail! :resource/invalid-setting "Resource settings entries require a string path or {:path string}"
                 {:entry entry})))

(defn- configured-path [base entry]
  (let [path (u/path (:path entry))]
    (if (.isAbsolute path)
      (.normalize path)
      (.normalize (.resolve (u/path base) path)))))

(defn- resolve-configured-path [base entry]
  (let [raw (:path entry)
        resolved (configured-path base entry)]
    (when-not (Files/exists resolved (make-array LinkOption 0))
      (fail! :resource/not-found "Configured resource path does not exist" {:path raw :base base}))
    (canonical-existing resolved)))

(defn- discover-entry [kind root entry source scope]
  (let [options (normalize-path-entry entry)
        disabled? (= false (:enabled? options))
        candidate (configured-path root options)]
    (if (and disabled?
             (not (Files/exists candidate (make-array LinkOption 0))))
      []
      (let [path (resolve-configured-path root options)
            resources
            (case kind
              :extensions (mapv #(extension-descriptor % source scope options) (extension-files path))
              :skills (if (and (file? path)
                               (str/ends-with? (str/lower-case path) ".md"))
                        [(skill-descriptor path source scope)]
                        (scan-skills path source scope))
              :prompts (mapv #(prompt-descriptor % source scope) (flat-files path ".md"))
              :themes (mapv #(theme-descriptor % source scope) (flat-files path ".edn")))]
        (when (and (empty? resources) (not disabled?))
          (fail! :resource/no-matches
                 "Configured resource path contains no resources of the requested type"
                 {:kind kind :path (:path options) :source source}))
        (if disabled?
          (mapv #(assoc % :excluded? true) resources)
          resources)))))

(defn- discover-default [kind root source scope]
  (let [path (u/resolve-path root (name kind))]
    (if-not (Files/exists (u/path path) (make-array LinkOption 0))
      []
      (case kind
        :extensions (mapv #(extension-descriptor % source scope {}) (extension-files path))
        :skills (scan-skills path source scope)
        :prompts (mapv #(prompt-descriptor % source scope) (flat-files path ".md"))
        :themes (mapv #(theme-descriptor % source scope) (flat-files path ".edn"))))))

(defn- package-resources [descriptor]
  (let [root (:path descriptor)
        scope (:scope descriptor)
        source (str "package:" (:name descriptor))]
    (reduce (fn [result kind]
              (assoc result kind
                     (mapcat #(discover-entry kind root % source scope)
                             (get-in descriptor [:manifest kind] []))))
            {} resource-kinds)))

(defn- merge-by-name [groups]
  (->> groups
       (reduce (fn [by-name descriptor] (assoc by-name (:name descriptor) descriptor)) {})
       vals
       (remove :excluded?)
       (sort-by :name)
       vec))

(defn- configured-resources [settings root source scope]
  (reduce (fn [result kind]
            (let [entries (or (get settings kind) [])]
              (when-not (sequential? entries)
                (fail! :resource/invalid-setting
                       "Resource settings fields must be vectors"
                       {:field kind :value entries}))
              (assoc result kind
                     (mapcat #(discover-entry kind root % source scope) entries))))
          {} resource-kinds))

(defn- context-setting [value base description]
  (cond
    (nil? value) nil
    (not (string? value)) (fail! :resource/invalid-setting (str description " must be a string") {:value value})
    :else (let [path (u/path value)
                candidate (if (.isAbsolute path) path (.resolve (u/path base) path))]
            (if (file? candidate)
              (read-text! candidate :resource/context-read-failed)
              value))))

(defn- compute-state [manager]
  (let [global (assert-no-secrets!
                (read-map-file (global-settings-path manager) {} :settings/invalid) [:global])
        trusted? (trusted-project? manager)
        project (if trusted?
                  (assert-no-secrets!
                   (read-map-file (project-settings-path manager) {} :settings/invalid) [:project])
                  {})
        initial (assert-no-secrets! (:initial-settings manager) [:explicit])
        global-keybindings (read-map-file (global-config-path manager "keybindings.edn")
                                          {} :keybindings/invalid)
        project-keybindings (if trusted?
                              (read-map-file (u/resolve-path (:project-dir manager) "keybindings.edn")
                                             {} :keybindings/invalid)
                              {})
        effective (-> (patch-map global project)
                      (patch-map initial)
                      (update :keybindings #(patch-map
                                             (patch-map global-keybindings project-keybindings)
                                             %)))
        installed (if trusted?
                    (packages/list-packages (:home manager) (:cwd manager))
                    (packages/list-packages (:home manager) (:cwd manager) {:scope :global}))
        package-groups (map (fn [descriptor]
                              {:scope (:scope descriptor)
                               :resources (package-resources descriptor)})
                            installed)
        global-configured (configured-resources global (:home manager) :settings :global)
        initial-configured (configured-resources initial (:cwd manager) :explicit :global)
        project-configured (if trusted?
                             (configured-resources project (:project-dir manager) :settings :project)
                             (zipmap resource-kinds (repeat [])))
        catalog (reduce (fn [result kind]
                          (let [package-resources-by-scope
                                (fn [scope]
                                  (mapcat #(get-in % [:resources kind] [])
                                          (filter #(= scope (:scope %)) package-groups)))]
                            (assoc result kind
                                   (merge-by-name
                                    (concat
                                     (package-resources-by-scope :global)
                                     (discover-default kind (:home manager) :local :global)
                                     (get global-configured kind)
                                     (when trusted?
                                       (package-resources-by-scope :project))
                                     (when trusted?
                                       (discover-default kind (:project-dir manager)
                                                         :local :project))
                                     (get project-configured kind)
                                     (get initial-configured kind))))))
                        {} resource-kinds)
        global-context (context-file (:home manager))
        project-context (if trusted?
                          (project-context-files (get-in manager [:project :root])
                                                 (:cwd manager))
                          [])
        system (context-setting (:system-prompt effective) (:cwd manager) "System prompt")
        instructions (context-setting (:instructions effective) (:cwd manager) "Instructions")
        append-value (:append-system-prompt effective)
        append-values (cond (nil? append-value) []
                            (string? append-value) [append-value]
                            (sequential? append-value) append-value
                            :else (fail! :resource/invalid-setting
                                         ":append-system-prompt must be a string or vector" {:value append-value}))
        context-parts (concat (keep identity [system instructions (:content global-context)])
                              (map :content project-context)
                              (map #(context-setting % (:cwd manager) "Appended system prompt") append-values))]
    {:settings effective
     :trusted? trusted?
     :context-files (vec (concat (keep identity [global-context]) project-context))
     :context (str/join "\n\n" (remove str/blank? context-parts))
     :catalog catalog
     :closed? false}))

(defn create!
  "Creates a resource manager for cwd using global and HOME-backed project state."
  [{:keys [cwd home settings trust] :or {settings {}}}]
  (when-not (and cwd home)
    (fail! :resource/invalid-options "Resource manager requires :cwd and :home" {:cwd cwd :home home}))
  (let [cwd (if (directory? cwd) (canonical-existing cwd) (normalized-path cwd))
        home (normalized-path home)
        project (:metadata (u/open-project! home cwd))
        manager {:cwd cwd
                 :home home
                 :project project
                 :project-dir (:directory project)
                 :initial-settings (or settings {})
                 :trust-override (atom trust)
                 :state (atom nil)
                 :activations (atom {})
                 :commands (atom {})
                 :renderers (atom {})
                 :ui-entries (atom {})
                 :loaded-code (atom {})
                 :lock (Object.)}]
    (reset! (:state manager) (compute-state manager))
    manager))

(defn settings [manager]
  (:settings @(:state manager)))

(defn keybindings [manager]
  (get-in @(:state manager) [:settings :keybindings] {}))

(defn context [manager]
  (:context @(:state manager)))

(defn- nonempty-directory? [directory]
  (when (directory? directory)
    (with-open [stream (Files/list (u/path directory))]
      (.isPresent (.findAny stream)))))

(defn project-trust
  "Returns exact-root trust and whether project state or instructions require a decision."
  [manager]
  (let [persisted (persisted-trust manager)
        root (get-in manager [:project :root])
        project-dir (:project-dir manager)
        context? (some context-present? (ancestor-paths root (:cwd manager)))
        state? (or (some #(file? (u/resolve-path project-dir %))
                         ["settings.edn" "keybindings.edn"])
                   (some #(nonempty-directory? (u/resolve-path project-dir %))
                         ["packages" "extensions" "skills" "prompts" "themes"]))]
    {:root root
     :directory project-dir
     :trusted? (trusted-project? manager)
     :configured? (boolean persisted)
     :required? (boolean (or context? state?))
     :updated-at (:updated-at persisted)}))

(defn- public-resource [descriptor]
  (dissoc descriptor :content :data :enabled?))

(defn catalog [manager]
  (let [state @(:state manager)
        active-commands (->> @(:commands manager)
                             vals
                             (mapcat vals)
                             (reduce (fn [m descriptor] (assoc m (:name descriptor) descriptor)) {})
                             vals
                             (map #(dissoc % :fn :owner :activation-id))
                             (sort-by :name)
                             vec)]
    {:skills (mapv public-resource (get-in state [:catalog :skills]))
     :prompts (mapv public-resource (get-in state [:catalog :prompts]))
     :extensions (mapv public-resource (get-in state [:catalog :extensions]))
     :themes (mapv #(assoc (public-resource %) :data (:data %) :theme (:data %)) (get-in state [:catalog :themes]))
     :commands active-commands}))

(defn- canonical-shared-path [path]
  (loop [candidate (.normalize (.toAbsolutePath (u/path path)))
         suffix ()]
    (if (Files/exists candidate (make-array LinkOption 0))
      (str (reduce (fn [^Path root ^Path child] (.resolve root child))
                   (.toRealPath candidate (make-array LinkOption 0))
                   suffix))
      (recur (.getParent candidate) (conj suffix (.getFileName candidate))))))

(defn- settings-lock [path]
  (let [key (canonical-shared-path path)]
    (get (swap! settings-locks
                #(if (contains? % key) % (assoc % key (Object.))))
         key)))

(defn- with-settings-lock [path f]
  (let [lock (settings-lock path)]
    (if (Thread/holdsLock lock)
      (f)
      (locking lock
        (let [owner (u/path (str path ".lock"))]
          (u/ensure-dir! (str (.getParent owner)))
          (when (Files/isSymbolicLink owner)
            (fail! :settings/insecure-lock "Settings lock cannot be a symbolic link" {:path (str owner)}))
          (with-open [channel (FileChannel/open
                               owner (into-array OpenOption [StandardOpenOption/CREATE
                                                             StandardOpenOption/WRITE
                                                             LinkOption/NOFOLLOW_LINKS]))
                      file-lock (.lock channel)]
            (f)))))))

(defn update-settings!
  "Atomically patches global or trusted project settings and reloads effective resources. nil removes a key."
  [manager changes {:keys [scope]}]
  (when-not (map? changes)
    (fail! :settings/invalid-changes "Settings changes must be a map" {:changes changes}))
  (let [scope (case scope (:global "global") :global (:project "project") :project
                    (fail! :settings/invalid-scope "Settings scope must be :global or :project" {:scope scope}))]
    (when (and (= scope :project) (not (trusted-project? manager)))
      (fail! :trust/required "Project must be trusted before project settings can be changed"
             {:cwd (:cwd manager)}))
    (let [path (if (= scope :global) (global-settings-path manager) (project-settings-path manager))]
      (with-settings-lock path
        (fn []
          (let [existed? (file? path)
                existing (read-map-file path {} :settings/invalid)
                updated (assert-no-secrets! (patch-map existing changes) [scope])]
            (u/write-edn! path updated)
            (try
              (reload! manager)
              {:settings (settings manager)
               :application {:scope scope :changed (vec (sort (keys changes))) :reloaded? true}}
              (catch Throwable error
                (try
                  (when (= updated (read-map-file path {} :settings/invalid))
                    (if existed?
                      (u/write-edn! path existing)
                      (Files/deleteIfExists (u/path path))))
                  (catch Throwable restore-error
                    (throw (ex-info (ex-message error)
                                    (assoc (ex-data error)
                                           :settings-restore-error (ex-message restore-error))
                                    error))))
                (throw error)))))))))

(defn- find-named! [manager kind name code]
  (or (some #(when (= name (:name %)) %) (get-in @(:state manager) [:catalog kind]))
      (fail! code "Named resource was not found" {:name name :kind kind})))

(defn read-skill
  "Reads a skill body or a contained support file. Symlink and .. escapes are rejected."
  ([manager name] (read-skill manager name nil))
  ([manager name path]
   (let [skill (find-named! manager :skills name :skill/not-found)]
     (if path
       (read-text! (contained-path! (:base-dir skill) path) :skill/read-failed)
       (:content skill)))))

(defn- parse-command-args [text]
  (loop [chars (seq (or text "")) quote nil escaped? false current (StringBuilder.) result []]
    (if-let [ch (first chars)]
      (cond
        escaped? (do (.append current ^char ch)
                     (recur (next chars) quote false current result))
        (= ch \\) (recur (next chars) quote true current result)
        quote (if (= ch quote)
                (recur (next chars) nil false current result)
                (do (.append current ^char ch)
                    (recur (next chars) quote false current result)))
        (or (= ch \') (= ch \")) (recur (next chars) ch false current result)
        (Character/isWhitespace ^char ch)
        (if (pos? (.length current))
          (recur (next chars) nil false (StringBuilder.) (conj result (str current)))
          (recur (next chars) nil false current result))
        :else (do (.append current ^char ch)
                  (recur (next chars) nil false current result)))
      (do
        (when quote (fail! :prompt/invalid-arguments "Prompt arguments contain an unterminated quote" {:arguments text}))
        (when escaped? (.append current \\))
        (cond-> result (pos? (.length current)) (conj (str current)))))))

(defn- positional-values [arguments]
  (cond
    (nil? arguments) []
    (string? arguments) (parse-command-args arguments)
    (sequential? arguments) (mapv str arguments)
    (map? arguments) (let [value (or (get arguments "@") (:arguments arguments) (get arguments "arguments"))]
                       (cond (nil? value) []
                             (string? value) (parse-command-args value)
                             (sequential? value) (mapv str value)
                             :else (fail! :prompt/invalid-arguments
                                          "Prompt positional arguments must be a string or vector"
                                          {:arguments value})))
    :else (fail! :prompt/invalid-arguments "Prompt arguments must be a string, vector, or map"
                 {:arguments arguments})))

(defn- named-values [arguments]
  (if (map? arguments)
    (into {} (map (fn [[key value]] [(name key) (str value)]))
          (dissoc arguments "@" :arguments "arguments"))
    {}))

(def ^:private substitution-pattern
  #"\$\{(\d+|ARGUMENTS|@):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)|\{\{([A-Za-z][A-Za-z0-9_-]*)\}\}|\$\{([A-Za-z][A-Za-z0-9_-]*)\}")

(defn- substitute [content arguments]
  (let [positionals (positional-values arguments)
        named (named-values arguments)
        all (str/join " " positionals)
        matcher (re-matcher substitution-pattern content)
        output (StringBuffer.)]
    (while (.find matcher)
      (let [default-target (.group matcher 1)
            default-value (.group matcher 2)
            slice-start (.group matcher 3)
            slice-length (.group matcher 4)
            simple (.group matcher 5)
            curly-name (.group matcher 6)
            dollar-name (.group matcher 7)
            replacement
            (cond
              default-target (let [value (if (#{"@" "ARGUMENTS"} default-target)
                                           all
                                           (get positionals (dec (Long/parseLong default-target)) ""))]
                               (if (str/blank? value) default-value value))
              slice-start (let [start (max 0 (dec (Long/parseLong slice-start)))
                                values (if slice-length
                                         (take (Long/parseLong slice-length) (drop start positionals))
                                         (drop start positionals))]
                            (str/join " " values))
              simple (if (#{"@" "ARGUMENTS"} simple)
                       all
                       (get positionals (dec (Long/parseLong simple)) ""))
              :else (let [key (or curly-name dollar-name)]
                      (if (contains? named key)
                        (get named key)
                        (fail! :prompt/missing-argument "Required named prompt argument is missing"
                               {:argument key}))))]
        (.appendReplacement matcher output (java.util.regex.Matcher/quoteReplacement replacement))))
    (.appendTail matcher output)
    (str output)))

(defn- validate-prompt-arguments! [prompt arguments]
  (let [schema (:arguments prompt)
        named (named-values arguments)]
    (cond
      (nil? schema) nil
      (sequential? schema)
      (doseq [key schema]
        (when-not (contains? named (name key))
          (fail! :prompt/missing-argument "Required prompt argument is missing"
                 {:prompt (:name prompt) :argument (name key)})))
      (map? schema)
      (doseq [key (or (:required schema) [])]
        (when-not (contains? named (name key))
          (fail! :prompt/missing-argument "Required prompt argument is missing"
                 {:prompt (:name prompt) :argument (name key)})))
      :else (fail! :prompt/invalid-schema "Prompt frontmatter arguments must be a vector or map"
                   {:prompt (:name prompt)}))))

(defn render-prompt [manager name arguments]
  (let [prompt (find-named! manager :prompts name :prompt/not-found)]
    (validate-prompt-arguments! prompt arguments)
    (substitute (:content prompt) arguments)))

(defn expand-input
  "Expands a leading /prompt or /skill:name invocation. Other input is returned unchanged."
  [manager text]
  (if-let [[_ command argument-text] (and (string? text)
                                          (re-matches #"(?s)^/([^\s]+)(?:\s+(.*))?$" text))]
    (if (str/starts-with? command "skill:")
      (let [name (subs command 6)
            skill (read-skill manager name)]
        (if (str/blank? argument-text) skill (str skill "\n\n" argument-text)))
      (if (some #(= command (:name %)) (get-in @(:state manager) [:catalog :prompts]))
        (render-prompt manager command (or argument-text ""))
        text))
    text))

(defn- resolve-api [symbol]
  (or (requiring-resolve symbol)
      (fail! :extension/api-unavailable "Required extension API is unavailable" {:symbol (str symbol)})))

(defn- acquire-provider! [provider id descriptor source]
  (let [key [provider id]]
    (locking provider-leases
      (if-let [lease (get @provider-leases key)]
        (do
          (when-not (= source (:source lease))
            (fail! :extension/duplicate-provider
                   "Another extension already owns this provider id"
                   {:id id :owner (:source lease) :extension source}))
          (swap! provider-leases update-in [key :count] inc)
          (:value lease))
        (let [lease-owner (str "extension-provider:" (u/id))
              value ((resolve-api 'arrodes.provider/register!)
                     provider id (assoc descriptor :owner lease-owner :replace? false))]
          (swap! provider-leases assoc key
                 {:source source :count 1 :owner lease-owner :value value})
          value)))))

(defn- release-provider! [provider id source]
  (let [key [provider id]]
    (locking provider-leases
      (when-let [lease (get @provider-leases key)]
        (when-not (= source (:source lease))
          (fail! :extension/provider-not-owned
                 "Extension cannot remove another extension's provider"
                 {:id id :owner (:source lease) :extension source}))
        (if (> (:count lease) 1)
          (swap! provider-leases update-in [key :count] dec)
          (do
            ((resolve-api 'arrodes.provider/unregister!) provider id)
            (swap! provider-leases dissoc key))))
      true)))

(defn- rollback! [effects]
  (let [pending (volatile! [])
        errors (reduce (fn [errors cleanup]
                         (try
                           (cleanup)
                           errors
                           (catch Throwable error
                             (vswap! pending conj cleanup)
                             (conj errors {:message (ex-message error) :data (ex-data error)}))))
                       [] (reverse @effects))]
    (reset! effects (vec (rseq @pending)))
    errors))

(defn- call-context! [context key args]
  (if-let [callback (get context key)]
    (apply callback args)
    (fail! :extension/callback-unavailable "Runtime did not provide an extension callback"
           {:callback key :session-id (:session-id context)})))

(defn- extension-api [manager registry context activation-id effects extension-source]
  (let [commands (:commands manager)
        renderers (:renderers manager)
        ui-entries (:ui-entries manager)
        owner (str "extension:" activation-id ":" (subs (u/sha256 extension-source) 0 16))
        push! #(swap! effects conj %)]
    (push! #((resolve-api 'arrodes.capabilities/withdraw!) registry owner))
    {:session-id (:session-id context)
     :cwd (:cwd manager)
     :home (:home manager)
     :settings #(settings manager)
     :session #(call-context! context :get-session [(:session-id context)])
     :get-session (:get-session context)
     :register-tool!
     (fn [descriptor]
       (when-not (map? descriptor)
         (fail! :extension/invalid-tool "Tool descriptor must be a map" {:descriptor descriptor}))
       (when (and (contains? descriptor :replace?) (not (boolean? (:replace? descriptor))))
         (fail! :extension/invalid-tool "Tool descriptor :replace? must be boolean"
                {:name (:name descriptor)}))
       (let [replace? (true? (:replace? descriptor))
             owned (cond-> (assoc descriptor :owner owner :replace? replace?)
                     replace? (assoc :replace-owner? true))
             receipt ((resolve-api 'arrodes.capabilities/register-restorable!) registry owned)]
         (push! #((resolve-api 'arrodes.capabilities/restore!) registry receipt))
         (select-keys receipt [:name :owner :replaced?])))
     :register-hook!
     (fn [point descriptor]
       (let [id (or (:id descriptor) (u/id))
             hook (assoc descriptor :id id :owner owner)
             value ((resolve-api 'arrodes.capabilities/add-hook!) registry point hook)]
         value))
     :register-command!
     (fn [descriptor]
       (when-not (and (map? descriptor) (string? (:name descriptor)) (fn? (:fn descriptor)))
         (fail! :extension/invalid-command "Command descriptor requires string :name and function :fn"
                {:descriptor (dissoc descriptor :fn)}))
       (when (contains? (get @commands activation-id {}) (:name descriptor))
         (fail! :extension/duplicate-command "Extension registered the same command twice"
                {:name (:name descriptor)}))
       (let [value (assoc descriptor :owner owner :activation-id activation-id)]
         (swap! commands assoc-in [activation-id (:name descriptor)] value)
         (push! #(swap! commands update activation-id dissoc (:name descriptor)))
         (dissoc value :fn)))
     :register-provider!
     (fn [id descriptor]
       (let [provider (:provider context)]
         (when-not provider
           (fail! :extension/provider-unavailable "No provider manager is available" {:id id}))
         (let [value (acquire-provider! provider id descriptor extension-source)]
           (push! #(release-provider! provider id extension-source))
           value)))
     :register-renderer!
     (fn [descriptor]
       (when-not (and (map? descriptor) (string? (:name descriptor)) (fn? (:fn descriptor)))
         (fail! :extension/invalid-renderer "Renderer descriptor requires string :name and function :fn"
                {:descriptor (dissoc descriptor :fn)}))
       (when (contains? (get @renderers activation-id {}) (:name descriptor))
         (fail! :extension/duplicate-renderer "Extension renderer name is already registered"
                {:name (:name descriptor)}))
       (let [name (:name descriptor)]
         (call-context! context :ui! [{:kind :renderer :id name :render (:fn descriptor)}])
         (swap! renderers assoc-in [activation-id name] (assoc descriptor :owner owner))
         (push! #(try
                   (call-context! context :ui! [{:kind :renderer :id name :remove? true}])
                   (finally (swap! renderers update activation-id dissoc name))))
         (dissoc descriptor :fn)))
     :register-ui!
     (fn [descriptor]
       (when-not (and (map? descriptor) (string? (:name descriptor))
                      (contains? #{:widget :set-widget} (:kind descriptor)))
         (fail! :extension/invalid-ui-entry
                "Persistent UI descriptors require string :name and :kind :widget or :set-widget"
                {:descriptor descriptor}))
       (when (contains? (get @ui-entries activation-id {}) (:name descriptor))
         (fail! :extension/duplicate-ui-entry "Extension UI entry name is already registered"
                {:name (:name descriptor)}))
       (let [name (:name descriptor)
             request (-> descriptor (dissoc :name) (assoc :id name))]
         (call-context! context :ui! [request])
         (swap! ui-entries assoc-in [activation-id name] (assoc descriptor :owner owner))
         (push! #(try
                   (call-context! context :ui! [(assoc request :remove? true)])
                   (finally (swap! ui-entries update activation-id dissoc name))))
         descriptor))
     :append-entry!
     (fn [entry]
       (call-context! context :append-entry! [entry]))
     :send-message!
     (fn
       ([content] (call-context! context :command!
                                 ["session.run" {:session-id (:session-id context) :prompt content}]))
       ([content {:keys [mode] :or {mode :run}}]
        (let [method (case mode
                       :run "session.run"
                       :steer "session.steer"
                       :follow-up "session.follow-up"
                       (fail! :extension/invalid-message-mode "Message mode must be :run, :steer, or :follow-up"
                              {:mode mode}))]
          (call-context! context :command!
                         [method {:session-id (:session-id context) :prompt content}]))))
     :emit! (fn [event]
              (when-not (map? event)
                (fail! :extension/invalid-event "Extension events must be maps" {:event event}))
              (call-context! context :emit!
                             [(assoc event :id nil :session-id (:session-id context) :durable? false)]))
     :ui! (fn [request] (call-context! context :ui! [request]))
     :on-close! (fn [cleanup]
                  (when-not (fn? cleanup)
                    (fail! :extension/invalid-cleanup "Extension cleanup must be a function" {}))
                  (push! cleanup)
                  nil)}))

(defn- read-extension-init [path]
  (let [source (read-text! path :extension/read-failed)
        generated (symbol (str "arrodes.extension.loaded_" (str/replace (u/id) "-" "_")))
        namespace (create-ns generated)
        eof (Object.)]
    (try
      (binding [*ns* namespace *file* path]
        (clojure.core/refer 'clojure.core)
        (with-open [reader (LineNumberingPushbackReader. (StringReader. source))]
          (loop [last-value nil]
            (let [raw-form (try (read {:eof eof} reader)
                                (catch Throwable error
                                  (fail! :extension/read-failed "Clojure extension could not be read"
                                         {:path path :cause (ex-message error)})))]
              (if (identical? raw-form eof)
                (let [entry (or (when (fn? last-value) last-value)
                                (when (map? last-value) (:init last-value))
                                (some-> (ns-resolve *ns* 'activate) deref)
                                (some-> (ns-resolve *ns* 'init) deref))]
                  (when-not (= generated (ns-name *ns*))
                    (fail! :extension/namespace-escape
                           "Clojure extension changed to an unowned namespace"
                           {:path path :namespace (str (ns-name *ns*))}))
                  (when-not (fn? entry)
                    (fail! :extension/missing-entrypoint
                           "Clojure extension must evaluate to a function/map :init or define activate/init"
                           {:path path :namespace (str (ns-name *ns*))}))
                  {:init entry :namespace (ns-name *ns*) :generated generated})
                (let [head (when (seq? raw-form) (first raw-form))
                      form (cond
                             (contains? #{'ns 'clojure.core/ns} head)
                             (let [declared (second raw-form)]
                               (when-not (symbol? declared)
                                 (fail! :extension/invalid-namespace
                                        "Clojure extension ns form requires a namespace symbol"
                                        {:path path}))
                               (with-meta
                                 (list* 'ns (with-meta generated (meta declared)) (nnext raw-form))
                                 (meta raw-form)))

                             (contains? #{'in-ns 'clojure.core/in-ns} head)
                             (fail! :extension/namespace-escape
                                    "Clojure extensions cannot switch namespaces with in-ns"
                                    {:path path})

                             :else raw-form)
                      value (try (eval form)
                                 (catch Throwable error
                                   (fail! :extension/load-failed "Clojure extension evaluation failed"
                                          {:path path :cause (ex-message error)})))]
                  (recur value)))))))
      (catch Throwable error
        (try (remove-ns generated) (catch Throwable _ nil))
        (throw error)))))
(defn- loaded-extension [manager descriptor]
  (or (get @(:loaded-code manager) (:path descriptor))
      (locking (:lock manager)
        (or (get @(:loaded-code manager) (:path descriptor))
            (let [loaded (read-extension-init (:path descriptor))]
              (swap! (:loaded-code manager) assoc (:path descriptor) loaded)
              loaded)))))

(defn- activation-key [registry context]
  [(:session-id context) registry])

(defn- validate-activation-context! [manager context]
  (when-not (and (string? (:session-id context))
                 (fn? (:get-session context))
                 (fn? (:command! context))
                 (fn? (:append-entry! context))
                 (fn? (:emit! context))
                 (fn? (:ui! context))
                 (some? (:provider context)))
    (fail! :extension/invalid-context
           "Activation context requires :session-id, :get-session, :command!, :append-entry!, :emit!, :ui!, and :provider"
           {:session-id (:session-id context)}))
  (let [session ((:get-session context) (:session-id context))
        session-cwd (:cwd session)]
    (when-not (= (:session-id context) (:id session))
      (fail! :extension/session-mismatch
             "Activation context returned a different session"
             {:expected (:session-id context) :actual (:id session)}))
    (when-not (and session-cwd
                   (= (:cwd manager)
                      (if (directory? session-cwd)
                        (canonical-existing session-cwd)
                        (normalized-path session-cwd))))
      (fail! :extension/cwd-mismatch
             "Activation context cwd does not match its resource manager"
             {:session-id (:session-id context) :expected (:cwd manager) :actual session-cwd})))
  context)

(defn- skill-capability [manager owner]
  {:name "skill"
   :owner owner
   :replace? true
   :replace-owner? true
   :description
   (str "Discover and read installed skills from the persistent Clojure REPL. "
        "Use {:action \"catalog\"} to inspect skills and "
        "{:action \"read\" :name \"...\" :path \"optional/relative/file\"} "
        "to read instructions or a contained support file.")
   :parameters
   {:type "object"
    :properties {:action {:type "string" :enum ["catalog" "read"]}
                 :name {:type "string"}
                 :path {:type "string"
                        :description "Optional path relative to the selected skill directory."}}
    :required ["action"]
    :additionalProperties false}
   :execution :parallel
   :permission :read
   :validate
   (fn [{:keys [action name]}]
     (if (and (= "read" action)
              (not (and (string? name) (not (str/blank? name)))))
       "Skill read requires a non-blank name"
       true))
   :fn
   (fn [{:keys [action name path]}]
     (case action
       "catalog"
       (let [value (mapv public-resource
                         (get-in @(:state manager) [:catalog :skills]))]
         {:value value :content (pr-str value)
          :details {:resource :skills :action :catalog}})

       "read"
       (let [descriptor (find-named! manager :skills name :skill/not-found)
             value (assoc (public-resource descriptor)
                          :content (read-skill manager name path)
                          :resource-path path)]
         {:value value :content (:content value)
          :details (dissoc value :content)})

       (fail! :skill/unsupported-action "Unsupported skill action"
              {:action action :available ["catalog" "read"]})))})

(defn- prompt-capability [manager owner]
  {:name "prompt"
   :owner owner
   :replace? true
   :replace-owner? true
   :description
   (str "Discover and render installed prompt material from the persistent Clojure REPL. "
        "Use {:action \"catalog\"} or "
        "{:action \"render\" :name \"...\" :arguments {...}}.")
   :parameters
   {:type "object"
    :properties
    {:action {:type "string" :enum ["catalog" "render"]}
     :name {:type "string"}
     :arguments
     {:oneOf [{:type "string"}
              {:type "array" :items {}}
              {:type "object" :additionalProperties true}]
      :description "Named arguments, positional values, or a command-style argument string."}}
    :required ["action"]
    :additionalProperties false}
   :execution :parallel
   :permission :read
   :validate
   (fn [{:keys [action name]}]
     (if (and (= "render" action)
              (not (and (string? name) (not (str/blank? name)))))
       "Prompt render requires a non-blank name"
       true))
   :fn
   (fn [{:keys [action name arguments]}]
     (case action
       "catalog"
       (let [value (mapv public-resource
                         (get-in @(:state manager) [:catalog :prompts]))]
         {:value value :content (pr-str value)
          :details {:resource :prompts :action :catalog}})

       "render"
       (let [descriptor (find-named! manager :prompts name :prompt/not-found)
             value (assoc (public-resource descriptor)
                          :content (render-prompt manager name arguments))]
         {:value value :content (:content value)
          :details (dissoc value :content)})

       (fail! :prompt/unsupported-action "Unsupported prompt action"
              {:action action :available ["catalog" "render"]})))})

(defn- register-session-functions!
  [manager registry activation-id effects]
  (let [owner (str "resources:" activation-id)
        pool (mcp/create! (:cwd manager) (settings manager))
        register! (resolve-api 'arrodes.capabilities/register-restorable!)
        restore! (resolve-api 'arrodes.capabilities/restore!)]
    ;; The pool exists before any receipt, so every partial activation can close it.
    (swap! effects conj
           #(let [report (mcp/close! pool)]
              (value/check! (:cleanup-complete? report) :mcp/cleanup-incomplete
                            "MCP clients did not finish shutting down" {:cleanup report})))
    (doseq [descriptor [(skill-capability manager owner)
                        (prompt-capability manager owner)
                        (mcp/gateway-descriptor pool owner)]]
      (let [receipt (register! registry
                               (assoc descriptor :replace? true
                                      :replace-owner? true))]
        (swap! effects conj #(restore! registry receipt))
        (when-let [wrapper (ns-resolve (the-ns (:namespace registry))
                                       (symbol (:name descriptor)))]
          (alter-meta! wrapper assoc :doc (:description descriptor)))))
    pool))


(defn activate!
  "Activates core resource functions and discovered extensions with complete receipt rollback."
  [manager registry context]
  (when (:closed? @(:state manager))
    (fail! :resource/closed "Resource manager is closed" {}))
  (validate-activation-context! manager context)
  (deactivate! manager (activation-key registry context))
  (let [activation-id (u/id)
        effects (atom [])
        extensions (filter :enabled? (get-in @(:state manager) [:catalog :extensions]))
        activated (atom [])]
    (try
      ;; Core resource functions precede extensions so extensions can explicitly
      ;; replace them and the existing receipt rollback restores every layer.
      (register-session-functions! manager registry activation-id effects)
      (doseq [descriptor extensions]
        (let [{:keys [init]} (loaded-extension manager descriptor)
              api (extension-api manager registry context activation-id effects (:path descriptor))
              result (try (init api)
                          (catch Throwable error
                            (fail! :extension/activation-failed "Extension activation failed"
                                   {:extension (:name descriptor) :path (:path descriptor)
                                    :cause (ex-message error)})))]
          (cond
            (fn? result) (swap! effects conj result)
            (and (map? result) (fn? (:close result))) (swap! effects conj (:close result))
            (or (nil? result) (map? result)) nil
            :else (fail! :extension/invalid-result
                         "Extension activation must return nil, a cleanup function, or {:close fn}"
                         {:extension (:name descriptor) :result-type (str (type result))}))
          (swap! activated conj (:name descriptor))))
      (let [key (activation-key registry context)
            record {:id activation-id :key key :registry registry :context context
                    :effects effects :extensions @activated :activated-at (u/now)}]
        (swap! (:activations manager) assoc key record)
        {:id activation-id :session-id (:session-id context)
         :extensions @activated :status :active})
      (catch Throwable error
        (let [rollback-errors (rollback! effects)]
          (swap! (:commands manager) dissoc activation-id)
          (swap! (:renderers manager) dissoc activation-id)
          (swap! (:ui-entries manager) dissoc activation-id)
          (if (seq rollback-errors)
            (throw (ex-info (ex-message error)
                            (assoc (ex-data error) :rollback-errors rollback-errors)
                            error))
            (throw error)))))))

(defn deactivate!
  "Tears down one activation by its internal key or descriptor id."
  [manager key-or-id]
  (let [activations @(:activations manager)
        [key record] (or (when-let [record (get activations key-or-id)]
                           [key-or-id record])
                         (some (fn [[key value]] (when (= key-or-id (:id value)) [key value]))
                               activations))]
    (if-not record
      {:status :not-active}
      (let [errors (rollback! (:effects record))
            id (:id record)]
        (when (seq errors)
          (fail! :extension/teardown-failed "One or more extension teardown callbacks failed"
                 {:activation-id id :errors errors}))
        (swap! (:activations manager) dissoc key)
        (swap! (:commands manager) dissoc id)
        (swap! (:renderers manager) dissoc id)
        (swap! (:ui-entries manager) dissoc id)
        {:id id :status :inactive :extensions (:extensions record)}))))

(defn- active-command [manager registry context name]
  (let [activation (get @(:activations manager) (activation-key registry context))]
    (get-in @(:commands manager) [(:id activation) name])))

(defn command!
  "Invokes an extension command registered for the current session activation."
  [manager registry context name args]
  (if-let [descriptor (active-command manager registry context name)]
    (try
      {:handled? true :value ((:fn descriptor) args)}
      (catch Throwable error
        (throw (ex-info "Extension command failed"
                        {:error/code "extension/command-failed" :name name
                         :owner (:owner descriptor) :cause (ex-message error)}
                        error))))
    {:handled? false}))

(defn renderers
  "Returns public renderer descriptors for an active session; :fn remains local and callable by UI hosts."
  [manager registry context]
  (let [activation (get @(:activations manager) (activation-key registry context))]
    (vec (vals (get @(:renderers manager) (:id activation) {})))))

(defn ui-entries
  "Returns custom UI entries registered for an active session."
  [manager registry context]
  (let [activation (get @(:activations manager) (activation-key registry context))]
    (vec (vals (get @(:ui-entries manager) (:id activation) {})))))

(defn- unload-generated-namespaces! [loaded-code]
  (doseq [[_ {:keys [generated namespace]}] loaded-code]
    (when (= generated namespace)
      (try (remove-ns generated) (catch Throwable _ nil)))))

(defn reload!
  "Re-discovers settings/resources and reactivates prior session contexts with restoration on load failure."
  [manager]
  (locking (:lock manager)
    (when (:closed? @(:state manager))
      (fail! :resource/closed "Resource manager is closed" {}))
    (let [candidate (compute-state manager)
          old-state @(:state manager)
          old-code @(:loaded-code manager)
          previous (vec (vals @(:activations manager)))
          teardown-errors
          (reduce (fn [errors activation]
                    (try
                      (deactivate! manager (:id activation))
                      errors
                      (catch Throwable error
                        (conj errors {:activation-id (:id activation)
                                      :message (ex-message error)
                                      :data (ex-data error)}))))
                  [] previous)]
      (when (seq teardown-errors)
        (let [restore-errors
              (reduce (fn [errors {:keys [registry context]}]
                        (try
                          (activate! manager registry context)
                          errors
                          (catch Throwable error
                            (conj errors {:session-id (:session-id context)
                                          :message (ex-message error)
                                          :data (ex-data error)}))))
                      [] previous)]
          (fail! :extension/teardown-failed
                 "Resources could not reload because extension teardown failed"
                 {:errors teardown-errors :restoration-errors restore-errors})))
      ;; Keep the old generated namespaces alive until the candidate activation
      ;; succeeds so rollback can execute the exact prior code.
      (reset! (:loaded-code manager) {})
      (reset! (:state manager) candidate)
      (try
        (doseq [{:keys [registry context]} previous]
          (activate! manager registry context))
        (unload-generated-namespaces! old-code)
        manager
        (catch Throwable reload-error
          (let [cleanup-errors
                (reduce (fn [errors activation]
                          (try
                            (deactivate! manager (:id activation))
                            errors
                            (catch Throwable error
                              (conj errors {:activation-id (:id activation)
                                            :message (ex-message error)
                                            :data (ex-data error)}))))
                        [] (vec (vals @(:activations manager))))]
            (unload-generated-namespaces! @(:loaded-code manager))
            (reset! (:loaded-code manager) old-code)
            (reset! (:state manager) old-state)
            (let [restore-errors
                  (reduce (fn [errors {:keys [registry context]}]
                            (try
                              (activate! manager registry context)
                              errors
                              (catch Throwable error
                                (conj errors {:session-id (:session-id context)
                                              :message (ex-message error)
                                              :data (ex-data error)}))))
                          [] previous)]
              (throw (ex-info (ex-message reload-error)
                              (assoc (ex-data reload-error)
                                     :reload-cleanup-errors cleanup-errors
                                     :restoration-errors restore-errors)
                              reload-error)))))))))

(defn close!
  "Tears down all attributable resource effects and closes this manager. Idempotent."
  [manager]
  (locking (:lock manager)
    (if (:closed? @(:state manager))
      {:status :closed :already-closed? true :errors []}
      (let [records (reverse (sort-by :activated-at (vals @(:activations manager))))
            errors (reduce (fn [result activation]
                             (try (deactivate! manager (:id activation)) result
                                  (catch Throwable error
                                    (conj result {:activation-id (:id activation)
                                                  :message (ex-message error)
                                                  :data (ex-data error)}))))
                           [] records)]
        (if (seq errors)
          {:status :closing :already-closed? false :errors errors}
          (do
            (reset! (:commands manager) {})
            (reset! (:renderers manager) {})
            (reset! (:ui-entries manager) {})
            (unload-generated-namespaces! @(:loaded-code manager))
            (reset! (:loaded-code manager) {})
            (swap! (:state manager) assoc :closed? true)
            {:status :closed :already-closed? false :errors []}))))))
