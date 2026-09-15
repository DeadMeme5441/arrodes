(ns arrodes.tui-app
  "Controller composition root: connection lifecycle, setup and action dispatch."
  (:require [clojure.string :as str]
            [arrodes.tui-model :as model]
            [arrodes.tui-rpc :as rpc]
            [arrodes.tui.controller.attachments :as attachments]
            [arrodes.tui.controller.catalog :as catalog]
            [arrodes.tui.controller.client :as client]
            [arrodes.tui.controller.sessions :as sessions]
            [arrodes.tui.controller.submission :as submission]))

(declare max-stderr-characters refresh-project! event! host-request! remove-host-overlay host-cancelled! update-widget! setup-params run-setup! boot-sessions! boot! make-client! connect! create! start! close! remove-host-request! perform-command! command!)

(def max-stderr-characters (* 16 1024))


(defn refresh-project! [app]
  (let [cwd (client/workspace app)
        child-process (js/require "node:child_process")]
    (.execFile child-process "git" #js ["-C" cwd "rev-parse" "--abbrev-ref" "HEAD"]
               #js {:timeout 1500 :maxBuffer 4096}
               (fn [error stdout _]
                 (when (and (not @(:closed? app)) (= cwd (client/workspace app)))
                   (swap! (:state app) assoc :branch
                          (when-not error (str/trim stdout))))))))


(defn event! [app wire-event]
  (let [event (client/decode wire-event)]
    (when (contains? #{:operation/completed :operation/failed :operation/cancelled} (:type event))
      (refresh-project! app))
    (swap! (:state app)
           (fn [state]
             (let [{:keys [session-id] :as hydrating} (:hydrating state)
                   active-sid (client/session-id-from state)]
               (cond
                 (and hydrating (client/same-session? event session-id))
                 (update-in state [:hydrating :events] conj event)

                 (and active-sid (client/same-session? event active-sid))
                 (cond-> (-> state
                             (update :view model/apply-event event)
                             (cond-> (= :operation/started (:type event)) (assoc :notice nil))
                             client/operation-notice)
                   (= :operation-error (:type event))
                   (assoc :notice {:kind :error :message (or (get-in event [:data :error :message]) "Operation error")
                                   :data (:data event)}))

                 :else state))))))


(defn host-request! [app envelope]
  (let [request {:id (:id envelope)
                 :request-id (:request-id envelope)
                 :request (client/decode (:request envelope))}]
    (swap! (:state app) update :host-requests
           (fn [requests]
             (if (some #(= (:id request) (:id %)) requests)
               requests
               (conj (vec requests) request))))))


(defn remove-host-overlay [overlay id]
  (when overlay
    (let [previous (remove-host-overlay (:return-overlay overlay) id)]
      (if (= id (:host-id overlay))
        previous
        (cond-> overlay
          (contains? overlay :return-overlay) (assoc :return-overlay previous))))))


(defn host-cancelled! [app id reason]
  (swap! (:state app)
         (fn [state]
           (-> state
               (update :host-requests
                       #(filterv (fn [request] (not= id (:id request))) %))
               (update-in [:ui :overlay] remove-host-overlay id)
               (assoc :notice {:kind :info
                               :message (str "Host interaction cancelled"
                                             (when reason (str ": " reason)))})))))


(defn update-widget! [app request]
  (let [sid (client/value-field request :session-id)
        id (client/value-field request :id)
        remove? (boolean (client/value-field request :remove?))]
    (when-not (and (string? sid) (not (str/blank? sid)))
      (throw (client/error "invalid-widget-session" "Widget request requires a session id" {})))
    (when-not (and (string? id) (not (str/blank? id)))
      (throw (client/error "invalid-widget" "Widget request requires a non-empty id" {})))
    (swap! (:state app)
           (fn [state]
             (if remove?
               (let [widgets (dissoc (get-in state [:widgets-by-session sid] {}) id)]
                 (if (seq widgets)
                   (assoc-in state [:widgets-by-session sid] widgets)
                   (update state :widgets-by-session dissoc sid)))
               (assoc-in state [:widgets-by-session sid id]
                         {:id id
                          :placement (keyword (or (client/value-field request :placement) :status))
                          :content (or (client/value-field request :content) "")
                          :order (or (client/value-field request :order) 0)}))))
    {:widget-id id :visible? (not remove?)}))


(defn setup-params [app]
  (let [options (:options app)
        config (if (:browser-selection? @(:state app)) {}
                   (client/non-nil-map (select-keys options [:provider :model :thinking])))
        sid (client/session-id-from @(:state app))]
    (cond-> config
      sid (assoc :session-id sid))))

(defn run-setup! [app startup? preferred-session-id]
  (swap! (:state app) assoc :setup {:status :running})
  (-> (client/call! app "setup.run" (cond-> (setup-params app) (not startup?) (assoc :force? true))
             {:mutation? true :timeout-ms (* 15 60 1000)})
      (.then
       (fn [wire-result]
         (let [result (client/decode wire-result)
               session (client/value-field result :session)]
           (swap! (:state app) assoc :setup (assoc result :status :ready))
           (if session
             (do
               (sessions/update-session-state! app session)
               (catalog/load-models! app false))
             (boot-sessions! app preferred-session-id)))))
      (.catch
       (fn [setup-error]
         (let [cancelled? (= "cancelled" (:code (ex-data setup-error)))]
           (swap! (:state app) assoc
                  :setup {:status (if cancelled? :cancelled :error)
                          :error (when-not cancelled? (ex-message setup-error))}
                  :notice {:kind (if cancelled? :info :error)
                           :message (if cancelled?
                                      "Setup cancelled. Run /setup or /login when you are ready."
                                      (str "Setup needs attention: " (ex-message setup-error)))})
           nil)))))


(defn boot-sessions! [app preferred-session-id]
  (-> (sessions/load-sessions! app)
      (.then #(sessions/select-start-session! app % preferred-session-id))
      (.then (fn [_] (refresh-project! app) (catalog/refresh-catalog! app (catalog/catalog-context app))))))


(defn boot! [app preferred-session-id]
  (if (false? (get-in app [:options :setup?]))
    (boot-sessions! app preferred-session-id)
    (-> (client/call! app "setup.status" (setup-params app))
        (.then
         (fn [wire-result]
           (let [result (client/decode wire-result)]
             (swap! (:state app) assoc :setup result)
             (if (client/value-field result :ready?)
               (boot-sessions! app preferred-session-id)
               (if (:configuration-ready? result)
                 (run-setup! app true preferred-session-id)
                 (do
                   (swap! (:state app) assoc :providers (:providers result))
                   (swap! (:state app) assoc-in [:ui :overlay]
                          {:kind :providers :title "Providers" :token (str (random-uuid))
                           :query "" :index 0 :startup? true
                           :hint "Welcome to Arrodes. Connect a provider, then choose your default model."})
                   nil)))))))))


(defn make-client! [app]
  (let [options (:options app)
        initialize (client/non-nil-map (select-keys options [:cwd :home :data-dir :memory? :trust]))
        client
        (rpc/create!
         {:command (or (:rpc-command options) ["clojure" "-Srepro" "-M:host"])
          :process-cwd (:runtime-root options)
          :initialize initialize
          :request-timeout-ms (:request-timeout-ms options)
          :handshake-timeout-ms (:handshake-timeout-ms options)
          :initialize-timeout-ms (or (:initialize-timeout-ms options) 60000)
          :shutdown-timeout-ms (or (:shutdown-timeout-ms options) 5000)
          :on-event #(event! app %)
          :on-host-request #(host-request! app %)
          :on-host-cancel #(host-cancelled! app %1 %2)
          :on-stderr
          (fn [chunk]
            (swap! (:state app) update-in [:connection :stderr]
                   (fn [current]
                     (let [combined (str (or current "") chunk)]
                       (if (> (count combined) max-stderr-characters)
                         (subs combined (- (count combined) max-stderr-characters))
                         combined)))))
          :on-protocol-error
          (fn [protocol-error]
            (swap! (:state app) assoc :notice
                   {:kind :error :message (ex-message protocol-error)}))
          :on-status
          (fn [status details]
            (when (and (not @(:closed? app))
                       (nil? @(:connect-promise app))
                       (contains? #{:disconnected :closed} status))
              (swap! (:state app)
                     (fn [state]
                       (if (= :closing (get-in state [:connection :status]))
                         state
                         (let [stderr (get-in state [:connection :stderr])
                               failure (:error details)]
                           (cond-> (assoc state :connection
                                          (cond-> {:status status}
                                            stderr (assoc :stderr stderr)
                                            (= :disconnected status)
                                            (assoc :error (if failure
                                                            {:message (ex-message failure) :data (ex-data failure)}
                                                            {:code (:code details)
                                                             :signal (:signal details)
                                                             :message (:spawn-error details)}))))
                             (= :disconnected status) (assoc :host-requests []))))))))})]
    (reset! (:client app) client)
    client))


(defn connect! [app reconnect?]
  (cond
    @(:closed? app)
    (client/rejected (client/error "closed" "TUI application is closed" {}))

    @(:connect-promise app)
    @(:connect-promise app)

    :else
    (let [preferred-session-id (when reconnect? (client/session-id-from @(:state app)))
          _ (swap! (:state app) assoc :connection {:status :starting}
                   :host-requests [] :widgets-by-session {} :hydrating nil :notice nil)
          before (-> (client/resolved nil)
                     (.then (fn [_]
                              (if-let [client @(:client app)]
                                (rpc/close! client)
                                nil))))
          attempt
          (-> before
              (.then
               (fn [_]
                 (when @(:closed? app)
                   (throw (client/error "closed" "TUI application was closed during connection" {})))
                 (let [client (make-client! app)]
                   (-> (rpc/start! client)
                       (.then (fn [_] (boot! app preferred-session-id)))))))
              (.then
               (fn [result]
                 (if @(:closed? app)
                   (-> (rpc/close! (client/current-client app))
                       (.then (fn [_]
                                (throw (client/error "closed"
                                              "TUI application was closed during connection" {})))))
                   (do
                     (swap! (:state app)
                            #(-> %
                                 (update :connection (fn [connection]
                                                       (-> connection (assoc :status :ready) (dissoc :error))))
                                 client/operation-notice))
                     result)))
               (fn [connect-error]
                 (when-not @(:closed? app)
                   (swap! (:state app) update :connection
                          (fn [connection]
                            (assoc connection
                                   :status :disconnected
                                   :error {:message (ex-message connect-error)
                                           :data (ex-data connect-error)}))))
                 (swap! (:state app) assoc :notice
                        {:kind :error :message (or (ex-message connect-error) (.-message connect-error))
                         :data (ex-data connect-error)})
                 (throw connect-error))))
          tracked
          (.then attempt
                 (fn [value]
                   (reset! (:connect-promise app) nil)
                   value)
                 (fn [connect-error]
                   (reset! (:connect-promise app) nil)
                   (throw connect-error)))]
      (reset! (:connect-promise app) tracked)
      tracked)))


(defn create!
  "Create the renderer-independent application controller."
  [options]
  (when-not (and (string? (:runtime-root options))
                 (.isAbsolute client/path-module (:runtime-root options)))
    (throw (client/error "invalid-runtime-root" ":runtime-root must be an absolute path" {})))
  {:options options
   :client (atom nil)
   :connect-promise (atom nil)
   :closed? (atom false)
   :state
   (atom {:connection {:status :starting}
          :view (model/empty-state)
          :sessions []
          :models []
          :providers []
          :catalog-operation nil
          :setup {:status :checking}
          :history nil
          :history-by-session {}
          :widgets-by-session {}
          :navigation-generation 0
          :ui {:draft "" :drafts {} :session-ui {} :attachments []
               :overlay nil :selected nil :inspect-tab :summary
               :focus :composer :follow? true}
          :notice nil
          :host-requests []
          :hydrating nil})})


(defn start! [app]
  (cond
    @(:connect-promise app) @(:connect-promise app)
    (= :ready (get-in @(:state app) [:connection :status])) (client/resolved @(:state app))
    :else (connect! app false)))


(defn close! [app]
  (reset! (:closed? app) true)
  (swap! (:state app) assoc :connection {:status :closing})
  (if-let [client @(:client app)]
    (-> (rpc/close! client)
        (.then
         (fn [report]
           (reset! (:client app) nil)
           (swap! (:state app) assoc :connection {:status :closed})
           report)
         (fn [close-error]
           (swap! (:state app) assoc
                  :connection {:status :closing
                               :error {:message (ex-message close-error)
                                       :data (ex-data close-error)}})
           (throw close-error))))
    (do
      (swap! (:state app) assoc :connection {:status :closed})
      (client/resolved {:status :closed}))))


(defn remove-host-request! [app id]
  (swap! (:state app) update :host-requests
         #(filterv (fn [request] (not= id (:id request))) %)))


(defn perform-command! [app action data]
  (let [state @(:state app)
        sid (client/session-id-from state)
        oid (client/operation-id-from state)
        navigation (:navigation-generation state)
        context {:session-id sid :navigation navigation}]
    (when (and (:first-submit? state) (contains? #{:new-session :switch-session :select-model} action))
      (throw (client/error "submission-pending" "Wait for the first message to finish submitting." {})))
    (case action
      :submit (cond (:first-submit? state) (client/rejected (client/error "submission-pending" "The first message is being submitted." {}))
                    sid (submission/submit! app data)
                    (or (false? (get-in app [:options :setup?])) (get-in state [:setup :configuration-ready?]))
                    (submission/submit-first! app data)
                    :else (client/rejected (client/error "setup-required" "Open /providers and choose a default model to start a conversation." {})))

      :cancel
      (if oid
        (-> (client/mutation! app "operation.cancel" {:operation-id oid}) (.then client/decode))
        (client/resolved {:session-id sid :operation-id nil :status :idle}))

      :refresh (if sid (sessions/hydrate-session! app sid false) (client/resolved (:view state)))
      :sessions (sessions/load-sessions! app)
      :switch-session (sessions/switch-session! app (:id data))

      :new-session
      (if (and (not= false (get-in app [:options :setup?]))
               (not (get-in state [:setup :configuration-ready?])))
        (client/rejected (client/error "setup-required" "Choose a default model in /providers before creating a conversation." {}))
        (sessions/start-empty! app data))

      :rename-session
      (if (and (nil? sid) (nil? (:id data)))
        (do (swap! (:state app) assoc-in [:view :session :name] (:name data))
            (client/resolved (get-in @(:state app) [:view :session])))
      (let [target (or (:id data) sid)]
        (-> (client/mutation! app "session.name" {:session-id target :name (:name data)})
            (.then #(sessions/update-session-state! app %)))))

      :delete-session
      (let [target (:id data)]
        (-> (client/mutation! app "session.delete" {:session-id target})
            (.then
             (fn [wire-result]
               (swap! (:state app)
                      (fn [state]
                        (cond-> (-> state
                                    (update :sessions
                                            #(filterv (fn [session]
                                                        (not= target (client/value-field session :id))) %))
                                    (update :history-by-session dissoc target)
                                    (update :widgets-by-session dissoc target)
                                    (update-in [:ui :drafts] dissoc target)
                                    (update-in [:ui :session-ui] dissoc target))
                          (= target sid) (assoc-in [:view :session] nil)
                          (= target sid) (assoc :history nil))))
               (if (= target sid)
                 (-> (sessions/load-sessions! app)
                     (.then
                      (fn [sessions]
                        (if-let [next-session (first sessions)]
                          (-> (sessions/hydrate-session! app (client/value-field next-session :id) true)
                              (.then (fn [_] (client/decode wire-result))))
                          (-> (sessions/start-empty! app {})
                              (.then (fn [_] (client/decode wire-result))))))))
                 (client/decode wire-result))))))

      :branch
      (-> (client/mutation! app "session.fork"
                     {:session-id sid :entry-id (:entry-id data) :position :at})
          (.then
           (fn [wire-session]
             (let [session (client/decode wire-session)]
               (swap! (:state app) update :sessions sessions/replace-session session)
               (-> (sessions/hydrate-session! app (client/value-field session :id) true)
                   (.then (fn [_] session)))))))

      :models (catalog/load-models! app (boolean (:refresh? data)))

      :browse-provider (catalog/browse-provider! app data)

      :providers (catalog/load-providers! app)

      :provider-cancel (rpc/cancel-method! (client/current-client app) "auth.login")

      :provider-login
      (catalog/catalog-work!
       app "Connecting provider… Esc cancels"
       (fn [_]
         (-> (client/call! app "auth.login" (catalog/catalog-params context (select-keys data [:provider :type]))
                    {:mutation? true :timeout-ms (* 15 60 1000)})
             (.then (fn [_] (catalog/auth-observation! app context (:provider data) false)))
             (.catch (fn [failure]
                       (if (= "cancelled" (:code (ex-data failure)))
                         (catalog/auth-observation! app context (:provider data) true)
                         (throw failure)))))))

      :provider-logout
      (catalog/catalog-work! app "Disconnecting provider…"
                     (fn [_] (-> (client/mutation! app "auth.logout" (catalog/catalog-params context {:provider (:provider data)}))
                                 (.then (fn [_] (catalog/load-providers! app context))))))

      :provider-models
      (catalog/catalog-work!
       app "Discovering models…"
       (fn [_]
         (-> (client/call! app "model.refresh" (catalog/catalog-params context {:provider (:provider data)}))
             (.then (fn [_] (catalog/load-models! app false context)))
             (.then (fn [models]
                      (-> (catalog/load-providers! app context) (.then (fn [_] models))))))))

      :select-model
      (catalog/catalog-work!
       app "Saving model selection…"
       (fn [_]
         (-> (if (and (nil? sid) (= :session (keyword (:scope data))))
               (let [entry (some #(when (= (keyword (:provider data)) (keyword (:provider %))) %) (:providers state))
                     m (some #(when (and (= (keyword (:provider data)) (keyword (:provider %)))
                                        (= (:model data) (:id %))) %) (:models state))]
                 (when-not (and (:available? entry) m
                                (some #{(keyword (:thinking data))} (map keyword (or (seq (:thinking-levels m)) [:none]))))
                   (throw (client/error "model-unavailable" "Select a connected provider and a supported model/effort." {})))
                 (client/resolved {:config (select-keys data [:provider :model :thinking])}))
               (client/mutation! app "model.select"
                          (cond-> (select-keys data [:provider :model :thinking :scope])
                            sid (assoc :session-id sid))))
             (.then (fn [wire]
                      (let [result (client/decode wire)]
                        (when (= :default (keyword (:scope data)))
                          (swap! (:state app) assoc :browser-selection? true))
                        (when-let [session (:session result)] (sessions/update-session-state! app session))
                        (when (and (nil? sid) (catalog/owns-catalog? app context))
                          (swap! (:state app) update-in [:view :session :config] merge (:config result)))
                        (when (or (= :default (keyword (:scope data))) (catalog/owns-catalog? app context))
                          (swap! (:state app) assoc :notice
                                 {:kind :info :message (if (= :default (keyword (:scope data)))
                                                        (if sid "Model applied here and saved as the default for new sessions." "Default saved for new sessions.")
                                                        (if oid "Model saved for the next turn; the current run continues."
                                                            "Model changed for this conversation."))}))
                        (if sid
                          (if (= :default (keyword (:scope data)))
                            (-> (client/call! app "setup.status" {})
                                (.then (fn [status]
                                         (swap! (:state app) assoc :setup (client/decode status))
                                         result)))
                            result)
                          (if (= :default (keyword (:scope data)))
                            (-> (run-setup! app true nil) (.then (fn [_] result)))
                            result))))))))

      :setup (run-setup! app false nil)

      :set-model
      (-> (client/mutation! app "session.configure"
                     {:session-id sid
                      :config {:provider (:provider data)
                               :model (:model data)
                               :thinking (:thinking data)}})
          (.then
           (fn [wire-session]
             (sessions/update-session-state! app wire-session)
             (sessions/refresh-owned-session! app sid navigation))))

      :queue-edit (submission/queue-edit! app data)
      :queue-drop (submission/queue-drop! app data)

      :result
      (-> (client/call! app "result.inspect"
                 {:session-id sid :result-id (:result-id data)})
          (.then client/decode))

      :artifact
      (-> (client/call! app "artifact.read"
                 (client/non-nil-map {:session-id sid
                               :artifact-id (:artifact-id data)
                               :offset (:offset data)
                               :limit (:limit data)}))
          (.then client/decode))

      :attach (client/resolved (attachments/add-attachment! app (:path data)))
      :files (client/resolved (attachments/complete-files app (:query data)))

      :host-widget
      (client/resolved (update-widget! app (:request data)))


      :host-response
      (-> (rpc/host-response! (client/current-client app) (:id data) (:result data))
          (.then (fn [result] (remove-host-request! app (:id data)) result)))

      :host-cancel
      (-> (rpc/host-cancel! (client/current-client app) (:id data))
          (.then (fn [result] (remove-host-request! app (:id data)) result)))

      :continue
      (-> (client/mutation! app "session.continue" {:session-id sid}) (.then client/decode))

      :compact
      (-> (client/mutation! app "session.compact" {:session-id sid}) (.then client/decode))

      :reload
      (-> (client/mutation! app "session.reload" {:session-id sid})
          (.then (fn [_] (sessions/refresh-owned-session! app sid navigation))))

      :history
      (-> (client/call! app "session.tree" {:session-id sid})
          (.then
           (fn [wire-result]
             (let [history (client/decode wire-result)]
               (swap! (:state app)
                      (fn [state]
                        (cond-> (assoc-in state [:history-by-session sid] history)
                          (and (= sid (client/session-id-from state))
                               (= navigation (:navigation-generation state)))
                          (assoc :history history))))
               history))))

      :export
      (-> (client/mutation! app "session.export"
                     (client/non-nil-map {:session-id sid
                                   :path (when (:path data) (.resolve client/path-module (client/workspace app) (:path data)))
                                   :format (:format data)}))
          (.then client/decode))

      :reconnect (connect! app true)

      (client/rejected (client/error "unknown-action" "Unknown TUI command" {:action action})))))


(defn command!
  "Execute a renderer action after pending startup/reconnect has settled. Host replies
  bypass that barrier so initialization can ask for input. Mutations are never retried."
  ([app action] (command! app action {}))
  ([app action data]
   (try
     (let [execute (fn [_]
                     (when @(:closed? app)
                       (throw (client/error "closed" "TUI application is closed" {})))
                     (perform-command! app action (or data {})))
           connection (when-not (contains? #{:reconnect :host-response :host-cancel :host-widget} action)
                        @(:connect-promise app))
           promise (if connection (.then connection execute) (execute nil))]
       (.catch promise
               (fn [command-error]
                 (swap! (:state app) assoc :notice
                        {:kind :error
                         :message (or (ex-message command-error)
                                      (.-message command-error)
                                      (str command-error))
                         :unknown-outcome? (boolean (:unknown-outcome? (ex-data command-error)))
                         :data (ex-data command-error)})
                 (throw command-error))))
     (catch :default command-error
       (swap! (:state app) assoc :notice
              {:kind :error
               :message (or (ex-message command-error)
                            (.-message command-error)
                            (str command-error))
               :unknown-outcome? (boolean (:unknown-outcome? (ex-data command-error)))
               :data (ex-data command-error)})
       (client/rejected command-error)))))
