(ns arrodes.tui.controller.agents
  "Root-scoped roster reconciliation and explicitly addressed mutations."
  (:require [clojure.string :as str]
            [arrodes.tui-model :as model]
            [arrodes.tui.controller.client :as client]))

(declare refresh! reconcile-submissions!)

(defn team-member? [state sid]
  (some #(= sid (:session-id %)) (get-in state [:agents :agents])))

(defn relevant-event? [state event]
  (let [root (get-in state [:agents :root-id])
        target (or (get-in event [:data :session-id]) (:session-id event))]
    (or (and (nil? root)
             (get-in state [:agents :loading?])
             (contains? #{:agent/changed :agent/message} (:type event)))
        (and root
             (or (and (contains? #{:agent/changed :agent/message} (:type event))
                      (= root (or (get-in event [:data :root-id]) (:session-id event))))
                 (and (contains? #{:operation/started :operation/phase :operation/cancelling
                                   :operation/completed :operation/failed :operation/cancelled
                                   :operation/interrupted} (:type event))
                      (team-member? state target)))))))

(defn observe-event! [app event]
  (when (relevant-event? @(:state app) event)
    (swap! (:state app)
           (fn [state]
             (-> state
                 (update :agents model/apply-agent-event event)
                 (update-in [:agents :buffer] (fnil conj []) event))))
    (when-not (get-in @(:state app) [:agents :loading?])
      (-> (refresh! app (get-in @(:state app) [:agents :root-id]))
          (.catch (fn [_] nil))))))

(defn refresh! [app sid]
  (if-not sid
    (client/resolved nil)
    (if (get-in @(:state app) [:agents :loading?])
      (do (swap! (:state app) assoc-in [:agents :requested-sid] sid)
          (client/resolved nil))
      (let [token (str (random-uuid))]
        (swap! (:state app)
               (fn [state]
                 (-> state
                     (assoc-in [:agents :loading?] true)
                     (assoc-in [:agents :token] token)
                     (assoc-in [:agents :error] nil)
                     (assoc-in [:agents :buffer] [])
                     (assoc-in [:agents :requested-sid] nil))))
        (.catch
         (.then (client/call! app "agent.list" {:session-id sid})
                (fn [wire]
                  (let [snapshot (client/decode wire)
                        cursor (or (:cursor snapshot) 0)
                        current @(:state app)]
                    (when (= token (get-in current [:agents :token]))
                      (let [buffer (get-in current [:agents :buffer])
                            requested (get-in current [:agents :requested-sid])
                            changed? (some #(or (nil? (:seq %)) (> (:seq %) cursor)) buffer)]
                        (swap! (:state app)
                               (fn [state]
                                 (let [old (:agents state)]
                                   (if (not= token (:token old))
                                     state
                                     (assoc state :agents
                                            (merge old (model/agent-snapshot snapshot buffer)
                                                   {:loading? false :buffer [] :requested-sid nil :error nil}))))))
                        (when (or requested changed?)
                          (.catch (refresh! app (or requested sid)) (fn [_] nil)))))
                    snapshot)))
         (fn [error]
           (swap! (:state app)
                  (fn [state]
                    (if (= token (get-in state [:agents :token]))
                      (-> state
                          (assoc-in [:agents :loading?] false)
                          (assoc-in [:agents :error] (ex-message error)))
                      state)))
           (throw error)))))))

(defn- mutation! [app method params]
  (let [submission-id (str (random-uuid))
        source (:session-id params)
        params (assoc params :submission-id submission-id)]
    (when-let [pending (some (fn [[id entry]]
                               (when (and (contains? #{:unknown :submitting} (:status entry))
                                          (= source (:session-id entry))
                                          (= method (:method entry))
                                          (= (dissoc params :submission-id) (:params entry)))
                                 id))
                             (get-in @(:state app) [:agents :submissions]))]
      (throw (client/error (if (= :unknown (get-in @(:state app) [:agents :submissions pending :status]))
                             "agent-submission-unknown" "agent-submission-pending")
                           (str "Submission " pending " is awaiting a receipt; do not send it again")
                           {:submission-id pending :unknown-outcome?
                            (= :unknown (get-in @(:state app) [:agents :submissions pending :status]))})))
    (let [request (client/mutation! app method params)]
      (swap! (:state app) assoc-in [:agents :submissions submission-id]
             {:session-id source :method method :params (dissoc params :submission-id)
              :status :submitting})
      (-> request
        (.then (fn [wire]
                 (let [receipt (client/decode wire)]
                   (swap! (:state app) assoc-in [:agents :submissions submission-id]
                          {:session-id source :method method :params (dissoc params :submission-id)
                           :status :accepted :receipt receipt})
                   (-> (refresh! app source) (.catch (fn [_] nil)))
                   receipt)))
        (.catch (fn [error]
                  (swap! (:state app) update-in [:agents :submissions submission-id]
                         assoc :status (if (:unknown-outcome? (ex-data error)) :unknown :rejected))
                  (throw error)))))))

(defn start! [app {:keys [name task context]}]
  (let [sid (client/session-id-from @(:state app))]
    (when-not sid (throw (client/error "no-session" "Open a session before starting an agent" {})))
    (when (or (str/blank? name) (str/blank? task))
      (throw (client/error "invalid-agent" "Name and task are required" {})))
    (mutation! app "agent.start" (client/non-nil-map {:session-id sid :name name :task task :context context}))))

(defn send! [app {:keys [target content]}]
  (let [sid (client/session-id-from @(:state app))]
    (when (or (not sid) (str/blank? content))
      (throw (client/error "invalid-agent-message" "Open a session and enter a message" {})))
    (mutation! app "agent.send" {:session-id sid :target target :content content :wake? true})))

(defn reconcile-submissions! [app]
  (doseq [[id {:keys [session-id status]}] (get-in @(:state app) [:agents :submissions])
          :when (= status :unknown)]
    (-> (client/call! app "agent.submission" {:session-id session-id :submission-id id})
        (.then (fn [wire]
                 (let [receipt (client/decode wire)]
                   (if receipt
                     (do
                       (swap! (:state app)
                              (fn [state]
                                (-> state
                                    (assoc-in [:agents :submissions id :status] :accepted)
                                    (assoc-in [:agents :submissions id :receipt] receipt)
                                    (assoc :notice {:kind :info
                                                    :message (str "Agent submission " id " was accepted; roster refreshed.")}))))
                       (-> (refresh! app session-id) (.catch (fn [_] nil))))
                     (swap! (:state app) assoc :notice
                            {:kind :error :unknown-outcome? true
                             :message (str "Agent submission " id
                                           " has no receipt after reconnect; it was not resent.")})))))
        (.catch (fn [_] nil)))))
