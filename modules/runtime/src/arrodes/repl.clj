(ns arrodes.repl
  "Persistent session-local Clojure evaluation and explicit tool registration."
  (:require [clojure.string :as str]
            [arrodes.util :as util])
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
      (pr value))
    {:text (str buffer
                (when @truncated?
                  (str "\n[value preview truncated at " max-printed-value-characters
                       " characters]")))
     :truncated? @truncated?}))


(defn- evaluate-source [namespace current-context source]
  (util/check! (string? source) :invalid-arguments "clojure_eval source must be a string" {})
  (util/check! (<= (count source) max-source-characters) :source-too-large
               "clojure_eval source exceeds the supported size"
               {:characters (count source) :limit max-source-characters})
  (util/check-cancelled! (:cancelled? (current-context)))
  (let [out-state (util/bounded-writer max-evaluation-output-characters)
        err-state (util/bounded-writer max-evaluation-output-characters)]
    (try
      (let [[value form-count]
            (binding [*ns* (the-ns namespace)
                      *read-eval* false
                      *out* (:writer out-state)
                      *err* (:writer err-state)]
              (with-open [reader (LineNumberingPushbackReader. (StringReader. source))]
                (loop [value nil form-count 0]
                  (util/check-cancelled! (:cancelled? (current-context)))
                  (let [form (read {:eof eof :read-cond :allow :features #{:clj}} reader)]
                    (if (identical? eof form)
                      [value form-count]
                      (recur (eval form) (inc form-count)))))))
            stdout (writer-text out-state "stdout")
            stderr (writer-text err-state "stderr")
            printed-result (bounded-pr-str value)
            printed (:text printed-result)
            sections (cond-> []
                       (not (empty? stdout)) (conj (str "stdout:\n" stdout))
                       (not (empty? stderr)) (conj (str "stderr:\n" stderr))
                       true (conj (str "=> " printed)))]
        {:value value
         :content (str/join (System/lineSeparator) sections)
         :details {:stdout stdout :stderr stderr :printed printed
                   :stdout-truncated? @(:truncated? out-state)
                   :stderr-truncated? @(:truncated? err-state)
                   :printed-truncated? (:truncated? printed-result)
                   :forms form-count}})
      (catch Throwable error
        (if (util/cancelled? (:cancelled? (current-context)))
          (util/fail! :cancelled "Clojure evaluation was cancelled"
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
                   (ex-data error))
            error)))))))

(defn- tool-name-for-var [^Var var descriptor]
  (or (:name descriptor)
      (some-> var meta :name name)))

(defn- register-var! [register! owner value descriptor]
  (util/check! (instance? Var value) :invalid-tool-var
               "register-tool! requires a Var, for example (register-tool! #'my-tool {...})" {})
  (let [root @^Var value
        name (tool-name-for-var value descriptor)]
    (util/check! (ifn? root) :invalid-tool-var "The registered Var must contain a callable value"
                 {:var (str value)})
    (util/check! (and (string? name) (not (str/blank? name))) :invalid-tool-descriptor
                 "A registered tool needs a non-empty string name" {:var (str value)})
    (register! (merge {:name name
                       :description (or (:doc (meta value)) (str "Session function " name))
                       :parameters {:type "object" :properties {} :additionalProperties true}
                       :execution :sequential
                       :permission :execute
                       :owner owner}
                      descriptor
                      {:name name :owner owner
                       :fn (fn [arguments] (root arguments))}))
    {:name name :registered? true}))

(defn install!
  "Installs stable helper Vars in a session namespace and returns the
  clojure_eval descriptor. register! and invoke-value! must be the shared
  capability implementations, so REPL and provider calls take the same path."
  [{:keys [namespace current-context register! invoke-value! registered-tools
           result-value artifact-value]}]
  (let [owner (str "repl:" namespace)
        ns-object (the-ns namespace)]
    (intern ns-object (with-meta 'result {:doc "Return a native live result or its durable reconstructed value."})
            (fn [id] (result-value id)))
    (intern ns-object (with-meta 'artifact {:doc "Read a durable artifact by id up to the bounded REPL helper limit; page larger artifacts through artifact.read."})
            (fn [id] (artifact-value id)))
    (intern ns-object (with-meta 'registered-tools {:doc "Return the current public capability catalog."})
            (fn [] (registered-tools)))
    (intern ns-object (with-meta 'invoke-tool {:doc "Invoke a capability through the same hooks, validation, cancellation, and locks used by providers."})
            (fn [name arguments] (invoke-value! name arguments)))
    (intern ns-object (with-meta 'register-tool! {:doc "Explicitly expose a function Var as a model and REPL capability."})
            (fn [var descriptor] (register-var! register! owner var descriptor)))
    {:name "clojure_eval"
     :owner "arrodes.builtin"
     :description (str "Evaluate Clojure forms in the persistent session namespace. "
                       "Definitions persist across evaluations. Use registered functions for session-relative file operations. "
                       "New Vars remain private until explicitly registered with register-tool!.")
     :parameters {:type "object"
                  :properties {:source {:type "string" :maxLength max-source-characters
                                        :description "Clojure forms to evaluate"}}
                  :required ["source"]
                  :additionalProperties false}
     :execution :exclusive
     :permission :execute
     :fn (fn [{:keys [source]}] (evaluate-source namespace current-context source))}))
