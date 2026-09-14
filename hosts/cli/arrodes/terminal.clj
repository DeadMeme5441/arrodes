(ns arrodes.terminal
  "Interactive JLine terminal controller. Domain mutations are delegated to arrodes.commands."
  (:refer-clojure :exclude [run!])
  (:require [arrodes.commands :as commands]
            [arrodes.render :as render]
            [arrodes.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.awt Graphics2D Image Toolkit)
           (java.awt.datatransfer DataFlavor StringSelection)
           (java.awt.image BufferedImage)
           (java.io BufferedReader ByteArrayOutputStream InputStreamReader)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.nio.file Files LinkOption Path Paths StandardOpenOption)
           (java.util Base64)
           (java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit)
           (javax.imageio ImageIO)
           (org.jline.keymap KeyMap)
           (org.jline.reader Binding Candidate Completer EndOfFileException LineReader LineReaderBuilder ParsedLine Reference UserInterruptException Widget)
           (org.jline.reader.impl DefaultParser)
           (org.jline.terminal Terminal TerminalBuilder)))

(def ^:private terminal-operation-statuses #{:completed :failed :cancelled :interrupted})
(def ^:private image-extensions
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif"
   "webp" "image/webp" "bmp" "image/bmp" "tif" "image/tiff" "tiff" "image/tiff"})
(def ^:private builtin-commands
  ["/new" "/resume" "/session" "/tree" "/rewind" "/fork" "/clone" "/name" "/label"
   "/continue" "/compact" "/cancel" "/steer" "/follow-up" "/queue" "/delete"
   "/model" "/thinking" "/tools" "/settings" "/trust" "/reload" "/login" "/logout"
   "/copy" "/paste" "/editor" "/export" "/import" "/share" "/eval" "/usage"
   "/help" "/quit"])

(defn- error! [code message & [data]]
  (throw (ex-info message (merge {:error/code code} data))))

(defn- dispatch! [rt method params]
  (commands/dispatch! rt method (or params {})))

(defn- kw [value]
  (cond (keyword? value) value (string? value) (keyword value) :else value))

(defn- normalize-config [config]
  (cond-> config
    (:provider config) (update :provider kw)
    (:thinking config) (update :thinking kw)
    (string? (:tools config)) (update :tools #(if (= "all" %) :all [%]))))

(defn- shell-words
  "Small argv lexer used for slash commands and EDITOR; supports quotes and backslash escapes."
  [s]
  (loop [chars (seq (or s "")) word (StringBuilder.) words [] quote nil escaped? false]
    (if-let [ch (first chars)]
      (cond
        escaped? (do (.append word ^char ch) (recur (next chars) word words quote false))
        (= ch \\) (recur (next chars) word words quote true)
        quote (if (= ch quote)
                (recur (next chars) word words nil false)
                (do (.append word ^char ch) (recur (next chars) word words quote false)))
        (or (= ch \') (= ch \")) (recur (next chars) word words ch false)
        (Character/isWhitespace ^char ch)
        (if (pos? (.length word))
          (recur (next chars) (StringBuilder.) (conj words (.toString word)) nil false)
          (recur (next chars) word words nil false))
        :else (do (.append word ^char ch) (recur (next chars) word words nil false)))
      (do
        (when escaped? (.append word \\))
        (when quote (error! "terminal.invalid-input" "Unclosed quote"))
        (cond-> words (pos? (.length word)) (conj (.toString word)))))))

(defn- split-command [line]
  (let [[head raw] (str/split line #"\s+" 2)
        command (some-> head (subs 1) str/lower-case)
        raw (or raw "")
        raw-input? (or (contains? #{"eval" "name" "compact" "steer" "follow-up" "editor" "settings"} command)
                       (not (some #{(str "/" command)} builtin-commands)))]
    {:command command
     :args (if raw-input? [] (vec (shell-words raw)))
     :raw raw}))

(defn- fuzzy-match? [query value]
  (let [query (str/lower-case (str query))
        value (str/lower-case (str value))]
    (loop [qs (seq query) vs (seq value)]
      (cond
        (nil? qs) true
        (nil? vs) false
        (= (first qs) (first vs)) (recur (next qs) (next vs))
        :else (recur qs (next vs))))))

(defn- candidate-items [state word]
  (let [selector-items (:selector-items @state)]
    (if (seq selector-items)
      (->> selector-items
           (filter #(fuzzy-match? word (:label %)))
           (map #(Candidate. ^String (:label %))))
      (cond
        (str/starts-with? word "/")
        (->> (concat builtin-commands (map #(str "/" %) (:extension-commands @state)))
             distinct sort (filter #(str/starts-with? % word)) (map #(Candidate. ^String %)))

        (str/starts-with? word "@")
        (let [cwd ^Path (:cwd @state)
              raw (subs word 1)
              raw-path (if (str/blank? raw) "." raw)
              candidate-path (.normalize (.resolve cwd raw-path))
              dir (if (or (str/ends-with? raw "/") (Files/isDirectory candidate-path (make-array LinkOption 0)))
                    candidate-path
                    (or (.getParent candidate-path) cwd))
              prefix (if (or (str/ends-with? raw "/") (str/blank? raw)) "" (str (.getFileName candidate-path)))]
          (when (Files/isDirectory dir (make-array LinkOption 0))
            (with-open [stream (Files/list dir)]
              (->> (.iterator stream) iterator-seq
                   (remove #(= ".git" (str (.getFileName ^Path %))))
                   (filter #(str/starts-with? (str (.getFileName ^Path %)) prefix))
                   (sort-by #(str/lower-case (str (.getFileName ^Path %))))
                   (take 200)
                   (mapv (fn [^Path path]
                           (let [relative (str (.relativize cwd path))
                                 suffix (if (Files/isDirectory path (make-array LinkOption 0)) "/" "")]
                             (Candidate. (str "@" relative suffix)))))))))
        :else []))))

(defn- completer [state]
  (reify Completer
    (complete [_ _ line candidates]
      (doseq [candidate (candidate-items state (.word ^ParsedLine line))]
        (.add ^java.util.List candidates candidate)))))

(defn- tty? [^Terminal terminal options]
  (and (not (:plain options))
       (not= "dumb" (.getType terminal))
       (some? (System/console))))

(defn- print-above! [state text]
  (when-not (str/blank? (str text))
    (locking (:output-lock @state)
      (if-let [^LineReader reader (:reader @state)]
        (if (:tty? @state)
          (.printAbove reader (str text))
          (let [^Terminal terminal (:terminal @state)]
            (.println (.writer terminal) (render/strip-ansi text))
            (.flush (.writer terminal))))
        (println (render/strip-ansi text))))))

(defn- print-lines! [state lines]
  (doseq [line lines] (print-above! state line)))

(defn- bytes->base64 [^bytes bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn- extension [^Path path]
  (let [name (str/lower-case (str (.getFileName path)))
        idx (.lastIndexOf name ".")]
    (when (pos? idx) (subs name (inc idx)))))

(defn- image-mime [^Path path]
  (or (get image-extensions (extension path))
      (let [mime (Files/probeContentType path)]
        (when (and mime (str/starts-with? mime "image/")) mime))))

(defn- strict-utf8 [^bytes bytes]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch CharacterCodingException _ nil)))

(defn- resolve-user-path [state path]
  (str (.normalize (.resolve ^Path (:cwd @state) (str path)))))

(defn- read-reference [^Path cwd raw]
  (let [path (.normalize (.resolve cwd raw))]
    (when-not (Files/isRegularFile path (make-array LinkOption 0))
      (error! "terminal.reference-not-found" (str "Referenced file does not exist: " raw) {:path raw}))
    (let [size (Files/size path)]
      (when (> size (* 20 1024 1024))
        (error! "terminal.reference-too-large" (str "Referenced file exceeds 20 MiB: " raw) {:path raw :bytes size}))
      (let [bytes (Files/readAllBytes path)]
        (if-let [mime (image-mime path)]
          (let [data (bytes->base64 bytes)]
            {:kind :image
             :part {:part/type :image :image/mime-type mime :image/data data
                    :image/url (str "data:" mime ";base64," data)}
             :label raw})
          (if-let [text (strict-utf8 bytes)]
            {:kind :text :text text :label raw}
            (error! "terminal.unsupported-reference"
                    (str "Only UTF-8 text and supported image files can be referenced: " raw)
                    {:path raw})))))))

(def ^:private reference-pattern
  #"(?m)(^|\s)@(?:\"([^\"]+)\"|'([^']+)'|([^\s]+))")

(defn expand-references
  "Expands @path references. Text is embedded with a source boundary; images become canonical parts."
  ([cwd input] (expand-references cwd input []))
  ([cwd input initial-parts]
   (let [cwd (if (instance? Path cwd) cwd (Paths/get (str cwd) (make-array String 0)))
         matcher (re-matcher reference-pattern (or input ""))
         output (StringBuffer.)
         parts (atom (vec initial-parts))]
     (while (.find matcher)
       (let [leading (or (.group matcher 1) "")
             raw (or (.group matcher 2) (.group matcher 3) (.group matcher 4))
             ref (read-reference cwd raw)
             replacement (if (= :image (:kind ref))
                           (do (swap! parts conj (:part ref))
                               (str leading "[attached image " raw "]"))
                           (str leading "<file path=" (pr-str raw) ">\n" (:text ref) "\n</file>"))]
         (.appendReplacement matcher output (java.util.regex.Matcher/quoteReplacement replacement))))
     (.appendTail matcher output)
     (let [text (.toString output)
           all-parts (cond-> [] (not (str/blank? text)) (conj {:part/type :text :text text}) true (into @parts))]
       (if (seq @parts) all-parts text)))))

(defn- clipboard-image-part []
  (try
    (let [clipboard (.getSystemClipboard (Toolkit/getDefaultToolkit))]
      (when (.isDataFlavorAvailable clipboard DataFlavor/imageFlavor)
        (let [^Image image (.getData clipboard DataFlavor/imageFlavor)
              width (.getWidth image nil)
              height (.getHeight image nil)]
          (when (or (not (pos? width)) (not (pos? height)))
            (error! "terminal.clipboard-unavailable" "Clipboard image has invalid dimensions"))
          (let [buffer (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
                ^Graphics2D graphics (.createGraphics buffer)
                bytes (ByteArrayOutputStream.)]
            (try (.drawImage graphics image 0 0 nil) (finally (.dispose graphics)))
            (ImageIO/write buffer "png" bytes)
            (let [data (bytes->base64 (.toByteArray bytes))]
              {:part/type :image :image/mime-type "image/png" :image/data data
               :image/url (str "data:image/png;base64," data)})))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Throwable e
      (error! "terminal.clipboard-unavailable" (str "Image clipboard is unavailable: " (ex-message e))))))

(defn- clipboard-text []
  (try
    (let [clipboard (.getSystemClipboard (Toolkit/getDefaultToolkit))]
      (when (.isDataFlavorAvailable clipboard DataFlavor/stringFlavor)
        (str (.getData clipboard DataFlavor/stringFlavor))))
    (catch Throwable e
      (error! "terminal.clipboard-unavailable" (str "Text clipboard is unavailable: " (ex-message e))))))

(defn- copy-text! [text]
  (when (nil? text) (error! "terminal.nothing-to-copy" "There is no text to copy"))
  (try
    (.setContents (.getSystemClipboard (Toolkit/getDefaultToolkit)) (StringSelection. (str text)) nil)
    {:copied? true :characters (count (str text))}
    (catch Throwable e
      (error! "terminal.clipboard-unavailable" (str "Clipboard write failed: " (ex-message e))))))

(defn- editor-command [state]
  (or (get-in @state [:settings :terminal :editor])
      (get-in @state [:settings :editor])
      (System/getenv "VISUAL") (System/getenv "EDITOR")))

(defn- external-editor! [state content]
  (let [command (editor-command state)]
    (when (str/blank? command)
      (error! "terminal.editor-unconfigured" "Set $VISUAL, $EDITOR, or :terminal/:editor in settings"))
    (let [dir (Files/createTempDirectory "arrodes-editor-" (make-array java.nio.file.attribute.FileAttribute 0))
          path (.resolve dir "prompt.txt")
          argv (shell-words command)
          ^Terminal terminal (:terminal @state)
          before (.getAttributes terminal)]
      (try
        (when-let [original (:original-attributes @state)] (.setAttributes terminal original))
        (Files/writeString path (or content "") StandardCharsets/UTF_8
                           (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/TRUNCATE_EXISTING]))
        (let [process (-> (ProcessBuilder. ^java.util.List (vec (concat argv [(str path)])))
                          (.inheritIO)
                          (.start))
              status (.waitFor process)]
          (when-not (zero? status)
            (error! "terminal.editor-failed" (str "External editor exited with status " status)))
          (-> (Files/readString path StandardCharsets/UTF_8)
              (str/replace-first #"^\uFEFF" "")
              (str/replace #"\r?\n$" "")))
        (finally
          (try (.setAttributes terminal before) (catch Throwable _))
          (try (Files/deleteIfExists path) (catch Throwable _))
          (try (Files/deleteIfExists dir) (catch Throwable _)))))))

(defn- normalize-item [item]
  (cond
    (map? item) {:label (render/safe-text
                          (or (:label item) (:name item) (:title item) (:id item) (:value item)))
                 :value (if (contains? item :value) (:value item) item)
                 :description (some-> (:description item) render/safe-text)}
    :else {:label (render/safe-text item) :value item}))

(declare select! handle-ui! refresh-presentation! command-line! install-bindings!)

(defn- read-input! [state prompt & [mask]]
  (if (:tty? @state)
    (let [^LineReader reader (:reader @state)]
      (if mask (.readLine reader prompt (Character/valueOf ^char mask)) (.readLine reader prompt)))
    (do
      (print (render/strip-ansi prompt)) (flush)
      (.readLine ^BufferedReader (:plain-reader @state)))))

(defn- select! [state title items & [{:keys [allow-cancel? default] :or {allow-cancel? true}}]]
  (let [items (mapv normalize-item items)]
    (when (empty? items) (error! "terminal.empty-selection" (str title ": no choices are available")))
    (if (:tty? @state)
      (do
        (swap! state assoc :selector-items items)
        (try
          (print-lines! state [(render/paint (:theme @state) :accent title (:colors? @state))
                               (render/paint (:theme @state) :muted "Type to search; Tab/Shift-Tab and arrow keys navigate completions; Enter selects." (:colors? @state))])
          (let [answer (or (read-input! state "select> ") "")
                exact (some #(when (= (str/lower-case (:label %)) (str/lower-case answer)) %) items)
                n (try (Long/parseLong answer) (catch Throwable _ nil))
                chosen (or exact
                           (when (and n (<= 1 n (count items))) (nth items (dec n)))
                           (when-not (str/blank? answer)
                             (first (filter #(fuzzy-match? answer (:label %)) items)))
                           (when (str/blank? answer)
                             (some #(when (= default (:value %)) %) items)))]
            (if chosen (:value chosen)
                (if (and allow-cancel? (str/blank? answer)) nil
                    (if (str/blank? answer)
                      (:value (first items))
                      (error! "terminal.invalid-selection" (str "No choice matches: " answer))))))
          (finally (swap! state assoc :selector-items []))))
      (do
        (println title)
        (doseq [[idx item] (map-indexed vector items)]
          (println (format "  %d) %s%s" (inc idx) (:label item)
                           (if-let [d (:description item)] (str " — " d) ""))))
        (let [answer (or (read-input! state "select number or search> ") "")
              n (try (Long/parseLong (or answer "")) (catch Throwable _ nil))
              chosen (or (when (and n (<= 1 n (count items))) (nth items (dec n)))
                         (when-not (str/blank? answer)
                           (first (filter #(fuzzy-match? answer (:label %)) items)))
                         (when (str/blank? answer)
                           (some #(when (= default (:value %)) %) items)))]
          (if chosen (:value chosen)
              (if allow-cancel? nil (:value (first items)))))))))

(defn- confirm! [state prompt default]
  (let [suffix (if default " [Y/n] " " [y/N] ")
        answer (some-> (read-input! state (str prompt suffix)) str/trim str/lower-case)]
    (cond (str/blank? answer) default
          (#{"y" "yes"} answer) true
          (#{"n" "no"} answer) false
          :else (error! "terminal.invalid-confirmation" "Please answer yes or no"))))

(defn- handle-ui! [state request]
  (let [kind (kw (:kind request))]
    (case kind
      :notify (do (print-above! state (render/safe-text
                                        (or (:message request) (:content request) (:text request)
                                            (:event/message request) (pr-str (dissoc request :kind))))) nil)
      :render (do (print-lines! state (if (sequential? (:content request)) (:content request) [(:content request)]))
                  nil)
      :input (read-input! state (str (or (:prompt request) (:message request) "input") "> ")
                          (when (:secret? request) \*))
      :confirm (confirm! state (or (:prompt request) (:message request) "Confirm?")
                         (boolean (:default request)))
      :select (select! state (or (:title request) (:prompt request) "Select")
                       (or (:items request) (:options request))
                       {:allow-cancel? (not= false (:allow-cancel? request)) :default (:default request)})
      :editor (external-editor! state (or (:content request) (:initial request) ""))
      :widget (let [id (or (:id request) (error! "terminal.invalid-widget" "Widget request requires :id"))]
                (if (:remove? request)
                  (swap! state update :widgets dissoc id)
                  (swap! state assoc-in [:widgets id]
                         {:id id :placement (kw (or (:placement request) :status))
                          :content (or (:content request) "") :order (or (:order request) 0)}))
                {:widget-id id :visible? (not (:remove? request))})
      :set-widget (handle-ui! state (assoc request :kind :widget))
      :renderer (let [id (or (:id request) (:event-type request) (:tool request)
                             (error! "terminal.invalid-renderer" "Renderer request requires an id"))]
                  (if (:remove? request)
                    (swap! state update :renderers dissoc id)
                    (let [f (:render request)]
                      (when-not (ifn? f) (error! "terminal.invalid-renderer" "Renderer :render must be callable"))
                      (swap! state assoc-in [:renderers id] f)))
                  {:renderer-id id :active? (not (:remove? request))})
      :capability (error! "terminal.unsupported-ui" "Terminal cannot fulfill reverse host capability requests")
      (error! "terminal.unsupported-ui" (str "Unsupported terminal UI request: " kind) {:kind kind}))))

(defn- install-ui! [rt state]
  (let [callback (fn [request]
                   (if (= (Thread/currentThread) (:main-thread @state))
                     (handle-ui! state request)
                     (let [future (CompletableFuture.)]
                       (.put ^LinkedBlockingQueue (:ui-queue @state) {:request request :future future})
                       (when-let [^LineReader reader (:reader @state)]
                         (try (.runMacro reader "\n") (catch Throwable _)))
                       (try
                         (.get future 30 TimeUnit/MINUTES)
                         (catch Throwable e
                           (error! "terminal.ui-failed" (or (ex-message e) "Terminal UI request failed")))))))]
    (runtime/set-ui! rt callback)
    callback))

(defn- drain-ui! [state]
  (loop [handled? false]
    (if-let [{:keys [request ^CompletableFuture future]} (.poll ^LinkedBlockingQueue (:ui-queue @state))]
      (do
        (try (.complete future (handle-ui! state request))
             (catch Throwable e (.completeExceptionally future e)))
        (recur true))
      handled?)))

(defn- widgets-line [state placement]
  (->> (:widgets @state)
       vals (filter #(= placement (:placement %))) (sort-by (juxt :order :id))
       (map (comp render/safe-text :content)) (remove str/blank?) (str/join " · ")))

(defn- current-snapshot [state]
  (get (dispatch! (:runtime @state) "session.inspect" {:session-id (:session-id @state)}) :session))

(defn- current-state [state]
  (dispatch! (:runtime @state) "session.state" {:session-id (:session-id @state)}))

(defn- refresh-status! [state]
  (try
    (let [session (current-snapshot state)
          runtime-state (current-state state)]
      (swap! state assoc :session session :runtime-state runtime-state
             :operation-id (:operation-id runtime-state)))
    (catch Throwable _))
  nil)

(defn- prompt [state]
  (refresh-status! state)
  (let [theme (:theme @state)
        colors? (:colors? @state)
        header (widgets-line state :header)
        footer (widgets-line state :footer)
        status (render/session-status (:session @state) (:runtime-state @state))
        widget (widgets-line state :status)]
    (when (seq header) (print-above! state header))
    (when (seq footer) (print-above! state footer))
    (str (render/paint theme :muted (str status (when (seq widget) (str " · " widget))) colors?) "\n"
         (render/paint theme :accent "arrodes> " colors?))))

(defn- active-operation? [state]
  (when-let [oid (:operation-id @state)]
    (try
      (let [operation (dispatch! (:runtime @state) "operation.inspect" {:operation-id oid})]
        (if (terminal-operation-statuses (kw (:status operation)))
          (do (swap! state assoc :operation-id nil) false)
          true))
      (catch Throwable _
        (swap! state assoc :operation-id nil)
        false))))

(defn- remember-receipt! [state receipt]
  (when-let [oid (or (:id receipt) (:operation-id receipt))]
    (swap! state assoc :operation-id oid))
  receipt)

(defn- render-current-event! [state event]
  (let [type (or (:type event) (:event/type event))
        data (or (:data event) {})
        render-type (or (:event/type data) (:type data) type)
        tool (or (:name data) (:tool/name data) (:tool-call/name data))
        renderer (or (get (:renderers @state) render-type) (get (:renderers @state) tool))]
    (when-let [message (or (:message data) (when (:message/role data) data))]
      (when (= :assistant (:message/role message))
        (swap! state assoc :last-answer (render/content-text (:message/content message)))))
    (if renderer
      (try
        (let [rendered (renderer event)]
          (print-lines! state (if (sequential? rendered) rendered [rendered])))
        (catch Throwable e (print-above! state (render/format-error (:theme @state) e (:colors? @state)))))
      (if-let [{:keys [channel text]} (render/stream-fragment event)]
        (let [now (System/currentTimeMillis)
              prior (get-in @state [:streams channel] {:buffer "" :last-flush now})
              buffer (str (:buffer prior) text)
              newline (.lastIndexOf buffer "\n")
              flush? (or (>= newline 0) (> (count buffer) 160) (> (- now (:last-flush prior)) 300))
              [shown remaining] (if flush?
                                  (if (>= newline 0)
                                    [(subs buffer 0 newline) (subs buffer (inc newline))]
                                    [buffer ""])
                                  [nil buffer])]
          (swap! state assoc-in [:streams channel] {:buffer remaining :last-flush (if flush? now (:last-flush prior))})
          (when (seq shown)
            (print-above! state
                          (str (render/paint (:theme @state) (if (= channel :reasoning) :reasoning :assistant)
                                             (if (= channel :reasoning) "thinking> " "assistant> ") (:colors? @state))
                               shown))))
        (let [type-key (kw (or type :event))
              operation-terminal? (and (= "operation" (namespace type-key))
                                       (terminal-operation-statuses (keyword (name type-key)))
                                       (= (:operation-id @state) (:operation-id event)))
              stream-end? (= :stream/end render-type)]
          (when (or operation-terminal? stream-end?)
            (doseq [[channel {:keys [buffer]}] (:streams @state)]
              (when (seq buffer)
                (print-above! state (str (if (= channel :reasoning) "thinking> " "assistant> ") buffer))))
            (swap! state assoc :streams {})
            (when-let [usage (:usage data)] (swap! state assoc :usage usage)))
          (when operation-terminal?
            (swap! state assoc :operation-id nil))
          (print-lines! state (render/event-lines (:theme @state) event (:colors? @state))))))))

(defn- render-event! [state event]
  (when (or (nil? (:session-id event))
            (= (:session-id @state) (:session-id event)))
    (render-current-event! state event)))

(defn- render-history! [state]
  (let [result (dispatch! (:runtime @state) "session.entries" {:session-id (:session-id @state) :branch? true})]
    (doseq [entry (:entries result)]
      (print-lines! state (render/entry-lines (:theme @state) entry (:colors? @state)))
      (when (and (= :message (:kind entry)) (= :assistant (get-in entry [:data :message/role])))
        (swap! state assoc :last-answer (render/content-text (get-in entry [:data :message/content])))))))

(defn- resource-command-names [catalog]
  (->> (:commands catalog) (map #(if (map? %) (or (:name %) (:id %)) %)) (remove nil?) (map str) vec))

(defn- select-session! [state]
  (let [sessions (:sessions (dispatch! (:runtime @state) "session.list" {}))
        sid (select! state "Sessions"
                     (map (fn [s] {:label (str (or (:name s) "session") "  " (:id s) "  " (:cwd s))
                                   :value (:id s)}) sessions))]
    (when sid
      (let [selected (some #(when (= sid (:id %)) %) sessions)]
        (swap! state assoc :session-id sid :cwd (Paths/get (str (:cwd selected)) (make-array String 0))
               :operation-id nil :streams {} :pending-parts [])
        (refresh-presentation! state)
        (when (:tty? @state) (install-bindings! state))
        (render-history! state)))
    sid))

(defn- refresh-presentation! [state]
  (refresh-status! state)
  (let [sid (:session-id @state)
        settings (try (:settings (dispatch! (:runtime @state) "settings.get" {:session-id sid}))
                      (catch Throwable _ {}))
        catalog (try (dispatch! (:runtime @state) "resource.list" {:session-id sid})
                     (catch Throwable _ {}))
        theme-setting (or (get-in settings [:terminal :theme]) (:theme settings) :auto)
        auto-base (if (str/includes? (or (System/getenv "COLORFGBG") "") ";15") render/light-theme render/dark-theme)
        named (when-not (map? theme-setting)
                (some (fn [descriptor]
                        (when-let [descriptor-name (or (:name descriptor) (:id descriptor))]
                          (when (= (name (kw descriptor-name)) (name (kw theme-setting))) descriptor)))
                      (:themes catalog)))
        theme-data (cond
                     (map? theme-setting) theme-setting
                     (= :light (kw theme-setting)) render/light-theme
                     (= :dark (kw theme-setting)) render/dark-theme
                     (map? named) (or (:theme named) named)
                     :else auto-base)]
    (swap! state assoc :settings settings :resource-catalog catalog
           :extension-commands (resource-command-names catalog)
           :theme (render/normalize-theme auto-base theme-data))))

(defn- model-choice [model]
  {:label (str (name (kw (:provider model))) "/" (:id model)
               (when-let [name (:name model)] (str " — " name)))
   :value model})

(defn- command-help []
  ["Commands:"
   "  /new [name]       create a session       /resume, /session   search sessions"
   "  /tree             navigate/rewind/fork/label the branch tree"
   "  /fork [entry] [name]  /clone [name]  /name NAME  /label ENTRY LABEL"
   "  /continue  /compact [instructions]  /cancel  /steer TEXT  /follow-up TEXT  /queue"
   "  /model [provider model]  /thinking [level]  /tools [all|none|names]"
   "  /settings [set PATH EDN [global|project]]  /trust [on|off]  /reload"
   "  /login [provider [api-key]]  /logout [provider]"
   "  /copy [transcript]  /paste  /editor  /export [path]  /import PATH  /share"
   "  /eval FORM  /usage  /delete  /help  /quit"
   "Input: @path embeds UTF-8 text or attaches an image. Enter steers while running; Alt-Enter queues follow-up."
   "Keys: Ctrl-C/Escape cancel or clear, Ctrl-D exits, Alt-M inserts newline, Ctrl-X Ctrl-E opens $VISUAL/$EDITOR, Alt-V pastes clipboard."])

(defn- parse-setting-path [s]
  (let [parts (-> (or s "") (str/replace #"^:" "") (str/split #"[./]") (->> (remove str/blank?) (mapv keyword)))]
    (when (empty? parts) (error! "terminal.invalid-setting" "Setting path is required"))
    parts))

(defn- tree-command! [state]
  (let [{:keys [entries head]} (dispatch! (:runtime @state) "session.tree" {:session-id (:session-id @state)})
        rows (render/tree-lines (:theme @state) entries head (:colors? @state))]
    (print-lines! state (map :display rows))
    (when-let [entry (select! state "Branch entries" (map (fn [{:keys [entry plain]}] {:label plain :value entry}) rows))]
      (let [action (select! state "Tree action" ["rewind" "fork" "label" "cancel"])]
        (case action
          "rewind" (do (dispatch! (:runtime @state) "session.rewind"
                                   {:session-id (:session-id @state) :entry-id (:id entry)})
                       (refresh-status! state))
          "fork" (let [name (read-input! state "fork name (optional)> ")
                       session (dispatch! (:runtime @state) "session.fork"
                                          {:session-id (:session-id @state) :entry-id (:id entry)
                                           :position :at :name (not-empty name)})]
                   (swap! state assoc :session-id (:id session)
                          :cwd (Paths/get (str (:cwd session)) (make-array String 0))
                          :operation-id nil)
                   (refresh-presentation! state)
                   (when (:tty? @state) (install-bindings! state)))
          "label" (let [label (read-input! state "label> ")]
                    (when (str/blank? label) (error! "terminal.invalid-label" "Label cannot be blank"))
                    (dispatch! (:runtime @state) "session.label"
                               {:session-id (:session-id @state) :entry-id (:id entry) :label label}))
          nil)))))

(defn- settings-command! [state raw]
  (let [[action path source] (str/split (str/trim raw) #"\s+" 3)]
    (if (= "set" action)
      (let [_ (when (or (str/blank? path) (str/blank? source))
                (error! "terminal.invalid-setting" "Usage: /settings set PATH EDN [global|project]"))
            [parsed scope]
            (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
              (let [eof (Object.)
                    value (edn/read {:eof eof} reader)
                    raw-scope (edn/read {:eof eof} reader)
                    scope (if (identical? eof raw-scope) :project
                              (if (keyword? raw-scope) raw-scope (keyword (str raw-scope))))]
                (when-not (and (not (identical? eof value))
                               (contains? #{:global :project} scope)
                               (identical? eof (edn/read {:eof eof} reader)))
                  (error! "terminal.invalid-setting" "Expected one EDN value and optional global/project scope"))
                [value scope]))
            changes (assoc-in {} (parse-setting-path path) parsed)
          result (dispatch! (:runtime @state) "settings.update"
                            {:session-id (:session-id @state) :changes changes :scope (kw (or scope :project))})]
      (refresh-presentation! state)
      (when (:tty? @state) (install-bindings! state))
      (print-above! state (pr-str result)))
    (let [settings (:settings (dispatch! (:runtime @state) "settings.get" {:session-id (:session-id @state)}))
          rows (if (seq settings)
                 (map (fn [[k v]] {:label (str (name k) " = " (pr-str v)) :value k}) (sort-by (comp str key) settings))
                 [{:label "No settings are currently defined" :value nil}])
          selected (select! state "Settings (select to edit, blank cancels)" rows)]
      (when selected
        (let [value (read-input! state (str (name selected) " EDN> "))
              parsed (edn/read-string value)
              scope (select! state "Setting scope" ["project" "global"])]
          (dispatch! (:runtime @state) "settings.update"
                     {:session-id (:session-id @state) :changes {selected parsed} :scope (kw scope)})
          (refresh-presentation! state)
          (when (:tty? @state) (install-bindings! state))))))))

(defn- last-assistant-text [state]
  (or (:last-answer @state)
      (let [entries (:entries (dispatch! (:runtime @state) "session.entries"
                                         {:session-id (:session-id @state) :branch? true}))]
        (some->> entries reverse
                 (filter #(and (= :message (:kind %)) (= :assistant (get-in % [:data :message/role]))))
                 first :data :message/content render/content-text))))

(defn- prepare-prompt! [state prompt]
  (let [pending (:pending-parts @state)
        expanded (cond
                   (string? prompt) (expand-references (:cwd @state) prompt pending)
                   (sequential? prompt) (into (vec prompt) pending)
                   :else (error! "terminal.invalid-prompt" "Prompt must be text or canonical content parts"))]
    (swap! state assoc :pending-parts [])
    expanded))

(defn- submit-prompt! [state prompt mode]
  (let [expanded (prepare-prompt! state prompt)]
    (cond
      (= mode :follow-up)
      (dispatch! (:runtime @state) "session.follow-up" {:session-id (:session-id @state) :prompt expanded})

      (active-operation? state)
      (dispatch! (:runtime @state) "session.steer" {:session-id (:session-id @state) :prompt expanded})

      :else
      (remember-receipt! state
                         (dispatch! (:runtime @state) "session.run"
                                    {:session-id (:session-id @state) :prompt expanded})))))

(defn- extension-command! [state command raw]
  (let [result (dispatch! (:runtime @state) "session.command"
                          {:session-id (:session-id @state) :name command :arguments raw})]
    (if (:handled? result)
      (when (some? (:value result)) (print-above! state (pr-str (:value result))))
      (error! "terminal.unknown-command" (str "Unknown command: /" command)))))

(defn- command-line! [state line]
  (let [{:keys [command args raw]} (split-command line)
        rt (:runtime @state)
        sid (:session-id @state)]
    (case command
      ("quit" "exit") :quit
      "help" (print-lines! state (command-help))
      "new" (let [session (dispatch! rt "session.create" {:cwd (str (:cwd @state)) :name (not-empty (str/join " " args))})]
                (swap! state assoc :session-id (:id session) :operation-id nil :pending-parts [] :streams {})
                (refresh-presentation! state))
      ("resume" "session") (select-session! state)
      "tree" (tree-command! state)
      "rewind" (dispatch! rt "session.rewind" {:session-id sid :entry-id (first args)})
      "fork" (let [[entry name] args
                   session (dispatch! rt "session.fork" {:session-id sid :entry-id entry :position :at :name name})]
               (swap! state assoc :session-id (:id session)
                      :cwd (Paths/get (str (:cwd session)) (make-array String 0))
                      :operation-id nil)
               (refresh-presentation! state)
               (when (:tty? @state) (install-bindings! state)))
      "clone" (let [session (dispatch! rt "session.clone" {:session-id sid :name (not-empty (str/join " " args))})]
                (swap! state assoc :session-id (:id session)
                       :cwd (Paths/get (str (:cwd session)) (make-array String 0))
                       :operation-id nil)
                (refresh-presentation! state)
                (when (:tty? @state) (install-bindings! state)))
      "name" (let [name (str/trim raw)]
               (when (str/blank? name) (error! "terminal.invalid-name" "Usage: /name NAME"))
               (dispatch! rt "session.name" {:session-id sid :name name})
               (refresh-status! state))
      "label" (let [[entry & label] args]
                  (when (or (str/blank? entry) (empty? label)) (error! "terminal.invalid-label" "Usage: /label ENTRY LABEL"))
                  (dispatch! rt "session.label" {:session-id sid :entry-id entry :label (str/join " " label)}))
      "continue" (remember-receipt! state (dispatch! rt "session.continue" {:session-id sid}))
      "compact" (remember-receipt! state (dispatch! rt "session.compact" {:session-id sid :instructions (not-empty raw)}))
      "cancel" (do (dispatch! rt "session.cancel" {:session-id sid}) (swap! state assoc :operation-id nil))
      "steer" (do (when (str/blank? raw) (error! "terminal.invalid-prompt" "Usage: /steer TEXT"))
                  (dispatch! rt "session.steer" {:session-id sid :prompt (prepare-prompt! state raw)}))
      "follow-up" (do (when (str/blank? raw) (error! "terminal.invalid-prompt" "Usage: /follow-up TEXT"))
                      (dispatch! rt "session.follow-up" {:session-id sid :prompt (prepare-prompt! state raw)}))
      "queue" (print-above! state (pr-str (dispatch! rt "session.queue" {:session-id sid})))
      "delete" (when (confirm! state "Delete this session permanently?" false)
                   (dispatch! rt "session.delete" {:session-id sid})
                   (let [session (dispatch! rt "session.create" {:cwd (str (:cwd @state))})]
                     (swap! state assoc :session-id (:id session) :operation-id nil)
                     (refresh-presentation! state)))
      "model" (let [models (:models (dispatch! rt "model.list" {:session-id sid}))
                    explicit? (seq args)
                    [provider-token model-token] (if (>= (count args) 2)
                                                   [(first args) (second args)]
                                                   (let [[provider model] (str/split (or (first args) "") #"/" 2)]
                                                     (if model [provider model] [nil provider])))
                    chosen (if explicit?
                             (some #(when (and (= (:id %) model-token)
                                               (or (nil? provider-token)
                                                   (= (name (kw (:provider %))) provider-token))) %) models)
                             (select! state "Models" (map model-choice models)))]
                (when (and explicit? (nil? chosen))
                  (error! "terminal.model-not-found" (str "Model was not found: " (str/join " " args))))
                (when chosen
                  (dispatch! rt "session.configure"
                             {:session-id sid :config {:provider (kw (:provider chosen)) :model (:id chosen)}})
                  (refresh-status! state)))
      "thinking" (let [level (or (some-> (first args) kw)
                                  (some-> (select! state "Thinking level" [:none :minimal :low :medium :high :xhigh :max]) kw))]
                     (when level
                       (dispatch! rt "session.configure" {:session-id sid :config {:thinking level}})
                       (refresh-status! state)))
      "tools" (let [arg (first args)
                    tools (cond (= arg "all") :all
                                (#{"none" "off"} arg) []
                                (seq args) (vec args)
                                :else (let [caps (:capabilities (dispatch! rt "capability.list" {:session-id sid}))]
                                        (select! state "Tool selection"
                                                 (concat [{:label "all" :value :all}
                                                          {:label "none" :value []}]
                                                         (map #(hash-map :label (:name %) :value [(:name %)]) caps)))))]
                (when (some? tools)
                  (dispatch! rt "capability.set" {:session-id sid :tools tools})
                  (refresh-status! state)))
      "settings" (settings-command! state raw)
      "trust" (let [trusted? (case (some-> (first args) str/lower-case)
                               ("on" "true" "yes") true
                               ("off" "false" "no") false
                               (confirm! state (str "Trust project " (:cwd @state) "?") false))]
                  (dispatch! rt "project.trust" {:cwd (str (:cwd @state)) :trusted? trusted?})
                  (print-above! state "Trust decision saved for the next session or /reload; explicit per-run trust overrides remain in effect."))
      "reload" (do (dispatch! rt "session.reload" {:session-id sid})
                   (refresh-presentation! state)
                   (when (:tty? @state) (install-bindings! state)))
      "login" (let [providers (->> (:models (dispatch! rt "model.list" {:session-id sid})) (map :provider) distinct)
                    provider (or (some-> (first args) kw) (select! state "Provider" providers))
                    api-key (second args)]
                (print-above! state
                              (pr-str (dispatch! rt "auth.login"
                                                 (cond-> {:provider provider :session-id sid} api-key (assoc :api-key api-key))))))
      "logout" (let [provider (or (some-> (first args) kw)
                                   (select! state "Provider"
                                            (->> (:models (dispatch! rt "model.list" {:session-id sid}))
                                                 (map :provider) distinct)))]
                 (print-above! state (pr-str (dispatch! rt "auth.logout" {:provider provider :session-id sid}))))
      "copy" (let [text (if (= "transcript" (first args))
                           (:content (dispatch! rt "session.export" {:session-id sid :format :edn}))
                           (last-assistant-text state))]
               (copy-text! text)
               (print-above! state "Copied to clipboard."))
      "paste" (if-let [image (clipboard-image-part)]
                (do (swap! state update :pending-parts conj image) (print-above! state "Attached clipboard image to the next prompt."))
                (if-let [text (clipboard-text)]
                  (if (:tty? @state)
                    (let [^LineReader reader (:reader @state)]
                      (.write (.getBuffer reader) ^CharSequence text))
                    (print-above! state (str "Clipboard text (paste into the next prompt):\n" text)))
                  (error! "terminal.clipboard-empty" "Clipboard has no supported text or image")))
      "editor" (let [edited (external-editor! state raw)]
                   (if (:tty? @state)
                     (let [^LineReader reader (:reader @state)]
                       (.clear (.getBuffer reader))
                       (.write (.getBuffer reader) ^CharSequence edited))
                     (when-not (str/blank? edited) (submit-prompt! state edited :normal))))
      "export" (let [raw-path (first args)
                     path (some->> raw-path (resolve-user-path state))
                     format (cond (some-> raw-path (str/ends-with? ".html")) :html
                                  (some-> raw-path (str/ends-with? ".edn")) :edn
                                  :else :jsonl)
                     result (dispatch! rt "session.export"
                                       (cond-> {:session-id sid :format format} path (assoc :path path)))]
                 (print-above! state (if-let [p (:path result)] (str "Exported to " p) (:content result))))
      "import" (let [raw-path (first args)]
                 (when (str/blank? raw-path) (error! "terminal.invalid-import" "Usage: /import PATH"))
                 (let [path (resolve-user-path state raw-path)
                       session (dispatch! rt "session.import" {:path path :cwd (str (:cwd @state))})]
                   (swap! state assoc :session-id (:id session)
                          :cwd (Paths/get (str (:cwd session)) (make-array String 0))
                          :operation-id nil)
                   (refresh-presentation! state)
                   (when (:tty? @state) (install-bindings! state))
                   (render-history! state)))
      "share" (when (confirm! state "Upload this session to an unlisted GitHub gist? Anyone with its URL can read it." false)
                (let [result (dispatch! rt "session.share" {:session-id sid})]
                  (print-above! state (str "Unlisted session link (not access-controlled): " (:url result)))))
      "eval" (do (when (str/blank? raw) (error! "terminal.invalid-eval" "Usage: /eval FORM"))
                 (let [result (dispatch! rt "session.evaluate" {:session-id sid :source raw})]
                   (print-above! state (or (:content result) (pr-str (dissoc result :value))))))
      "usage" (let [result (dispatch! rt "session.inspect" {:session-id sid})]
                (print-above! state (render/format-usage (or (:usage result) (get-in result [:state :usage]) {}))))
      (extension-command! state command raw))))

(defn- key-sequence [spec]
  (cond
    (not (string? spec)) nil
    (re-matches #"(?i)ctrl-[a-z]" spec) (KeyMap/ctrl (.charAt ^String spec 5))
    (re-matches #"(?i)alt-.+" spec) (KeyMap/alt (subs spec 4))
    (= "escape" (str/lower-case spec)) "\u001b"
    (= "enter" (str/lower-case spec)) "\r"
    :else spec))

(defn- install-widget! [^LineReader reader name f]
  (.put (.getWidgets reader) name (reify Widget (apply [_] (f) true)))
  (Reference. name))

(defn- binding-specs [configured action defaults]
  (let [value (get configured action)]
    (cond
      (string? value) [value]
      (sequential? value) value
      :else defaults)))

(defn- install-bindings! [state]
  (let [^LineReader reader (:reader @state)
        keymap ^KeyMap (get (.getKeyMaps reader) LineReader/MAIN)
        _ (doseq [sequence (:bound-sequences @state)]
            (.unbind keymap (into-array String [sequence])))
        bound (atom [])
        configured (or (get-in @state [:settings :terminal :keybindings])
                       (:keybindings (:settings @state)) {})
        bind! (fn [binding sequences]
                (doseq [sequence sequences :let [encoded (key-sequence sequence)] :when encoded]
                  (.bind keymap ^Binding binding (into-array String [encoded]))
                  (swap! bound conj encoded)))
        cancel-ref (install-widget! reader "arrodes-cancel"
                                    #(do (if (active-operation? state)
                                           (try (dispatch! (:runtime @state) "session.cancel" {:session-id (:session-id @state)})
                                                (catch Throwable e (print-above! state (render/format-error e))))
                                           (.clear (.getBuffer reader)))
                                         (.callWidget reader LineReader/REDRAW_LINE)))
        follow-ref (install-widget! reader "arrodes-follow-up"
                                    #(do (swap! state assoc :submit-mode :follow-up) (.callWidget reader LineReader/ACCEPT_LINE)))
        newline-ref (install-widget! reader "arrodes-newline"
                                     #(do (.write (.getBuffer reader) "\n") (.callWidget reader LineReader/REDRAW_LINE)))
        editor-ref (install-widget! reader "arrodes-editor"
                                    #(try
                                       (let [edited (external-editor! state (str (.getBuffer reader)))]
                                         (.clear (.getBuffer reader)) (.write (.getBuffer reader) ^CharSequence edited)
                                         (.callWidget reader LineReader/REDRAW_LINE))
                                       (catch Throwable e (print-above! state (render/format-error (:theme @state) e (:colors? @state))))))
        paste-ref (install-widget! reader "arrodes-paste"
                                   #(try
                                      (if-let [image (clipboard-image-part)]
                                        (do (swap! state update :pending-parts conj image)
                                            (print-above! state "Attached clipboard image to the next prompt."))
                                        (if-let [text (clipboard-text)] (.write (.getBuffer reader) ^CharSequence text)
                                                (error! "terminal.clipboard-empty" "Clipboard has no supported text or image")))
                                      (.callWidget reader LineReader/REDRAW_LINE)
                                      (catch Throwable e (print-above! state (render/format-error (:theme @state) e (:colors? @state))))))]
    (bind! cancel-ref (binding-specs configured :cancel ["ctrl-c" "escape"]))
    (bind! follow-ref (binding-specs configured :follow-up [(str "\u001b\r") (str "\u001b\n")]))
    (bind! newline-ref (binding-specs configured :newline ["alt-m"]))
    (bind! editor-ref (binding-specs configured :external-editor [(str (KeyMap/ctrl \X) (KeyMap/ctrl \E))]))
    (bind! paste-ref (binding-specs configured :paste ["alt-v"]))
    (bind! (Reference. LineReader/REVERSE_MENU_COMPLETE) ["\u001b[Z"])
    (bind! (Reference. LineReader/MENU_COMPLETE) ["\t"])
    (swap! state assoc :bound-sequences @bound)
    true))

(defn- create-session [rt options cwd]
  (let [specified (or (:session-id options)
                      (when (string? (:session options)) (:session options))
                      (when (map? (:session options)) (:id (:session options))))]
    (if specified
      (:session (dispatch! rt "session.inspect" {:session-id specified}))
      (dispatch! rt "session.create"
                 (cond-> {:cwd (str cwd)}
                   (:name options) (assoc :name (:name options))
                   (:config options) (assoc :config (normalize-config (:config options))))))))

(defn run!
  "Runs the production interactive terminal until EOF or /quit. The runtime remains caller-owned."
  [rt options]
  (let [terminal (-> (TerminalBuilder/builder) (.system true) (.dumb true) (.build))
        original-attributes (.getAttributes terminal)
        requested-cwd (Paths/get (str (or (:cwd options) (:cwd (dispatch! rt "runtime.inspect" {})) "."))
                                 (make-array String 0))
        session (create-session rt options requested-cwd)
        cwd (Paths/get (str (:cwd session)) (make-array String 0))
        state (atom {:runtime rt :terminal terminal :reader nil :plain-reader nil :tty? false
                     :cwd cwd :session-id (:id session) :session session :runtime-state {}
                     :operation-id nil :submit-mode :normal
                     :pending-parts (vec (or (:pending-parts options) [])) :last-answer nil
                     :theme render/dark-theme :colors? (not= false (:color options)) :settings {}
                     :resource-catalog {} :extension-commands [] :selector-items [] :bound-sequences []
                     :widgets {} :renderers {} :streams {} :ui-queue (LinkedBlockingQueue.)
                     :main-thread (Thread/currentThread) :output-lock (Object.)
                     :original-attributes original-attributes})
        parser (doto (DefaultParser.) (.eofOnEscapedNewLine true))
        reader (-> (LineReaderBuilder/builder) (.terminal terminal) (.parser parser)
                   (.completer (completer state)) (.build))
        plain? (not (tty? terminal options))
        plain-reader (when plain? (BufferedReader. (InputStreamReader. System/in StandardCharsets/UTF_8)))]
    (swap! state assoc :reader reader :plain-reader plain-reader :tty? (not plain?))
    (refresh-presentation! state)
    (when-not plain? (install-bindings! state))
    (install-ui! rt state)
    (let [unsubscribe (runtime/subscribe! rt #(render-event! state %))]
      (try
        (when plain?
          (print-above! state "Plain line terminal: advanced keybindings, completion menus, and cursor-preserving redraw are unavailable; slash commands and streaming remain active."))
        (when (or (:resume options) (:session-id options) (:session options)) (render-history! state))
        (when-let [initial (:prompt options)]
          (when (or (not (string? initial)) (not (str/blank? initial)))
            (submit-prompt! state initial :normal)))
        (when (:continue options)
          (remember-receipt! state (dispatch! rt "session.continue" {:session-id (:session-id @state)})))
        (loop []
          (let [line (try (read-input! state (prompt state))
                          (catch UserInterruptException _
                            (if (active-operation? state)
                              (do (dispatch! rt "session.cancel" {:session-id (:session-id @state)}) "")
                              ""))
                          (catch EndOfFileException _ ::eof))
                ui? (drain-ui! state)
                mode (:submit-mode @state)]
            (swap! state assoc :submit-mode :normal)
            (cond
              (= ::eof line) nil
              ui? (do
                    (when (and (string? line) (not (str/blank? line)))
                      (when-let [^LineReader r (:reader @state)] (.write (.getBuffer r) ^CharSequence line)))
                    (recur))
              (nil? line) nil
              (str/blank? line) (recur)
              (str/starts-with? (str/triml line) "/")
              (let [quit? (try
                            (= :quit (command-line! state (str/triml line)))
                            (catch Throwable e
                              (print-above! state (render/format-error (:theme @state) e (:colors? @state)))
                              false))]
                (when-not quit? (recur)))
              :else
              (do
                (try (submit-prompt! state line mode)
                     (catch Throwable e (print-above! state (render/format-error (:theme @state) e (:colors? @state)))))
                (recur)))))
        (finally
          (try (unsubscribe) (catch Throwable _))
          (try (runtime/set-ui! rt nil) (catch Throwable _))
          (try (.flush (.writer terminal)) (catch Throwable _)))))
    nil))
