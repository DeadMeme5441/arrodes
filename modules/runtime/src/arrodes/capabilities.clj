(ns arrodes.capabilities
  "Session-local capability registry, invocation pipeline, hooks, ownership,
  locking, durable result projection, and persistent REPL namespace."
  (:refer-clojure :exclude [catalog definitions])
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [arrodes.artifacts :as artifacts]
            [arrodes.coding :as coding]
            [arrodes.repl :as repl]
            [arrodes.util :as util])
  (:import (java.io ByteArrayOutputStream)
           (java.lang AutoCloseable)
           (java.nio.charset StandardCharsets)
           (java.util Base64)
           (java.util.concurrent.locks Lock ReentrantLock ReentrantReadWriteLock)))

(def ^:private max-output-bytes (* 50 1024))
(def ^:private max-output-lines 2000)
(def ^:private max-inline-value-bytes (* 32 1024))
(def ^:private max-durable-result-bytes (* 4 1024 1024))
(def ^:private max-artifact-helper-bytes (* 32 1024 1024))
(def ^:private max-content-parts 256)
(def ^:private max-retained-output-characters (* 16 1024 1024))
(def ^:private max-durable-nodes 100000)
(def ^:private hook-points
  #{:before-invoke :after-invoke :transform-context :transform-request
    :input :before-run :after-run})

(def ^:dynamic *invocation-context* nil)
(def ^:private ^ThreadLocal invocation-stack (ThreadLocal.))
(defonce ^:private namespace-lock (Object.))

(defrecord Registry
  [session-id cwd store config emit! get-session namespace capabilities order
   hooks tool-selection wrapper-vars mutation-locks sequential-lock exclusive-lock
   live-values resources closed?])

(declare register! invoke! invoke-value! catalog result-value artifact-value close!)

(defn- ensure-open! [registry]
  (util/check! (not @(:closed? registry)) :registry-closed
               "Capability registry is closed" {:session-id (:session-id registry)}))
(defn- public-descriptor [descriptor]
  (cond-> (select-keys descriptor
                       [:name :description :parameters :execution :permission :owner])
    (fn? (:permission descriptor)) (assoc :permission :custom)))

(defn- schema-get [schema key]
  (if (contains? schema key)
    (get schema key)
    (get schema (name key))))

(defn- value-at [value key]
  (let [keyword-key (keyword key)]
    (if (contains? value keyword-key) (get value keyword-key) (get value key))))

(defn- contains-property? [value key]
  (or (contains? value (keyword key)) (contains? value key)))
(defn- schema-type-key [type]
  (if (keyword? type) type (keyword (str type))))


(defn- schema-type? [type value]
  (case (schema-type-key type)
    :object (map? value)
    :array (vector? value)
    :string (string? value)
    :integer (integer? value)
    :number (number? value)
    :boolean (instance? Boolean value)
    :null (nil? value)
    true))

(defn- validation-error! [path message data]
  (util/fail! :invalid-arguments message (merge {:path path} data)))

(declare validate-schema!)

(defn- validate-object! [schema value path]
  (let [properties (or (schema-get schema :properties) {})
        required (or (schema-get schema :required) [])]
    (doseq [property required]
      (when-not (contains-property? value property)
        (validation-error! (conj path property) (str "Missing required argument: " property) {})))
    (when (false? (schema-get schema :additionalProperties))
      (let [allowed (set (map (comp name key) properties))]
        (doseq [provided (keys value)]
          (when-not (contains? allowed (name provided))
            (validation-error! (conj path (name provided))
                               (str "Unknown argument: " (name provided)) {})))))
    (doseq [[property child-schema] properties]
      (when (contains-property? value (name property))
        (validate-schema! child-schema (value-at value (name property)) (conj path (name property)))))))

(defn- validate-array! [schema value path]
  (when-let [minimum (schema-get schema :minItems)]
    (when (< (count value) minimum)
      (validation-error! path (str "Expected at least " minimum " items") {:minimum minimum})))
  (when-let [maximum (schema-get schema :maxItems)]
    (when (> (count value) maximum)
      (validation-error! path (str "Expected no more than " maximum " items") {:maximum maximum})))
  (when-let [item-schema (schema-get schema :items)]
    (doseq [[index item] (map-indexed vector value)]
      (validate-schema! item-schema item (conj path index)))))

(defn- validate-schema! [schema value path]
  (when-let [alternatives (or (schema-get schema :oneOf) (schema-get schema :anyOf))]
    (let [valid? (some (fn [alternative]
                         (try (validate-schema! alternative value path) true
                              (catch clojure.lang.ExceptionInfo _ false)))
                       alternatives)]
      (when-not valid? (validation-error! path "Value does not satisfy any allowed schema" {}))))
  (when-let [enum-values (schema-get schema :enum)]
    (when-not (some #(= % value) enum-values)
      (validation-error! path "Value is not one of the allowed values" {:allowed enum-values :value value})))
  (when-let [type (schema-get schema :type)]
    (let [type-key (schema-type-key type)]
      (when-not (schema-type? type value)
        (validation-error! path (str "Expected " (name type-key))
                           {:expected type :actual (some-> value class .getName)}))
      (case type-key
        :object (validate-object! schema value path)
        :array (validate-array! schema value path)
        :string (do
                  (when-let [minimum (schema-get schema :minLength)]
                    (when (< (count value) minimum)
                      (validation-error! path (str "String must contain at least " minimum " characters") {})))
                  (when-let [maximum (schema-get schema :maxLength)]
                    (when (> (count value) maximum)
                      (validation-error! path (str "String must contain no more than " maximum " characters") {}))))
        nil)
      (when (contains? #{:number :integer} type-key)
        (when-let [minimum (schema-get schema :minimum)]
          (when (< value minimum)
            (validation-error! path (str "Value must be at least " minimum) {})))
        (when-let [minimum (schema-get schema :exclusiveMinimum)]
          (when (<= value minimum)
            (validation-error! path (str "Value must be greater than " minimum) {})))
        (when-let [maximum (schema-get schema :maximum)]
          (when (> value maximum)
            (validation-error! path (str "Value must be no more than " maximum) {}))))))
  value)

(defn- parse-arguments [arguments]
  (cond
    (map? arguments) arguments
    (string? arguments)
    (try
      (let [parsed (json/read-str arguments :key-fn keyword)]
        (util/check! (map? parsed) :invalid-arguments "Tool arguments JSON must decode to an object" {})
        parsed)
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Throwable error
        (util/fail! :invalid-arguments (str "Tool arguments are not valid JSON: " (ex-message error)) {})))
    (nil? arguments) {}
    :else (util/fail! :invalid-arguments "Tool arguments must be a map or JSON object string"
                      {:actual (some-> arguments class .getName)})))

(defn- wrapper-symbol [registry name]
  (or (get @(:wrapper-vars registry) name)
      (let [base (-> name (str/replace #"[^A-Za-z0-9*+!_?.<>=$%-]" "_")
                     (#(if (re-matches #"^[0-9].*" %) (str "tool_" %) %)))
            used (set (vals @(:wrapper-vars registry)))
            candidate (loop [index 0]
                        (let [value (symbol (if (zero? index) base (str base "_" index)))]
                          (if (contains? used value) (recur (inc index)) value)))]
        (swap! (:wrapper-vars registry) assoc name candidate)
        candidate)))

(defn- install-wrapper! [registry name]
  (let [symbol (wrapper-symbol registry name)
        ns-object (the-ns (:namespace registry))
        function (fn
                   ([] (invoke-value! registry name {}))
                   ([arguments] (invoke-value! registry name arguments)))]
    (when (ns-resolve ns-object symbol) (ns-unmap ns-object symbol))
    (intern ns-object
            (with-meta symbol {:doc (str "Invoke registered capability " name " through the shared pipeline.")
                               :capability/name name})
            function)
    symbol))

(defn register!
  "Registers a descriptor and installs a same-pipeline wrapper in the session
  namespace. Replacement must be explicit; changing owner additionally requires
  :replace-owner? true. Returns registry."
  [registry descriptor]
  (ensure-open! registry)
  (let [{:keys [name description parameters execution permission owner replace? replace-owner?]} descriptor
        owner (or owner "anonymous")
        execution (or execution :parallel)
        permission (or permission :execute)
        current (get @(:capabilities registry) name)]
    (util/check! (and (string? name) (not (str/blank? name))) :invalid-capability
                 "Capability name must be a non-empty string" {})
    (util/check! (string? (or description "")) :invalid-capability
                 "Capability description must be a string" {:name name})
    (util/check! (map? parameters) :invalid-capability
                 "Capability parameters must be a JSON Schema map" {:name name})
    (util/check! (fn? (:fn descriptor)) :invalid-capability
                 "Capability descriptor requires a function" {:name name})
    (util/check! (contains? #{:parallel :sequential :exclusive} execution) :invalid-capability
                 "Capability execution must be parallel, sequential, or exclusive"
                 {:name name :execution execution})
    (util/check! (or (contains? #{:read :write :execute} permission) (fn? permission))
                 :invalid-capability "Capability permission must be read, write, execute, or a function"
                 {:name name})
    (when current
      (util/check! replace? :duplicate-capability
                   (str "Capability already registered: " name) {:name name :owner (:owner current)})
      (util/check! (or (= owner (:owner current)) replace-owner?) :capability-owner-mismatch
                   "Replacing a capability owned by another component requires :replace-owner? true"
                   {:name name :owner owner :current-owner (:owner current)}))
    (let [normalized (-> descriptor
                         (assoc :owner owner :execution execution :permission permission
                                :description (or description ""))
                         (dissoc :replace? :replace-owner?))]
      (swap! (:capabilities registry) assoc name normalized)
      (when-not current (swap! (:order registry) conj name))
      (install-wrapper! registry name)
      registry)))

(defn unregister!
  "Removes one capability. Returns true only when a capability was removed."
  [registry name]
  (ensure-open! registry)
  (if-not (contains? @(:capabilities registry) name)
    false
    (do
      (swap! (:capabilities registry) dissoc name)
      (swap! (:order registry) #(vec (remove #{name} %)))
      (when-let [symbol (get @(:wrapper-vars registry) name)]
        (ns-unmap (the-ns (:namespace registry)) symbol))
      (swap! (:wrapper-vars registry) dissoc name)
      true)))

(defn add-hook!
  "Adds an ordered attributed hook and returns its normalized descriptor."
  [registry point descriptor]
  (ensure-open! registry)
  (util/check! (contains? hook-points point) :invalid-hook-point
               (str "Unsupported hook point: " point) {:point point})
  (let [hook (merge {:order 0 :owner "anonymous"} descriptor {:point point})
        {:keys [id owner order]} hook]
    (util/check! (and (string? id) (not (str/blank? id))) :invalid-hook "Hook id must be a non-empty string" {})
    (util/check! (string? owner) :invalid-hook "Hook owner must be a string" {:id id})
    (util/check! (integer? order) :invalid-hook "Hook order must be an integer" {:id id})
    (util/check! (fn? (:fn hook)) :invalid-hook "Hook descriptor requires a function" {:id id})
    (util/check! (not-any? #(= id (:id %)) (mapcat val @(:hooks registry))) :duplicate-hook
                 (str "Hook id already registered: " id) {:id id})
    (swap! (:hooks registry) update point (fnil conj []) hook)
    (dissoc hook :fn)))

(defn remove-hook!
  "Removes a hook by globally unique id and returns whether it existed."
  [registry id]
  (ensure-open! registry)
  (let [found? (boolean (some #(= id (:id %)) (mapcat val @(:hooks registry))))]
    (when found?
      (swap! (:hooks registry)
             (fn [points]
               (into {} (map (fn [[point hooks]] [point (vec (remove #(= id (:id %)) hooks))]) points)))))
    found?))

(defn apply-hooks
  "Applies an immutable, stably ordered snapshot of hooks for point. Hook
  failures carry the hook id, owner, and point in ex-data."
  [registry point context value]
  (ensure-open! registry)
  (util/check! (contains? hook-points point) :invalid-hook-point
               (str "Unsupported hook point: " point) {:point point})
  (reduce
    (fn [current hook]
      (try
        ((:fn hook) context current)
        (catch Throwable error
          (throw (ex-info (str "Hook " (:id hook) " failed at " (name point) ": "
                               (or (ex-message error) (.getName (class error))))
                          (merge {:error/code "hook-failed" :hook/id (:id hook)
                                  :hook/owner (:owner hook) :hook/point point}
                                 (ex-data error))
                          error)))))
    value
    (sort-by (juxt :order :owner :id) (get @(:hooks registry) point []))))

(defn- close-owner-resources! [registry owner]
  (let [owned (filter #(= owner (:owner %)) @(:resources registry))]
    (swap! (:resources registry) #(vec (remove (fn [item] (= owner (:owner item))) %)))
    (doseq [{:keys [value]} (reverse owned)]
      (try
        (cond
          (instance? AutoCloseable value) (.close ^AutoCloseable value)
          (fn? value) (value))
        (catch Throwable _ nil)))))

(defn withdraw!
  "Removes every capability and hook attributed to owner and closes resources
  returned by that owner's invocations."
  [registry owner]
  (ensure-open! registry)
  (let [tools (->> @(:capabilities registry) (keep (fn [[name descriptor]]
                                                    (when (= owner (:owner descriptor)) name))) vec)
        hook-ids (->> @(:hooks registry) vals (mapcat identity)
                      (keep #(when (= owner (:owner %)) (:id %))) vec)]
    (doseq [name tools] (unregister! registry name))
    (doseq [id hook-ids] (remove-hook! registry id))
    (close-owner-resources! registry owner)
    {:owner owner :tools tools :hooks hook-ids}))

(defn catalog
  "Returns public descriptors in deterministic name order."
  [registry]
  (ensure-open! registry)
  (let [all @(:capabilities registry)]
    (->> @(:order registry)
         (keep #(some-> (get all %) public-descriptor))
         (sort-by :name)
         vec)))

(defn set-tools!
  "Sets the provider-visible selection to :all or a vector of capability names."
  [registry selection]
  (ensure-open! registry)
  (util/check! (or (= :all selection)
                   (and (vector? selection) (every? string? selection)))
               :invalid-tool-selection
               "Tool selection must be :all or a vector of names" {:tools selection})
  (when (vector? selection)
    (util/check! (= (count selection) (count (distinct selection)))
                 :invalid-tool-selection "Tool selection contains duplicate names"
                 {:tools selection})
    (doseq [name selection]
      (util/check! (contains? @(:capabilities registry) name) :unknown-capability
                   (str "Unknown capability in selection: " name) {:name name})))
  (reset! (:tool-selection registry) selection)
  registry)

(defn definitions
  "Returns canonical llm.sdk function-tool definitions in deterministic order."
  ([registry] (definitions registry @(:tool-selection registry)))
  ([registry selection]
   (ensure-open! registry)
   (util/check! (or (= :all selection)
                    (and (vector? selection) (every? string? selection)))
                :invalid-tool-selection
                "Tool selection must be :all or a vector of names" {:tools selection})
   (when (vector? selection)
     (util/check! (= (count selection) (count (distinct selection)))
                  :invalid-tool-selection "Tool selection contains duplicate names"
                  {:tools selection}))
   (let [descriptors (catalog registry)
         selected (if (= :all selection)
                    descriptors
                    (let [by-name (into {} (map (juxt :name identity) descriptors))]
                      (->> selection
                           (map (fn [name]
                                  (or (get by-name name)
                                      (util/fail! :unknown-capability
                                                  (str "Unknown capability in selection: " name)
                                                  {:name name}))))
                           (sort-by :name)
                           vec)))]
     (mapv (fn [{:keys [name description parameters]}]
             {:type :function
              :function {:name name :description description :parameters parameters}})
           selected))))

(defn- cancellation-check! [context]
  (util/check-cancelled! (:cancelled? context)))

(defn- acquire! [^Lock lock context]
  (loop []
    (cancellation-check! context)
    (when-not (.tryLock lock 50 java.util.concurrent.TimeUnit/MILLISECONDS)
      (recur))))

(defn- with-acquired [^Lock lock context thunk]
  (acquire! lock context)
  (try (thunk) (finally (.unlock lock))))

(defn- mutation-lock [registry descriptor arguments]
  (when-let [key-source (:mutation-key descriptor)]
    (let [key (if (fn? key-source) (key-source arguments) key-source)]
      (util/check! (some? key) :invalid-mutation-key
                   "Capability mutation key function returned nil" {:name (:name descriptor)})
      (or (get @(:mutation-locks registry) key)
          (get (swap! (:mutation-locks registry)
                      #(if (contains? % key) % (assoc % key (ReentrantLock. true)))) key)))))

(defn- with-execution-lock [registry descriptor arguments context thunk]
  (let [stack (or (.get invocation-stack) [])
        enclosing (filterv #(identical? registry (:registry %)) stack)
        enclosed-by-exclusive? (some #(= :exclusive (:execution %)) enclosing)
        requested (:execution descriptor)]
    (when (and (seq enclosing) (= :exclusive requested) (not enclosed-by-exclusive?))
      (util/fail! :nested-exclusive-invocation
                  "An exclusive capability cannot be invoked while a non-exclusive capability holds the registry read lock"
                  {:name (:name descriptor)}))
    (let [^ReentrantReadWriteLock exclusive (:exclusive-lock registry)
          read-lock (.readLock exclusive)
          write-lock (.writeLock exclusive)
          mutation (mutation-lock registry descriptor arguments)
          guarded (fn []
                    (if mutation (with-acquired mutation context thunk) (thunk)))
          execute (cond
                    enclosed-by-exclusive? guarded
                    (= :exclusive requested) #(with-acquired write-lock context guarded)
                    (= :sequential requested)
                    #(with-acquired read-lock context
                       (fn [] (with-acquired (:sequential-lock registry) context guarded)))
                    :else #(with-acquired read-lock context guarded))]
      (.set invocation-stack
            (conj stack {:registry registry :execution requested :name (:name descriptor)}))
      (try (execute) (finally (.set invocation-stack stack))))))

(defn- invoke-validator! [descriptor arguments context]
  (validate-schema! (:parameters descriptor) arguments [])
  (when-let [validator (:validate descriptor)]
    (let [outcome (try (validator arguments context)
                       (catch clojure.lang.ArityException _ (validator arguments)))]
      (cond
        (or (nil? outcome) (true? outcome)) nil
        (string? outcome) (util/fail! :invalid-arguments outcome {:name (:name descriptor)})
        (map? outcome) (util/fail! :invalid-arguments
                                   (or (:message outcome) "Capability arguments failed validation") outcome)
        :else (util/check! outcome :invalid-arguments "Capability arguments failed validation"
                           {:name (:name descriptor)})))))

(defn- check-permission! [descriptor context arguments]
  (when (fn? (:permission descriptor))
    (let [allowed? (try ((:permission descriptor) context arguments)
                        (catch clojure.lang.ArityException _ ((:permission descriptor) arguments)))]
      (util/check! allowed? :permission-denied
                   (str "Permission denied for capability " (:name descriptor))
                   {:name (:name descriptor)}))))

(defn- canonical-content-parts? [value]
  (and (vector? value)
       (<= (count value) max-content-parts)
       (every? #(and (map? %) (keyword? (:part/type %))) value)
       (<= (reduce
            (fn [total part]
              (+ total (reduce (fn [n item]
                                 (+ n (if (string? item) (count item) 0)))
                               0 (vals part))))
            0 value)
           max-retained-output-characters)))

(defn- bounded-native-print [value]
  (let [{:keys [writer buffer truncated?]} (util/bounded-writer max-output-bytes)]
    (binding [*out* writer *print-length* 200 *print-level* 20]
      (pr value))
    (str buffer
         (when @truncated?
           "\n[Native value preview truncated.]"))))

(defn- native-content [value]
  (cond
    (string? value) value
    (nil? value) ""
    :else (bounded-native-print value)))

(defn- explicit-content [name value fallback]
  (let [value (if (nil? value) fallback value)]
    (util/check! (or (string? value) (canonical-content-parts? value))
                 :invalid-capability-result
                 "Capability result content must be a string or bounded canonical content parts"
                 {:name name})
    value))

(defn- normalized-result [name returned]
  (if (and (map? returned)
           (contains? returned :content)
           (or (contains? returned :value) (contains? returned :details)
               (contains? returned :error?)))
    (let [details (or (:details returned) {})]
      (util/check! (map? details) :invalid-capability-result
                   "Capability result details must be a map" {:name name})
      (-> returned
          (assoc :content (explicit-content name (:content returned) (:value returned))
                 :details details :name name)
          (update :error? boolean)))
    {:value returned :content (native-content returned) :details {} :error? false :name name}))

(defn- content-text [content]
  (let [segments (if (string? content)
                   [content]
                   (keep (fn [part]
                           (when (= :text (:part/type part)) (:text part)))
                         content))]
    (loop [remaining (seq segments) output (StringBuilder.) first? true]
      (if-let [segment (first remaining)]
        (let [separator (if first? "" "\n")
              available (- max-retained-output-characters (.length output))
              needed (+ (count separator) (count segment))
              accepted (max 0 (min available needed))]
          (when (pos? accepted)
            (let [piece (str separator segment)]
              (.append output ^String piece 0 (int accepted))))
          (if (< accepted needed)
            {:text (str output) :hard-truncated? true}
            (recur (next remaining) output false)))
        {:text (str output) :hard-truncated? false}))))

(defn- utf8-bytes [text]
  (alength (.getBytes (str text) StandardCharsets/UTF_8)))

(defn- bounded-head [text]
  (let [lines (str/split text #"\n" -1)
        candidates (take max-output-lines lines)]
    (loop [remaining candidates output [] bytes 0]
      (if-let [line (first remaining)]
        (let [line-bytes (+ (utf8-bytes line) (if (seq output) 1 0))]
          (if (> (+ bytes line-bytes) max-output-bytes)
            (str/join "\n" output)
            (recur (next remaining) (conj output line) (+ bytes line-bytes))))
        (str/join "\n" output)))))

(defn- text-truncated? [text]
  (or (> (utf8-bytes text) max-output-bytes)
      (> (count (str/split text #"\n" -1)) max-output-lines)))

(defn- bound-content! [registry call-id content details]
  (let [{:keys [text hard-truncated?]} (content-text content)]
    (if-not (or hard-truncated? (text-truncated? text))
      {:content content :details details}
      (let [artifact (artifacts/put! (:store registry) (:session-id registry) text
                                     {:kind :text :name (str "capability-" call-id "-output.txt")})
            preview (str (bounded-head text)
                         "\n\n[Output preview truncated. "
                         (if hard-truncated?
                           "Retained output prefix"
                           "Full retained output")
                         " is artifact " (:id artifact) ".]")
            bounded-content (if (string? content)
                              preview
                              (into [{:part/type :text :text preview}]
                                    (remove #(= :text (:part/type %)) content)))]
        {:content bounded-content
         :details (cond-> (assoc details :output-truncated? true :artifact artifact)
                    hard-truncated? (assoc :output-hard-truncated? true
                                           :retained-characters (count text)))}))))

(defn- durable-value? [value]
  (let [nodes (volatile! 0)
        characters (volatile! 0)
        max-characters (quot max-durable-result-bytes 4)]
    (letfn [(scalar-text? [item]
              (<= (vswap! characters + (count (str item))) max-characters))
            (durable? [item depth]
              (vswap! nodes inc)
              (and (<= @nodes max-durable-nodes)
                   (< depth 32)
                   (cond
                     (nil? item) true
                     (or (string? item) (keyword? item) (symbol? item) (char? item))
                     (scalar-text? item)
                     (or (number? item) (instance? Boolean item)) true
                     (vector? item) (and (<= (count item) 10000)
                                         (every? #(durable? % (inc depth)) item))
                     (list? item) (and (<= (count item) 10000)
                                       (every? #(durable? % (inc depth)) item))
                     (set? item) (and (<= (count item) 10000)
                                      (every? #(durable? % (inc depth)) item))
                     (and (map? item) (not (record? item)))
                     (and (<= (count item) 10000)
                          (every? (fn [[key child]]
                                    (and (durable? key (inc depth))
                                         (durable? child (inc depth))))
                                  item))
                     :else false)))]
      (durable? value 0))))

(defn- result-descriptor! [registry call-id value content details error?]
  (let [text (:text (content-text content))
        durable? (durable-value? value)
        printed (when durable?
                  (binding [*print-length* 10001 *print-level* 33] (pr-str value)))
        durable? (and durable? (<= (utf8-bytes printed) max-durable-result-bytes))
        base {:content text :details (assoc details :call-id call-id :error? error?)
              :available? true}
        pending (cond
                  (and durable? (<= (utf8-bytes printed) max-inline-value-bytes))
                  (assoc base :kind :inline :value value)

                  durable?
                  (let [artifact (artifacts/put! (:store registry) (:session-id registry) printed
                                                 {:kind :edn :name (str "result-" call-id ".edn")})]
                    (assoc base :kind :artifact :artifact-id (:id artifact)))

                  :else (assoc base :kind :live))
        persisted (artifacts/put-result! (:store registry) (:session-id registry) pending)]
    (swap! (:live-values registry) assoc (:id persisted) value)
    persisted))

(defn- failure-result [name error phase]
  {:value nil
   :content (str "Capability " name " failed: " (or (ex-message error) (.getName (class error))))
   :details (assoc (util/error-map error) :capability name :phase phase)
   :error? true})

(defn invoke!
  "Invokes one capability through hooks, final-argument validation, permission
  checks, cancellation and fair execution/mutation locks. Failures are returned
  as attributed normalized results; provider callers can pass them to
  result-message."
  [registry call options]
  (ensure-open! registry)
  (let [call-id (:id call)
        initial-name (:name call)
        phase (volatile! :arguments)
        context (merge (:context options)
                       {:registry registry :session-id (:session-id registry) :call-id call-id}
                       (select-keys options [:cancelled? :on-progress]))]
    (util/check! (and (string? call-id) (not (str/blank? call-id))) :invalid-tool-call
                 "Capability call id must be a non-empty string" {})
    (util/check! (and (string? initial-name) (not (str/blank? initial-name))) :invalid-tool-call
                 "Capability call name must be a non-empty string" {:id call-id})
    (let [raw
          (try
            (cancellation-check! context)
            (let [arguments (parse-arguments (:arguments call))
                  _ (vreset! phase :before-invoke)
                  transformed (apply-hooks registry :before-invoke context
                                           {:name initial-name :arguments arguments})
                  _ (util/check! (map? transformed) :invalid-hook-result
                                 "before-invoke hooks must return a map" {})
                  name (:name transformed)
                  final-arguments (:arguments transformed)
                  descriptor (get @(:capabilities registry) name)
                  invocation-context (assoc context :name name :descriptor (public-descriptor descriptor))]
              (util/check! descriptor :unknown-capability (str "Unknown capability: " name) {:name name})
              (util/check! (map? final-arguments) :invalid-arguments
                           "before-invoke hooks must leave arguments as a map" {:name name})
              (vreset! phase :validation)
              (invoke-validator! descriptor final-arguments invocation-context)
              (check-permission! descriptor invocation-context final-arguments)
              (cancellation-check! context)
              (vreset! phase :execution)
              (let [returned (binding [*invocation-context* invocation-context]
                               (with-execution-lock registry descriptor final-arguments context
                                 #((:fn descriptor) final-arguments)))
                    normalized (normalized-result name returned)]
                (when (instance? AutoCloseable (:value normalized))
                  (swap! (:resources registry) conj {:owner (:owner descriptor) :value (:value normalized)}))
                (cancellation-check! context)
                (vreset! phase :after-invoke)
                (let [hooked (apply-hooks registry :after-invoke invocation-context normalized)]
                  (util/check! (map? hooked) :invalid-hook-result
                               "after-invoke hooks must return a normalized result map" {:name name})
                  (normalized-result name hooked))))
            (catch Throwable error (failure-result initial-name error @phase)))
          bounded (bound-content! registry call-id (:content raw) (:details raw))
          complete (assoc raw :id call-id :name initial-name
                          :content (:content bounded) :details (:details bounded))
          descriptor (result-descriptor! registry call-id (:value complete) (:content complete)
                                         (:details complete) (:error? complete))]
      (assoc complete :result descriptor))))

(defn invoke-value!
  "Direct Clojure invocation wrapper. Uses invoke! and returns the native value,
  throwing a meaningful exception for normalized failures."
  ([registry name arguments]
   (let [context *invocation-context*]
     (invoke-value! registry name arguments
                    (cond-> {}
                      (:cancelled? context) (assoc :cancelled? (:cancelled? context))
                      (:on-progress context) (assoc :on-progress (:on-progress context))
                      context (assoc :context
                                     (dissoc context :registry :descriptor :name
                                             :call-id :cancelled? :on-progress))))))
  ([registry name arguments options]
   (let [result (invoke! registry {:id (util/id) :name name :arguments arguments} options)]
     (if (:error? result)
       (throw (ex-info (:content result)
                       (merge {:error/code (or (get-in result [:details :code]) "capability-failed")
                               :capability name :result (:result result)}
                              (dissoc (:details result) :code))))
       (:value result)))))

(defn result-message
  "Projects an invocation result as a canonical tool message without discarding
  its durable result descriptor."
  [invocation-result]
  {:message/role :tool
   :message/tool-call-id (:id invocation-result)
   :message/name (:name invocation-result)
   :message/content (:content invocation-result)
   :message/result (:result invocation-result)})

(defn- read-artifact-completely [registry id]
  (loop [offset 1
         chunks []
         bytes (ByteArrayOutputStream.)
         descriptor nil]
    (let [page (artifacts/read! (:store registry) (:session-id registry) id
                                {:offset offset :limit max-output-bytes})
          descriptor (or descriptor (:artifact page))
          _ (util/check! (<= (:bytes descriptor) max-artifact-helper-bytes)
                         :artifact-too-large
                         "Artifact is too large for the REPL helper; use artifact.read paging"
                         {:artifact-id id :bytes (:bytes descriptor)
                          :limit max-artifact-helper-bytes})
          binary? (= :binary (:kind descriptor))
          piece (if binary?
                  (.decode (Base64/getDecoder) ^String (:content page))
                  (:content page))
          _ (if binary?
              (.write bytes ^bytes piece 0 (alength ^bytes piece))
              nil)
          chunks (if binary? chunks (conj chunks piece))]
      (if-let [next-offset (:next-offset page)]
        (recur next-offset chunks bytes descriptor)
        {:artifact descriptor
         :content (if binary? (.toByteArray bytes) (str/join "" chunks))}))))

(defn artifact-value
  "Returns complete artifact content up to the REPL helper limit; larger
  artifacts must be paged through the artifact API. Cross-session access is
  rejected by arrodes.artifacts."
  [registry id]
  (:content (read-artifact-completely registry id)))

(defn result-value
  "Returns the live native value when present, otherwise reconstructs durable
  inline or EDN values. Live-only values from an earlier process fail clearly."
  [registry id]
  (ensure-open! registry)
  (if (contains? @(:live-values registry) id)
    (get @(:live-values registry) id)
    (let [descriptor (artifacts/result (:store registry) (:session-id registry) id)]
      (case (:kind descriptor)
        :inline (:value descriptor)
        :artifact (let [content (:content (read-artifact-completely registry (:artifact-id descriptor)))]
                    (try (edn/read-string content)
                         (catch Throwable error
                           (util/fail! :invalid-result-artifact
                                       (str "Could not reconstruct result " id ": " (ex-message error))
                                       {:result-id id :artifact-id (:artifact-id descriptor)}))))
        :live (util/fail! :live-result-unavailable
                          (str "Result " id " was a live JVM value and is unavailable after restart")
                          {:result-id id :available? false})
        (util/fail! :invalid-result (str "Result " id " has unknown kind")
                    {:result-id id :kind (:kind descriptor)})))))

(defn create!
  "Creates a complete session registry, persistent namespace, coding tools,
  clojure_eval, and result/artifact helpers."
  [{:keys [session-id cwd store config emit! get-session] :as options}]
  (util/check! (and (string? session-id) (not (str/blank? session-id))) :invalid-session-id
               "Capability registry requires a session id" {})
  (util/check! (and (string? cwd) (not (str/blank? cwd))) :invalid-cwd
               "Capability registry requires a cwd" {})
  (util/check! store :invalid-store "Capability registry requires a store" {})
  (let [namespace (symbol (str "arrodes.session."
                               (str/replace session-id #"[^A-Za-z0-9_]" "_")))
        ns-object (locking namespace-lock
                    (util/check! (nil? (find-ns namespace)) :session-namespace-in-use
                                 "This session already has a live evaluator namespace" {:session-id session-id})
                    (create-ns namespace))
        _ (binding [*ns* ns-object] (clojure.core/refer 'clojure.core))
        registry (map->Registry
                   {:session-id session-id :cwd (util/canonical-path cwd) :store store
                    :config (or config {}) :emit! (or emit! (fn [_] nil))
                    :get-session (or get-session (fn [] nil)) :namespace namespace :namespace-object ns-object
                    :capabilities (atom {}) :order (atom []) :hooks (atom {})
                    :tool-selection (atom (or (:tools config) :all)) :wrapper-vars (atom {})
                    :mutation-locks (atom {}) :sequential-lock (ReentrantLock. true)
                    :exclusive-lock (ReentrantReadWriteLock. true) :live-values (atom {})
                    :resources (atom []) :closed? (atom false)})
        current-context (fn [] (or *invocation-context* {}))
        callbacks {:namespace namespace :current-context current-context
                   :register! #(register! registry %)
                   :invoke-value! #(invoke-value! registry %1 %2)
                   :registered-tools #(catalog registry)
                   :result-value #(result-value registry %)
                   :artifact-value #(artifact-value registry %)}]
    (try
      (intern ns-object 'cwd (:cwd registry))
      (doseq [descriptor (coding/descriptors
                           {:cwd (:cwd registry)
                            :current-context current-context
                            :put-artifact! #(artifacts/put! store session-id %1 %2)})]
        (register! registry descriptor))
      (register! registry (repl/install! callbacks))
      registry
      (catch Throwable error
        (close! registry)
        (throw error)))))

(defn close!
  "Idempotently closes owned resources, expires live-only durable descriptors,
  and removes the session namespace. Cleanup failures are reported."
  [registry]
  (if-not (compare-and-set! (:closed? registry) false true)
    {:session-id (:session-id registry) :closed? true :already-closed? true
     :released-live-results 0 :errors []}
    (let [errors (volatile! [])
          released (try
                     (artifacts/release-live-results! (:store registry)
                                                      (:session-id registry))
                     (catch Throwable error
                       (vswap! errors conj (util/error-map error))
                       0))]
      (doseq [{:keys [value]} (reverse @(:resources registry))]
        (try
          (cond
            (instance? AutoCloseable value) (.close ^AutoCloseable value)
            (fn? value) (value))
          (catch Throwable error
            (vswap! errors conj (util/error-map error)))))
      (reset! (:resources registry) [])
      (reset! (:live-values registry) {})
      (reset! (:capabilities registry) {})
      (reset! (:order registry) [])
      (reset! (:hooks registry) {})
      (reset! (:wrapper-vars registry) {})
      (reset! (:mutation-locks registry) {})
      (locking namespace-lock
        (when (identical? (:namespace-object registry) (find-ns (:namespace registry)))
          (remove-ns (:namespace registry))))
      {:session-id (:session-id registry) :closed? true :already-closed? false
       :released-live-results released :errors @errors})))
