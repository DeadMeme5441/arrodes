(ns arrodes.tui.jobs
  "Session-owned background job inspection."
  (:require [arrodes.tui.context :as c]
            [arrodes.tui-model :as model]
            [arrodes.tui.inspection :as inspection]))

(declare open!)

(defn open! [view]
  (let [token (str (random-uuid))]
    (c/action! :open-overlay! view {:kind :jobs :title "Background jobs" :token token :query ""
                                  :hint "Enter inspects · F5 refreshes. Jobs also appear in the conversation."})
    (-> (c/invoke! view :jobs {})
        (.catch (fn [error]
                  (when (= token (get-in (c/state view) [:ui :overlay :token]))
                    (c/ui! view update :overlay assoc :hint (c/error-text error))))))))

(defn inspect! [view job]
  (c/action! :close-overlay! view)
  (inspection/inspect! view (model/job-row job))
  (c/ui! view assoc :keyboard-navigation? true))

(defn items [view]
  (let [jobs (sort-by (juxt #(if (contains? #{:queued :running :cancelling} (:status %)) 0 1) #(- (:created-at %)) :id)
                      (get-in (c/state view) [:view :jobs]))]
    (into []
          (concat
            (if (seq jobs)
              (map (fn [job]
                     {:label (str "[" (name (:status job)) "] " (:name job))
                      :description (or (get-in job [:error :message])
                                       (str "Started " (.toLocaleTimeString (js/Date. (:created-at job)))
                                            (when (:result-id job) " · Result available")))
                      :choose #(inspect! view job)}) jobs)
              [{:label "No background jobs" :description "Start one from the REPL with jobs/start!" :choose #(open! view)}])
            (when-let [before (get-in (c/state view) [:view :jobs-next-before])]
              [{:label "Older jobs" :description "Load the next page"
                :choose #(c/invoke! view :jobs {:before before})}])))))
