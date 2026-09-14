(ns arrodes.packages
  "Atomic local, Git, and Maven package installation and manifest discovery."
  (:require [arrodes.platform :as u]
            [arrodes.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedInputStream)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.file AtomicMoveNotSupportedException Files LinkOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration)
           (java.util.concurrent TimeUnit)
           (java.util.zip ZipInputStream)))

(def ^:private resource-keys [:extensions :skills :prompts :themes])

(def ^:private package-locks (atom {}))
(def ^:private max-maven-archive-bytes (* 128 1024 1024))
(def ^:private max-maven-expanded-bytes (* 512 1024 1024))
(def ^:private max-maven-entries 50000)


(defn- package-lock [root]
  (let [key (str (.normalize (.toAbsolutePath (u/path root))))]
    (get (swap! package-locks
                #(if (contains? % key) % (assoc % key (Object.))))
         key)))

(defn- fail! [code message data] (value/fail! code message data))

(defn- scope-key [scope]
  (case scope
    (:global "global" :user "user") :global
    (:project "project") :project
    (fail! :package/invalid-scope "Package scope must be :global or :project" {:scope scope})))

(defn- package-root [home cwd scope]
  (case (scope-key scope)
    :global (u/resolve-path home "packages")
    :project (u/resolve-path cwd ".arrodes-mono/packages")))


(defn- index-path [root] (u/resolve-path root "packages.edn"))

(defn- read-index [root]
  (let [path (index-path root)]
    (when (Files/isSymbolicLink (u/path path))
      (fail! :package/invalid-index "Package index cannot be a symbolic link" {:path path}))
    (let [value (try
                  (u/read-edn path {:version 1 :packages {}})
                  (catch Throwable error
                    (fail! :package/invalid-index "Package index is not valid EDN"
                           {:path path :cause (ex-message error)})))]
      (when-not (and (map? value) (= 1 (:version value)) (map? (:packages value)))
        (fail! :package/invalid-index "Package index is malformed" {:path path}))
      value)))

(defn- write-index! [root value]
  (u/ensure-dir! root)
  (u/write-edn! (index-path root) value))

(defn- safe-name [value]
  (let [name (-> (str value)
                 (str/replace #"\.git$" "")
                 (str/replace #"[^A-Za-z0-9._-]+" "-")
                 (str/replace #"^[._-]+|[._-]+$" ""))]
    (when (or (str/blank? name) (#{"." ".."} name))
      (fail! :package/invalid-name "Package name cannot be derived from source" {:source value}))
    name))

(defn- path-under? [root child]
  (let [r (.normalize (.toAbsolutePath (u/path root)))
        c (.normalize (.toAbsolutePath (u/path child)))]
    (or (= r c) (.startsWith c r))))
(defn- verify-package-root! [home cwd scope root]
  (when (Files/exists (u/path root) (make-array LinkOption 0))
    (let [base (case (scope-key scope) :global home :project cwd)
          real-base (.toRealPath (u/path base) (make-array LinkOption 0))
          real-root (.toRealPath (u/path root) (make-array LinkOption 0))]
      (when-not (path-under? real-base real-root)
        (fail! :package/path-escape
               "Package root resolves outside its home or project directory"
               {:scope (scope-key scope) :root (str root)}))))
  root)

(defn- assert-under! [root child]
  (when-not (path-under? root child)
    (fail! :package/path-escape "Package path escapes its installation root"
           {:root (str root) :path (str child)}))
  child)

(defn- installed-path-valid? [root name descriptor]
  (let [path (:path descriptor)
        expected (.normalize (.toAbsolutePath (.resolve (u/path root) (str name))))]
    (and (string? name)
         (= name (safe-name name))
         (= name (:name descriptor))
         (string? path)
         (= expected (.normalize (.toAbsolutePath (u/path path))))
         (Files/isDirectory (u/path path) (make-array LinkOption 0))
         (not (Files/isSymbolicLink (u/path path))))))

(defn- delete-tree! [path]
  (let [p (u/path path)]
    (when (Files/exists p (make-array LinkOption 0))
      (with-open [stream (Files/walk p (make-array java.nio.file.FileVisitOption 0))]
        (doseq [entry (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator stream))))]
          (Files/deleteIfExists entry))))))

(defn- delete-tree-quietly! [path]
  (try
    (delete-tree! path)
    (catch Throwable _ nil)))

(defn- assert-no-symbolic-links! [path]
  (with-open [stream (Files/walk (u/path path) (make-array java.nio.file.FileVisitOption 0))]
    (doseq [entry (iterator-seq (.iterator stream))]
      (when (Files/isSymbolicLink entry)
        (fail! :package/symlink "Installed packages may not contain symbolic links"
               {:path (str entry)}))))
  path)

(defn- copy-tree! [source target]
  (let [src (.toRealPath (u/path source) (make-array LinkOption 0))
        dst (.normalize (.toAbsolutePath (u/path target)))]
    (with-open [stream (Files/walk src (make-array java.nio.file.FileVisitOption 0))]
      (doseq [entry (iterator-seq (.iterator stream))]
        (let [relative (.relativize src entry)
              first-segment (when (pos? (.getNameCount relative))
                              (str (.getName relative 0)))]
          (when-not (= ".git" first-segment)
            (when (Files/isSymbolicLink entry)
              (fail! :package/symlink "Package sources may not contain symbolic links"
                     {:path (str entry)}))
            (let [output (.resolve dst relative)]
              (assert-under! dst output)
              (if (Files/isDirectory entry (make-array LinkOption 0))
                (Files/createDirectories output (make-array FileAttribute 0))
                (do
                  (Files/createDirectories (.getParent output) (make-array FileAttribute 0))
                  (Files/copy entry output
                              (into-array StandardCopyOption [StandardCopyOption/COPY_ATTRIBUTES
                                                             StandardCopyOption/REPLACE_EXISTING])))))))))))

(defn- move-replacing! [source target]
  (try
    (Files/move (u/path source) (u/path target)
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (u/path source) (u/path target)
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(defn- run-git! [args cwd]
  (let [null-device (if (= "Windows_NT" (System/getenv "OS")) "NUL" "/dev/null")
        command (into ["git" "-c" (str "core.hooksPath=" null-device)
                       "-c" "credential.helper="] args)
        builder (ProcessBuilder. ^java.util.List command)
        env (.environment builder)]
    (doseq [key ["GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE" "GIT_OBJECT_DIRECTORY"
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES" "GIT_COMMON_DIR" "GIT_CONFIG"
                 "GIT_CONFIG_SYSTEM" "GIT_CONFIG_PARAMETERS" "GIT_TEMPLATE_DIR"]]
      (.remove env key))
    (.put env "GIT_CONFIG_NOSYSTEM" "1")
    (.put env "GIT_CONFIG_GLOBAL" null-device)
    (.put env "GIT_CONFIG_COUNT" "0")
    (.put env "GIT_ATTR_NOSYSTEM" "1")
    (.put env "GIT_TERMINAL_PROMPT" "0")
    (when cwd (.directory builder (io/file cwd)))
    (.redirectErrorStream builder true)
    (let [process (.start builder)
          output (future (slurp (.getInputStream process)))
          finished? (.waitFor process 180 TimeUnit/SECONDS)]
      (when-not finished?
        (.destroyForcibly process)
        (fail! :package/git-timeout "Git operation timed out" {:command args}))
      (let [text @output]
        (when-not (zero? (.exitValue process))
          (fail! :package/git-failed "Git operation failed"
                 {:command args :exit (.exitValue process) :output (u/bounded-string text 12000)}))
        (str/trim text)))))

(defn- git-source? [source]
  (boolean (or (re-find #"^(https?|ssh|git|file)://" source)
               (re-find #"^[^/@:]+@[^:]+:.+" source))))

(defn- split-git-ref [source explicit-ref]
  (let [validate-ref (fn [value]
                       (let [value (str value)]
                         (when (or (str/blank? value) (> (count value) 1024)
                                   (re-find #"[\u0000\r\n]" value))
                           (fail! :package/invalid-ref "Git ref is empty or contains invalid characters" {}))
                         value))]
    (if (some? explicit-ref)
      [source (validate-ref explicit-ref)]
      (let [i (.lastIndexOf ^String source "#")]
        (cond
          (neg? i) [source nil]
          (= i (dec (count source)))
          (fail! :package/invalid-ref "Git source has an empty fragment ref" {})
          :else [(subs source 0 i) (validate-ref (subs source (inc i)))])))))
(defn- parse-maven [source]
  (when (str/starts-with? source "mvn:")
    (let [coordinate (subs source 4)
          [ga version] (str/split coordinate #":" 2)
          [group artifact] (str/split (or ga "") #"/" 2)]
      (when-not (and (seq group) (seq artifact) (seq version)
                     (re-matches #"[A-Za-z0-9_.-]+" group)
                     (re-matches #"[A-Za-z0-9_.-]+" artifact)
                     (re-matches #"[A-Za-z0-9_.+\-]+" version))
        (fail! :package/invalid-maven-coordinate
               "Maven source must be mvn:group/artifact:version"
               {:source source}))
      {:type :maven :group group :artifact artifact :version version})))

(defn- local-source [cwd source]
  (when-not (str/starts-with? source "file://")
    (let [raw (if (str/starts-with? source "file:") (subs source 5) source)
          path (u/path raw)
          resolved (if (.isAbsolute path) (.normalize path) (.normalize (.resolve (u/path cwd) path)))]
      (when (Files/isDirectory resolved (make-array LinkOption 0))
        {:type :local :path (str (.toRealPath resolved (make-array LinkOption 0)))}))))

(defn- source-spec [cwd source opts]
  (when-not (and (string? source) (not (str/blank? source)))
    (fail! :package/invalid-source "Package source must be a non-empty string" {:source source}))
  (when (re-find #"[\u0000\r\n]" source)
    (fail! :package/invalid-source "Package source contains invalid control characters" {}))
  (let [local (local-source cwd source)
        maven (parse-maven source)]
    (cond
      local
      (do
        (when (:ref opts)
          (fail! :package/invalid-ref "A ref can only be supplied for a Git package" {:source source}))
        local)

      maven
      (do
        (when (:ref opts)
          (fail! :package/invalid-ref "A ref can only be supplied for a Git package" {:source source}))
        maven)

      (git-source? source)
      (let [[url ref] (split-git-ref source (:ref opts))
            uri (when (re-find #"^(https?|ssh|git)://" url) (URI/create url))
            user-info (some-> uri .getUserInfo)
            sensitive-query? (boolean (and uri (seq (.getQuery uri))))]
        (when (or (and (re-find #"^https?://" url) user-info)
                  (and (str/starts-with? url "ssh://") user-info (str/includes? user-info ":"))
                  sensitive-query?)
          (fail! :package/credential-in-source
                 "Git URLs containing credentials are not accepted; use SSH or an external askpass helper"
                 {:scheme (some-> uri .getScheme) :host (some-> uri .getHost)}))
        {:type :git :url url :ref ref :pinned? (boolean ref)})

      :else
      (fail! :package/unsupported-source
             "Package source must be a local directory, Git URL, or Maven coordinate"
             {:source source}))))

(defn- source-name [spec]
  (case (:type spec)
    :local (safe-name (str (.getFileName (u/path (:path spec)))))
    :git (safe-name (last (str/split (:url spec) #"[/ :]")))
    :maven (safe-name (:artifact spec))))

(defn- manifest-entry-path [package-dir kind entry]
  (let [raw (if (string? entry) entry (:path entry))]
    (when-not (and (string? raw) (not (str/blank? raw)))
      (fail! :package/invalid-manifest "Manifest resource entries require a path"
             {:kind kind :entry entry :package package-dir}))
    (let [candidate (.normalize (.resolve (u/path package-dir) raw))]
      (assert-under! package-dir candidate)
      (when (= (.normalize (.toAbsolutePath (u/path package-dir)))
               (.normalize (.toAbsolutePath candidate)))
        (fail! :package/invalid-manifest
               "Manifest resources must name a child path, not the package root"
               {:kind kind :path raw :package package-dir}))
      (when-not (Files/exists candidate (make-array LinkOption 0))
        (fail! :package/missing-resource "Manifest resource does not exist"
               {:kind kind :path raw :package package-dir}))
      (let [real (.toRealPath candidate (make-array LinkOption 0))
            root (.toRealPath (u/path package-dir) (make-array LinkOption 0))]
        (assert-under! root real))
      (assoc (if (map? entry) entry {}) :path raw))))

(defn read-manifest
  "Reads and validates arrodes.edn at a package root. Resource paths are contained by the root."
  [package-dir]
  (let [root (str (.toRealPath (u/path package-dir) (make-array LinkOption 0)))
        file (u/resolve-path root "arrodes.edn")]
    (when-not (.isFile (io/file file))
      (fail! :package/missing-manifest "Package does not contain arrodes.edn" {:path root}))
    (let [manifest (try (edn/read-string (slurp file))
                        (catch Throwable error
                          (fail! :package/invalid-manifest "Package manifest is not valid EDN"
                                 {:path file :cause (ex-message error)})))]
      (when-not (map? manifest)
        (fail! :package/invalid-manifest "Package manifest must be an EDN map" {:path file}))
      (reduce (fn [result kind]
                (let [entries (get manifest kind [])]
                  (when-not (vector? entries)
                    (fail! :package/invalid-manifest "Manifest resource field must be a vector"
                           {:path file :field kind}))
                  (assoc result kind (mapv #(manifest-entry-path root kind %) entries))))
              (select-keys manifest [:name :version :description])
              resource-keys))))

(defn- extract-zip! [archive target]
  (let [root (.normalize (.toAbsolutePath (u/path target)))
        buffer (byte-array 8192)]
    (Files/createDirectories root (make-array FileAttribute 0))
    (with-open [input (ZipInputStream. (BufferedInputStream. (io/input-stream archive)))]
      (loop [entry-count 0 total-bytes 0 seen #{}]
        (when-let [entry (.getNextEntry input)]
          (let [output (.normalize (.resolve root (.getName entry)))
                output-key (str output)]
            (assert-under! root output)
            (when (or (= root output) (contains? seen output-key))
              (fail! :package/invalid-archive
                     "Maven package archive contains an empty or duplicate entry"
                     {:entry (.getName entry)}))
            (when (>= entry-count max-maven-entries)
              (fail! :package/archive-too-large "Maven package archive contains too many entries"
                     {:limit max-maven-entries}))
            (let [next-total
                  (if (.isDirectory entry)
                    (do (Files/createDirectories output (make-array FileAttribute 0))
                        total-bytes)
                    (do
                      (Files/createDirectories (.getParent output) (make-array FileAttribute 0))
                      (with-open [stream (Files/newOutputStream output (make-array java.nio.file.OpenOption 0))]
                        (loop [written total-bytes]
                          (let [n (.read input buffer)]
                            (if (neg? n)
                              written
                              (let [next-written (+ written n)]
                                (when (> next-written max-maven-expanded-bytes)
                                  (fail! :package/archive-too-large
                                         "Expanded Maven package exceeds the size limit"
                                         {:limit max-maven-expanded-bytes}))
                                (.write stream buffer 0 n)
                                (recur next-written))))))))]
              (.closeEntry input)
              (recur (inc entry-count) next-total (conj seen output-key)))))))))

(defn- fetch-maven! [spec payload opts]
  (let [repository (str/replace (or (:repository opts) "https://repo1.maven.org/maven2") #"/+$" "")
        repository-uri (URI/create repository)
        _ (when (or (.getUserInfo repository-uri)
                    (seq (.getQuery repository-uri)))
            (fail! :package/credential-in-source
                   "Maven repository URLs containing credentials or query parameters are not accepted"
                   {:scheme (.getScheme repository-uri) :host (.getHost repository-uri)}))
        relative (str (str/replace (:group spec) "." "/") "/" (:artifact spec) "/"
                      (:version spec) "/" (:artifact spec) "-" (:version spec) ".jar")
        uri (URI/create (str repository "/" relative))
        archive (Files/createTempFile (.getParent (u/path payload)) "arrodes-maven-" ".jar"
                                      (make-array FileAttribute 0))
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 20))
                   (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
                   .build)
        request (-> (HttpRequest/newBuilder uri) (.timeout (Duration/ofSeconds 120)) .GET .build)]
    (try
      (let [response (.send client request (HttpResponse$BodyHandlers/ofInputStream))]
        (with-open [input (.body response)]
          (when-not (= 200 (.statusCode response))
            (fail! :package/maven-download-failed "Maven artifact download failed"
                   {:coordinate (str (:group spec) "/" (:artifact spec) ":" (:version spec))
                    :repository repository :status (.statusCode response)}))
          (with-open [output (Files/newOutputStream archive (make-array java.nio.file.OpenOption 0))]
            (let [buffer (byte-array 8192)]
              (loop [total 0]
                (let [n (.read input buffer)]
                  (when-not (neg? n)
                    (let [next-total (+ total n)]
                      (when (> next-total max-maven-archive-bytes)
                        (fail! :package/archive-too-large "Maven package download exceeds the size limit"
                               {:limit max-maven-archive-bytes}))
                      (.write output buffer 0 n)
                      (recur next-total))))))))
        (extract-zip! archive payload))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Throwable error
        (fail! :package/maven-download-failed "Maven artifact download failed"
               {:source (:source spec) :cause (ex-message error)}))
      (finally (Files/deleteIfExists archive)))))

(defn- copy-local-package! [source payload]
  ;; Validate before copying, then retain support files as well as manifest
  ;; entrypoints. Nothing in the copied tree is executed by the installer.
  (read-manifest source)
  (copy-tree! source payload))

(defn- prepare-source! [spec payload opts]
  (case (:type spec)
    :local (copy-local-package! (:path spec) payload)
    :git (do
           (run-git! ["clone" "--no-checkout" "--filter=blob:none" "--"
                      (:url spec) (str payload)] nil)
           (let [target (run-git! ["-C" (str payload) "rev-parse" "--verify"
                                   "--end-of-options" (str (or (:ref spec) "HEAD") "^{commit}")] nil)]
             (run-git! ["-C" (str payload) "checkout" "--detach" target] nil)))
    :maven (fetch-maven! spec payload opts))
  payload)

(defn- git-revision [spec payload]
  (when (= :git (:type spec))
    (run-git! ["-C" (str payload) "rev-parse" "HEAD"] nil)))

(defn- install-internal! [home cwd source opts replacing?]
  (let [scope (scope-key (:scope opts))
        spec (source-spec cwd source opts)
        root (package-root home cwd scope)]
    (locking (package-lock root)
      (u/ensure-dir! root)
      (verify-package-root! home cwd scope root)
      (let [name (or (:name opts) (source-name spec))
            _ (when-not (= name (safe-name name))
                (fail! :package/invalid-name "Package name contains unsafe characters" {:name name}))
            index (read-index root)
            old (get-in index [:packages name])
            _ (when (and old (not replacing?))
                (fail! :package/already-installed "Package is already installed"
                       {:name name :scope scope :source (:source old)}))
            destination (assert-under! root (u/resolve-path root name))
            _ (when (and old (not (installed-path-valid? root name old)))
                (fail! :package/corrupt-installation "Existing package ownership is invalid"
                       {:name name :scope scope}))
            _ (when (and (Files/exists (u/path destination) (make-array LinkOption 0)) (nil? old))
                (fail! :package/unowned-destination "Package destination exists but is not owned by the package index"
                       {:name name :scope scope}))
            _ (when (= :local (:type spec))
                (let [source-path (.toRealPath (u/path (:path spec)) (make-array LinkOption 0))
                      root-path (.toRealPath (u/path root) (make-array LinkOption 0))
                      target-path (.resolve root-path name)]
                  (when (or (.startsWith target-path source-path) (.startsWith source-path target-path))
                    (fail! :package/source-overlap "Local source and installation destination must not contain one another"
                           {:name name :scope scope}))))
            staging (Files/createTempDirectory (u/path root) ".install-" (make-array FileAttribute 0))
            payload (.resolve staging "package")
            backup (.resolve (u/path root) (str ".backup-" (u/id)))]
        (try
          (prepare-source! spec payload opts)
          (let [revision (git-revision spec payload)
                _ (when (= :git (:type spec))
                    (delete-tree! (.resolve payload ".git")))
                _ (assert-no-symbolic-links! payload)
                manifest (read-manifest payload)
                descriptor {:name name
                            :source source
                            :scope scope
                            :type (:type spec)
                            :ref (:ref spec)
                            :pinned? (or (:pinned? spec) (= :maven (:type spec)))
                            :revision revision
                            :path (str destination)
                            :manifest manifest
                            :installed-at (u/now)
                            :available? true}]
            (when (Files/exists (u/path destination) (make-array LinkOption 0))
              (when-not old
                (fail! :package/unowned-destination "Package destination appeared during preparation"
                       {:name name :scope scope}))
              (move-replacing! destination backup))
            (let [activated? (volatile! false)]
              (try
                (move-replacing! payload destination)
                (vreset! activated? true)
                (write-index! root (assoc-in index [:packages name] descriptor))
                ;; Backup cleanup cannot roll back a committed index.
                (delete-tree-quietly! backup)
                descriptor
                (catch Throwable error
                  (when @activated? (delete-tree-quietly! destination))
                  (when (Files/exists backup (make-array LinkOption 0))
                    (move-replacing! backup destination))
                  (throw error)))))
          (finally (delete-tree-quietly! staging)))))))

(defn install!
  "Installs a package atomically. opts requires :scope and may include :ref or :repository."
  [home cwd source opts]
  (install-internal! (u/canonical-path home) (u/canonical-path cwd) source opts false))

(defn- packages-in [home cwd scope]
  (let [root (verify-package-root! home cwd scope (package-root home cwd scope))
        index (read-index root)]
    (mapv (fn [[name descriptor]]
            (when-not (installed-path-valid? root name descriptor)
              (fail! :package/corrupt-installation "Installed package has an invalid name or path"
                     {:name name :scope scope :path (:path descriptor)}))
            (let [path (:path descriptor)]
              (assert-no-symbolic-links! path)
              (assoc descriptor :manifest (read-manifest path) :available? true)))
          (sort-by key (:packages index)))))
(defn list-packages
  "Returns validated package descriptors. The three-argument form can restrict :scope."
  ([home cwd]
   (vec (concat (packages-in (u/canonical-path home) (u/canonical-path cwd) :global)
                (packages-in (u/canonical-path home) (u/canonical-path cwd) :project))))
  ([home cwd {:keys [scope]}]
   (if scope
     (packages-in (u/canonical-path home) (u/canonical-path cwd) (scope-key scope))
     (list-packages home cwd))))

(defn remove!
  "Removes one package in the requested scope without touching other installations."
  [home cwd name opts]
  (let [scope (scope-key (:scope opts))
        home (u/canonical-path home)
        cwd (u/canonical-path cwd)
        root (verify-package-root! home cwd scope (package-root home cwd scope))]
    (locking (package-lock root)
      (let [index (read-index root)
            descriptor (get-in index [:packages name])]
        (when-not descriptor
          (fail! :package/not-found "Package is not installed" {:name name :scope scope}))
        (let [path (:path descriptor)
              trash (.resolve (u/path root) (str ".remove-" (u/id)))]
          (when-not (installed-path-valid? root name descriptor)
            (fail! :package/corrupt-installation "Installed package has an invalid name or path"
                   {:name name :scope scope :path path}))
          (move-replacing! path trash)
          (try
            (write-index! root (update index :packages dissoc name))
            ;; Trash deletion follows the committed index update and cannot
            ;; safely be treated as a transaction failure.
            (delete-tree-quietly! trash)
            (assoc descriptor :removed? true :available? false)
            (catch Throwable error
              (when (Files/exists trash (make-array LinkOption 0))
                (move-replacing! trash path))
              (throw error))))))))

(defn- update-one! [home cwd descriptor]
  (if (:pinned? descriptor)
    (assoc descriptor :status :pinned)
    (assoc (install-internal! home cwd (:source descriptor)
                              {:scope (:scope descriptor) :ref (:ref descriptor)
                               :name (:name descriptor)} true)
           :status :updated)))

(defn update!
  "Updates one or all unpinned packages in a scope. Pinned Git refs and Maven versions are retained."
  [home cwd name-or-nil opts]
  (let [home (u/canonical-path home)
        cwd (u/canonical-path cwd)
        scope (scope-key (:scope opts))
        root (package-root home cwd scope)]
    (locking (package-lock root)
      (let [candidates (list-packages home cwd {:scope scope})
            selected (if name-or-nil
                       (let [package (some #(when (= name-or-nil (:name %)) %) candidates)]
                         (when-not package
                           (fail! :package/not-found "Package is not installed" {:name name-or-nil :scope scope}))
                         [package])
                       candidates)]
        (mapv #(update-one! home cwd %) selected)))))
