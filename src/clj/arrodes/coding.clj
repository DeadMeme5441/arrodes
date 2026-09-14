(ns arrodes.coding
  "Trusted-local coding capabilities. Paths may be relative to the session cwd or
  absolute; this namespace deliberately does not claim to provide an OS sandbox."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arrodes.owned-process :as owned-process]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.io BufferedReader ByteArrayOutputStream File InputStream InputStreamReader)
           (java.nio ByteBuffer CharBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.nio.file FileVisitResult Files LinkOption Path Paths SimpleFileVisitor)
           (java.util Base64)
           (java.util.concurrent TimeUnit)
           (java.util.regex Pattern PatternSyntaxException)))

(def ^:private max-read-lines 2000)
(def ^:private max-search-results 100)
(def ^:private max-find-results 1000)
(def ^:private max-list-results 500)
(def ^:private max-traversed-files 100000)
(def ^:private max-line-chars 500)
(def ^:private max-image-bytes (* 5 1024 1024))
(def ^:private max-read-file-bytes (* 64 1024 1024))
(def ^:private max-edit-bytes (* 32 1024 1024))
(def ^:private max-process-stream-bytes (* 16 1024 1024))
(def ^:private max-search-file-bytes (* 8 1024 1024))
(def ^:private max-search-file-lines 100000)
(def ^:private max-directory-entries 100000)
(def ^:private max-exact-edits 1000)
(def ^:private ignored-directory-names
  #{".git" ".hg" ".svn" ".cache" ".gradle" ".idea" ".next" ".turbo"
    "build" "coverage" "dist" "node_modules" "out" "target"})
(def ^:private image-mime-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif"
   "webp" "image/webp" "bmp" "image/bmp"})

(defn- schema-object [properties required]
  {:type "object" :properties properties :required required :additionalProperties false})

(defn- string-property [description]
  {:type "string" :description description})

(defn- positive-integer-property [description]
  {:type "integer" :minimum 1 :description description})
(defn- bounded-positive-integer-property [maximum description]
  {:type "integer" :minimum 1 :maximum maximum :description description})

(defn- cancelled? [current-context]
  (util/cancelled? (:cancelled? (current-context))))

(defn- check-cancelled! [current-context]
  (when (cancelled? current-context)
    (value/fail! :cancelled "Capability invocation was cancelled" {})))

(defn- progress! [current-context event]
  (let [context (current-context)]
    (when-let [callback (:on-progress context)]
      (try
        (callback (merge (select-keys context [:call-id :parent-call-id]) event))
        (catch Throwable _ nil)))))

(defn- resolve-path ^Path [cwd value]
  (let [path (Paths/get (str value) (make-array String 0))]
    (.normalize (.toAbsolutePath (if (.isAbsolute path) path (.resolve (Paths/get cwd (make-array String 0)) path))))))
(defn- mutation-path ^Path [cwd value]
  (let [target (resolve-path cwd value)]
    (if (Files/exists target (make-array LinkOption 0))
      (.toRealPath target (make-array LinkOption 0))
      target)))

(defn- require-regular-file! [^Path path display]
  (value/check! (Files/exists path (make-array LinkOption 0)) :not-found
               (str "Path does not exist: " display) {:path (str path)})
  (value/check! (Files/isRegularFile path (make-array LinkOption 0)) :not-a-file
               (str "Not a regular file: " display) {:path (str path)}))

(defn- extension [^Path path]
  (let [name (str (.getFileName path))
        at (.lastIndexOf name ".")]
    (when (and (pos? at) (< at (dec (count name))))
      (str/lower-case (subs name (inc at))))))

(defn- decode-utf8 [^bytes bytes display]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch CharacterCodingException _
      (value/fail! :unsupported-file "File is neither supported image data nor valid UTF-8 text"
                  {:path display}))))

(defn- split-lines [text]
  (str/split text #"\n" -1))
(defn- atomic-write-preserving-permissions! [^Path target content]
  (let [permissions (when (Files/exists target (make-array LinkOption 0))
                      (try (Files/getPosixFilePermissions target (make-array LinkOption 0))
                           (catch UnsupportedOperationException _ nil)))]
    (util/atomic-write! target content)
    (if permissions
      (try
        (Files/setPosixFilePermissions target permissions)
        true
        (catch Throwable _ false))
      true)))

(defn- validate-limit [value default maximum label]
  (let [value (or value default)]
    (value/check! (and (integer? value) (pos? value) (<= value maximum)) :invalid-arguments
                 (str label " must be an integer from 1 through " maximum) {label value})
    value))

(defn- read-text-page [^Path target display current-context offset limit]
  (let [offset (or offset 1)
        requested (validate-limit limit max-read-lines 100000 :limit)
        _ (value/check! (and (integer? offset) (pos? offset)) :invalid-offset
                       "Read offset must be a positive 1-based line number"
                       {:path (str target) :offset offset})
        size (Files/size target)
        _ (value/check! (<= size max-read-file-bytes) :file-too-large
                       "Text file is too large for bounded read paging"
                       {:path (str target) :bytes size :limit max-read-file-bytes})
        decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (if (zero? size)
      (do
        (value/check! (= 1 offset) :invalid-offset
                     "Read offset is beyond the end of the empty file"
                     {:path (str target) :offset offset})
        {:value "" :content ""
         :details {:path (str target) :offset 1 :lines 0 :next-offset nil}})
      (try
        (with-open [reader (BufferedReader.
                            (InputStreamReader.
                             (Files/newInputStream target (make-array java.nio.file.OpenOption 0))
                             decoder))]
          (loop [line-number 1 selected []]
            (check-cancelled! current-context)
            (let [line (.readLine reader)]
              (cond
                (nil? line)
                (do
                  (value/check! (or (seq selected) (= 1 offset)) :invalid-offset
                               (str "Offset " offset " is beyond end of file")
                               {:path (str target) :offset offset})
                  (let [text (str/join "\n" selected)]
                    {:value text :content text
                     :details {:path (str target) :offset offset
                               :lines (count selected) :next-offset nil}}))

                (< line-number offset)
                (recur (inc line-number) selected)

                (< (count selected) requested)
                (recur (inc line-number) (conj selected line))

                :else
                (let [text (str/join "\n" selected)
                      next-offset (+ offset requested)]
                  {:value text
                   :content (str text "\n\n[Showing lines " offset "-"
                                 (dec next-offset) ". Use offset=" next-offset
                                 " to continue.]")
                   :details {:path (str target) :offset offset
                             :lines requested :next-offset next-offset}})))))
        (catch CharacterCodingException _
          (value/fail! :unsupported-file
                      "File is neither supported image data nor valid UTF-8 text"
                      {:path display}))))))

(defn- read-tool [cwd current-context put-artifact! {:keys [path offset limit]}]
  (check-cancelled! current-context)
  (let [target (resolve-path cwd path)
        _ (require-regular-file! target path)
        mime (get image-mime-types (extension target))]
    (if mime
      (let [size (Files/size target)
            _ (value/check! (<= size max-image-bytes) :file-too-large
                           "Image is too large to return inline"
                           {:path (str target) :bytes size :limit max-image-bytes})
            bytes (Files/readAllBytes target)
            _ (check-cancelled! current-context)
            artifact (put-artifact! bytes {:kind :binary :name (str (.getFileName target))})
            encoded (.encodeToString (Base64/getEncoder) bytes)
            descriptor {:path (str target) :mime-type mime :bytes (alength bytes)
                        :artifact artifact}]
        {:value bytes
         :content [{:part/type :text
                    :text (str "Read image file " path " [" mime ", " (alength bytes)
                               " bytes; artifact " (:id artifact) "]")}
                   {:part/type :image :image/mime-type mime :image/data encoded}]
         :details descriptor})
      (read-text-page target path current-context offset limit))))

(defn- write-tool [cwd current-context {:keys [path content]}]
  (check-cancelled! current-context)
  (let [target (mutation-path cwd path)]
    (value/check! (string? content) :invalid-arguments "write content must be a string" {:path path})
    (let [bytes (alength (.getBytes content StandardCharsets/UTF_8))]
      (value/check! (<= bytes max-edit-bytes) :file-too-large
                   "write content exceeds the supported atomic edit size"
                   {:path (str target) :bytes bytes :limit max-edit-bytes})
      (check-cancelled! current-context)
      (let [permissions-preserved? (atomic-write-preserving-permissions! target content)]
        {:value (str target)
         :content (str "Successfully wrote " bytes " bytes to " path)
         :details {:path (str target) :bytes bytes
                   :permissions-preserved? permissions-preserved?}}))))

(defn- occurrences [^String content ^String needle]
  (loop [from 0 positions []]
    (if (= 2 (count positions))
      positions
      (let [at (.indexOf content needle from)]
        (if (neg? at)
          positions
          (recur (inc at) (conj positions at)))))))

(defn- line-ending [text]
  (if (and (str/includes? text "\r\n")
           (>= (count (re-seq #"\r\n" text))
               (count (re-seq #"(?<!\r)\n" text))))
    "\r\n" "\n"))

(defn- normalize-newlines [text]
  (-> text (str/replace "\r\n" "\n") (str/replace "\r" "\n")))

(defn- validate-edits! [edits path]
  (value/check! (and (vector? edits) (seq edits)) :invalid-arguments
               "edit requires a non-empty edits array" {:path path})
  (value/check! (<= (count edits) max-exact-edits) :invalid-arguments
               (str "edit accepts at most " max-exact-edits " replacements")
               {:path path :edits (count edits) :limit max-exact-edits})
  (doseq [[index edit] (map-indexed vector edits)]
    (value/check! (map? edit) :invalid-arguments "Each edit must be an object" {:index index})
    (value/check! (and (string? (:oldText edit)) (not (empty? (:oldText edit)))) :invalid-arguments
                 "Each edit oldText must be a non-empty string" {:index index})
    (value/check! (string? (:newText edit)) :invalid-arguments
                 "Each edit newText must be a string" {:index index})))

(defn- apply-exact-edits [content edits path]
  (let [located
        (mapv (fn [[index {:keys [oldText newText]}]]
                (let [positions (occurrences content (normalize-newlines oldText))]
                  (value/check! (= 1 (count positions)) :edit-mismatch
                               (if (empty? positions)
                                 (str "Edit " (inc index) " oldText was not found in " path)
                                 (str "Edit " (inc index) " oldText is not unique in " path))
                               {:path path :edit-index index :matches (count positions)})
                  {:start (first positions) :end (+ (first positions) (count (normalize-newlines oldText)))
                   :replacement (normalize-newlines newText) :index index}))
              (map-indexed vector edits))
        ordered (sort-by :start located)]
    (doseq [[left right] (partition 2 1 ordered)]
      (value/check! (<= (:end left) (:start right)) :overlapping-edits
                   "Edit replacements overlap in the original file"
                   {:path path :edit-indexes [(:index left) (:index right)]}))
    (loop [cursor 0 remaining (seq ordered) output (StringBuilder. (count content))]
      (if-let [{:keys [start end replacement]} (first remaining)]
        (do
          (.append output ^String content (int cursor) (int start))
          (.append output ^String replacement)
          (recur end (next remaining) output))
        (do
          (.append output ^String content (int cursor) (int (count content)))
          (str output))))))

(defn- edit-tool [cwd current-context {:keys [path edits]}]
  (check-cancelled! current-context)
  (validate-edits! edits path)
  (let [target (mutation-path cwd path)
        _ (require-regular-file! target path)
        size (Files/size target)
        _ (value/check! (<= size max-edit-bytes) :file-too-large
                       "edit target exceeds the supported atomic edit size"
                       {:path (str target) :bytes size :limit max-edit-bytes})
        original (decode-utf8 (Files/readAllBytes target) path)
        bom? (str/starts-with? original "\uFEFF")
        body (if bom? (subs original 1) original)
        ending (line-ending body)
        normalized (normalize-newlines body)
        changed (apply-exact-edits normalized edits path)
        restored (if (= ending "\r\n") (str/replace changed "\n" "\r\n") changed)
        final (str (when bom? "\uFEFF") restored)
        final-bytes (alength (.getBytes final StandardCharsets/UTF_8))]
    (value/check! (<= final-bytes max-edit-bytes) :file-too-large
                 "edited content exceeds the supported atomic edit size"
                 {:path (str target) :bytes final-bytes :limit max-edit-bytes})
    (check-cancelled! current-context)
    (let [permissions-preserved? (atomic-write-preserving-permissions! target final)]
      {:value (str target)
       :content (str "Successfully replaced " (count edits) " block(s) in " path ".")
       :details {:path (str target) :replacements (count edits)
                 :before-bytes (alength (.getBytes original StandardCharsets/UTF_8))
                 :after-bytes final-bytes
                 :permissions-preserved? permissions-preserved?}})))

(defn- executable-path [names]
  (let [path (or (System/getenv "PATH") "")
        separator (Pattern/quote File/pathSeparator)]
    (some (fn [dir]
            (some (fn [name]
                    (let [candidate (.toPath (io/file dir name))]
                      (when (and (Files/isRegularFile candidate (make-array LinkOption 0))
                                 (Files/isExecutable candidate))
                        (str candidate))))
                  names))
          (str/split path (re-pattern separator)))))

(defn- emit-decoded! [^java.nio.charset.CharsetDecoder decoder ^ByteBuffer bytes ^CharBuffer chars end? emit!]
  (loop []
    (let [result (.decode decoder bytes chars end?)]
      (.flip chars)
      (when (.hasRemaining chars)
        (emit! (.toString chars)))
      (.clear chars)
      (when (.isOverflow result)
        (recur))))
  (when end?
    (loop []
      (let [result (.flush decoder chars)]
        (.flip chars)
        (when (.hasRemaining chars)
          (emit! (.toString chars)))
        (.clear chars)
        (when (.isOverflow result)
          (recur))))))

(defn- stream-reader [^InputStream stream stream-name ^ByteArrayOutputStream output
                      truncated? current-context]
  (doto
    (Thread.
      (fn []
        (let [buffer (byte-array 8192)
              bytes (ByteBuffer/allocate 8196)
              chars (CharBuffer/allocate 8196)
              decoder (doto (.newDecoder StandardCharsets/UTF_8)
                        (.onMalformedInput CodingErrorAction/REPLACE)
                        (.onUnmappableCharacter CodingErrorAction/REPLACE))
              emit! #(progress! current-context
                                {:type :capability/output :stream stream-name
                                 :content %})]
          (loop []
            (let [n (try (.read stream buffer) (catch Throwable _ -1))]
              (if (pos? n)
                (do
                  (let [remaining (- max-process-stream-bytes (.size output))
                        retained (int (max 0 (min remaining n)))]
                    (when (pos? retained) (.write output buffer 0 retained))
                    (when (< retained n) (reset! truncated? true)))
                  (.put bytes buffer 0 n)
                  (.flip bytes)
                  (emit-decoded! decoder bytes chars false emit!)
                  (.compact bytes)
                  (recur))
                (do
                  (.flip bytes)
                  (emit-decoded! decoder bytes chars true emit!))))))))
    (.setDaemon true)
    (.setName (str "arrodes-" (name stream-name) "-reader"))
    (.start)))


(defn- timeout-millis [timeout]
  (when (some? timeout)
    (value/check! (and (number? timeout) (pos? timeout) (Double/isFinite (double timeout)))
                 :invalid-arguments "timeout must be a finite positive number of seconds"
                 {:timeout timeout})
    (let [millis (* (double timeout) 1000.0)]
      (value/check! (<= millis Integer/MAX_VALUE) :invalid-arguments
                   "timeout is too large" {:timeout timeout})
      (long millis))))

(defn- run-process [cwd current-context argv command timeout]
  (check-cancelled! current-context)
  (let [context (current-context)
        current-context (constantly context)
        dir (resolve-path cwd ".")
        _ (value/check! (Files/isDirectory dir (make-array LinkOption 0)) :invalid-cwd
                        (str "Working directory does not exist: " cwd) {:cwd cwd})
        timeout-ms (timeout-millis timeout)
        started (System/nanoTime)
        owned (try (owned-process/start! argv {:cwd (str dir)})
                   (catch Throwable error
                     (value/fail! :process-start-failed
                                  (str "Could not start " (first argv) ": " (ex-message error))
                                  {:program (first argv)
                                   :cause (ex-data error)})))
        process ^Process (:process owned)
        stdout (ByteArrayOutputStream.)
        stderr (ByteArrayOutputStream.)
        stdout-truncated? (atom false)
        stderr-truncated? (atom false)
        _ (try (.close (.getOutputStream process)) (catch Throwable _ nil))
        out-thread (stream-reader (.getInputStream process) :stdout stdout
                                  stdout-truncated? current-context)
        err-thread (stream-reader (.getErrorStream process) :stderr stderr
                                  stderr-truncated? current-context)]
    (try
      (loop []
        (cond
          (cancelled? current-context)
          (do (owned-process/stop! owned)
              (value/fail! :cancelled "Command execution was cancelled" {:command command}))

          (and timeout-ms (>= (/ (- (System/nanoTime) started) 1000000) timeout-ms))
          (do (owned-process/stop! owned)
              (value/fail! :timeout (str "Command timed out after " timeout " seconds")
                           {:command command :timeout timeout}))

          (.waitFor process 50 TimeUnit/MILLISECONDS) nil
          :else (recur)))
      (.join out-thread 1000)
      (.join err-thread 1000)
      (when (or (.isAlive out-thread) (.isAlive err-thread))
        (owned-process/stop! owned)
        (try (.close (.getInputStream process)) (catch Throwable _ nil))
        (try (.close (.getErrorStream process)) (catch Throwable _ nil))
        (.join out-thread 250)
        (.join err-thread 250))
      (let [out (.toString stdout StandardCharsets/UTF_8)
            err (.toString stderr StandardCharsets/UTF_8)
            out (if @stdout-truncated?
                  (str out "\n[stdout truncated at " max-process-stream-bytes " bytes]")
                  out)
            err (if @stderr-truncated?
                  (str err "\n[stderr truncated at " max-process-stream-bytes " bytes]")
                  err)
            exit (.exitValue process)
            combined (str (when-not (empty? out) out)
                          (when-not (or (empty? out) (empty? err) (str/ends-with? out "\n")) "\n")
                          (when-not (empty? err) err)
                          (when (and (empty? out) (empty? err)) "(no output)"))]
        {:value {:exit-code exit :stdout out :stderr err
                 :stdout-truncated? @stdout-truncated?
                 :stderr-truncated? @stderr-truncated?}
         :content combined
         :details {:exit-code exit :command command
                   :process-ownership (:kind owned)
                   :stdout-bytes (.size stdout) :stderr-bytes (.size stderr)
                   :stdout-truncated? @stdout-truncated?
                   :stderr-truncated? @stderr-truncated?}})
      (finally
        (when (owned-process/alive? owned)
          (owned-process/stop! owned))
        (try (.close (.getInputStream process)) (catch Throwable _ nil))
        (try (.close (.getErrorStream process)) (catch Throwable _ nil))))))

(defn- bash-tool [cwd current-context {:keys [command timeout]}]
  (value/check! (string? command) :invalid-arguments "bash command must be a string" {})
  (let [bash (or (when (Files/isExecutable (Paths/get "/bin/bash" (make-array String 0))) "/bin/bash")
                 (executable-path ["bash"]))]
    (value/check! bash :unsupported-system-function "bash is not available on this system" {})
    (run-process cwd current-context [bash "-lc" command] command timeout)))

(defn- powershell-tool [cwd current-context {:keys [command timeout]}]
  (value/check! (string? command) :invalid-arguments "powershell command must be a string" {})
  (let [program (executable-path (if (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows")
                                  ["pwsh.exe" "powershell.exe"] ["pwsh" "powershell"]))]
    (value/check! program :unsupported-system-function
                 "PowerShell is not available on this system" {:programs ["pwsh" "powershell"]})
    (run-process cwd current-context
                 [program "-NoLogo" "-NoProfile" "-NonInteractive" "-Command"
                  (str "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}; " command)]
                 command timeout)))

(defn- ignored-directory? [^Path path]
  (contains? ignored-directory-names (str (.getFileName path))))

(defn- walk-files [^Path root current-context visitor]
  (let [files (volatile! [])]
    (Files/walkFileTree
      root
      (proxy [SimpleFileVisitor] []
        (preVisitDirectory [dir attrs]
          (check-cancelled! current-context)
          (if (and (not= dir root) (ignored-directory? dir))
            FileVisitResult/SKIP_SUBTREE FileVisitResult/CONTINUE))
        (visitFile [file attrs]
          (check-cancelled! current-context)
          (when (.isRegularFile attrs)
            (vswap! files conj file)
            (value/check! (<= (count @files) max-traversed-files) :traversal-limit
                          (str "Traversal exceeded " max-traversed-files
                               " files; narrow the search path")
                          {:path (str root) :limit max-traversed-files}))
          FileVisitResult/CONTINUE)))
    (let [ordered (sort-by (fn [^Path file]
                             (let [relative (str (.relativize root file))]
                               [(str/lower-case relative) relative]))
                           @files)]
      (loop [remaining (seq ordered) visited 0]
        (if-let [file (first remaining)]
          (do
            (check-cancelled! current-context)
            (if (= :terminate (visitor file nil))
              (inc visited)
              (recur (next remaining) (inc visited))))
          visited)))))

(defn- glob-matchers [pattern]
  (try
    (let [filesystem (.getFileSystem (Paths/get "." (make-array String 0)))
          segments (str/split (str/replace pattern "\\" "/") #"/" -1)]
      {:whole (.getPathMatcher filesystem (str "glob:" pattern))
       :segments (mapv #(when-not (= "**" %)
                          (.getPathMatcher filesystem (str "glob:" %)))
                       segments)})
    (catch Throwable error
      (value/fail! :invalid-pattern (str "Invalid glob pattern: " (ex-message error)) {:pattern pattern}))))

(defn- matches-glob? [{:keys [whole segments]} ^Path relative]
  (or (.matches whole relative)
      (let [parts (str/split (str/replace (str relative) File/separator "/") #"/" -1)
            cache (atom {})]
        (letfn [(match? [pattern-index path-index]
                  (if-let [cached (find @cache [pattern-index path-index])]
                    (val cached)
                    (let [matched
                          (cond
                            (= pattern-index (count segments)) (= path-index (count parts))
                            (nil? (nth segments pattern-index))
                            (or (match? (inc pattern-index) path-index)
                                (and (< path-index (count parts))
                                     (match? pattern-index (inc path-index))))
                            (= path-index (count parts)) false
                            :else
                            (and (.matches ^java.nio.file.PathMatcher
                                           (nth segments pattern-index)
                                           (Paths/get (nth parts path-index)
                                                      (make-array String 0)))
                                 (match? (inc pattern-index) (inc path-index))))]
                      (swap! cache assoc [pattern-index path-index] matched)
                      matched)))]
          (match? 0 0)))))

(defn- search-files [cwd path]
  (let [root (resolve-path cwd (or path "."))]
    (value/check! (Files/exists root (make-array LinkOption 0)) :not-found
                 (str "Path does not exist: " (or path ".")) {:path (str root)})
    root))

(defn- find-tool [cwd current-context {:keys [pattern path limit]}]
  (value/check! (and (string? pattern) (not (empty? pattern))) :invalid-arguments
               "find pattern must be a non-empty glob" {})
  (let [root (search-files cwd path)
        limit (validate-limit limit max-find-results max-find-results :limit)
        matchers (glob-matchers pattern)
        results (volatile! [])
        stopped? (volatile! false)
        accept (fn [^Path file]
                 (when (< (count @results) limit)
                   (let [relative (if (Files/isDirectory root (make-array LinkOption 0))
                                    (.relativize root file) (.getFileName file))]
                     (when (matches-glob? matchers relative)
                       (vswap! results conj
                               (str/replace (str relative) File/separator "/")))))
                 (when (>= (count @results) limit)
                   (vreset! stopped? true)
                   :terminate))]
    (if (Files/isRegularFile root (make-array LinkOption 0))
      (accept root)
      (walk-files root current-context (fn [file _] (accept file))))
    (let [ordered (vec (sort-by (juxt str/lower-case identity) @results))]
      {:value ordered
       :content (if (seq ordered)
                  (str/join "\n" ordered)
                  "No files found matching pattern")
       :details {:path (str root) :count (count ordered)
                 :result-limit-reached? @stopped?}})))

(defn- compile-pattern [pattern literal? ignore-case?]
  (try
    (Pattern/compile (if literal? (Pattern/quote pattern) pattern)
                     (if ignore-case?
                       (bit-or Pattern/CASE_INSENSITIVE Pattern/UNICODE_CASE)
                       0))
    (catch PatternSyntaxException error
      (value/fail! :invalid-pattern
                  (str "Invalid regular expression: " (.getDescription error))
                  {:pattern pattern :index (.getIndex error)}))))

(defn- searchable-line-count? [^bytes bytes]
  (loop [index 0 newlines 0]
    (cond
      (> newlines max-search-file-lines) false
      (= index (alength bytes)) true
      :else (recur (inc index)
                   (if (= 10 (aget bytes index)) (inc newlines) newlines)))))

(defn- text-file-lines [^Path file]
  (when (<= (Files/size file) max-search-file-bytes)
    (let [bytes (Files/readAllBytes file)]
      (when (and (searchable-line-count? bytes)
                 (not (some zero? (take 8192 bytes))))
        (try
          (split-lines (decode-utf8 bytes (str file)))
          (catch clojure.lang.ExceptionInfo _ nil))))))

(defn- clipped-line [line]
  (if (> (count line) max-line-chars)
    (str (subs line 0 max-line-chars) "…") line))

(defn- grep-file [^Path root ^Path file ^Pattern pattern context remaining]
  (when-let [lines (text-file-lines file)]
    (let [relative (if (Files/isDirectory root (make-array LinkOption 0))
                     (str (.relativize root file)) (str (.getFileName file)))]
      (loop [index 0 blocks []]
        (if (or (>= index (count lines)) (>= (count blocks) remaining))
          blocks
          (if (.find (.matcher pattern (nth lines index)))
            (let [start (max 0 (- index context))
                  end (min (count lines) (+ index context 1))
                  block (mapv (fn [line-index]
                                (str (str/replace relative File/separator "/")
                                     (if (= line-index index) ":" "-") (inc line-index)
                                     (if (= line-index index) ": " "- ")
                                     (clipped-line (nth lines line-index))))
                              (range start end))]
              (recur (inc index) (conj blocks block)))
            (recur (inc index) blocks)))))))

(defn- grep-tool [cwd current-context {:keys [pattern path glob ignoreCase literal context limit]}]
  (value/check! (and (string? pattern) (not (empty? pattern))) :invalid-arguments
               "grep pattern must be a non-empty string" {})
  (let [root (search-files cwd path)
        limit (validate-limit limit max-search-results max-search-results :limit)
        context (or context 0)
        _ (value/check! (and (integer? context) (<= 0 context 20)) :invalid-arguments
                       "grep context must be an integer from 0 through 20" {:context context})
        matchers (when glob (glob-matchers glob))
        compiled (compile-pattern pattern literal ignoreCase)
        matches (volatile! [])
        hit-limit? (volatile! false)
        accept (fn [^Path file]
                 (when (< (count @matches) limit)
                   (let [relative (if (Files/isDirectory root (make-array LinkOption 0))
                                    (.relativize root file) (.getFileName file))]
                     (when (or (nil? matchers) (matches-glob? matchers relative))
                       (let [blocks (grep-file root file compiled context
                                               (- limit (count @matches)))]
                         (vswap! matches into blocks)))))
                 (when (>= (count @matches) limit)
                   (vreset! hit-limit? true)
                   :terminate))]
    (if (Files/isRegularFile root (make-array LinkOption 0))
      (accept root)
      (walk-files root current-context (fn [file _] (accept file))))
    (let [blocks @matches
          output (if (seq blocks)
                   (str/join "\n--\n" (map #(str/join "\n" %) blocks))
                   "No matches found")]
      {:value blocks :content output
       :details {:path (str root) :matches (count blocks)
                 :match-limit-reached? @hit-limit?}})))

(defn- ls-tool [cwd current-context {:keys [path limit]}]
  (check-cancelled! current-context)
  (let [dir (resolve-path cwd (or path "."))
        _ (value/check! (Files/exists dir (make-array LinkOption 0)) :not-found
                       (str "Path does not exist: " (or path ".")) {:path (str dir)})
        _ (value/check! (Files/isDirectory dir (make-array LinkOption 0)) :not-a-directory
                       (str "Not a directory: " (or path ".")) {:path (str dir)})
        limit (validate-limit limit max-list-results max-list-results :limit)]
    (with-open [stream (Files/list dir)]
      (let [iterator (.iterator stream)
            all (loop [entries []]
                  (check-cancelled! current-context)
                  (if (.hasNext iterator)
                    (let [entry ^Path (.next iterator)
                          entries (conj entries
                                        (str (.getFileName entry)
                                             (when (Files/isDirectory entry (make-array LinkOption 0))
                                               "/")))]
                      (value/check! (<= (count entries) max-directory-entries)
                                   :traversal-limit
                                   (str "Directory contains more than "
                                        max-directory-entries " entries")
                                   {:path (str dir) :limit max-directory-entries})
                      (recur entries))
                    entries))
            all (vec (sort-by (juxt str/lower-case identity) all))
            shown (subvec all 0 (min limit (count all)))]
        (check-cancelled! current-context)
        {:value shown
         :content (if (seq shown) (str/join "\n" shown) "(empty directory)")
         :details {:path (str dir) :entries (count shown)
                   :entry-limit-reached? (> (count all) limit)}}))))

(defn descriptors
  "Returns all built-in coding capability descriptors. current-context returns
  the dynamically bound invocation context from arrodes.capabilities."
  [{:keys [cwd current-context put-artifact!]}]
  (let [path-key (fn [args] (str (mutation-path cwd (:path args))))
        shell-schema (schema-object
                       {:command (string-property "Shell command to execute")
                        :timeout {:type "number" :exclusiveMinimum 0
                                  :description "Optional timeout in seconds"}}
                       ["command"])]
    [{:name "read" :owner "arrodes.builtin" :description
      "Read a UTF-8 text file with 1-based paging, or return supported images as canonical image parts."
      :parameters (schema-object
                    {:path (string-property "File path, relative to the session cwd or absolute")
                     :offset (positive-integer-property "First line to read, 1-based")
                     :limit (bounded-positive-integer-property
                             100000 "Maximum lines to return")}
                    ["path"])
      :execution :parallel :permission :read :fn #(read-tool cwd current-context put-artifact! %)}
     {:name "write" :owner "arrodes.builtin" :description
      "Atomically create or completely overwrite a UTF-8 text file, creating parent directories."
      :parameters (schema-object
                    {:path (string-property "File path, relative to the session cwd or absolute")
                     :content {:type "string" :maxLength max-edit-bytes
                               :description "Complete UTF-8 file content"}}
                    ["path" "content"])
      :execution :parallel :permission :write :mutation-key path-key
      :fn #(write-tool cwd current-context %)}
     {:name "edit" :owner "arrodes.builtin" :description
      "Atomically edit one file. Every oldText must match exactly once in the original file and replacements may not overlap."
      :parameters (schema-object
                    {:path (string-property "File path, relative to the session cwd or absolute")
                     :edits {:type "array" :minItems 1 :maxItems max-exact-edits
                             :items (schema-object
                                      {:oldText (string-property "Unique exact text to replace")
                                       :newText (string-property "Replacement text")}
                                      ["oldText" "newText"])}}
                    ["path" "edits"])
      :execution :parallel :permission :write :mutation-key path-key
      :fn #(edit-tool cwd current-context %)}
     {:name "bash" :owner "arrodes.builtin" :description
      "Execute a Bash command in the trusted local session cwd. Captures bounded stdout and stderr, reports truncation, and cancels the process tree on timeout or cancellation."
      :parameters shell-schema :execution :parallel :permission :execute
      :fn #(bash-tool cwd current-context %)}
     {:name "grep" :owner "arrodes.builtin" :description
      "Search UTF-8 files using a Java regular expression or literal text. Excludes generated and VCS directories by default."
      :parameters (schema-object
                    {:pattern (string-property "Regular expression or literal search text")
                     :path (string-property "File or directory to search; defaults to cwd")
                     :glob (string-property "Optional glob applied to relative file paths")
                     :ignoreCase {:type "boolean" :description "Case-insensitive matching"}
                     :literal {:type "boolean" :description "Treat pattern literally"}
                     :context {:type "integer" :minimum 0 :maximum 20
                               :description "Context lines around matches"}
                     :limit (bounded-positive-integer-property
                             max-search-results "Maximum matching lines")}
                    ["pattern"])
      :execution :parallel :permission :read :fn #(grep-tool cwd current-context %)}
     {:name "find" :owner "arrodes.builtin" :description
      "Find files by glob under a directory. Excludes generated and VCS directories by default."
      :parameters (schema-object
                    {:pattern (string-property "Glob such as *.clj or src/**/*.clj")
                     :path (string-property "Directory to search; defaults to cwd")
                     :limit (bounded-positive-integer-property
                             max-find-results "Maximum results")}
                    ["pattern"])
      :execution :parallel :permission :read :fn #(find-tool cwd current-context %)}
     {:name "ls" :owner "arrodes.builtin" :description
      "List a directory alphabetically, including dotfiles and '/' suffixes for directories."
      :parameters (schema-object
                    {:path (string-property "Directory to list; defaults to cwd")
                     :limit (bounded-positive-integer-property
                             max-list-results "Maximum entries")}
                    [])
      :execution :parallel :permission :read :fn #(ls-tool cwd current-context %)}
     {:name "powershell" :owner "arrodes.builtin" :description
      "Execute PowerShell in the trusted local session cwd when available. Captures bounded stdout and stderr, reports truncation, and cleans up the process tree."
      :parameters shell-schema :execution :parallel :permission :execute
      :fn #(powershell-tool cwd current-context %)}]))
