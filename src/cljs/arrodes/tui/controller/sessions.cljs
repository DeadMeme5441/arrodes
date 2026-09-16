(ns arrodes.tui.controller.sessions
  "Session hydration, navigation and empty-composer lifecycle."
  (:require [clojure.string :as str]
            [arrodes.tui-model :as model]
            [arrodes.tui.controller.catalog :as catalog]
            [arrodes.tui.controller.client :as client]))

(declare replay-page-size session-ui-keys save-session-ui restore-session-ui replace-session replay-pages! hydrate-session! refresh-owned-session! load-sessions! default-session-params create-session! start-empty! select-start-session! switch-session! update-session-state!)

(def replay-page-size 500)

(def session-ui-keys
  [:draft :attachments :selected :inspected-row :inspector? :inspect-return-focus
   :inspection :inspect-tab :follow? :scroll :scroll-top :scroll-offset
   :expanded :expanded-rows])


(defn save-session-ui [state sid]
  (if-not sid
    state
    (let [ui (:ui state)
          scoped (select-keys ui session-ui-keys)]
      (-> state
          (assoc-in [:ui :drafts sid] (:draft ui))
          (assoc-in [:ui :session-ui sid] scoped)))))


(defn restore-session-ui [state sid]
  (let [saved (get-in state [:ui :session-ui sid])
        draft (or (get-in state [:ui :drafts sid]) (:draft saved) "")
        scoped (merge {:draft draft
                       :attachments []
                       :selected nil
                       :inspected-row nil
                       :inspector? false
                       :inspect-return-focus :composer
                       :inspection nil
                       :inspect-tab :summary
                       :follow? true
                       :scroll nil
                       :scroll-top 0
                       :scroll-offset 0
                       :expanded {}
                       :expanded-rows {}}
                      saved
                      {:draft draft})]
    (update state :ui merge scoped)))


(defn replace-session [sessions session]
  (let [sid (client/value-field session :id)
        present? (some #(= sid (client/value-field % :id)) sessions)]
    (if present?
      (mapv #(if (= sid (client/value-field % :id)) session %) sessions)
      (conj (vec sessions) session))))


(defn replay-pages! [app sid after events through]
  (if (>= after through)
    (client/resolved events)
    (-> (client/call! app "event.replay" {:session-id sid :after after :limit replay-page-size})
        (.then
         (fn [wire-page]
           (let [page (client/decode wire-page)
                 batch (vec (or (client/value-field page :events) []))
                 cursor (or (client/value-field page :cursor) after)
                 combined (into events (filter #(<= (or (:seq %) 0) through)) batch)]
             (if (and (= replay-page-size (count batch)) (< cursor through) (> cursor after))
               (replay-pages! app sid cursor combined through)
               combined)))))))


(defn hydrate-session!
  ([app sid] (hydrate-session! app sid false))
  ([app sid switching?]
   (let [token (str (js/Date.now) "-" (rand-int 1000000))
         navigation (if switching?
                      (inc (or (:navigation-generation @(:state app)) 0))
                      (or (:navigation-generation @(:state app)) 0))]
     (swap! (:state app)
            (fn [state]
              (cond-> (assoc state :hydrating {:token token :session-id sid :events []})
                switching? (assoc :navigation-generation navigation))))
     (-> (client/call! app "session.view" {:session-id sid})
         (.then
          (fn [wire-snapshot]
            (let [snapshot (client/decode wire-snapshot)
                  cursor (or (client/value-field snapshot :cursor) 0)]
              (-> (replay-pages! app sid 0 [] cursor)
                  (.then
                   (fn [replayed]
                     (swap! (:state app)
                            (fn [state]
                              (if (and (= token (get-in state [:hydrating :token]))
                                       (= navigation (:navigation-generation state)))
                                (let [buffered (get-in state [:hydrating :events])
                                      boundary (last (keep-indexed
                                                      (fn [index event]
                                                        (when (and (number? (:seq event)) (<= (:seq event) cursor)) index))
                                                      buffered))
                                      buffered (if (some? boundary) (subvec (vec buffered) (inc boundary)) buffered)
                                      state (if switching?
                                              (save-session-ui state (client/session-id-from state))
                                              state)
                                      hydrated (model/hydrate snapshot
                                                              (into (vec replayed) buffered))]
                                  (-> state
                                      (assoc :view hydrated
                                             :hydrating nil
                                             :history (if switching?
                                                        (get-in state [:history-by-session sid])
                                                        (:history state)))
                                      (cond-> switching? (restore-session-ui sid))
                                      (assoc-in [:ui :inspection] nil)
                                      (cond-> (and (nil? @(:connect-promise app))
                                                   (= :ready (some-> @(:client app) :state deref :status)))
                                        (update :connection #(-> % (assoc :status :ready) (dissoc :error))))
                                      client/operation-notice))
                                state)))
                     (:view @(:state app))))))))
         (.catch
          (fn [hydrate-error]
            (swap! (:state app)
                   (fn [state]
                     (if (and (= token (get-in state [:hydrating :token]))
                              (= navigation (:navigation-generation state)))
                       (assoc state :hydrating nil)
                       state)))
            (throw hydrate-error)))))))


(defn refresh-owned-session! [app sid navigation]
  (let [state @(:state app)]
    (if (and (= sid (client/session-id-from state))
             (= navigation (:navigation-generation state)))
      (hydrate-session! app sid false)
      (client/resolved (:view state)))))


(defn load-sessions! [app]
  (-> (client/call! app "session.list" {})
      (.then
       (fn [wire-result]
         (let [sessions (vec (or (client/value-field (client/decode wire-result) :sessions) []))]
           (swap! (:state app) assoc :sessions sessions)
           sessions)))))


(defn default-session-params [app]
  (let [options (:options app)
        config (if (:browser-selection? @(:state app)) {}
                   (client/non-nil-map (select-keys options [:provider :model :thinking])))]
    (cond-> {:cwd (client/workspace app)}
      (:session-name options) (assoc :name (:session-name options))
      (seq config) (assoc :config config))))


(defn create-session! [app data]
  (let [base (default-session-params app)
        params (cond-> base
                 (:name data) (assoc :name (:name data))
                 (:config data) (assoc :config (:config data)))]
    (-> (client/mutation! app "session.create" params)
        (.then
         (fn [wire-session]
           (let [session (client/decode wire-session)]
             (swap! (:state app) update :sessions replace-session session)
             session))))))


(defn start-empty!
  ([app data] (start-empty! app data false))
  ([app data initializing?]
   (swap! (:state app)
          (fn [state]
            (let [config (merge (get-in state [:setup :config])
                                (:config (default-session-params app)) (:config data))]
              (-> state
                  (save-session-ui (client/session-id-from state))
                  (update :navigation-generation (fnil inc 0))
                  (assoc :empty-composer? true :first-send-unknown? false
                         :view (assoc (model/empty-state) :session
                                      {:name (or (:name data) (get-in app [:options :session-name]) "Untitled session")
                                       :metadata (when (or (:name data) (get-in app [:options :session-name]))
                                                   {:title/source :user})
                                       :cwd (client/workspace app) :config config})
                         :history nil :hydrating nil :notice nil)
                  ;; Boot may finish after the user opens a screen or types.
                  ;; Only an explicit New action owns resetting their UI state.
                  (cond-> (not initializing?)
                    (restore-session-ui nil)
                    (not initializing?) (assoc-in [:ui :overlay] nil)
                    (not initializing?) (assoc-in [:ui :focus] :composer))))))
   (client/resolved (get-in @(:state app) [:view :session]))))


(defn select-start-session! [app sessions preferred]
  (let [requested (or preferred (get-in app [:options :session-id]))
        selected (when requested
                   (some #(when (= requested (client/value-field % :id)) %) sessions))]
    (cond
      selected (hydrate-session! app (client/value-field selected :id) true)
      requested (client/rejected (client/error "session-not-found" "The requested session does not exist"
                                 {:session-id requested}))
      (:empty-composer? @(:state app)) (client/resolved (:view @(:state app)))
      :else (start-empty! app {} true))))


(defn switch-session! [app sid]
  (when-not (and (string? sid) (not (str/blank? sid)))
    (throw (client/error "invalid-session" "Session id must be non-empty" {})))
  (swap! (:state app) assoc :models [] :providers [] :empty-composer? false)
  (let [pending (hydrate-session! app sid true)
        context {:session-id sid :navigation (:navigation-generation @(:state app))}]
    (-> pending
        (.then (fn [snapshot]
                 (-> (catalog/refresh-catalog! app context)
                     (.then (fn [_] snapshot))))))))


(defn update-session-state! [app wire-session]
  (let [session (client/decode wire-session)
        sid (client/value-field session :id)]
    (swap! (:state app)
           (fn [state]
             (cond-> (update state :sessions replace-session session)
               (= sid (client/session-id-from state))
               (assoc-in [:view :session] session))))
    session))
