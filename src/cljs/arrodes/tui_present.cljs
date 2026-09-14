(ns arrodes.tui-present
  "Bounded human-facing views of observed events, never guesses about source code."
  (:require [arrodes.tui-model :as model]
            [cljs.pprint :as pprint]
            [clojure.string :as str]))

(defn pretty [value]
  (binding [*print-length* 100 *print-level* 10]
    (str/trimr (with-out-str (pprint/pprint value)))))

(defn brief [text limit]
  (let [text (model/safe-text (str (or text "")))]
    (if (> (count text) limit) (str (subs text 0 (max 0 (- limit 3))) "...") text)))

(defn lines-preview [text maximum]
  (let [lines (str/split-lines (model/safe-text (str (or text ""))))]
    (str/join "\n" (cond-> (vec (take maximum lines))
                      (> (count lines) maximum)
                      (conj (str "... " (- (count lines) maximum) " more lines; inspect for output"))))))

(defn activity [row] (or (:activity row) (first (:activities row))))

(defn execution [view current]
  (loop [current current seen #{}]
    (if-let [parent (and (not (contains? seen (:id current)))
                         (get (:activities view) (:parent-id current)))]
      (recur parent (conj seen (:id current)))
      current)))

(defn exit-code [a]
  (or (model/field (:details a) :exit-code)
      (model/field (model/field (:result a) :value) :exit-code)))

(defn failed? [a]
  (or (= :failed (:status a))
      (true? (:error? a))
      (let [exit (exit-code a)] (and (number? exit) (not (zero? exit))))))

(defn status-label [a]
  (cond
    (contains? #{:running :started} (:status a)) "running"
    (contains? #{:interrupted :cancelled} (:status a)) "interrupted"
    (some? (exit-code a)) (str "exit " (exit-code a))
    (failed? a) "failed"
    (= :completed (:status a)) (if (contains? #{"write" "edit"} (:name a)) "applied" "done")
    :else "pending"))

(defn row-title [row]
  (case (:kind row)
    :read-group (str "Read " (count (:activities row)) " files")
    :activity (model/activity-title (:activity row))
    :reasoning "Reasoning"
    :presentation "Extension"
    :message (case (:role row) :user "YOU" :assistant "ARRODES" "CONTEXT")
    "Execution"))

(defn recorded-diff [a]
  (when (= "edit" (:name a))
    (let [arguments (:arguments a)
          path (model/field arguments :path)
          edits (model/field arguments :edits)]
      (when (seq edits)
        (str "Recorded replacements in " path "\n"
             (str/join
              "\n"
              (map-indexed
               (fn [index edit]
                 (str "@@ replacement " (inc index) " @@\n"
                      (str/join "\n" (map #(str "- " %) (str/split (or (model/field edit :oldText) "") #"\n" -1)))
                      "\n"
                      (str/join "\n" (map #(str "+ " %) (str/split (or (model/field edit :newText) "") #"\n" -1)))))
               edits)))))))

(defn output-text [a]
  (let [details (:details a)
        value (model/field (:result a) :value)
        stdout (or (model/field details :stdout) (model/field value :stdout))
        stderr (or (model/field details :stderr) (model/field value :stderr))
        canonical (if (or (some? stdout) (some? stderr))
                    (str "STDOUT\n" (if (seq stdout) stdout "No output")
                         "\n\nSTDERR\n" (if (seq stderr) stderr "No output")
                         (when-some [exit (exit-code a)] (str "\n\nExit status: " exit)))
                    (or (not-empty (model/text-content (:content a)))
                        (not-empty (:output a))
                        (when (= :running (:status a)) "Waiting for output...")
                        "No textual output recorded."))
        presentation (not-empty (:presentation a))
        renderer-error (not-empty (:presentation-error a))
        content (cond
                  renderer-error canonical
                  presentation
                  (if (or (failed? a) (contains? #{:interrupted :cancelled} (:status a)))
                    (str presentation "\n\n" (str/upper-case (status-label a)) "\n" canonical)
                    presentation)
                  :else canonical)]
    (str content
         (when renderer-error
           (str "\n\nRenderer error (canonical result preserved): " renderer-error)))))

(defn inline-content [row expanded?]
  (let [a (activity row)
        diff (recorded-diff a)]
    (case (:kind row)
      :message (:text row)
      :presentation (:text row)
      :reasoning (when expanded? (:text row))
      :read-group (when expanded?
                    (str/join "\n" (map #(str (model/activity-title %) "  " (status-label %)) (:activities row))))
      :activity
      (cond
        diff (lines-preview (if expanded? diff (str/join "\n" (drop 2 (str/split-lines diff))))
                            (if expanded? 40 7))
        (or expanded? (failed? a) (= :evaluation (:kind a))
            (contains? #{"bash" "powershell"} (:name a)))
        (lines-preview (if (or (:presentation a) (:presentation-error a))
                         (output-text a)
                         (or (not-empty (model/text-content (:content a)))
                             (not-empty (:output a)) ""))
                       (if expanded? 40 5))
        :else nil)
      nil)))

(defn default-tab [row]
  (let [a (activity row)]
    (cond (recorded-diff a) :summary
          (contains? #{"bash" "powershell"} (:name a)) :output
          (= :evaluation (:kind a)) :value
          :else :summary)))

(defn lifetime [descriptor]
  (let [kind (keyword (or (model/field descriptor :kind) "unknown"))
        available? (model/field descriptor :available?)]
    (cond
      (false? available?) "Unavailable. Live values are not restored after restart."
      (= kind :live) "Live JVM value. Not a durable checkpoint."
      (contains? #{:inline :artifact} kind) "Saved result. Available independently of live definitions."
      :else "No retained result yet.")))

(defn inspection [view row tab inspection-state]
  (let [a (activity row)
        root (execution view a)
        descriptor (or (:descriptor inspection-state) (:result a))
        page (:page inspection-state)
        result-id (model/field descriptor :id)
        kind (keyword (or (model/field descriptor :kind) "unknown"))
        content
        (case tab
          :code (or (:source root) (:source a) "No Clojure source is associated with this entry.")
          :output (if a (output-text a) (:text row))
          :value
          (cond
            (false? (model/field descriptor :available?)) (lifetime descriptor)
            (= kind :inline)
            (if-let [edn (model/field descriptor :value-edn)]
              (str edn (when (model/field descriptor :value-truncated?)
                         "\n\nNative value preview is truncated."))
              (str "JSON projection (native representation is loading):\n"
                   (pretty (model/field descriptor :value))))
            page (str (:content page)
                      (when (:truncated? page) "\n\nMore content is available on the next page."))
            (= kind :artifact) "Saved artifact. Load a bounded page to inspect its value."
            (= kind :live) (str (lifetime descriptor) "\n\nRecorded preview:\n" (or (model/field descriptor :content) "No preview"))
            :else "This execution has not retained a value yet.")
          :summary
          (cond
            (= :read-group (:kind row))
            (str/join "\n\n" (map #(str (model/activity-title %) "\n" (status-label %) "\n" (output-text %)) (:activities row)))
            a (str (status-label a) "\n\n"
                   (or (recorded-diff a)
                       (when (:arguments a) (str "ARGUMENTS\n" (pretty (:arguments a)) "\n\n")))
                   (when (failed? a) (str "ERROR\n" (output-text a)))
                   (when (= :evaluation (:kind a))
                     (str (output-text a) "\n\nEarlier effects are not rolled back by an evaluation error.")))
            :else (:text row))
          "")]
    {:title (row-title row) :content (model/safe-text (str (or content "")))
     :result-id result-id :descriptor descriptor :lifetime (lifetime descriptor)
     :artifact-id (model/field descriptor :artifact-id) :source (:source root)
     :diff? (and (= tab :summary) (some? (recorded-diff a)))}))
