(ns arrodes.store.routing
  "Context-scoped agent routing and completion publication."
  (:require [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.records :as records]
            [arrodes.store.sql :as sql])
  (:import (java.sql ResultSet)))

(def ^:private max-agent-content-bytes (* 256 1024))
(def ^:private max-agent-completion-bytes (* 16 1024 1024))
(def max-agent-preview 1200)
(defn route-row [^ResultSet rs]
  {:session-id (.getString rs "session_id")
   :root-id (.getString rs "root_id")
   :parent-session-id (.getString rs "parent_session_id")
   :name (.getString rs "name")
   :context-id (.getString rs "context_id")
   :parent-context-id (.getString rs "parent_context_id")
   :depth (.getInt rs "depth")
   :paused? (pos? (.getInt rs "paused"))
   :stopped? (pos? (.getInt rs "stopped"))
   :origin (codec/decode (.getString rs "origin"))})

(defn route [connection sid]
  (or (first (sql/query-sql connection "SELECT * FROM agent_routes WHERE session_id=?" [sid] route-row))
      (let [snapshot (records/require-session connection sid)]
        {:session-id sid :root-id sid :parent-session-id nil :name "Main"
         :context-id sid :parent-context-id nil :depth 0 :paused? false
         :stopped? false :origin {} :session-name (:name snapshot)})))

(defn public-route [row]
  (select-keys row [:session-id :root-id :parent-session-id :name :context-id
                    :parent-context-id :depth :paused? :stopped?]))

(defn ensure-root-route! [connection sid]
  (when-not (sql/scalar connection "SELECT 1 FROM agent_routes WHERE session_id=?" [sid])
    (sql/execute-sql! connection
                  "INSERT INTO agent_routes(session_id,root_id,parent_session_id,name,context_id,parent_context_id,depth,paused,stopped,origin) VALUES(?,?,?,?,?,?,?,0,0,?)"
                  [sid sid nil "Main" sid nil 0 (codec/encode {})]))
  (route connection sid))

(defn routing-event! [connection root sid type data]
  (records/insert-event! connection root
                 {:type type :data (merge {:root-id root :session-id sid} data)}))

(defn checked-agent-content
  ([content] (checked-agent-content content max-agent-content-bytes))
  ([content limit]
   (let [serialized (codec/encode content)]
     (value/check! (<= (count (.getBytes ^String serialized "UTF-8")) limit)
                   :agent-content-too-large "Agent content exceeds the durable size limit"
                   {:limit limit})
     serialized)))

(defn- canonical-assistant-result [result]
  (when (= :assistant (:message/role result))
    (let [content (:message/content result)
          content (if (vector? content)
                    (mapv #(if (map? %)
                             (dissoc % :part/provider-data)
                             %) content)
                    content)
          metadata (select-keys (:message/provider-data result)
                                [:response/provider :response/model :response/usage
                                 :response/cost :response/finish-reason])]
      (cond-> {:message/role :assistant :message/content content}
        (seq metadata) (assoc :message/provider-data metadata)))))

(defn- completion-content [operation child-name]
  (checked-agent-content {:session-id (:session-id operation)
                          :name child-name
                          :operation-id (:id operation) :status (:status operation)
                          :result (canonical-assistant-result (:result operation))
                          :error (select-keys (:error operation)
                                              [:code :message :error/code])}
                         max-agent-completion-bytes))

(defn completion! [connection operation]
  (when (contains? #{:completed :failed :cancelled :interrupted} (:status operation))
    (when-let [child (first (sql/query-sql connection
                                      "SELECT * FROM agent_routes WHERE session_id=? AND parent_session_id IS NOT NULL"
                                      [(:session-id operation)] route-row))]
      (let [parent (:parent-session-id child)
            parent-route (route connection parent)
            content (completion-content operation (:name child))]
        (when (and (= (:parent-context-id child) (:context-id parent-route))
                   (not (sql/scalar connection
                                "SELECT 1 FROM agent_messages WHERE source_operation_id=? AND kind='completion'"
                                [(:id operation)])))
          (let [id (util/id)]
            (sql/execute-sql! connection
                          "INSERT INTO agent_messages(id,root_id,sender_id,kind,content,source_operation_id,created_at,wake) VALUES(?,?,?,?,?,?,?,1)"
                          [id (:root-id child) (:session-id operation) "completion" content
                           (:id operation) (util/now)])
            (sql/execute-sql! connection
                          "INSERT INTO agent_deliveries(message_id,recipient_id,context_id,status) VALUES(?,?,?,'pending')"
                          [id parent (:context-id parent-route)])
            [(routing-event! connection (:root-id child) parent :agent/message
                             {:message-id id :from (:session-id operation) :kind :completion})]))))))

(defn supersede-deliveries! [connection sid]
  (sql/execute-sql! connection
                "UPDATE agent_deliveries SET status='superseded' WHERE recipient_id=? AND status='pending'"
                [sid]))
