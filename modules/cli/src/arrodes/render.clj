(ns arrodes.render
  "Pure terminal rendering for canonical Arrodes messages, events, usage and trees."
  (:require [clojure.string :as str]))

(def ^:private ansi-reset "\u001b[0m")

(def dark-theme
  {:name :dark
   :colors {:text :default :accent :bright-cyan :muted :bright-black
            :success :bright-green :warning :bright-yellow :error :bright-red
            :user :bright-blue :assistant :bright-white :reasoning :bright-black
            :tool :bright-magenta :border :bright-black :selected :reverse}})

(def light-theme
  {:name :light
   :colors {:text :black :accent :blue :muted :bright-black
            :success :green :warning :yellow :error :red
            :user :blue :assistant :black :reasoning :bright-black
            :tool :magenta :border :bright-black :selected :reverse}})

(def ^:private named-codes
  {:default "39" :black "30" :red "31" :green "32" :yellow "33"
   :blue "34" :magenta "35" :cyan "36" :white "37"
   :bright-black "90" :bright-red "91" :bright-green "92"
   :bright-yellow "93" :bright-blue "94" :bright-magenta "95"
   :bright-cyan "96" :bright-white "97" :bold "1" :dim "2"
   :italic "3" :underline "4" :reverse "7"})

(defn- hex-color-code [value]
  (when-let [[_ r g b] (and (string? value)
                            (re-matches #"#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})" value))]
    (str "38;2;" (Integer/parseInt r 16) ";" (Integer/parseInt g 16) ";" (Integer/parseInt b 16))))

(defn color-code [value]
  (cond
    (keyword? value) (get named-codes value)
    (and (integer? value) (<= 0 value 255)) (str "38;5;" value)
    (and (vector? value) (= 3 (count value)) (every? #(and (integer? %) (<= 0 % 255)) value))
    (str "38;2;" (str/join ";" value))
    (string? value) (or (hex-color-code value) (get named-codes (keyword value)))
    :else nil))

(defn normalize-theme
  "Merges a resource/settings theme onto the built-in dark or light theme."
  ([theme] (normalize-theme dark-theme theme))
  ([base theme]
   (let [theme (or theme {})]
     (-> base
         (assoc :name (or (:name theme) (:name base)))
         (update :colors merge (or (:colors theme) theme))))))

(defn paint
  ([theme role value] (paint theme role value true))
  ([theme role value enabled?]
   (let [s (str (or value ""))
         code (when enabled? (color-code (get-in theme [:colors role])))]
     (if (and code (seq s)) (str "\u001b[" code "m" s ansi-reset) s))))

(defn safe-text
  "Removes terminal control characters from untrusted content while retaining layout whitespace."
  [value]
  (str/replace (str (or value "")) #"[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]" ""))

(defn strip-ansi [s]
  (str/replace (str (or s "")) #"\u001b\[[0-?]*[ -/]*[@-~]" ""))

(defn content-text
  "Returns readable text from canonical message content without discarding part types."
  [content]
  (cond
    (nil? content) ""
    (string? content) (safe-text content)
    (sequential? content)
    (->> content
         (map (fn [part]
                (case (:part/type part)
                  :text (safe-text (:text part))
                  :reasoning (safe-text (or (:reasoning/text part) (:text part) ""))
                  :image (str "[image" (when-let [mime (:image/mime-type part)] (str " " mime)) "]")
                  :file (str "[file" (when-let [name (:file/name part)] (str " " name)) "]")
                  :tool-call (str "[tool " (or (:tool-call/name part) (:name part) "unknown") "]")
                  (if (:text part) (safe-text (:text part)) (pr-str part)))))
         (remove str/blank?)
         (str/join "\n"))
    :else (pr-str content)))

(defn format-error
  ([error] (format-error dark-theme error true))
  ([theme error colors?]
   (let [data (when (instance? clojure.lang.IExceptionInfo error) (ex-data error))
         code (or (:error/code data) (:code data))
         message (cond
                   (instance? Throwable error) (or (ex-message error) (.getName (class error)))
                   (map? error) (or (:message error) (:error/message error) (pr-str error))
                   :else (str error))]
     (paint theme :error (str "error" (when code (str " [" code "]")) ": " (safe-text message)) colors?))))

(defn format-usage [usage]
  (let [in (or (:usage/input-tokens usage) (:input-tokens usage) (:input usage) (:prompt-tokens usage) 0)
        out (or (:usage/output-tokens usage) (:output-tokens usage) (:output usage) (:completion-tokens usage) 0)
        cache-read (or (:usage/cached-input-tokens usage) (:cache-read-tokens usage) (:cache-read usage) 0)
        cache-write (or (:usage/cache-write-tokens usage) (:cache-write-tokens usage) (:cache-write usage) 0)
        cost (or (:usage/cost usage) (:cost usage) (:total-cost usage))]
    (str "tokens " in " in / " out " out"
         (when (pos? cache-read) (str " / " cache-read " cached"))
         (when (pos? cache-write) (str " / " cache-write " cache-write"))
         (when (number? cost) (format " / $%.6f" (double cost))))))

(defn- call-summary [call]
  (safe-text
   (str (or (:tool-call/name call) (:name call) "tool")
        (when-let [args (or (:tool-call/arguments call) (:arguments call))]
          (str " " (if (string? args) args (pr-str args)))))))

(defn message-lines
  "Renders a canonical message as complete display lines."
  ([message] (message-lines dark-theme message true))
  ([theme message colors?]
   (let [role (:message/role message)
         content (content-text (:message/content message))
         calls (:message/tool-calls message)
         prefix (case role :user "you" :assistant "assistant" :tool "tool" (name (or role :message)))
         style (case role :user :user :assistant :assistant :tool :tool :text)
         body-lines (if (str/blank? content) [] (str/split-lines content))
         first-line (when (seq body-lines)
                      (str (paint theme style (str prefix "> ") colors?) (first body-lines)))
         continuation (mapv #(str "  " %) (rest body-lines))
         call-lines (mapv #(paint theme :tool (str "tool> " (call-summary %)) colors?) calls)]
     (vec (concat (when first-line [first-line]) continuation call-lines
                  (when (and (= role :tool) (:message/error? message))
                    [(paint theme :error "  tool failed" colors?)]))))))

(defn entry-lines
  ([entry] (entry-lines dark-theme entry true))
  ([theme entry colors?]
   (case (:kind entry)
     :message (message-lines theme (:data entry) colors?)
     :compaction [(paint theme :muted (str "compacted> " (or (get-in entry [:data :summary]) "")) colors?)]
     :branch-summary [(paint theme :muted (str "branch> " (or (get-in entry [:data :summary]) "")) colors?)]
     :label [(paint theme :accent (str "label> " (or (get-in entry [:data :label]) (pr-str (:data entry)))) colors?)]
     :evaluation [(paint theme :tool (str "eval> " (or (get-in entry [:data :content]) (get-in entry [:data :source]) (pr-str (:data entry)))) colors?)]
     :custom (let [data (:data entry)]
               [(paint theme :accent (str (or (:title data) "custom") "> ") colors?)
                (content-text (or (:content data) data))])
     :custom-context (message-lines theme (:data entry) colors?)
     [(paint theme :muted (str (name (or (:kind entry) :entry)) "> " (pr-str (:data entry))) colors?)])))

(defn- unwrap-event [event]
  (if (and (= :provider-event (:type event)) (map? (:data event)))
    (:data event)
    event))

(defn- event-type-name [event]
  (-> (or (:type event) (:event/type event) :event) name str/lower-case))

(defn stream-fragment
  "Returns {:channel :assistant|:reasoning :text s} for incremental provider output."
  [event]
  (let [event (unwrap-event event)
        type (event-type-name event)
        data (or (:data event) event)
        delta (or (:event/delta data) (:delta data) (:text-delta data) (:content-delta data)
                  (:reasoning-delta data) (:text data)
                  (get-in data [:part :text]) (get-in data [:part :reasoning/text]))]
    (when (and (string? delta)
               (or (str/includes? type "delta")
                   (str/includes? type "stream")))
      {:channel (if (or (str/includes? type "reason") (:reasoning-delta data)) :reasoning :assistant)
       :text (safe-text delta)})))

(defn event-lines
  "Renders non-text streaming and durable semantic events. Unknown events remain visible."
  ([event] (event-lines dark-theme event true))
  ([theme event colors?]
   (let [event (unwrap-event event)
         type (event-type-name event)
         data (or (:data event) event)
         message (or (:message data) (when (:message/role data) data))
         usage (or (:usage data) (:usage/usage data) (when (str/includes? type "usage") data))
         error (or (:error data) (:error/error data) (when (str/includes? type "error") data))
         tool-name (or (:name data) (:tool/name data) (:tool-call/name data))
         content (or (:content data) (:output data) (:message data))]
     (cond
       (stream-fragment event) []
       message (message-lines theme message colors?)
       error [(format-error theme error colors?)]
       usage [(paint theme :muted (format-usage usage) colors?)]
       (str/includes? type "retry")
       [(paint theme :warning
               (str "provider retry"
                    (when-let [attempt (or (:attempt data) (:retry/attempt data))]
                      (str " " attempt))
                    (when-let [delay (or (:delay-ms data) (:retry/delay-ms data))]
                      (str " in " delay "ms"))
                    (when-let [reason (or (:reason data) (:message data))]
                      (str ": " (safe-text reason)))) colors?)]
       (str/includes? type "tool")
       [(paint theme :tool
               (str "tool> " (or tool-name "tool")
                    (cond
                      (or (str/includes? type "start") (str/includes? type "call")) " started"
                      (or (str/includes? type "progress") (:progress data)) (str " " (or (:progress data) content "working"))
                      (or (str/includes? type "fail") (:error? data)) " failed"
                      :else (str " " (or (:status data) "completed")))
                    (when (and content (not (map? content))) (str ": " (safe-text content))))
               colors?)]
       (or (str/includes? type "complete") (str/includes? type "cancel")
           (str/includes? type "interrupt") (str/includes? type "failed"))
       [(paint theme (if (str/includes? type "fail") :error :muted)
               (str (str/replace type #"[-_.]" " ")
                    (when-let [status (:status data)] (str ": " (name status)))) colors?)]
       (or (str/includes? type "queue") (str/includes? type "compact")
           (str/includes? type "auth") (str/includes? type "reload"))
       [(paint theme :muted (str (str/replace type #"[-_.]" " ")
                                  (when (seq data) (str ": " (pr-str data)))) colors?)]
       :else []))))

(defn session-status [session state]
  (let [config (:config session)
        phase (or (:phase state) (:status session) :idle)
        usage (or (:usage state) {})]
    (safe-text
     (str (or (:name session) (subs (or (:id session) "session") 0 (min 8 (count (or (:id session) "session")))))
          " · " (name (or (:provider config) :provider)) "/" (or (:model config) "model")
          " · thinking " (name (or (:thinking config) :none))
          " · " (name phase)
          (when (seq usage) (str " · " (format-usage usage)))))))

(defn tree-lines
  "Renders chronological entries as a branch-aware tree. Parents may occur anywhere."
  ([entries head] (tree-lines dark-theme entries head true))
  ([theme entries head colors?]
   (let [by-parent (group-by :parent-id entries)
         index (zipmap (map :id entries) (range))
         children (fn [parent] (sort-by #(get index (:id %)) (get by-parent parent)))
         summary (fn [entry]
                   (let [data (:data entry)
                         text (if (= :message (:kind entry))
                                (content-text (:message/content data))
                                (or (:summary data) (:label data) (:name data) (pr-str data)))
                         one-line (-> text str/split-lines first (or "") (str/replace #"\s+" " "))]
                     (str (name (:kind entry)) " "
                          (subs (str (:id entry)) 0 (min 8 (count (str (:id entry)))))
                          (when (= head (:id entry)) " *")
                          (when (seq one-line) (str "  " (subs one-line 0 (min 72 (count one-line))))))))
         walk (fn walk [parent prefix]
                (let [xs (vec (children parent))]
                  (mapcat (fn [i entry]
                            (let [last? (= i (dec (count xs)))
                                  branch (if last? "└─" "├─")
                                  next-prefix (str prefix (if last? "  " "│ "))]
                              (cons {:entry entry
                                     :plain (str prefix branch " " (summary entry))
                                     :display (str (paint theme :border (str prefix branch) colors?) " "
                                                   (paint theme (if (= head (:id entry)) :accent :text)
                                                          (summary entry) colors?))}
                                    (walk (:id entry) next-prefix))))
                          (range (count xs)) xs)))]
     (vec (walk nil "")))))
