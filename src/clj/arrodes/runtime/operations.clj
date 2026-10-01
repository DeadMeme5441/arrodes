(ns arrodes.runtime.operations
  "Foreground driver execution, durable settlement, and completion ownership."
  (:require [arrodes.agents :as agents]
            [arrodes.coordination :as coordination]
            [arrodes.run :as run]
            [arrodes.runtime.control :as control]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.util.concurrent ExecutorService RejectedExecutionException)))

(defn operation-start! [runtime sid kind]
  (locking (control/session-lock runtime sid)
    (let [oid (util/id)
          slot (control/acquire-foreground! runtime sid kind oid)
          operation {:id oid :session-id sid :kind kind :status :running :created-at (util/now)}]
      (try
        (let [result (control/commit! runtime sid
                              {::command/session {:status :running}
                               ::command/operation operation
                               ::command/events [{:operation-id oid :type :operation/started
                                         :data {:kind kind}}]})]
          {:slot slot :operation (:operation result)})
        (catch Throwable error
          (swap! (:operations runtime) dissoc oid)
          (control/release-foreground! runtime sid oid)
          (swap! (:admission runtime) dissoc oid)
          (deliver (:finished slot) true)
          (agents/settled! (:agents runtime) sid)
          (throw error))))))

(defn durable-invocation [result]
  (dissoc result :value))

(defn settle-operation! [runtime sid slot requested result error]
  (let [oid (:operation-id slot)
        committed
        (locking (control/session-lock runtime sid)
          (let [status (coordination/terminal-status
                        requested (or @(:cancelled slot)
                                      (= :cancelling
                                         (:status (store/operation (:store runtime) oid)))))
                operation (cond-> {:id oid :session-id sid :kind (:kind slot)
                                   :status status :finished-at (util/now)}
                            (and (= status :completed) (some? result)) (assoc :result result)
                            error (assoc :error (value/error-map error)))
                session-status (case status
                                 :completed :idle
                                 :cancelled :interrupted
                                 :failed :failed
                                 :interrupted :interrupted
                                 :failed)
                unresolved (when-not (= :completed status)
                             (run/unresolved-tool-calls
                              (store/active-path (:store runtime) sid)))
                interrupted-entries
                (mapv (fn [call]
                        (let [content (str "Tool execution did not settle before the operation " (name status)
                                           ". Its external effect may have occurred; inspect state before retrying.")]
                          {:kind :message
                           :data {:message/role :tool
                                  :message/tool-call-id (:tool-call/id call)
                                  :message/name (:tool-call/name call)
                                  :message/content content
                                  :message/result {:kind :inline :content content
                                                   :details {:error/code (name status)}
                                                   :available? true}}}))
                      unresolved)
                command
                {::command/entries interrupted-entries
                 ::command/session {:status session-status}
                 ::command/operation operation
                 ::command/events (cond-> [{:operation-id oid
                                   :type (keyword "operation" (name status))
                                   :data (cond-> {}
                                           error (assoc :error (value/error-map error)))}]
                           (seq unresolved)
                           (conj {:operation-id oid :type :tool/interrupted
                                  :data {:call-ids (mapv :tool-call/id unresolved)
                                         :status status}}))}]
            (control/release-foreground! runtime sid oid)
            (try
              (store/commit! (:store runtime) sid command)
              (catch Throwable settlement-error
                (swap! (:foreground runtime) assoc sid slot)
                (throw settlement-error)))))]
    (control/emit-events! runtime (:events committed))
    (:operation committed)))

(defn- cancelled-error? [slot error]
  (or @(:cancelled slot)
      (instance? InterruptedException error)
      (= "cancelled" (:error/code (ex-data error)))))


(defn execute-operation! [runtime sid slot work]
  (reset! (:thread slot) (Thread/currentThread))
  (try
    (let [{:keys [result error]}
          (try {:result (let [value (work)]
                          (util/check-cancelled! (:cancelled slot))
                          value)}
               (catch Throwable failure {:error failure}))
          status (if error
                   (if (cancelled-error? slot error) :cancelled :failed)
                   :completed)
          durable-result (if (and (map? result) (contains? result :value))
                           (durable-invocation result) result)
          operation
          (try
            (settle-operation! runtime sid slot status durable-result error)
            (catch Throwable settlement-error
              (swap! (:settlement-failures runtime) assoc sid (:operation-id slot))
              (deliver (:done slot)
                       {:id (:operation-id slot) :session-id sid :kind (:kind slot)
                        :status :running
                        :error {:code "settlement-failed"
                                :message (ex-message settlement-error)}})
              (throw settlement-error)))]
      (deliver (:done slot) operation)
      (when (and error (not= :cancelled (:status operation)))
        (control/transient-event! runtime sid (:operation-id slot) :operation-error
                          {:error (value/error-map error)} nil))
      (cond
        error (throw error)
        (= :cancelled (:status operation))
        (throw (ex-info "Operation was cancelled before settlement"
                        {:error/code "cancelled" :operation-id (:operation-id slot)}))
        :else result))
    (finally
      (reset! (:thread slot) nil)
      (when-not (contains? @(:settlement-failures runtime) sid)
        (control/release-foreground! runtime sid (:operation-id slot))
        (swap! (:operations runtime) dissoc (:operation-id slot))
        (swap! (:admission runtime) dissoc (:operation-id slot)))
      (deliver (:finished slot) true)
      (agents/settled! (:agents runtime) sid))))

(defn submit-operation! [runtime sid slot work]
  (try
    (let [task (bound-fn []
                 (try (execute-operation! runtime sid slot work)
                      (catch Throwable _ nil)))]
      (.submit ^ExecutorService (:executor runtime)
               ^Runnable (reify Runnable
                           (run [_] (task)))))
    (store/operation (:store runtime) (:operation-id slot))
    (catch RejectedExecutionException error
      (let [operation (settle-operation! runtime sid slot :failed nil error)]
        (deliver (:done slot) operation)
        (control/release-foreground! runtime sid (:operation-id slot))
        (deliver (:finished slot) true)
        (swap! (:operations runtime) dissoc (:operation-id slot))
        (swap! (:admission runtime) dissoc (:operation-id slot))
        (agents/settled! (:agents runtime) sid)
        (throw error)))))

(defn await-operation! [slot deadline]
  (or (realized? (:finished slot))
      (let [remaining (control/remaining-close-millis deadline)]
        (and (pos? remaining)
             (not= :arrodes.runtime/timeout (deref (:finished slot) remaining :arrodes.runtime/timeout))))))

(defn queue-operation! [runtime oid kind content opts]
  (control/ensure-open! runtime)
  (let [op (store/operation (:store runtime) oid)
        sid (:session-id op)]
    (locking (control/session-lock runtime sid)
      (let [slot (control/foreground runtime sid)]
        (value/check! (= oid (:operation-id slot)) :operation-not-active
                      "Operation is not the session's current foreground operation"
                      {:operation-id oid :session-id sid
                       :current-operation-id (:operation-id slot)})
        (value/check! (contains? #{:run :continue} (:kind slot)) :operation-not-steerable
                      "Only running agent operations accept queued input"
                      {:operation-id oid :kind (:kind slot)})
        (value/check! @(:accepting-input? slot) :operation-not-active
                      "Operation has crossed its final input boundary"
                      {:operation-id oid :session-id sid})
        (let [item {:id (util/id) :session-id sid :operation-id oid :kind kind
                    :content (run/prompt-content content) :options (or opts {})
                    :created-at (util/now)}]
          (control/commit! runtime sid
                   {::command/queue-enqueue [(select-keys item [:id :kind :content :options :created-at])]
                    ::command/events [{:operation-id oid :type :queue/enqueued
                              :data (select-keys item [:id :kind])}]})
          (agents/settled! (:agents runtime) sid)
          (assoc item :status :queued))))))

(defn reconcile-settlements! [runtime]
  (doseq [[sid oid] @(:settlement-failures runtime)
          :let [slot (get @(:operations runtime) oid)]
          :when (and slot (realized? (:finished slot)))]
    (try
      (let [error (ex-info "Operation driver exited without a durable settlement"
                           {:error/code "settlement-interrupted" :operation-id oid})]
        (settle-operation! runtime sid slot :interrupted nil error)
        (swap! (:settlement-failures runtime) dissoc sid)
        (swap! (:operations runtime) dissoc oid)
        (swap! (:admission runtime) dissoc oid))
      (catch Throwable _ nil)))
  (empty? @(:settlement-failures runtime)))
