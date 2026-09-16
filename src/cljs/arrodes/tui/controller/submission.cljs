(ns arrodes.tui.controller.submission
  "Submission receipts, first-send ownership, and queued input."
  (:require [clojure.string :as str]
            [arrodes.tui.controller.client :as client]
            [arrodes.tui.controller.sessions :as sessions]))

(declare evaluation-timeout-ms normalize-mode prompt-parts clear-confirmed-submission! submit! submit-first! enrich-pending-by-id edited-queue-content queue-edit! queue-drop!)

(def evaluation-timeout-ms (* 10 60 1000))

(defn normalize-mode [mode]
  (cond
    (keyword? mode) mode
    (string? mode) (keyword mode)
    (nil? mode) :prompt
    :else mode))


(defn prompt-parts [text attachments]
  (let [parts (cond-> []
                (not (str/blank? text))
                (conj {:part/type :text :text text})
                (seq attachments)
                (into (mapv :part attachments)))]
    (cond
      (empty? parts)
      (throw (client/error "empty-prompt" "Prompt must contain text or attachments" {}))

      (and (= 1 (count parts))
           (= :text (:part/type (first parts)))
           (empty? attachments))
      text

      :else parts)))


(defn clear-confirmed-submission! [app sid text attachments]
  (swap! (:state app)
         (fn [state]
           (let [active? (= sid (client/session-id-from state))]
             (cond-> state
               (= text (get-in state [:ui :drafts sid]))
               (assoc-in [:ui :drafts sid] "")

               (= text (get-in state [:ui :session-ui sid :draft]))
               (assoc-in [:ui :session-ui sid :draft] "")

               (= attachments (get-in state [:ui :session-ui sid :attachments]))
               (assoc-in [:ui :session-ui sid :attachments] [])

               (and active? (= text (get-in state [:ui :draft])))
               (assoc-in [:ui :draft] "")

               (and active? (= attachments (get-in state [:ui :attachments])))
               (assoc-in [:ui :attachments] []))))))

(defn submit! [app data]
  (let [state @(:state app)
        sid (client/session-id-from state)
        text (or (:text data) (get-in state [:ui :draft]) "")
        mode (normalize-mode (:mode data))
        attachments (if (= :evaluate mode) [] (vec (if (contains? data :attachments) (:attachments data)
                                                  (get-in state [:ui :attachments]))))
        oid (client/operation-id-from state)]
    (when-not sid
      (throw (client/error "no-session" "No active session" {})))
    (if (= :evaluate mode)
      (do
        (when (str/blank? text)
          (throw (client/error "empty-source" "Evaluation source cannot be empty" {})))
        (-> (client/call! app "session.evaluate" {:session-id sid :source text}
                   {:timeout-ms (or (get-in app [:options :evaluation-timeout-ms])
                                    evaluation-timeout-ms)
                    :mutation? true})
            (.then
             (fn [result]
               (client/decode result)))))
      (let [prompt (prompt-parts text attachments)
            [method params]
            (case mode
              :follow-up
              (do
                (when-not oid
                  (throw (client/error "no-operation" "No active operation accepts follow-up input" {})))
                ["operation.follow-up" {:operation-id oid :prompt prompt}])
              :prompt
              (if oid
                ["operation.steer" {:operation-id oid :prompt prompt}]
                ["session.run" {:session-id sid :prompt prompt}])
              (throw (client/error "invalid-submit-mode" "Unknown submission mode" {:mode mode})))]
        (-> (client/mutation! app method params)
            (.then
             (fn [wire-result]
               (let [result (client/decode wire-result)]
                 (clear-confirmed-submission! app sid (or (:draft-text data) text) attachments)
                 (when (and (contains? #{"operation.steer" "operation.follow-up"} method)
                            (= sid (client/session-id-from @(:state app))))
                   (swap! (:state app) update-in [:view :queue] enrich-pending-by-id result))
                 result))))))))


(defn submit-first! [app data]
  (let [state @(:state app)
        mode (normalize-mode (:mode data))
        text (or (:text data) (get-in state [:ui :draft]) "")
        attachments (vec (get-in state [:ui :attachments]))
        session (get-in state [:view :session])
        created (atom nil) submitted? (atom false)]
    (when (or (:first-submit? state) (:first-send-unknown? state))
      (throw (client/error "submission-pending" "The first submission is pending or has an unknown outcome; inspect before retrying." {})))
    (when (not= :prompt mode)
      (throw (client/error "no-session" "Send a first message before evaluating or queueing work." {})))
    (prompt-parts text attachments)
    (swap! (:state app) assoc :first-submit? true)
    (-> (sessions/create-session! app
          (cond-> (select-keys session [:config])
            (or (= :user (get-in session [:metadata :title/source]))
                (not= "Untitled session" (:name session)))
            (assoc :name (:name session))))
        (.then (fn [session]
                 (reset! created (:id session))
                 (sessions/hydrate-session! app (:id session) false)))
        (.then (fn [_]
                 (swap! (:state app) assoc :empty-composer? false)
                 (reset! submitted? true)
                 (submit! app (assoc data :text text :attachments attachments))))
        (.catch (fn [failure]
                  (cond
                    (:unknown-outcome? (ex-data failure))
                    (do (swap! (:state app) assoc :first-send-unknown? true) (throw failure))
                    (and @created (not @submitted?))
                    (-> (client/mutation! app "session.delete" {:session-id @created})
                        (.then (fn [_]
                                 (swap! (:state app) update :sessions #(filterv (fn [x] (not= @created (:id x))) %))
                                 (throw failure))))
                    :else (throw failure))))
        (.finally (fn [] (swap! (:state app) assoc :first-submit? false))))))


(defn enrich-pending-by-id [items item]
  (let [id (client/value-field item :id)]
    (mapv #(if (= id (client/value-field % :id)) (merge % item) %) items)))


(defn edited-queue-content [content text]
  (when-not (string? text)
    (throw (client/error "invalid-queue-content" "Queue text must be a string" {})))
  (if (string? content)
    (do
      (when (str/blank? text)
        (throw (client/error "empty-queue-content" "Queued text cannot be empty" {})))
      text)
    (let [parts (vec (or content []))
          {:keys [result replaced?]}
          (reduce
           (fn [{:keys [replaced?] :as state} part]
             (let [type (client/value-field part :part/type)
                   text-part? (contains? #{:text "text"} type)]
               (cond
                 (not text-part?) (update state :result conj part)
                 replaced? state
                 :else (cond-> (assoc state :replaced? true)
                         (not (str/blank? text))
                         (update :result conj (assoc part :part/type :text :text text))))))
           {:result [] :replaced? false}
           parts)
          result (if (or replaced? (str/blank? text))
                   result
                   (into [{:part/type :text :text text}] result))]
      (when (empty? result)
        (throw (client/error "empty-queue-content"
                      "Queue edit must retain an attachment or contain text" {})))
      result)))


(defn queue-edit! [app data]
  (let [state @(:state app)
        sid (client/session-id-from state)
        queue-id (:id data)
        item (some #(when (= queue-id (client/value-field % :id)) %)
                   (get-in state [:view :queue]))]
    (when-not item
      (throw (client/error "queue-item-not-found" "Queued item is not present in the active session"
                    {:queue-id queue-id})))
    (when-not (or (contains? item :content) (contains? item "content"))
      (throw (client/error "queue-content-unavailable"
                    "Queued content is still synchronizing; edit after its receipt arrives"
                    {:queue-id queue-id})))
    (let [content (edited-queue-content (client/value-field item :content) (:text data))]
      (-> (client/mutation! app "session.queue.update"
                     {:session-id sid :queue-id queue-id :content content})
          (.then
           (fn [wire-result]
             (let [item (or (client/value-field (client/decode wire-result) :item)
                            (client/decode wire-result))
                   id (client/value-field item :id)]
               (swap! (:state app) update-in [:view :queue]
                      #(mapv (fn [existing]
                               (if (= id (client/value-field existing :id)) item existing)) %))
               item)))))))


(defn queue-drop! [app data]
  (let [sid (client/session-id-from @(:state app))]
    (-> (client/mutation! app "session.queue.drop"
                   {:session-id sid :queue-id (:id data)})
        (.then
         (fn [wire-result]
           (let [result (client/decode wire-result)
                 removed (or (client/value-field result :removed) result)
                 id (or (client/value-field removed :id) (:id data))]
             (swap! (:state app) update-in [:view :queue]
                    #(filterv (fn [item] (not= id (client/value-field item :id))) %))
             removed))))))
