(ns arrodes.tui.agents
  "Keyboard-accessible team browser; one focused transcript remains mounted."
  (:require [clojure.string :as str]
            [arrodes.tui-model :as model]
            [arrodes.tui-present :as present]
            [arrodes.tui.context :as c]))

(declare open! items selected open-member! parent! compose! messages! outcome! cancel! stop! resume! start!)

(defn- row-status [row]
  (let [status (get-in row [:operation :status])]
    (cond (:stopped? row) "stopped"
          (:paused? row) "paused"
          (= :cancelling status) "cancelling"
          (contains? #{:queued :running} status)
          (if (contains? #{:waiting :managed-wait} (:phase row)) "waiting" (name status))
          (and status (not= :completed status)) (name status)
          (:phase row) (name (:phase row))
          :else "idle")))

(defn usage-summary [rows]
  (let [measured (keep :usage-total rows)
        unknown-calls (reduce + 0 (keep :usage-unmeasured-calls rows))]
    (str "Measured tokens " (if (seq measured) (reduce + 0 measured) "—")
         (when (pos? unknown-calls) (str " · " unknown-calls " unmeasured calls")))))

(defn selected [view]
  (let [overlay (get-in (c/state view) [:ui :overlay])]
    (when (= :agents (:kind overlay))
      (let [items (c/action! :overlay-items view overlay)
            index (min (max 0 (dec (count items))) (or (:index overlay) 0))]
        (:agent-row (get items index))))))

(defn open! [view]
  (let [sid (get-in (c/state view) [:view :session :id])
        rows (get-in (c/state view) [:agents :agents])
        focused-index (or (first (keep-indexed #(when (= sid (:session-id %2)) %1) rows)) 0)]
    (c/action! :open-overlay! view
               {:kind :agents :title "Agents" :query "" :index focused-index
                :loading? (boolean sid)
                :hint "Enter opens · › targets controls · [current] is focused · F7 message · F8 messages · F5 refresh"})
    (when sid
      (let [token (get-in (c/state view) [:ui :overlay :token])]
        (-> (c/invoke! view :agents {})
            (.then (fn [_]
                     (when (= token (get-in (c/state view) [:ui :overlay :token]))
                       (let [overlay (get-in (c/state view) [:ui :overlay])
                             index (or (first (keep-indexed #(when (= sid (:session-id %2)) %1)
                                                             (get-in (c/state view) [:agents :agents]))) 0)]
                         (c/ui! view update :overlay assoc
                                :loading? false
                                :index (if (and (str/blank? (:query overlay))
                                                (= focused-index (:index overlay)))
                                         index (:index overlay)))))))
            (.catch (fn [error]
                      (when (= token (get-in (c/state view) [:ui :overlay :token]))
                        (c/ui! view update :overlay assoc :loading? false :error (c/error-text error))))))))))

(defn items [view]
  (let [s (c/state view)
        focused (get-in s [:view :session :id])]
    (mapv (fn [row]
            (let [id (:session-id row)
                  config (get-in row [:session :config])
                  provider (:provider config)
                  model (:model config)]
              {:label (str (apply str (repeat (min 8 (:depth row 0)) "  "))
                           (or (:name row) "Agent")
                           (when (= id focused) " [current]")
                           (when (= id (:root-id row)) " · root"))
               :description (str (row-status row)
                                 " · " (or model "no model") " / " (if (keyword? provider)
                                                                  (name provider)
                                                                  (or provider "no provider"))
                                 (when (pos? (or (:pending-count row) 0))
                                   (str " · " (:pending-count row) " pending"))
                                 (when (get-in row [:operation :error]) " · error")
                                 (when (:off-context? row) " · off-context; old branch route inactive")
                                 (when (number? (:usage-total row))
                                   (str " · " (:usage-total row) " measured tokens"))
                                 "\nID " id)
               :agent-row row
               :search-text id
               :choose #(open-member! view row)}))
          (get-in s [:agents :agents]))))

(defn open-member! [view row]
  (-> (c/invoke! view :agent-open {:id (:session-id row)})
      (.then (fn [_] (c/action! :close-overlay! view)))
      (.catch (fn [_] nil))))

(defn parent! [view]
  (let [s (c/state view)
        sid (get-in s [:view :session :id])
        row (some #(when (= sid (:session-id %)) %) (get-in s [:agents :agents]))]
    (if-let [parent (:parent-session-id row)]
      (open-member! view {:session-id parent})
      (c/notify! view "This is the root session. Select an agent with Enter."))))

(defn- inspect! [view title action data]
  (let [previous (get-in (c/state view) [:ui :overlay])]
    (-> (c/invoke! view action data)
        (.then (fn [result]
                 (c/action! :open-overlay! view
                            {:kind :notice :title title :body (model/safe-text (present/pretty result))
                             :return-overlay previous})
                 result))
        (.catch (fn [error]
                  (c/ui! view assoc-in [:overlay :error] (c/error-text error))
                  (throw error))))))

(defn messages! [view row]
  (let [sid (:session-id row)
        previous (get-in (c/state view) [:ui :overlay])]
    (-> (c/invoke! view :agent-messages {})
        (.then (fn [result]
                 (let [messages (filter #(or (= sid (:from %)) (some #{sid} (:recipients %)))
                                        (:messages result))]
                   (c/action! :open-overlay! view
                              {:kind :notice :title (str "Messages · " (:name row))
                               :body (if (seq messages)
                                       (str/join "\n\n" (map #(model/safe-text (present/pretty %)) messages))
                                       "No messages for this agent.")
                               :return-overlay previous})
                   result))))))

(defn outcome! [view row]
  (if-let [oid (get-in row [:operation :id])]
    (inspect! view (str "Native outcome · " (:name row)) :agent-result
              {:id (:session-id row) :operation-id oid})
    (c/notify! view "No agent operation to inspect.")))

(defn compose! [view row]
  (when row
    (c/action! :open-overlay! view
               {:kind :input :title (str "Message to " (:name row)) :query ""
                :return-overlay (get-in (c/state view) [:ui :overlay])
                :on-submit #(c/invoke! view :agent-send {:target (:session-id row) :content %})
                :hint (str "From the focused session · addressed to " (:name row)
                           ". Enter sends; Shift+Enter adds a line. Your conversation draft is unchanged.")})))

(defn start! [view]
  (let [roster (get-in (c/state view) [:ui :overlay])]
    (c/action! :open-overlay! view
               {:kind :input :title "New agent · name" :query "" :return-overlay roster
                :on-submit (fn [name]
                             (when (str/blank? name) (throw (js/Error. "Agent name is required")))
                             (c/action! :open-overlay! view
                                        {:kind :input :title (str "Task for " name) :query ""
                                         :return-overlay roster
                                         :on-submit #(c/invoke! view :agent-start {:name name :task %})
                                         :hint "Enter starts an independent session; Shift+Enter adds a line. Your draft is unchanged."})
                             (js/Promise.resolve nil))
                :hint "Team-local name; Enter continues to the task; Esc leaves your draft intact."})))

(defn cancel! [view row]
  (when row
    (let [oid (get-in row [:operation :id])]
      (c/action! :confirm! view (str "Cancel " (:name row) "?")
                 "Pauses this agent and requests cancellation of its current operation. Children and jobs survive."
                 #(c/fire! view :agent-cancel {:id (:session-id row)
                                               :operation-id (when (contains? #{:queued :running :cancelling}
                                                                             (get-in row [:operation :status])) oid)})))))

(defn stop! [view row]
  (when row
    (c/action! :confirm! view (str "Stop " (:name row) " and descendants?")
               "Stops this tree, cancels its operations and jobs; completion may remain stopping until workers exit."
               #(c/fire! view :agent-stop {:id (:session-id row)}))))

(defn resume! [view row]
  (when row (c/fire! view :agent-resume {:id (:session-id row)})))

