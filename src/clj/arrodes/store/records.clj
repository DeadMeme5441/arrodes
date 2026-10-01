(ns arrodes.store.records
  "Shared session, history, operation, and event record primitives."
  (:require [clojure.string :as str]
            [arrodes.session :as session-model]
            [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.sql :as sql])
  (:import (java.sql Connection ResultSet)))

(def entry-kinds
  #{:message :config :compaction :branch-summary :custom :custom-context :label :evaluation})
(def statuses #{:idle :running :failed :interrupted})
(def ^:private operation-kinds #{:run :continue :compact :evaluate :invoke})
(def ^:private operation-statuses
  #{:queued :running :cancelling :completed :failed :cancelled :interrupted})
(defn session-row [^ResultSet rs]
  (cond-> {:id (.getString rs "id")
           :name (.getString rs "name")
           :cwd (.getString rs "cwd")
           :head (.getString rs "head")
           :revision (.getLong rs "revision")
           :config (codec/decode (.getString rs "config"))
           :status (keyword (.getString rs "status"))
           :created-at (.getLong rs "created_at")
           :updated-at (.getLong rs "updated_at")
           :metadata (codec/decode (.getString rs "metadata"))
           :labels (codec/decode (.getString rs "labels"))
           :arrodes.store/base-config (codec/decode (.getString rs "base_config"))}
    (.getString rs "parent_id") (assoc :parent-id (.getString rs "parent_id"))
    (.getString rs "fork_entry") (assoc :fork-entry (.getString rs "fork_entry"))))

(defn public-session [row] (dissoc row :arrodes.store/base-config))

(defn find-session [^Connection connection sid]
  (first (sql/query-sql connection "SELECT * FROM sessions WHERE id = ?" [sid] session-row)))

(defn require-session [^Connection connection sid]
  (codec/require-uuid! sid :session-id)
  (or (find-session connection sid)
      (value/fail! :session-not-found "Session does not exist" {:session-id sid})))

(defn insert-session! [^Connection connection snapshot base-config]
  (sql/execute-sql! connection
                "INSERT INTO sessions(id,name,cwd,head,revision,base_config,config,status,created_at,updated_at,metadata,labels,parent_id,fork_entry) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                [(:id snapshot) (:name snapshot) (:cwd snapshot) (:head snapshot) (:revision snapshot)
                 (codec/encode base-config) (codec/encode (:config snapshot)) (name (:status snapshot))
                 (:created-at snapshot) (:updated-at snapshot) (codec/encode (:metadata snapshot))
                 (codec/encode (:labels snapshot)) (:parent-id snapshot) (:fork-entry snapshot)]))

(defn prepare-session-options
  [opts]
  (let [created-at (or (:created-at opts) (util/now))]
    (-> opts
        (assoc :id (or (:id opts) (util/id))
               :cwd (util/canonical-path (or (:cwd opts) "."))
               :created-at created-at
               :updated-at (or (:updated-at opts) created-at)))))
(defn entry-row [^ResultSet rs]
  {:id (.getString rs "id")
   :session-id (.getString rs "session_id")
   :parent-id (.getString rs "parent_id")
   :seq (.getLong rs "seq")
   :kind (keyword (.getString rs "kind"))
   :data (codec/decode (.getString rs "data"))
   :created-at (.getLong rs "created_at")})

(defn all-entries [^Connection connection sid]
  (sql/query-sql connection "SELECT * FROM entries WHERE session_id = ? ORDER BY seq" [sid] entry-row))
(defn entry-exists? [^Connection connection sid eid]
  (boolean (sql/scalar connection "SELECT 1 FROM entries WHERE session_id = ? AND id = ?" [sid eid])))

(defn validate-entry! [^Connection connection sid {:keys [id parent-id kind data created-at]}]
  (codec/require-uuid! id :entry-id)
  (value/check! (contains? entry-kinds kind) :invalid-entry "Invalid entry kind" {:kind kind})
  (value/check! (map? data) :invalid-entry "Entry data must be a map" {:kind kind})
  (value/check! (and (integer? created-at) (not (neg? created-at))) :invalid-entry
               "Entry creation time must be a non-negative integer" {:created-at created-at})
  (when parent-id
    (codec/require-uuid! parent-id :parent-id)
    (value/check! (entry-exists? connection sid parent-id) :invalid-branch
                 "Entry parent is not part of this session" {:session-id sid :parent-id parent-id}))
  (case kind
    :config
    (value/check! (= data (session-model/normalize-config data)) :invalid-entry
                 "Configuration entries must contain a complete normalized configuration" {})

    :compaction
    (let [kept (:first-kept-entry-id data)
          ancestors (set (map :id (session-model/active-path
                                   (all-entries connection sid) parent-id)))]
      (when kept
        (codec/require-uuid! kept :first-kept-entry-id)
        (value/check! (contains? ancestors kept) :invalid-entry
                     "Compaction retained entry must be on its preceding active path"
                     {:first-kept-entry-id kept}))
      (value/check! (string? (:summary data)) :invalid-entry
                   "Compaction summary must be a string" {})
      (value/check! (map? (or (:usage data) {})) :invalid-entry
                   "Compaction usage must be a map" {}))

    :branch-summary
    (do
      (value/check! (string? (:summary data)) :invalid-entry
                   "Branch summary must be a string" {})
      (when-let [from-id (:from-id data)]
        (codec/require-uuid! from-id :from-id)
        (value/check! (entry-exists? connection sid from-id) :invalid-entry
                     "Branch summary source is not part of this session"
                     {:from-id from-id})))

    :label
    (let [entry-id (:entry-id data)]
      (codec/require-uuid! entry-id :entry-id)
      (value/check! (entry-exists? connection sid entry-id) :invalid-entry
                   "Label target is not part of this session" {:entry-id entry-id}))

    nil))

(defn operation-row [^ResultSet rs]
  (cond-> {:id (.getString rs "id")
           :session-id (.getString rs "session_id")
           :kind (keyword (.getString rs "kind"))
           :status (keyword (.getString rs "status"))
           :created-at (.getLong rs "created_at")}
    (not= 0 (.getLong rs "finished_at")) (assoc :finished-at (.getLong rs "finished_at"))
    (.getString rs "result") (assoc :result (codec/decode (.getString rs "result")))
    (.getString rs "error") (assoc :error (codec/decode (.getString rs "error")))))

(defn find-operation [^Connection connection oid]
  (first (sql/query-sql connection "SELECT * FROM operations WHERE id = ?" [oid] operation-row)))

(def ^:private operation-transitions
  {:queued #{:queued :running :cancelling :completed :failed :cancelled :interrupted}
   :running #{:running :cancelling :completed :failed :cancelled :interrupted}
   :cancelling #{:cancelling :completed :failed :cancelled :interrupted}
   :completed #{:completed}
   :failed #{:failed}
   :cancelled #{:cancelled}
   :interrupted #{:interrupted}})

(defn normalize-operation [sid prior operation]
  (let [allowed #{:id :session-id :kind :status :created-at :finished-at :result :error}
        unknown (seq (remove allowed (keys operation)))]
    (value/check! (nil? unknown) :invalid-operation
                 "Operation contains unsupported fields" {:fields (vec unknown)})
    (when prior
      (value/check! (= sid (:session-id prior)) :operation-forbidden
                   "Operation belongs to another session" {:operation-id (:id prior)})
      (doseq [field [:session-id :kind :created-at]]
        (when (contains? operation field)
          (value/check! (= (get prior field) (get operation field)) :invalid-operation
                       "Operation identity fields are immutable"
                       {:operation-id (:id prior) :field field}))))
    (let [operation (if prior
                      (merge prior operation)
                      (merge {:id (util/id) :session-id sid :status :queued
                              :created-at (util/now)}
                             operation))]
      (codec/require-uuid! (:id operation) :operation-id)
      (value/check! (= sid (:session-id operation)) :invalid-operation
                   "Operation belongs to another session" {:operation-id (:id operation)})
      (value/check! (contains? operation-kinds (:kind operation)) :invalid-operation
                   "Invalid operation kind" {:kind (:kind operation)})
      (value/check! (contains? operation-statuses (:status operation)) :invalid-operation
                   "Invalid operation status" {:status (:status operation)})
      (value/check! (and (integer? (:created-at operation)) (not (neg? (:created-at operation))))
                   :invalid-operation "Operation creation time must be a non-negative integer" {})
      (when (contains? operation :finished-at)
        (value/check! (and (integer? (:finished-at operation))
                          (not (neg? (:finished-at operation))))
                     :invalid-operation "Operation finish time must be a non-negative integer" {}))
      (when prior
        (value/check! (contains? (get operation-transitions (:status prior) #{})
                                (:status operation))
                     :invalid-operation-transition "Operation status cannot move backward"
                     {:operation-id (:id operation)
                      :from (:status prior) :to (:status operation)}))
      operation)))

(defn upsert-operation! [^Connection connection operation]
  (sql/execute-sql! connection
                "INSERT INTO operations(id,session_id,kind,status,created_at,finished_at,result,error) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET kind=excluded.kind,status=excluded.status,finished_at=excluded.finished_at,result=excluded.result,error=excluded.error"
                [(:id operation) (:session-id operation) (name (:kind operation)) (name (:status operation))
                 (:created-at operation) (:finished-at operation)
                 (when (contains? operation :result) (codec/encode (:result operation)))
                 (when (contains? operation :error) (codec/encode (:error operation)))])
  operation)

(defn insert-event! [^Connection connection sid event]
  (let [id (or (:id event) (util/id))
        time (or (:time event) (util/now))
        data (or (:data event) {})]
    (codec/require-uuid! id :event-id)
    (value/check! (keyword? (:type event)) :invalid-event
                 "Event type must be a keyword" {:type (:type event)})
    (value/check! (map? data) :invalid-event "Event data must be a map" {})
    (value/check! (and (integer? time) (not (neg? time))) :invalid-event
                 "Event time must be a non-negative integer" {:time time})
    (when-let [oid (:operation-id event)]
      (codec/require-uuid! oid :operation-id)
      (let [operation (find-operation connection oid)]
        (value/check! operation :operation-not-found
                     "Event operation does not exist" {:operation-id oid})
        (value/check! (= sid (:session-id operation)) :operation-forbidden
                     "Event operation belongs to another session" {:operation-id oid})))
    (sql/execute-sql! connection
                  "INSERT INTO events(id,session_id,operation_id,type,data,time) VALUES(?,?,?,?,?,?)"
                  [id sid (:operation-id event) (codec/encode (:type event)) (codec/encode data) time])
    {:id id
     :seq (long (sql/scalar connection "SELECT last_insert_rowid()" []))
     :session-id sid
     :operation-id (:operation-id event)
     :type (:type event)
     :data data
     :time time}))

(defn update-session! [^Connection connection snapshot]
  (sql/execute-sql! connection
                "UPDATE sessions SET name=?,cwd=?,head=?,revision=?,config=?,status=?,updated_at=?,metadata=?,labels=? WHERE id=?"
                [(:name snapshot) (:cwd snapshot) (:head snapshot) (:revision snapshot)
                 (codec/encode (:config snapshot)) (name (:status snapshot)) (:updated-at snapshot)
                 (codec/encode (:metadata snapshot)) (codec/encode (:labels snapshot)) (:id snapshot)]))

(defn normalize-session-changes [snapshot changes]
  (let [allowed #{:name :cwd :head :config :status :metadata :labels}
        unknown (seq (remove allowed (keys changes)))]
    (value/check! (nil? unknown) :invalid-session-change "Unsupported session field" {:fields (vec unknown)})
    (let [result (merge snapshot changes)
          result (cond-> result
                   (contains? changes :cwd) (update :cwd util/canonical-path)
                   (contains? changes :config) (update :config session-model/normalize-config)
                   (contains? changes :labels) (update :labels vec))]
      (value/check! (and (string? (:name result)) (not (str/blank? (:name result)))) :invalid-session "Session name must not be blank" {})
      (value/check! (contains? statuses (:status result)) :invalid-session "Invalid session status" {:status (:status result)})
      (value/check! (map? (:metadata result)) :invalid-session "Session metadata must be a map" {})
      result)))
(defn pending-tool-calls [path]
  (let [{:keys [order calls]}
        (reduce
         (fn [{:keys [order calls] :as state} entry]
           (if-not (= :message (:kind entry))
             state
             (let [message (:data entry)]
               (case (:message/role message)
                 :assistant
                 (reduce
                  (fn [{:keys [order calls]} call]
                    (let [id (:tool-call/id call)]
                      (if (nil? id)
                        {:order order :calls calls}
                        {:order (if (contains? calls id) order (conj order id))
                         :calls (assoc calls id call)})))
                  state (or (:message/tool-calls message) []))

                 :tool
                 (let [id (:message/tool-call-id message)]
                   {:order (vec (remove #{id} order))
                    :calls (dissoc calls id)})

                 state))))
         {:order [] :calls {}} path)]
    (mapv (fn [id] [id (get calls id)])
          (filter #(contains? calls %) order))))

(def ^:private branch-boundary-message
  (str "Tool result is unavailable at this branch boundary. "
       "Selecting history did not undo external effects, and this tool call was not replayed."))

(defn tool-boundary-entries [path]
  (mapv
   (fn [[_ call]]
     {:id (util/id)
      :kind :message
      :data {:message/role :tool
             :message/tool-call-id (:tool-call/id call)
             :message/name (:tool-call/name call)
             :message/content branch-boundary-message}})
   (pending-tool-calls path)))
