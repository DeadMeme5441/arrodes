(ns arrodes.tui.controller.catalog
  "Navigation-owned provider discovery and authentication feedback."
  (:require
            [arrodes.tui.controller.client :as client]))

(declare catalog-context owns-catalog? catalog-params load-models! load-providers! refresh-catalog! auth-observation! catalog-work! browse-provider!)

(defn catalog-context [app]
  (let [state @(:state app)]
    {:session-id (client/session-id-from state) :navigation (:navigation-generation state)}))


(defn owns-catalog? [app context]
  (= context (catalog-context app)))


(defn catalog-params [context data]
  (cond-> data (:session-id context) (assoc :session-id (:session-id context))))


(defn load-models!
  ([app refresh?] (load-models! app refresh? (catalog-context app)))
  ([app refresh? context]
   (-> (client/call! app (if refresh? "model.refresh" "model.list") (catalog-params context {}))
       (.then (fn [wire-result]
                (let [models (vec (or (client/value-field (client/decode wire-result) :models) []))]
                  (when (owns-catalog? app context) (swap! (:state app) assoc :models models))
                  models))))))


(defn load-providers!
  ([app] (load-providers! app (catalog-context app)))
  ([app context]
   (-> (client/call! app "auth.status" (catalog-params context {}))
       (.then (fn [wire]
                (let [providers (vec (:providers (client/decode wire)))]
                  (when (owns-catalog? app context) (swap! (:state app) assoc :providers providers))
                  providers))))))


(defn refresh-catalog! [app context]
  (if (owns-catalog? app context)
    (js/Promise.all #js [(load-models! app false context) (load-providers! app context)])
    (client/resolved nil)))


(defn auth-observation! [app context provider cancelled?]
  (-> (load-providers! app context)
      (.then (fn [providers]
               (let [_ (when (owns-catalog? app context)
                         (swap! (:state app) update :discovery dissoc (keyword provider)))
                     entry (some #(when (= (keyword provider) (keyword (:provider %))) %) providers)
                     connected? (:available? entry)]
                 (when (owns-catalog? app context)
                   (swap! (:state app) assoc :notice
                          {:kind :info :message
                           (cond
                             (and cancelled? connected?) "Sign-in stopped; provider is connected."
                             cancelled? "Sign-in cancelled. Provider is not connected."
                             connected? "Provider connected. Browse its models when you are ready."
                             :else "Sign-in finished. Provider is not connected; check its credentials.")}))
                 providers)))))


(defn catalog-work! [app label work]
  (if (:catalog-operation @(:state app))
    (client/rejected (client/error "catalog-busy" "Wait for the current provider action to finish" {}))
    (do
      (swap! (:state app) assoc :catalog-operation label :notice nil)
      (-> (client/resolved nil)
          (.then work)
          (.finally (fn [] (swap! (:state app) assoc :catalog-operation nil)))))))


(defn browse-provider! [app data]
  (let [context (catalog-context app)
        id (keyword (:provider data))
        previous (get-in @(:state app) [:discovery id])
        fresh? (and (= context (:context previous))
                    (or (:loading? previous)
                        (and (:at previous) (< (- (.now js/Date) (:at previous)) 60000))))]
    (if (and fresh? (not (:refresh? data)))
      (client/resolved nil)
      (let [token (str (random-uuid))
            owned? #(and (owns-catalog? app context)
                         (= token (get-in @(:state app) [:discovery id :token])))
            merge-models! (fn [wire]
                            (when (owned?)
                              (let [models (filterv #(= id (keyword (:provider %))) (:models (client/decode wire)))]
                                (swap! (:state app) update :models
                                       #(into (filterv (fn [m] (not= id (keyword (:provider m)))) %) models)))))]
        (swap! (:state app) assoc-in [:discovery id]
               {:token token :context context :loading? true})
        (-> (client/call! app "model.list" (catalog-params context {:provider id}))
            (.then (fn [wire]
                     (merge-models! wire)
                     (when (:available? data)
                       (-> (client/call! app "model.refresh" (catalog-params context {:provider id}))
                           (.then merge-models!)))))
            (.then (fn [_]
                     (when (owned?)
                       (swap! (:state app) update-in [:discovery id]
                              assoc :loading? false :at (.now js/Date)))))
            (.catch (fn [failure]
                      (when (owned?)
                        (swap! (:state app) update-in [:discovery id]
                               assoc :loading? false :error (or (ex-message failure) (.-message failure)))))))))))

