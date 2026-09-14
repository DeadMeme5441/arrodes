(ns arrodes.session
  "Pure session construction and history projections."
  (:require [clojure.string :as str]
            [arrodes.value :as value]))

(def default-config
  {:provider :codex-backend
   :model "gpt-6-astra"
   :thinking :medium
   :tools :all
   :instructions ""
   :settings {}})

(def ^:private secret-key-pattern
  #?(:clj #"(?i)(api[-_]?key|password|secret|authorization|access[-_]?token|refresh[-_]?token)"
     :cljs (js/RegExp. "(api[-_]?key|password|secret|authorization|access[-_]?token|refresh[-_]?token)" "i")))

(def ^:private secret-reference-pattern
  #?(:clj #"(?i)(?:env|environment)(?:[-_]?var(?:iable)?)?s?$"
     :cljs (js/RegExp. "(?:env|environment)(?:[-_]?var(?:iable)?)?s?$" "i")))

(defn- secret-key? [key]
  (let [key-name (if (or (keyword? key) (symbol? key)) (name key) (str key))]
    (and (re-find secret-key-pattern key-name)
         (not (re-find secret-reference-pattern key-name)))))

(defn- secret-path [value path]
  (cond
    (map? value) (some (fn [[k v]]
                         (if (secret-key? k)
                           (conj path k)
                           (secret-path v (conj path k))))
                       value)
    (sequential? value) (some identity (map-indexed #(secret-path %2 (conj path %1)) value))
    (set? value) (some #(secret-path % (conj path :set-member)) value)
    :else nil))

(defn normalize-config
  "Returns a complete public configuration and rejects credential-shaped fields."
  [config]
  (let [config (or config {})
        secret (secret-path config [])
        result (merge default-config config)
        result (cond-> result
                 (string? (:provider result)) (update :provider keyword)
                 (string? (:thinking result)) (update :thinking keyword)
                 (and (string? (:tools result)) (= "all" (:tools result))) (assoc :tools :all)
                 (sequential? (:tools result)) (update :tools #(mapv (fn [tool] (if (keyword? tool) (name tool) (str tool))) %)))]
    (value/check! (nil? secret) :secret-config
                  "Provider credentials cannot be stored in a session configuration"
                  {:path secret})
    (value/check! (keyword? (:provider result)) :invalid-config "Session provider must be a keyword" {:field :provider})
    (value/check! (and (string? (:model result)) (not (str/blank? (:model result))))
                  :invalid-config "Session model must be a non-empty string" {:field :model})
    (value/check! (contains? #{:none :minimal :low :medium :high :xhigh :max} (:thinking result))
                  :invalid-config "Invalid thinking level" {:field :thinking :value (:thinking result)})
    (value/check! (or (= :all (:tools result))
                      (and (vector? (:tools result)) (every? string? (:tools result))))
                  :invalid-config "Session tools must be :all or a vector of names" {:field :tools})
    (value/check! (string? (:instructions result)) :invalid-config "Session instructions must be a string" {:field :instructions})
    (value/check! (map? (:settings result)) :invalid-config "Session settings must be a map" {:field :settings})
    result))

(defn new-snapshot
  "Constructs a canonical session snapshot from prepared portable values.

  IDs, timestamps, and canonical filesystem paths are supplied by the JVM store
  boundary rather than fabricated by this pure model."
  [opts]
  (let [now (:created-at opts)
        _ (value/check! (or (nil? (:labels opts)) (sequential? (:labels opts)))
                        :invalid-session "Session labels must be sequential" {})
        snapshot
        (cond-> {:id (:id opts)
                 :name (or (:name opts) "Untitled session")
                 :cwd (:cwd opts)
                 :head nil
                 :revision (or (:revision opts) 0)
                 :config (normalize-config (:config opts))
                 :status (or (:status opts) :idle)
                 :created-at now
                 :updated-at (or (:updated-at opts) now)
                 :metadata (or (:metadata opts) {})
                 :labels (vec (or (:labels opts) []))}
          (:parent-id opts) (assoc :parent-id (:parent-id opts))
          (:fork-entry opts) (assoc :fork-entry (:fork-entry opts)))]
    (value/check! (and (string? (:id snapshot)) (not (str/blank? (:id snapshot))))
                  :invalid-session "Session id must be a non-blank string" {})
    (value/check! (and (string? (:name snapshot)) (not (str/blank? (:name snapshot))))
                  :invalid-session "Session name must be a non-blank string" {})
    (value/check! (and (string? (:cwd snapshot)) (not (str/blank? (:cwd snapshot))))
                  :invalid-session "Session cwd must be a non-blank string" {})
    (value/check! (and (integer? (:revision snapshot)) (not (neg? (:revision snapshot))))
                  :invalid-session "Session revision must be a non-negative integer"
                  {:revision (:revision snapshot)})
    (value/check! (contains? #{:idle :running :failed :interrupted} (:status snapshot))
                  :invalid-session "Invalid session status" {:status (:status snapshot)})
    (value/check! (and (integer? (:created-at snapshot)) (not (neg? (:created-at snapshot)))
                       (integer? (:updated-at snapshot)) (not (neg? (:updated-at snapshot))))
                  :invalid-session "Session timestamps must be non-negative integers" {})
    (value/check! (map? (:metadata snapshot)) :invalid-session
                  "Session metadata must be a map" {})
    snapshot))

(defn active-path
  "Returns the root-to-leaf path from an entry collection. Throws for a broken graph."
  ([entries]
   (active-path entries (:id (last entries))))
  ([entries leaf]
   (if (nil? leaf)
     []
     (let [by-id (into {} (map (juxt :id identity)) entries)]
       (loop [id leaf, seen #{}, result ()]
         (value/check! (not (contains? seen id)) :invalid-history "Entry history contains a cycle" {:entry-id id})
         (let [entry (get by-id id)]
           (value/check! entry :entry-not-found "Entry is not part of this session" {:entry-id id})
           (if-let [parent (:parent-id entry)]
             (recur parent (conj seen id) (conj result entry))
             (vec (conj result entry)))))))))

(defn effective-config
  "Projects the effective configuration at the end of a path."
  ([base-config path]
   (reduce (fn [config entry]
             (if (= :config (:kind entry))
               (normalize-config (:data entry))
               config))
           (normalize-config base-config)
           path))
  ([snapshot entries leaf]
   (effective-config (:config snapshot) (active-path entries leaf))))

(defn- context-entry [messages {:keys [kind data]}]
  (case kind
    :message (conj messages data)
    :custom-context (conj messages (if (contains? data :message/role)
                                     data
                                     (or (:message data) data)))
    :branch-summary (conj messages
                          {:message/role :user
                           :message/content (str "Conversation branch summary:\n" (:summary data))})
    messages))

(defn context-messages
  "Projects canonical provider messages from an active path.

  The latest compaction replaces the discarded prefix with its summary, then restores
  the suffix beginning at :first-kept-entry-id before projecting entries after the
  marker. Branch summaries and custom-context entries participate in context."
  [path]
  (let [marker-index (last (keep-indexed #(when (= :compaction (:kind %2)) %1) path))]
    (if (nil? marker-index)
      (reduce context-entry [] path)
      (let [marker (nth path marker-index)
            kept-id (get-in marker [:data :first-kept-entry-id])
            kept-index (when kept-id
                         (first (keep-indexed #(when (= kept-id (:id %2)) %1)
                                              (subvec (vec path) 0 marker-index))))
            _ (when kept-id
                (value/check! (some? kept-index) :invalid-history
                              "Compaction retained entry is not on its active prefix"
                              {:entry-id (:id marker) :first-kept-entry-id kept-id}))
            summary {:message/role :user
                     :message/content (str "Conversation summary:\n" (get-in marker [:data :summary]))
                     :message/compaction {:first-kept-entry-id kept-id
                                          :usage (get-in marker [:data :usage])}}
            retained (if kept-index (subvec (vec path) kept-index marker-index) [])
            following (subvec (vec path) (inc marker-index))]
        (reduce context-entry [summary] (concat retained following))))))

(defn tree
  "Returns chronological entries annotated with a recursive :children vector."
  [entries]
  (let [children (group-by :parent-id entries)
        build (fn build [entry]
                (assoc entry :children (mapv build (sort-by (juxt :seq :created-at :id)
                                                            (get children (:id entry) [])))))]
    (mapv build (sort-by (juxt :seq :created-at :id) (get children nil [])))))
