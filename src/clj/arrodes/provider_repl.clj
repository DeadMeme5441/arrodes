(ns arrodes.provider-repl
  "Translate session evaluation to the function-call protocol used by model providers."
  (:require [clojure.data.json :as json]
            [arrodes.value :as value]))

(def ^:private definition
  {:type :function
   :function
   {:name "repl"
    :description "Evaluate Clojure in your persistent session environment. Discover available functions with (registered-tools), then compose them as ordinary Clojure. Definitions and native values persist between evaluations."
    :parameters {:type "object"
                 :properties {:source {:type "string" :maxLength 1048576
                                       :description "One or more Clojure forms."}}
                 :required ["source"] :additionalProperties false}}})

(def instructions
  (str "You work in a persistent, trusted JVM Clojure REPL, not a menu of provider tools. "
       "Use repl to evaluate source. Begin with (registered-tools) to discover function symbols, "
       "documentation and argument schemas; registered functions take Clojure maps. "
       "Use these functions for session-relative file and shell work, skills, prompts and MCP. "
       "cwd is the session's working directory. Ordinary def and defn need no registration. "
       "Compose functions, bind useful intermediate values, and inspect only the portions you need. "
       "*1, *2, *3 hold recent form values and *e the most recent exception. "
       "Each evaluation also returns a retained integer result id: (result 42) recovers that native value; "
       "(artifact \"id\") reads a retained artifact. Previews are bounded, not the live values. "
       "Definitions and JVM objects are live state, not checkpoints: branch navigation, reload or restart resets them. "
       "Completed external effects are not rolled back when evaluation fails. Inspect state before retrying effects. "
       "Join any concurrent work before returning; unjoined futures and arbitrary background threads are not owned session operations. "
       "Finish by replying to the user normally, using what you actually observed."))

(defn request [request]
  (assoc request :request/tools [definition]))

(defn evaluation
  "Decode the one provider action into a core evaluation request. Never dispatch arbitrary tool names."
  [call]
  (value/check! (= "repl" (:tool-call/name call)) :unsupported-agent-action
            "The agent works through the REPL; call repl with Clojure source."
            {:name (:tool-call/name call)})
  (let [raw (:tool-call/arguments call)
        arguments (if (string? raw) (json/read-str raw :key-fn keyword) raw)]
    (value/check! (and (map? arguments) (= #{:source} (set (keys arguments)))
                   (string? (:source arguments)))
              :invalid-evaluation "repl requires exactly one string field: source" {})
    {:id (:tool-call/id call) :source (:source arguments)}))

(defn result-message
  "Store evaluation output and its structured reference, not an embedded result id."
  [result]
  {:message/role :tool
   :message/tool-call-id (:id result)
   :message/name "repl"
   :message/content (:content result)
   :message/result (:result result)})

(defn messages
  "Render result references at the provider boundary, after fork/import remapping."
  [messages]
  (mapv (fn [message]
          (if-let [descriptor (when (and (= :tool (:message/role message))
                                         (= "repl" (:message/name message)))
                                (:message/result message))]
            (if (:id descriptor)
              (update message :message/content
                      #(str % "\n\nRetained result: " (:id descriptor)
                            " (" (name (:kind descriptor)) "). Use (result "
                            (pr-str (:id descriptor)) ") to work with its value."))
              message)
            message))
        messages))
