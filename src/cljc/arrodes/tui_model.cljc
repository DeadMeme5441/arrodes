(ns arrodes.tui-model
  "Portable wire decoding and semantic transcript projection for the TUI."
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])))

(def ^:private max-progress-characters (* 64 1024))
(def ^:private max-presentation-rows 200)

(def ^:private enum-keys
  #{:type :event/type :part/type :message/role :role :kind :status :phase
    :provider :thinking :stream :mode :inspect-tab :focus :error/type})

(def ^:private ansi-sequence-pattern
  #"\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001B\\)|[PX^_].*?(?:\u001B\\|$)|[@-_])")

(def ^:private unsafe-control-pattern
  #"[\u0000-\u0008\u000B-\u001F\u007F-\u009F]")

(defn- key-string [key]
  (if (keyword? key)
    (if-let [namespace (namespace key)]
      (str namespace "/" (name key))
      (name key))
    (str key)))

(defn field
  "Looks up a wire field regardless of whether its map key is a keyword or string."
  [m key]
  (when (map? m)
    (let [text (key-string key)
          keyword-key (keyword text)]
      (cond
        (contains? m key) (get m key)
        (contains? m text) (get m text)
        (contains? m keyword-key) (get m keyword-key)
        :else nil))))

(defn- payload-key? [key]
  (and (or (keyword? key) (string? key))
       (contains? #{"arguments" "value"} (name key))))

(defn- enum-key? [key]
  (and (or (keyword? key) (string? key))
       (or (contains? enum-keys key)
           (contains? #{"type" "role" "kind" "status" "phase" "provider"
                        "thinking" "stream" "mode"}
                      (name key)))))

(declare decode-wire apply-event put-activity event-presentation)

(defn- decode-map [m]
  (persistent!
   (reduce-kv
    (fn [result raw-key raw-value]
      (let [key (if (string? raw-key) (keyword raw-key) raw-key)
            value (cond
                    (payload-key? key) raw-value
                    (and (enum-key? key) (string? raw-value)) (keyword raw-value)
                    :else (decode-wire raw-value))]
        (assoc! result key value)))
    (transient {}) m)))

(defn decode-wire
  "Normalizes JSON protocol maps without rewriting user payload keys under
  :arguments or :value. The input must already be persistent Clojure data."
  [value]
  (cond
    (map? value) (decode-map value)
    (vector? value) (mapv decode-wire value)
    (set? value) (set (map decode-wire value))
    (sequential? value) (mapv decode-wire value)
    :else value))

(defn safe-text
  "Removes terminal escape sequences and unsafe display controls from text.
  Newlines and tabs are retained."
  [value]
  (-> (if (nil? value) "" (str value))
      (str/replace ansi-sequence-pattern "")
      (str/replace unsafe-control-pattern "")))

(defn text-content
  "Returns the textual portion of canonical message content."
  [value]
  (cond
    (nil? value) ""
    (string? value) value
    (sequential? value)
    (->> value
         (keep (fn [part]
                 (cond
                   (string? part) part
                   (and (map? part)
                        (= :text (let [type (field part :part/type)]
                                   (if (string? type) (keyword type) type))))
                   (some-> (field part :text) str)
                   :else nil)))
         (str/join ""))
    (map? value)
    (cond
      (some? (field value :message/content)) (text-content (field value :message/content))
      (some? (field value :text)) (str (field value :text))
      (some? (field value :content)) (str (field value :content))
      :else (pr-str value))
    :else (str value)))

(defn empty-state []
  {:session nil
   :phase :idle
   :entries []
   :activities {}
   :activity-order []
   :presentations []
   :streams {:operation-id nil :content "" :reasoning ""}
   :queue []
   :operation nil
   :cursor 0})

(defn- replace-by-id [items item]
  (let [id (:id item)
        index (first (keep-indexed #(when (= id (:id %2)) %1) items))]
    (if (some? index)
      (assoc (vec items) index item)
      (conj (vec items) item))))

(defn- active-entries [session entries]
  (let [entries (vec entries)
        head (or (:head session) (:id (peek entries)))
        by-id (into {} (map (juxt :id identity)) entries)]
    (if (and head (contains? by-id head))
      (loop [id head, seen #{}, result ()]
        (cond
          (nil? id) (vec result)
          (contains? seen id) entries
          :else
          (if-let [entry (get by-id id)]
            (recur (:parent-id entry) (conj seen id) (conj result entry))
            entries)))
      entries)))

(def ^:private replay-owned-operation-statuses
  #{:cancelling :completed :failed :cancelled :interrupted})

(defn- snapshot-operation [state prior]
  (if (contains? state :operation)
    (:operation state)
    (when-let [id (:operation-id state)]
      (let [prior (when (= id (:id prior)) prior)
            phase (:phase state)]
        (cond
          (and prior (contains? replay-owned-operation-statuses (:status prior)))
          prior

          (= :idle phase)
          {:id id :kind (:operation-kind state) :status :completed}

          :else
          {:id id
           :kind (:operation-kind state)
           :status (if (= :cancelling phase) :cancelling :running)})))))

(defn- recorded-source [arguments]
  (let [arguments (if (string? arguments)
                    (try #?(:clj (json/read-str arguments)
                            :cljs (js->clj (js/JSON.parse arguments)))
                         (catch #?(:clj Throwable :cljs :default) _ nil))
                    arguments)]
    (field arguments :source)))

(defn- seed-recorded-activities [model]
  (let [entries (:entries model)
        results (into {} (keep (fn [entry]
                                (let [data (:data entry)]
                                  (when (= :tool (:message/role data))
                                    [(:message/tool-call-id data) data])))) entries)
        add-record
        (fn [model activity descriptor error?]
          (if (or (nil? (:id activity)) (contains? (:activities model) (:id activity)))
            model
            (put-activity model
                          (assoc activity :result descriptor :recorded? true
                                 :details (or (:details descriptor) {})
                                 :status (cond error? :failed descriptor :completed
                                               (:operation model) :running :else :interrupted)))))]
    (reduce
     (fn [model entry]
       (let [data (:data entry)]
         (case (:kind entry)
           :message
           (reduce (fn [model call]
                     (let [result (get results (:tool-call/id call))
                           descriptor (:message/result result)
                           evaluation? (= "repl" (:tool-call/name call))]
                       (add-record model
                                   {:id (:tool-call/id call)
                                    :kind (if evaluation? :evaluation :capability)
                                    :name (when-not evaluation? (:tool-call/name call))
                                    :arguments (:tool-call/arguments call)
                                    :source (when evaluation? (recorded-source (:tool-call/arguments call)))
                                    :content (:message/content result)}
                                   descriptor (true? (get-in descriptor [:details :error?])))))
                   model (:message/tool-calls data))
           (:evaluation :custom)
           (let [result (:result data)]
             (add-record model
                         {:id (:id result) :kind (if (= :evaluation (:kind entry)) :evaluation :capability)
                          :name (:name data) :source (:source data) :arguments (:arguments data)
                          :content (:content result)}
                         (:result result) (:error? result)))
           model)))
     model entries)))

(defn hydrate
  "Combine an atomic branch snapshot with its historical activity ledger.
  Past entry/queue/operation mutations cannot overwrite the snapshot; later
  buffered events are then applied once in delivery order."
  [snapshot events]
  (let [snapshot (decode-wire (or snapshot {}))
        state (or (:state snapshot) snapshot)
        session (or (:session state) (:session snapshot))
        entries (or (:entries snapshot) (:entries state) [])
        cursor (or (:cursor snapshot) (:event-seq state) (:event-seq snapshot) 0)
        events (mapv decode-wire (or events []))
        past? #(and (number? (:seq %)) (<= (:seq %) cursor))
        ledger-types #{:evaluation/started :evaluation/completed
                       :capability/started :capability/completed
                       :operation/started :operation/cancelling :operation/completed
                       :operation/failed :operation/cancelled :operation/interrupted
                       :tool/interrupted}
        history (reduce apply-event (empty-state)
                        (filter #(and (past? %)
                                      (or (contains? ledger-types (:type %))
                                          (seq (event-presentation %))))
                                events))
        prior-operation (:operation history)
        active-operation (snapshot-operation state prior-operation)
        operation (if active-operation
                    (merge (when (= (:id active-operation) (:id prior-operation)) prior-operation)
                           (into {} (remove (comp nil? val)) active-operation))
                    (when (case (:status session)
                            :failed (= :failed (:status prior-operation))
                            :interrupted (contains? #{:cancelled :interrupted} (:status prior-operation))
                            :idle (= :completed (:status prior-operation))
                            false)
                      prior-operation))
        model (assoc history
                     :session session :entries (active-entries session entries)
                     :queue (vec (or (:queue state) (:queues state) (:queue snapshot) []))
                     :operation operation :phase (or (:phase state) :idle)
                     :cursor cursor :snapshot-cursor cursor
                     :streams {:operation-id (:operation-id state) :content "" :reasoning ""})]
    (reduce apply-event (seed-recorded-activities model) (remove past? events))))

(defn- put-activity [model activity]
  (let [id (:id activity)
        known? (contains? (:activities model) id)]
    (cond-> (assoc-in model [:activities id]
                      (merge (get-in model [:activities id]) activity))
      (and id (not known?)) (update :activity-order conj id))))

(defn- event-presentation [event]
  (let [presentation (field event :presentation)
        content (field presentation :content)
        error (field presentation :error)]
    (cond-> {}
      (string? content) (assoc :presentation (safe-text content))
      (string? error) (assoc :presentation-error (safe-text error)))))

(def ^:private activity-presentation-types
  #{:evaluation/started :evaluation/completed
    :capability/started :capability/completed})

(defn- add-event-presentation [model event]
  (let [{:keys [presentation presentation-error]} (event-presentation event)]
    (if (or (contains? activity-presentation-types (:type event))
            (and (nil? presentation) (nil? presentation-error)))
      model
      (let [id (str "presentation:"
                    (or (:seq event) (:id event)
                        (str (:operation-id event) ":" (:type event) ":" (:time event))))
            row {:id id
                 :kind :presentation
                 :text (str (or presentation "")
                            (when presentation-error
                              (str (when presentation "\n\n")
                                   "Renderer error (canonical event preserved): "
                                   presentation-error)))
                 :error? (boolean presentation-error)}
            rows (replace-by-id (:presentations model) row)
            rows (if (> (count rows) max-presentation-rows)
                   (subvec rows (- (count rows) max-presentation-rows))
                   rows)]
        (assoc model :presentations rows)))))

(defn- start-activity [model event kind]
  (let [data (:data event)
        id (:call-id data)
        presentation (event-presentation event)]
    (if-not id
      model
      (put-activity
       model
       (cond-> (merge {:id id
                       :parent-id (:parent-call-id data)
                       :kind kind
                       :name (when (= kind :capability) (:name data))
                       :content ""
                       :details {}
                       :result nil
                       :status :running
                       :start-seq (:seq event) :started-at (:time event)
                       :operation-id (:operation-id event)}
                      presentation)
         (= kind :evaluation) (assoc :source (:source data))
         (= kind :capability) (assoc :arguments (:arguments data)))))))

(defn- failure-status [data]
  (if-not (:error? data)
    :completed
    (let [raw-code (or (get-in data [:details :error/code])
                       (get-in data [:details :code]))
          code (some-> (if (keyword? raw-code) (name raw-code) raw-code)
                       str str/lower-case)]
      (if (contains? #{"cancelled" "interrupted"} code)
        :interrupted
        :failed))))

(defn- complete-activity [model event kind]
  (let [data (:data event)
        id (:call-id data)
        presentation (event-presentation event)]
    (if-not id
      model
      (let [prior (get-in model [:activities id])
            base (cond-> {:id id
                          :parent-id (:parent-call-id data)
                          :kind kind
                          :name (when (= kind :capability) (:name data))
                          :content ""
                          :details {}
                          :result nil
                          :start-seq (:seq event) :started-at (:time event)
                          :operation-id (:operation-id event)}
                   (= kind :evaluation) (assoc :source (:source data))
                   (= kind :capability) (assoc :arguments (:arguments data)))
            activity (merge base prior
                            (select-keys data [:name :source :arguments :content :result])
                            presentation
                            {:details (merge (:details prior) (:details data))
                             :status (failure-status data)})]
        (put-activity model activity)))))

(defn- append-limited [current addition remaining]
  (let [current (or current "")
        addition (if (nil? addition) "" (str addition))
        take-count (min (count addition) (max 0 remaining))]
    [(str current (subs addition 0 take-count))
     (< take-count (count addition))]))

(defn- append-progress [model event]
  (let [data (:data event)
        id (:call-id data)
        activity (get-in model [:activities id])]
    (if-not activity
      model
      (let [stream (or (:stream data) :output)
            chunk (:content data)
            used (count (or (:content activity) ""))
            remaining (- max-progress-characters used)
            [content truncated?] (append-limited (:content activity) chunk remaining)
            stream-used (reduce + 0 (map count (vals (or (:progress activity) {}))))
            stream-remaining (- max-progress-characters stream-used)
            [stream-content stream-truncated?]
            (append-limited (get-in activity [:progress stream]) chunk stream-remaining)]
        (put-activity model
                      (cond-> (assoc activity
                                     :content content
                                     :progress (assoc (or (:progress activity) {})
                                                      stream stream-content))
                        (or truncated? stream-truncated?)
                        (assoc-in [:details :progress-truncated?] true)))))))

(defn- clear-streams [model]
  (assoc model :streams {:operation-id (get-in model [:streams :operation-id])
                         :content "" :reasoning ""}))

(defn- append-stream [model event stream-key delta]
  (let [current (get-in model [:streams stream-key] "")
        remaining (- max-progress-characters (count current))
        [content truncated?] (append-limited current delta remaining)]
    (cond-> (-> model
                (assoc-in [:streams :operation-id] (:operation-id event))
                (assoc-in [:streams stream-key] content))
      truncated? (assoc-in [:streams :truncated?] true))))

(defn- apply-provider-event [model event]
  (let [provider-event (:data event)
        event-type (:event/type provider-event)]
    (case event-type
      :stream/start (assoc model :streams {:operation-id (:operation-id event)
                                           :content "" :reasoning ""})
      :stream/content-delta
      (append-stream model event :content (:event/delta provider-event))
      :stream/reasoning-delta
      (if (:event/encrypted provider-event)
        model
        (append-stream model event :reasoning (:event/delta provider-event)))
      model)))

(defn- insert-entry [model entry]
  (let [entries (:entries model)
        existing? (some #(= (:id entry) (:id %)) entries)
        candidates (replace-by-id entries entry)
        parent (:parent-id entry)
        current-head (:id (peek entries))
        entries (cond
                  existing? candidates
                  (or (empty? entries) (= parent current-head)) (conj entries entry)
                  (some #(= parent (:id %)) entries)
                  (active-entries {:head (:id entry)} candidates)
                  :else entries)
        model (-> model
                  (assoc :entries entries)
                  (assoc-in [:session :head] (:id (peek entries))))]
    (if (and (= :message (:kind entry))
             (= :assistant (get-in entry [:data :message/role])))
      (clear-streams model)
      model)))

(defn- upsert-queue [queue item]
  (let [id (:id item)
        prior (some #(when (= id (:id %)) %) queue)]
    (replace-by-id queue (merge prior item))))

(defn- remove-queue-ids [model ids]
  (let [ids (set ids)]
    (update model :queue #(vec (remove (comp ids :id) %)))))

(defn- operation-state [model event status]
  (let [data (:data event)
        operation (cond-> (merge (when-not (= status :running) (:operation model))
                                  (:operation data)
                                  (select-keys data [:kind :result :error])
                                  (event-presentation event)
                                  {:id (:operation-id event) :status status})
                    (and (= status :running) (:time event)) (assoc :started-at (:time event)))
        session-status (case status
                         :running :running
                         :completed :idle
                         :failed :failed
                         :cancelled :interrupted
                         :interrupted :interrupted
                         nil)]
    (cond-> (assoc model :operation operation)
      (= status :running) (assoc :phase :starting)
      (contains? #{:completed :failed :cancelled :interrupted} status) (assoc :phase :idle)
      session-status (assoc-in [:session :status] session-status)
      (= status :running) (assoc :streams {:operation-id (:operation-id event)
                                           :content "" :reasoning ""}))))

(defn- interrupt-running [model event ids]
  (let [ids (set ids)]
    (update model :activities
            (fn [activities]
              (reduce-kv
               (fn [result id activity]
                 (assoc result id
                        (if (and (= :running (:status activity))
                                 (= (:operation-id event) (:operation-id activity))
                                 (or (empty? ids) (contains? ids id)))
                          (assoc activity :status :interrupted)
                          activity)))
               {} activities)))))

(defn- event-seq [event]
  (let [seq (:seq event)]
    (when (number? seq) seq)))

(defn apply-event
  "Reduces one durable or transient runtime event. Durable sequence numbers at
  or behind :cursor are ignored."
  [model raw-event]
  (let [decoded (decode-wire raw-event)
        event (if (and (map? (:event decoded)) (nil? (:type decoded)))
                (:event decoded)
                decoded)
        seq (event-seq event)]
    (if (and seq (<= seq (or (:cursor model) 0)))
      model
      (let [type (:type event)
            data (:data event)
            next-model
            (case type
              :entry/committed
              (if-let [entry (:entry data)] (insert-entry model entry) model)

              :evaluation/started (start-activity model event :evaluation)
              :evaluation/completed (complete-activity model event :evaluation)
              :capability/started (start-activity model event :capability)
              :capability/completed (complete-activity model event :capability)
              :tool-progress (append-progress model event)
              :provider-event (apply-provider-event model event)

              :operation/phase
              (if (and (= (:operation-id event) (get-in model [:operation :id]))
                       (contains? #{:running :cancelling} (get-in model [:operation :status])))
                (assoc model :phase (:phase data)) model)

              :operation/started (operation-state model event :running)
              :operation/cancelling (operation-state model event :cancelling)
              :operation/completed (operation-state model event :completed)
              :operation/failed (-> (operation-state model event :failed)
                                    (interrupt-running event []))
              :operation/cancelled (-> (operation-state model event :cancelled)
                                       (interrupt-running event []))
              :operation/interrupted (-> (operation-state model event :interrupted)
                                         (interrupt-running event []))
              :tool/interrupted (interrupt-running model event (:call-ids data))

              :queue/enqueued
              (update model :queue upsert-queue (or (:item data) data))
              :queue/updated
              (if-let [item (:item data)] (update model :queue upsert-queue item) model)
              :queue/removed
              (remove-queue-ids model [(:id data)])
              :queue/delivered
              (remove-queue-ids model (:ids data))
              :queue/cleared
              (remove-queue-ids model (:ids data))

              :session/configured
              (if-let [config (:config data)] (assoc-in model [:session :config] config) model)
              :session/updated
              (if-let [session (:session data)] (assoc model :session session) model)
              :session/named (-> model (assoc-in [:session :name] (:name data))
                                (assoc-in [:session :metadata :title/source] (:source data)))

              ;; :message/assistant intentionally cannot insert a row. Only the
              ;; canonical :entry/committed event owns durable message identity.
              model)
            next-model (add-event-presentation next-model event)
            next-model (if seq
                         (assoc next-model :cursor (max (or (:cursor next-model) 0) seq))
                         next-model)]
        next-model))))

(defn activity-title [activity]
  (let [arguments (:arguments activity)
        path (field arguments :path)
        name (:name activity)]
    (safe-text
     (if (= :evaluation (:kind activity))
       "Execution"
       (case name
         "read" (str "Read " path)
         "write" (str "Write " path)
         "edit" (str "Edit " path)
         "bash" (str "$ " (field arguments :command))
         "powershell" (str "PowerShell " (field arguments :command))
         "mcp" (str "MCP " (field arguments :server)
                    (when-let [remote (field arguments :name)] (str " / " remote))
                    " " (field arguments :action))
         "skill" (str "Skill " (field arguments :action) " " (field arguments :name))
         "grep" (str "Search " (field arguments :pattern))
         "find" (str "Find " (field arguments :pattern))
         "ls" (str "List " (or path "."))
         (or name "Function"))))))

(defn- reasoning-content [content]
  (if (sequential? content)
    (->> content
         (keep (fn [part]
                 (when (= :reasoning (:part/type part))
                   (or (:reasoning/text part) (:text part)))))
         (str/join ""))
    ""))

(defn- message-rows [entry message]
  (let [id (:id entry)
        content (:message/content message)
        reasoning (reasoning-content content)
        text (text-content content)
        role (:message/role message)]
    (cond-> []
      (not (str/blank? reasoning))
      (conj {:kind :reasoning :id (str "reasoning:" id)
             :text (safe-text reasoning) :entry-id id})
      (or (not (str/blank? text)) (not= :assistant role))
      (conj {:kind :message :id (str "message:" id)
             :role role :text (safe-text text) :entry-id id}))))

(defn- activity-children [model id]
  (let [activities (:activities model)]
    (->> (:activity-order model)
         (filter #(= id (:parent-id (get activities %))))
         vec)))

(defn- project-activity [model id seen ancestry]
  (if (or (contains? seen id) (contains? ancestry id))
    {:rows [] :seen seen}
    (if-let [activity (get-in model [:activities id])]
      (let [seen (conj seen id)
            ancestry (conj ancestry id)
            children (activity-children model id)
            projected
            (reduce (fn [{:keys [rows seen]} child]
                      (let [child-result (project-activity model child seen ancestry)]
                        {:rows (into rows (:rows child-result))
                         :seen (:seen child-result)}))
                    {:rows [] :seen seen} children)
            row {:kind :activity :id (str "activity:" id) :activity activity}
            evaluation-wrapper? (and (= :evaluation (:kind activity)) (seq children))
            output-rows (cond
                          (and evaluation-wrapper? (= :completed (:status activity)))
                          (:rows projected)

                          evaluation-wrapper?
                          (conj (:rows projected) row)

                          :else
                          (into [row] (:rows projected)))]
        {:rows output-rows :seen (:seen projected)})
      {:rows [] :seen seen})))

(defn- entry-activity-ids [entry]
  (let [data (:data entry)]
    (case (:kind entry)
      :message
      (if (= :assistant (:message/role data))
        (mapv :tool-call/id (:message/tool-calls data))
        [])
      :evaluation (cond-> [] (get-in data [:result :id])
                    (conj (get-in data [:result :id])))
      :custom (cond-> [] (get-in data [:result :id])
                (conj (get-in data [:result :id])))
      [])))

(defn- fallback-entry-rows [entry]
  (let [data (:data entry)]
    (case (:kind entry)
      :message (message-rows entry data)
      :custom-context (message-rows entry (or (:message data) data))
      :evaluation
      (if-let [content (get-in data [:result :content])]
        [{:kind :message :id (str "message:" (:id entry)) :role :tool
          :text (safe-text content) :entry-id (:id entry)}]
        [])
      :custom
      (if-let [content (get-in data [:result :content])]
        [{:kind :message :id (str "message:" (:id entry)) :role :tool
          :text (safe-text content) :entry-id (:id entry)}]
        [])
      [])))

(defn- project-entry [model {:keys [rows seen]} entry]
  (let [activity-ids (entry-activity-ids entry)
        data (:data entry)
        tool-result-id (when (and (= :message (:kind entry))
                                  (= :tool (:message/role data)))
                         (:message/tool-call-id data))
        base-rows (if (or (and tool-result-id (contains? (:activities model) tool-result-id))
                          (and (contains? #{:evaluation :custom} (:kind entry))
                               (some #(contains? (:activities model) %) activity-ids)))
                    []
                    (fallback-entry-rows entry))
        projected
        (reduce (fn [{:keys [rows seen]} id]
                  (let [result (project-activity model id seen #{})]
                    {:rows (into rows (:rows result)) :seen (:seen result)}))
                {:rows base-rows :seen seen} activity-ids)]
    {:rows (into rows (:rows projected)) :seen (:seen projected)}))

(defn- project-unanchored [model projected]
  (let [activities (:activities model)
        operation (:operation model)
        current? (fn [activity]
                   (or (> (or (:start-seq activity) 0) (or (:snapshot-cursor model) 0))
                       (and (contains? #{:running :cancelling} (:status operation))
                            (= (:id operation) (:operation-id activity)))))
        roots (filter (fn [id]
                        (let [activity (get activities id)
                              parent (:parent-id activity)]
                          (and (current? activity)
                               (or (nil? parent) (not (contains? activities parent))))))
                      (:activity-order model))]
    (reduce (fn [{:keys [rows seen]} id]
              (let [result (project-activity model id seen #{})]
                {:rows (into rows (:rows result)) :seen (:seen result)}))
            projected roots)))

(defn- groupable-read? [row]
  (let [activity (:activity row)]
    (and (= :activity (:kind row))
         (= :capability (:kind activity))
         (= "read" (:name activity))
         (= :completed (:status activity)))))

(defn- flush-read-buffer [result buffer]
  (case (count buffer)
    0 result
    1 (conj result (first buffer))
    (conj result {:kind :read-group
                  :id (str "read-group:" (get-in buffer [0 :activity :id]))
                  :activities (mapv :activity buffer)})))

(defn- group-reads [rows]
  (loop [remaining rows, result [], buffer []]
    (if-let [row (first remaining)]
      (if (and (groupable-read? row)
               (or (empty? buffer)
                   (= (get-in row [:activity :parent-id])
                      (get-in buffer [0 :activity :parent-id]))))
        (recur (next remaining) result (conj buffer row))
        (recur (next remaining) (conj (flush-read-buffer result buffer) row) []))
      (flush-read-buffer result buffer))))

(defn rows
  "Projects stable, renderer-independent transcript rows from the active path."
  [model]
  (let [projected (reduce (partial project-entry model)
                          {:rows [] :seen #{}}
                          (:entries model))
        projected (project-unanchored model projected)
        stream-id (or (get-in model [:streams :operation-id]) "live")
        reasoning (get-in model [:streams :reasoning])
        content (get-in model [:streams :content])
        live-rows (cond-> []
                    (not (str/blank? reasoning))
                    (conj {:kind :reasoning
                           :id (str "reasoning:stream:" stream-id)
                           :text (safe-text reasoning)
                           :streaming? true})
                    (not (str/blank? content))
                    (conj {:kind :message
                           :id (str "message:stream:" stream-id)
                           :role :assistant
                           :text (safe-text content)
                           :entry-id nil
                           :streaming? true}))]
    (group-reads (into (into (:rows projected) (:presentations model)) live-rows))))
