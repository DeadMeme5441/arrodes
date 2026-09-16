(ns arrodes.repl
  "Session-local Clojure evaluation. The caller owns serialization and retention."
  (:require [clojure.string :as str]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (clojure.lang LineNumberingPushbackReader Var)
           (java.io StringReader)))

(def ^:private eof (Object.))
(def ^:private max-source-characters (* 1024 1024))
(def ^:private max-evaluation-output-characters (* 1024 1024))
(def ^:private max-printed-value-characters (* 32 1024))


(defn- writer-text [{:keys [buffer truncated?]} label]
  (str buffer
       (when @truncated?
         (str "\n[" label " truncated at " max-evaluation-output-characters
              " characters]"))))

(defn- bounded-pr-str [value]
  (let [{:keys [writer buffer truncated?]} (util/bounded-writer max-printed-value-characters)]
    (binding [*out* writer *print-length* 200 *print-level* 20]
      (if (string? value) (print value) (pr value)))
    {:text (str buffer
                (when @truncated?
                  (str "\n[value preview truncated at " max-printed-value-characters
                       " characters]")))
     :truncated? @truncated?}))


(defn- output-capture [stream context]
  (let [{:keys [writer buffer] :as state}
        (util/bounded-writer max-evaluation-output-characters)]
    (if-let [callback (:on-progress context)]
      (let [emit-write!
            (fn [write!]
              (locking buffer
                (let [before (.length ^StringBuffer buffer)]
                  (write!)
                  (when (< before (.length ^StringBuffer buffer))
                    (try
                      (callback {:type :evaluation/output :call-id (:call-id context)
                                 :parent-call-id (:parent-call-id context) :stream stream
                                 :content (.substring ^StringBuffer buffer before)})
                      (catch Throwable _ nil))))))]
        (assoc state :writer
               (proxy [java.io.Writer] []
                 (write
                   ([value]
                    (emit-write! #(cond
                                    (number? value) (.write ^java.io.Writer writer (int value))
                                    (string? value) (.write ^java.io.Writer writer ^String value)
                                    :else (.write ^java.io.Writer writer ^chars value))))
                   ([value offset length]
                    (emit-write! #(if (string? value)
                                    (.write ^java.io.Writer writer ^String value (int offset) (int length))
                                    (.write ^java.io.Writer writer ^chars value (int offset) (int length))))))
                 (flush [] (.flush ^java.io.Writer writer))
                 (close [] (.close ^java.io.Writer writer)))))
      state)))

(defn evaluate!
  "Evaluate forms in order, preserving native values and the session's REPL history.
  Definitions take effect as each form runs; a later error does not roll them back."
  [namespace history current-context source]
  (value/check! (string? source) :invalid-arguments "Evaluation source must be a string" {})
  (value/check! (<= (count source) max-source-characters) :source-too-large
               "Evaluation source exceeds the supported size"
               {:characters (count source) :limit max-source-characters})
  (util/check-cancelled! (:cancelled? (current-context)))
  (let [context (current-context)
        out-state (output-capture :stdout context)
        err-state (output-capture :stderr context)
        progress (atom {:completed-forms 0 :form-index 1 :phase :reading})]
    (try
      (let [[value form-count]
            (binding [*ns* (the-ns namespace)
                      *read-eval* false
                      *out* (:writer out-state)
                      *err* (:writer err-state)
                      *1 (:one @history) *2 (:two @history) *3 (:three @history)
                      *e (:error @history)]
              (with-open [reader (LineNumberingPushbackReader. (StringReader. source))]
                (loop [value nil form-count 0]
                  (util/check-cancelled! (:cancelled? (current-context)))
                  (swap! progress assoc :phase :reading :form-index (inc form-count)
                         :line (.getLineNumber reader))
                  (let [form (read {:eof eof :read-cond :allow :features #{:clj}} reader)]
                    (if (identical? eof form)
                      (do (swap! progress assoc :phase :printing :form-index nil)
                          [value form-count])
                      (let [_ (swap! progress assoc :phase :evaluating
                                     :line (or (:line (meta form)) (.getLineNumber reader)))
                            next-value (eval form)]
                        (swap! progress assoc :completed-forms (inc form-count))
                        (set! *3 *2)
                        (set! *2 *1)
                        (set! *1 next-value)
                        (swap! history assoc :one *1 :two *2 :three *3)
                        (recur next-value (inc form-count))))))))
            stdout (writer-text out-state "stdout")
            stderr (writer-text err-state "stderr")
            printed-result (bounded-pr-str value)
            printed (:text printed-result)
            sections (cond-> []
                       (not (empty? stdout)) (conj (str "stdout:\n" stdout))
                       (not (empty? stderr)) (conj (str "stderr:\n" stderr))
                       true (conj (str "=> " printed)))]
        {:value value
         :content (str/join "\n" sections)
         :details {:namespace (str namespace)
                   :stdout stdout :stderr stderr :printed printed
                   :stdout-truncated? @(:truncated? out-state)
                   :stderr-truncated? @(:truncated? err-state)
                   :printed-truncated? (:truncated? printed-result)
                   :forms form-count}})
      (catch Throwable error
        (swap! history assoc :error error)
        (if (util/cancelled? (:cancelled? (current-context)))
          (value/fail! :cancelled "Clojure evaluation was cancelled"
                      {:stdout (writer-text out-state "stdout")
                       :stderr (writer-text err-state "stderr")})
          (throw
           (ex-info
            (str "Clojure evaluation failed: "
                 (or (ex-message error) (.getName (class error))))
            (merge {:error/code "evaluation-failed"
                    :stdout (writer-text out-state "stdout")
                    :stderr (writer-text err-state "stderr")
                    :exception (.getName (class error))}
                   (ex-data error) @progress)
            error)))))))

(defn- tool-name-for-var [^Var var descriptor]
  (or (:name descriptor)
      (some-> var meta :name name)))

(defn- register-var! [register! registered-implementation owner value descriptor]
  (value/check! (instance? Var value) :invalid-tool-var
               "register-tool! requires a Var, for example (register-tool! #'my-tool {...})" {})
  (let [root @^Var value
        name (tool-name-for-var value descriptor)
        metadata (meta value)
        wrapped-name (:capability/name metadata)
        wrapper-function (:capability/wrapper-function metadata)
        implementation (if (and (= name wrapped-name) (identical? root wrapper-function))
                         (or (registered-implementation name) root)
                         root)]
    (value/check! (ifn? implementation) :invalid-tool-var "The registered Var must contain a callable value"
                 {:var (str value)})
    (value/check! (and (string? name) (not (str/blank? name))) :invalid-tool-descriptor
                 "A registered tool needs a non-empty string name" {:var (str value)})
    (register! (merge {:name name
                       :description (or (:doc (meta value)) (str "Session function " name))
                       :parameters {:type "object" :properties {} :additionalProperties true}
                       :execution :sequential
                       :permission :execute
                       :owner owner}
                      descriptor
                      {:name name :owner owner
                       :fn (fn [arguments] (implementation arguments))}))
    {:name name :registered? true}))

(defn- workspace-value [namespace generation {:keys [offset limit query]
                                            :or {offset 0 limit 50 query ""}}]
  (value/check! (and (integer? offset) (<= 0 offset)
                     (integer? limit) (<= 1 limit 100) (string? query))
                :invalid-arguments "workspace expects offset >= 0, limit 1..100 and a string query" {})
  (let [bindings (->> (ns-interns namespace)
                      (remove (fn [[sym var]]
                                (or (= 'cwd sym) (:arrodes/helper (meta var))
                                    (:capability/name (meta var)))))
                      (filter (fn [[sym _]] (str/includes? (str sym) query)))
                      (sort-by (comp str key)) vec)
        selected (take limit (drop offset bindings))
        brief (fn [[sym ^Var var]]
                (let [bound? (.hasRoot var)
                      x (when bound? (.getRawRoot var))
                      metadata (meta var)]
                  (cond-> {:name (str sym) :bound? bound? :type (some-> x class .getName)}
                    (string? (:doc metadata)) (assoc :doc (subs (:doc metadata) 0 (min 1000 (count (:doc metadata)))))
                    (string? (:label metadata)) (assoc :label (subs (:label metadata) 0 (min 200 (count (:label metadata)))))
                    (or (string? x) (instance? clojure.lang.IPersistentVector x)
                        (instance? clojure.lang.IPersistentMap x) (instance? clojure.lang.IPersistentSet x))
                    (assoc :count (count x)))))]
    {:namespace (str namespace) :generation generation :bindings (mapv brief selected)
     :total-bindings (count bindings)
     :next-offset (when (< (+ offset limit) (count bindings)) (+ offset limit))}))

(defn install!
  "Install discoverable Clojure helpers. Evaluation itself is not a capability."
  [{:keys [namespace generation register! registered-implementation invoke-value!
           registered-tools result-value result-info result-page artifact-value artifact-page]}]
  (let [owner (str "repl:" namespace)
        ns-object (the-ns namespace)]
    (intern ns-object (with-meta 'result {:doc "Return a native live result or its durable reconstructed value."})
            (fn [id] (result-value id)))
    (intern ns-object (with-meta 'artifact {:doc "Read a durable artifact by id up to the bounded REPL helper limit; page larger artifacts with artifact-page."})
            (fn [id] (artifact-value id)))
    (intern ns-object (with-meta 'registered-tools {:doc "Return the current public capability catalog."})
            (fn
              ([] (registered-tools))
              ([selection]
               (if (string? selection)
                 (or (some #(when (= selection (:name %)) %) (registered-tools))
                     (value/fail! :unknown-tool "No registered function with this name" {:name selection}))
                 (do (value/check! (= {:brief? true} selection) :invalid-arguments
                                    "Use a function name or {:brief? true}" {})
                     (mapv #(select-keys % [:name :symbol :description]) (registered-tools)))))))
    (intern ns-object (with-meta 'workspace {:doc "Inspect live bindings without printing values or realizing lazy sequences. Optional {:query string :offset 0 :limit 50}."})
            (fn ([] (workspace-value namespace generation {}))
              ([opts] (workspace-value namespace generation opts))))
    (intern ns-object (with-meta 'result-info {:doc "Inspect a retained descriptor, including durable failure details, availability and output artifact references; does not load its value."})
            result-info)
    (intern ns-object (with-meta 'results {:doc "List retained result references newest first. Optional {:limit 20 :before-id n}; pass :next-before-id for the next page."})
            (fn ([] (result-page {})) ([opts] (result-page opts))))
    (intern ns-object (with-meta 'artifact-page {:doc "Read a bounded artifact page: (artifact-page id {:offset 1 :limit 4096}). Text offsets count characters; binary offsets count bytes."})
            (fn ([id] (artifact-page id {:limit 4096})) ([id opts] (artifact-page id opts))))
    (intern ns-object (with-meta 'invoke-tool {:doc "Invoke a registered function through hooks, validation, cancellation, and effect locks."})
            (fn [name arguments] (invoke-value! name arguments)))
    (intern ns-object (with-meta 'register-tool! {:doc "Add discovery metadata and invocation tracing to a function Var. Ordinary functions need no registration to be called."})
            (fn [var descriptor]
              (register-var! register! registered-implementation owner var descriptor)))
    (doseq [sym '[result artifact registered-tools workspace result-info results artifact-page invoke-tool register-tool!]]
      (alter-meta! (ns-resolve ns-object sym) assoc :arrodes/helper true))
    nil))
