(ns arrodes.tui-app
  "Stateful controller used by the OpenTUI renderer. All durable behavior remains
  in the protocol host; this namespace coordinates transport and presentation state."
  (:require [clojure.string :as str]
            [arrodes.tui-model :as model]
            [arrodes.tui-rpc :as rpc]))

(def ^:private fs (js/require "fs"))
(def ^:private path-module (js/require "path"))

(def ^:private replay-page-size 500)
(def ^:private command-timeout-ms 30000)
(def ^:private evaluation-timeout-ms (* 10 60 1000))
(def ^:private max-completion-results 200)
(def ^:private max-completion-entries 20000)
(def ^:private max-completion-depth 24)
(def ^:private max-text-attachment-bytes (* 8 1024 1024))
(def ^:private max-image-attachment-bytes (* 5 1024 1024))
(def ^:private max-stderr-characters (* 16 1024))

(def ^:private ignored-directories
  #{".git" ".hg" ".svn" ".cache" ".gradle" ".idea" ".next" ".turbo"
    "build" "coverage" "dist" "node_modules" "out" "target"})

(def ^:private image-mime-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "webp" "image/webp" "bmp" "image/bmp"})

(def ^:private session-ui-keys
  [:draft :attachments :selected :inspected-row :inspector? :inspect-return-focus
   :inspection :inspect-tab :follow? :scroll :scroll-top :scroll-offset
   :expanded :expanded-rows])

(defn- error
  ([code message] (error code message {}))
  ([code message data] (ex-info message (assoc data :code code))))

(defn- resolved [value]
  (js/Promise.resolve value))

(defn- rejected [value]
  (js/Promise.reject value))

(defn- decode [value]
  (model/decode-wire value))

(defn- value-field [value key]
  (model/field value key))

(defn- non-nil-map [value]
  (into {} (remove (comp nil? val)) value))

(defn- session-id-from [state]
  (value-field (get-in state [:view :session]) :id))

(defn- operation-id-from [state]
  (let [operation (get-in state [:view :operation])
        status (value-field operation :status)]
    (when (and operation
               (not (contains? #{:completed :failed :cancelled :interrupted}
                               (if (string? status) (keyword status) status))))
      (value-field operation :id))))

(defn- workspace [app]
  (.resolve path-module (or (get-in @(:state app) [:view :session :cwd])
                            (get-in app [:options :cwd]) (.cwd js/process))))

(defn- same-session? [event sid]
  (= sid (value-field event :session-id)))

(defn- save-session-ui [state sid]
  (if-not sid
    state
    (let [ui (:ui state)
          scoped (select-keys ui session-ui-keys)]
      (-> state
          (assoc-in [:ui :drafts sid] (:draft ui))
          (assoc-in [:ui :session-ui sid] scoped)))))

(defn- restore-session-ui [state sid]
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

(defn- replace-session [sessions session]
  (let [sid (value-field session :id)
        present? (some #(= sid (value-field % :id)) sessions)]
    (if present?
      (mapv #(if (= sid (value-field % :id)) session %) sessions)
      (conj (vec sessions) session))))

(defn- current-client [app]
  (or @(:client app)
      (throw (error "not-connected" "TUI backend is not connected" {}))))

(defn- call!
  ([app method params] (call! app method params {}))
  ([app method params options]
   (rpc/request! (current-client app) method params
                 (merge {:timeout-ms (or (get-in app [:options :request-timeout-ms])
                                         command-timeout-ms)}
                        options))))

(defn- mutation! [app method params]
  (call! app method params {:mutation? true}))

(defn- operation-notice [state]
  (let [operation (get-in state [:view :operation])
        error (:error operation)]
    (if (= :failed (:status operation))
      (assoc state :notice {:kind :error
                            :message (or (value-field error :message) "The operation failed.")
                            :data error})
      state)))

(defn- refresh-project! [app]
  (let [cwd (workspace app)
        child-process (js/require "node:child_process")]
    (.execFile child-process "git" #js ["-C" cwd "rev-parse" "--abbrev-ref" "HEAD"]
               #js {:timeout 1500 :maxBuffer 4096}
               (fn [error stdout _]
                 (when (and (not @(:closed? app)) (= cwd (workspace app)))
                   (swap! (:state app) assoc :branch
                          (when-not error (str/trim stdout))))))))

(defn- event! [app wire-event]
  (let [event (decode wire-event)]
    (when (contains? #{:operation/completed :operation/failed :operation/cancelled} (:type event))
      (refresh-project! app))
    (swap! (:state app)
           (fn [state]
             (let [{:keys [session-id] :as hydrating} (:hydrating state)
                   active-sid (session-id-from state)]
               (cond
                 (and hydrating (same-session? event session-id))
                 (update-in state [:hydrating :events] conj event)

                 (and active-sid (same-session? event active-sid))
                 (cond-> (-> state
                             (update :view model/apply-event event)
                             (cond-> (= :operation/started (:type event)) (assoc :notice nil))
                             operation-notice)
                   (= :operation-error (:type event))
                   (assoc :notice {:kind :error :message (or (get-in event [:data :error :message]) "Operation error")
                                   :data (:data event)}))

                 :else state))))))

(defn- host-request! [app envelope]
  (let [request {:id (:id envelope)
                 :request-id (:request-id envelope)
                 :request (decode (:request envelope))}]
    (swap! (:state app) update :host-requests
           (fn [requests]
             (if (some #(= (:id request) (:id %)) requests)
               requests
               (conj (vec requests) request))))))

(defn- remove-host-overlay [overlay id]
  (when overlay
    (let [previous (remove-host-overlay (:return-overlay overlay) id)]
      (if (= id (:host-id overlay))
        previous
        (cond-> overlay
          (contains? overlay :return-overlay) (assoc :return-overlay previous))))))

(defn- host-cancelled! [app id reason]
  (swap! (:state app)
         (fn [state]
           (-> state
               (update :host-requests
                       #(filterv (fn [request] (not= id (:id request))) %))
               (update-in [:ui :overlay] remove-host-overlay id)
               (assoc :notice {:kind :info
                               :message (str "Host interaction cancelled"
                                             (when reason (str ": " reason)))})))))

(defn- update-widget! [app request]
  (let [sid (value-field request :session-id)
        id (value-field request :id)
        remove? (boolean (value-field request :remove?))]
    (when-not (and (string? sid) (not (str/blank? sid)))
      (throw (error "invalid-widget-session" "Widget request requires a session id" {})))
    (when-not (and (string? id) (not (str/blank? id)))
      (throw (error "invalid-widget" "Widget request requires a non-empty id" {})))
    (swap! (:state app)
           (fn [state]
             (if remove?
               (let [widgets (dissoc (get-in state [:widgets-by-session sid] {}) id)]
                 (if (seq widgets)
                   (assoc-in state [:widgets-by-session sid] widgets)
                   (update state :widgets-by-session dissoc sid)))
               (assoc-in state [:widgets-by-session sid id]
                         {:id id
                          :placement (keyword (or (value-field request :placement) :status))
                          :content (or (value-field request :content) "")
                          :order (or (value-field request :order) 0)}))))
    {:widget-id id :visible? (not remove?)}))

(defn- replay-pages! [app sid after events through]
  (if (>= after through)
    (resolved events)
    (-> (call! app "event.replay" {:session-id sid :after after :limit replay-page-size})
        (.then
         (fn [wire-page]
           (let [page (decode wire-page)
                 batch (vec (or (value-field page :events) []))
                 cursor (or (value-field page :cursor) after)
                 combined (into events (filter #(<= (or (:seq %) 0) through)) batch)]
             (if (and (= replay-page-size (count batch)) (< cursor through) (> cursor after))
               (replay-pages! app sid cursor combined through)
               combined)))))))

(defn- hydrate-session!
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
     (-> (call! app "session.view" {:session-id sid})
         (.then
          (fn [wire-snapshot]
            (let [snapshot (decode wire-snapshot)
                  cursor (or (value-field snapshot :cursor) 0)]
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
                                              (save-session-ui state (session-id-from state))
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
                                      operation-notice))
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

(defn- refresh-owned-session! [app sid navigation]
  (let [state @(:state app)]
    (if (and (= sid (session-id-from state))
             (= navigation (:navigation-generation state)))
      (hydrate-session! app sid false)
      (resolved (:view state)))))

(defn- load-sessions! [app]
  (-> (call! app "session.list" {})
      (.then
       (fn [wire-result]
         (let [sessions (vec (or (value-field (decode wire-result) :sessions) []))]
           (swap! (:state app) assoc :sessions sessions)
           sessions)))))

(defn- default-session-params [app]
  (let [options (:options app)
        config (if (:browser-selection? @(:state app)) {}
                   (non-nil-map (select-keys options [:provider :model :thinking])))]
    (cond-> {:cwd (workspace app)}
      (:session-name options) (assoc :name (:session-name options))
      (seq config) (assoc :config config))))

(defn- create-session! [app data]
  (let [base (default-session-params app)
        params (cond-> base
                 (:name data) (assoc :name (:name data)))]
    (-> (mutation! app "session.create" params)
        (.then
         (fn [wire-session]
           (let [session (decode wire-session)]
             (swap! (:state app) update :sessions replace-session session)
             session))))))

(defn- catalog-context [app]
  (let [state @(:state app)]
    {:session-id (session-id-from state) :navigation (:navigation-generation state)}))

(defn- owns-catalog? [app context]
  (= context (catalog-context app)))

(defn- catalog-params [context data]
  (cond-> data (:session-id context) (assoc :session-id (:session-id context))))

(defn- load-models!
  ([app refresh?] (load-models! app refresh? (catalog-context app)))
  ([app refresh? context]
   (-> (call! app (if refresh? "model.refresh" "model.list") (catalog-params context {}))
       (.then (fn [wire-result]
                (let [models (vec (or (value-field (decode wire-result) :models) []))]
                  (when (owns-catalog? app context) (swap! (:state app) assoc :models models))
                  models))))))

(defn- load-providers!
  ([app] (load-providers! app (catalog-context app)))
  ([app context]
   (-> (call! app "auth.status" (catalog-params context {}))
       (.then (fn [wire]
                (let [providers (vec (:providers (decode wire)))]
                  (when (owns-catalog? app context) (swap! (:state app) assoc :providers providers))
                  providers))))))

(defn- refresh-catalog! [app context]
  (if (owns-catalog? app context)
    (js/Promise.all #js [(load-models! app false context) (load-providers! app context)])
    (resolved nil)))

(defn- auth-observation! [app context provider cancelled?]
  (-> (load-providers! app context)
      (.then (fn [providers]
               (let [entry (some #(when (= (keyword provider) (keyword (:provider %))) %) providers)
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

(defn- catalog-work! [app label work]
  (if (:catalog-operation @(:state app))
    (rejected (error "catalog-busy" "Wait for the current provider action to finish" {}))
    (do
      (swap! (:state app) assoc :catalog-operation label :notice nil)
      (-> (resolved nil)
          (.then work)
          (.finally (fn [] (swap! (:state app) assoc :catalog-operation nil)))))))

(defn- select-start-session! [app sessions preferred]
  (let [requested (or preferred (get-in app [:options :session-id]))
        cwd (.realpathSync fs (or (get-in app [:options :cwd]) (.cwd js/process)))
        selected (if requested
                   (some #(when (= requested (value-field % :id)) %) sessions)
                   (first (filter #(= cwd (.resolve path-module (value-field % :cwd))) sessions)))]
    (cond
      selected (hydrate-session! app (value-field selected :id) true)
      requested (rejected (error "session-not-found" "The requested session does not exist"
                                 {:session-id requested}))
      :else (-> (create-session! app {})
                (.then #(hydrate-session! app (value-field % :id) true))))))

(defn- setup-params [app]
  (let [options (:options app)
        config (if (:browser-selection? @(:state app)) {}
                   (non-nil-map (select-keys options [:provider :model :thinking])))
        sid (session-id-from @(:state app))]
    (cond-> config
      sid (assoc :session-id sid))))

(declare boot-sessions! update-session-state!)

(defn- run-setup! [app startup? preferred-session-id]
  (swap! (:state app) assoc :setup {:status :running})
  (-> (call! app "setup.run" (cond-> (setup-params app) (not startup?) (assoc :force? true))
             {:mutation? true :timeout-ms (* 15 60 1000)})
      (.then
       (fn [wire-result]
         (let [result (decode wire-result)
               session (value-field result :session)]
           (swap! (:state app) assoc :setup (assoc result :status :ready))
           (if session
             (do
               (update-session-state! app session)
               (load-models! app false))
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

(defn- boot-sessions! [app preferred-session-id]
  (-> (load-sessions! app)
      (.then #(select-start-session! app % preferred-session-id))
      (.then (fn [_] (refresh-project! app) (refresh-catalog! app (catalog-context app))))))

(defn- boot! [app preferred-session-id]
  (if (false? (get-in app [:options :setup?]))
    (boot-sessions! app preferred-session-id)
    (-> (call! app "setup.status" (setup-params app))
        (.then
         (fn [wire-result]
           (let [result (decode wire-result)]
             (swap! (:state app) assoc :setup result)
             (if (value-field result :ready?)
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

(defn- make-client! [app]
  (let [options (:options app)
        initialize (non-nil-map (select-keys options [:cwd :home :data-dir :memory? :trust]))
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

(defn- connect! [app reconnect?]
  (cond
    @(:closed? app)
    (rejected (error "closed" "TUI application is closed" {}))

    @(:connect-promise app)
    @(:connect-promise app)

    :else
    (let [preferred-session-id (when reconnect? (session-id-from @(:state app)))
          _ (swap! (:state app) assoc :connection {:status :starting}
                   :host-requests [] :widgets-by-session {} :hydrating nil :notice nil)
          before (-> (resolved nil)
                     (.then (fn [_]
                              (if-let [client @(:client app)]
                                (rpc/close! client)
                                nil))))
          attempt
          (-> before
              (.then
               (fn [_]
                 (when @(:closed? app)
                   (throw (error "closed" "TUI application was closed during connection" {})))
                 (let [client (make-client! app)]
                   (-> (rpc/start! client)
                       (.then (fn [_] (boot! app preferred-session-id)))))))
              (.then
               (fn [result]
                 (if @(:closed? app)
                   (-> (rpc/close! (current-client app))
                       (.then (fn [_]
                                (throw (error "closed"
                                              "TUI application was closed during connection" {})))))
                   (do
                     (swap! (:state app)
                            #(-> %
                                 (update :connection (fn [connection]
                                                       (-> connection (assoc :status :ready) (dissoc :error))))
                                 operation-notice))
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
                 (.isAbsolute path-module (:runtime-root options)))
    (throw (error "invalid-runtime-root" ":runtime-root must be an absolute path" {})))
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
    (= :ready (get-in @(:state app) [:connection :status])) (resolved @(:state app))
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
      (resolved {:status :closed}))))

(defn- normalize-mode [mode]
  (cond
    (keyword? mode) mode
    (string? mode) (keyword mode)
    (nil? mode) :prompt
    :else mode))

(defn- prompt-parts [text attachments]
  (let [parts (cond-> []
                (not (str/blank? text))
                (conj {:part/type :text :text text})
                (seq attachments)
                (into (mapv :part attachments)))]
    (cond
      (empty? parts)
      (throw (error "empty-prompt" "Prompt must contain text or attachments" {}))

      (and (= 1 (count parts))
           (= :text (:part/type (first parts)))
           (empty? attachments))
      text

      :else parts)))

(defn- clear-confirmed-submission! [app sid text attachments]
  (swap! (:state app)
         (fn [state]
           (let [active? (= sid (session-id-from state))]
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

(declare enrich-pending-by-id)

(defn- submit! [app data]
  (let [state @(:state app)
        sid (session-id-from state)
        text (or (:text data) (get-in state [:ui :draft]) "")
        mode (normalize-mode (:mode data))
        attachments (if (= :evaluate mode) [] (vec (get-in state [:ui :attachments])))
        oid (operation-id-from state)]
    (when-not sid
      (throw (error "no-session" "No active session" {})))
    (if (= :evaluate mode)
      (do
        (when (str/blank? text)
          (throw (error "empty-source" "Evaluation source cannot be empty" {})))
        (-> (call! app "session.evaluate" {:session-id sid :source text}
                   {:timeout-ms (or (get-in app [:options :evaluation-timeout-ms])
                                    evaluation-timeout-ms)
                    :mutation? true})
            (.then
             (fn [result]
               (decode result)))))
      (let [prompt (prompt-parts text attachments)
            [method params]
            (case mode
              :follow-up
              (do
                (when-not oid
                  (throw (error "no-operation" "No active operation accepts follow-up input" {})))
                ["operation.follow-up" {:operation-id oid :prompt prompt}])
              :prompt
              (if oid
                ["operation.steer" {:operation-id oid :prompt prompt}]
                ["session.run" {:session-id sid :prompt prompt}])
              (throw (error "invalid-submit-mode" "Unknown submission mode" {:mode mode})))]
        (-> (mutation! app method params)
            (.then
             (fn [wire-result]
               (let [result (decode wire-result)]
                 (clear-confirmed-submission! app sid (or (:draft-text data) text) attachments)
                 (when (and (contains? #{"operation.steer" "operation.follow-up"} method)
                            (= sid (session-id-from @(:state app))))
                   (swap! (:state app) update-in [:view :queue] enrich-pending-by-id result))
                 result))))))))

(defn- switch-session! [app sid]
  (when-not (and (string? sid) (not (str/blank? sid)))
    (throw (error "invalid-session" "Session id must be non-empty" {})))
  (swap! (:state app) assoc :models [] :providers [])
  (let [pending (hydrate-session! app sid true)
        context {:session-id sid :navigation (:navigation-generation @(:state app))}]
    (-> pending
        (.then (fn [snapshot]
                 (-> (refresh-catalog! app context)
                     (.then (fn [_] snapshot))))))))

(defn- update-session-state! [app wire-session]
  (let [session (decode wire-session)
        sid (value-field session :id)]
    (swap! (:state app)
           (fn [state]
             (cond-> (update state :sessions replace-session session)
               (= sid (session-id-from state))
               (assoc-in [:view :session] session))))
    session))

(defn- remove-host-request! [app id]
  (swap! (:state app) update :host-requests
         #(filterv (fn [request] (not= id (:id request))) %)))

(defn- extension [file]
  (let [name (.toLowerCase (str (.basename path-module file)))
        dot (.lastIndexOf name ".")]
    (when (and (pos? dot) (< dot (dec (count name))))
      (.slice name (inc dot)))))

(defn- read-attachment [app requested]
  (when-not (and (string? requested) (not (str/blank? requested)))
    (throw (error "invalid-path" "Attachment path must be non-empty" {})))
  (let [cwd (workspace app)
        candidate (if (.isAbsolute path-module requested)
                    requested
                    (.resolve path-module cwd requested))
        canonical (.realpathSync fs candidate)
        stat (.statSync fs canonical)]
    (when-not (.isFile stat)
      (throw (error "unsupported-file" "Attachment must be a regular file"
                    {:path canonical})))
    (let [size (.-size stat)
          mime (get image-mime-types (extension canonical))
          limit (if mime max-image-attachment-bytes max-text-attachment-bytes)]
      (when (> size limit)
        (throw (error "file-too-large" "Attachment exceeds the exact-read limit"
                      {:path canonical :bytes size :limit limit})))
      (let [bytes (.readFileSync fs canonical)
            name (.basename path-module canonical)]
        (if mime
          {:path canonical :name name :kind :image :mime-type mime :bytes size
           :part {:part/type :image
                  :image/mime-type mime
                  :image/data (.toString bytes "base64")}}
          (let [decoder (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true})
                text (try
                       (.decode decoder bytes)
                       (catch :default _
                         (throw (error "unsupported-file"
                                       "Text attachment is not valid UTF-8"
                                       {:path canonical}))))]
            {:path canonical :name name :kind :text :bytes size
             :part {:part/type :text
                    :text (str "Attached file: " (.relative path-module cwd canonical) "\n\n" text)}}))))))

(defn- add-attachment! [app requested]
  (let [attachment (read-attachment app requested)]
    (swap! (:state app) update-in [:ui :attachments]
           (fn [attachments]
             (conj (filterv #(not= (:path attachment) (:path %)) attachments)
                   attachment)))
    attachment))

(defn- normalized-relative [value]
  (str/replace value "\\" "/"))

(defn- directory-entries [directory limit]
  (try
    (let [handle (.opendirSync fs directory)]
      (try
        (loop [remaining limit
               entries []]
          (if (zero? remaining)
            entries
            (if-let [entry (.readSync handle)]
              (recur (dec remaining) (conj entries entry))
              entries)))
        (finally
          (.closeSync handle))))
    (catch :default _ [])))

(defn- completion-score [query candidate]
  (let [path (:path candidate)
        lower (.toLowerCase path)
        base (.toLowerCase (.basename path-module path))]
    (cond
      (= lower query) 0
      (.startsWith base query) 1
      (.startsWith lower query) 2
      (not (neg? (.indexOf base query))) 3
      :else 4)))

(defn- complete-files [app query]
  (let [root (workspace app)
        query (-> (or query "") str .toLowerCase (str/replace "\\" "/"))]
    (loop [stack [[root "" 0]]
           traversed 0
           matches []]
      (if (or (empty? stack)
              (>= traversed max-completion-entries))
        (->> matches
             (sort-by (juxt #(completion-score query %) :path))
             (take max-completion-results)
             vec)
        (let [[directory relative depth] (peek stack)
              stack (pop stack)
              entries (directory-entries directory
                                         (- max-completion-entries traversed))
              [stack matches traversed]
              (reduce
               (fn [[pending found seen] entry]
                 (if (>= seen max-completion-entries)
                   [pending found seen]
                   (let [name (.-name entry)
                         directory? (.isDirectory entry)
                         ignored? (contains? ignored-directories name)
                         absolute (.join path-module directory name)
                         rel (normalized-relative (if (str/blank? relative)
                                                    name
                                                    (.join path-module relative name)))
                         display rel
                         match? (or (str/blank? query)
                                    (not (neg? (.indexOf (.toLowerCase display) query))))
                         pending (if (and directory? (not ignored?) (< depth max-completion-depth))
                                   (conj pending [absolute rel (inc depth)])
                                   pending)
                         found (if (and (not directory?) (not ignored?) match?)
                                 (conj found {:path display :directory? false})
                                 found)]
                     [pending found (inc seen)])))
               [stack matches traversed]
               entries)]
          (recur stack traversed matches))))))

(defn- enrich-pending-by-id [items item]
  (let [id (value-field item :id)]
    (mapv #(if (= id (value-field % :id)) (merge % item) %) items)))

(defn- edited-queue-content [content text]
  (when-not (string? text)
    (throw (error "invalid-queue-content" "Queue text must be a string" {})))
  (if (string? content)
    (do
      (when (str/blank? text)
        (throw (error "empty-queue-content" "Queued text cannot be empty" {})))
      text)
    (let [parts (vec (or content []))
          {:keys [result replaced?]}
          (reduce
           (fn [{:keys [replaced?] :as state} part]
             (let [type (value-field part :part/type)
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
        (throw (error "empty-queue-content"
                      "Queue edit must retain an attachment or contain text" {})))
      result)))

(defn- queue-edit! [app data]
  (let [state @(:state app)
        sid (session-id-from state)
        queue-id (:id data)
        item (some #(when (= queue-id (value-field % :id)) %)
                   (get-in state [:view :queue]))]
    (when-not item
      (throw (error "queue-item-not-found" "Queued item is not present in the active session"
                    {:queue-id queue-id})))
    (when-not (or (contains? item :content) (contains? item "content"))
      (throw (error "queue-content-unavailable"
                    "Queued content is still synchronizing; edit after its receipt arrives"
                    {:queue-id queue-id})))
    (let [content (edited-queue-content (value-field item :content) (:text data))]
      (-> (mutation! app "session.queue.update"
                     {:session-id sid :queue-id queue-id :content content})
          (.then
           (fn [wire-result]
             (let [item (or (value-field (decode wire-result) :item)
                            (decode wire-result))
                   id (value-field item :id)]
               (swap! (:state app) update-in [:view :queue]
                      #(mapv (fn [existing]
                               (if (= id (value-field existing :id)) item existing)) %))
               item)))))))

(defn- queue-drop! [app data]
  (let [sid (session-id-from @(:state app))]
    (-> (mutation! app "session.queue.drop"
                   {:session-id sid :queue-id (:id data)})
        (.then
         (fn [wire-result]
           (let [result (decode wire-result)
                 removed (or (value-field result :removed) result)
                 id (or (value-field removed :id) (:id data))]
             (swap! (:state app) update-in [:view :queue]
                    #(filterv (fn [item] (not= id (value-field item :id))) %))
             removed))))))

(defn- perform-command! [app action data]
  (let [state @(:state app)
        sid (session-id-from state)
        oid (operation-id-from state)
        navigation (:navigation-generation state)
        context {:session-id sid :navigation navigation}]
    (case action
      :submit (if sid (submit! app data)
                  (rejected (error "setup-required" "Open /providers and choose a default model to start a conversation." {})))

      :cancel
      (if oid
        (-> (mutation! app "operation.cancel" {:operation-id oid}) (.then decode))
        (resolved {:session-id sid :operation-id nil :status :idle}))

      :refresh (hydrate-session! app sid false)
      :sessions (load-sessions! app)
      :switch-session (switch-session! app (:id data))

      :new-session
      (if (and (not= false (get-in app [:options :setup?]))
               (not (get-in state [:setup :configuration-ready?])))
        (rejected (error "setup-required" "Choose a default model in /providers before creating a conversation." {}))
        (-> (create-session! app data)
            (.then #(switch-session! app (value-field % :id)))))

      :rename-session
      (let [target (or (:id data) sid)]
        (-> (mutation! app "session.name" {:session-id target :name (:name data)})
            (.then #(update-session-state! app %))))

      :delete-session
      (let [target (:id data)]
        (-> (mutation! app "session.delete" {:session-id target})
            (.then
             (fn [wire-result]
               (swap! (:state app)
                      (fn [state]
                        (cond-> (-> state
                                    (update :sessions
                                            #(filterv (fn [session]
                                                        (not= target (value-field session :id))) %))
                                    (update :history-by-session dissoc target)
                                    (update :widgets-by-session dissoc target)
                                    (update-in [:ui :drafts] dissoc target)
                                    (update-in [:ui :session-ui] dissoc target))
                          (= target sid) (assoc-in [:view :session] nil)
                          (= target sid) (assoc :history nil))))
               (if (= target sid)
                 (-> (load-sessions! app)
                     (.then
                      (fn [sessions]
                        (if-let [next-session (first sessions)]
                          (-> (hydrate-session! app (value-field next-session :id) true)
                              (.then (fn [_] (decode wire-result))))
                          (-> (create-session! app {})
                              (.then #(hydrate-session! app (value-field % :id) true))
                              (.then (fn [_] (decode wire-result))))))))
                 (decode wire-result))))))

      :branch
      (-> (mutation! app "session.fork"
                     {:session-id sid :entry-id (:entry-id data) :position :at})
          (.then
           (fn [wire-session]
             (let [session (decode wire-session)]
               (swap! (:state app) update :sessions replace-session session)
               (-> (hydrate-session! app (value-field session :id) true)
                   (.then (fn [_] session)))))))

      :models (load-models! app (boolean (:refresh? data)))

      :providers (load-providers! app)

      :provider-cancel (rpc/cancel-method! (current-client app) "auth.login")

      :provider-login
      (catalog-work!
       app "Connecting provider… Esc cancels"
       (fn [_]
         (-> (call! app "auth.login" (catalog-params context (select-keys data [:provider :type]))
                    {:mutation? true :timeout-ms (* 15 60 1000)})
             (.then (fn [_] (auth-observation! app context (:provider data) false)))
             (.catch (fn [failure]
                       (if (= "cancelled" (:code (ex-data failure)))
                         (auth-observation! app context (:provider data) true)
                         (throw failure)))))))

      :provider-logout
      (catalog-work! app "Disconnecting provider…"
                     (fn [_] (-> (mutation! app "auth.logout" (catalog-params context {:provider (:provider data)}))
                                 (.then (fn [_] (load-providers! app context))))))

      :provider-models
      (catalog-work!
       app "Discovering models…"
       (fn [_]
         (-> (call! app "model.refresh" (catalog-params context {:provider (:provider data)}))
             (.then (fn [_] (load-models! app false context)))
             (.then (fn [models]
                      (-> (load-providers! app context) (.then (fn [_] models))))))))

      :select-model
      (catalog-work!
       app "Saving model selection…"
       (fn [_]
         (-> (mutation! app "model.select"
                        (cond-> (select-keys data [:provider :model :thinking :scope])
                          sid (assoc :session-id sid)))
             (.then (fn [wire]
                      (let [result (decode wire)]
                        (when (= :default (keyword (:scope data)))
                          (swap! (:state app) assoc :browser-selection? true))
                        (when-let [session (:session result)] (update-session-state! app session))
                        (when (or (= :default (keyword (:scope data))) (owns-catalog? app context))
                          (swap! (:state app) assoc :notice
                                 {:kind :info :message (if (= :default (keyword (:scope data)))
                                                        "Default saved for new conversations."
                                                        (if oid "Model saved for the next turn; the current run continues."
                                                            "Model changed for this conversation."))}))
                        (if sid
                          (if (= :default (keyword (:scope data)))
                            (-> (call! app "setup.status" {})
                                (.then (fn [status]
                                         (swap! (:state app) assoc :setup (decode status))
                                         result)))
                            result)
                          (-> (run-setup! app true nil)
                              (.then (fn [_] result))))))))))

      :setup (run-setup! app false nil)

      :set-model
      (-> (mutation! app "session.configure"
                     {:session-id sid
                      :config {:provider (:provider data)
                               :model (:model data)
                               :thinking (:thinking data)}})
          (.then
           (fn [wire-session]
             (update-session-state! app wire-session)
             (refresh-owned-session! app sid navigation))))

      :queue-edit (queue-edit! app data)
      :queue-drop (queue-drop! app data)

      :result
      (-> (call! app "result.inspect"
                 {:session-id sid :result-id (:result-id data)})
          (.then decode))

      :artifact
      (-> (call! app "artifact.read"
                 (non-nil-map {:session-id sid
                               :artifact-id (:artifact-id data)
                               :offset (:offset data)
                               :limit (:limit data)}))
          (.then decode))

      :attach (resolved (add-attachment! app (:path data)))
      :files (resolved (complete-files app (:query data)))

      :host-widget
      (resolved (update-widget! app (:request data)))


      :host-response
      (-> (rpc/host-response! (current-client app) (:id data) (:result data))
          (.then (fn [result] (remove-host-request! app (:id data)) result)))

      :host-cancel
      (-> (rpc/host-cancel! (current-client app) (:id data))
          (.then (fn [result] (remove-host-request! app (:id data)) result)))

      :continue
      (-> (mutation! app "session.continue" {:session-id sid}) (.then decode))

      :compact
      (-> (mutation! app "session.compact" {:session-id sid}) (.then decode))

      :reload
      (-> (mutation! app "session.reload" {:session-id sid})
          (.then (fn [_] (refresh-owned-session! app sid navigation))))

      :history
      (-> (call! app "session.tree" {:session-id sid})
          (.then
           (fn [wire-result]
             (let [history (decode wire-result)]
               (swap! (:state app)
                      (fn [state]
                        (cond-> (assoc-in state [:history-by-session sid] history)
                          (and (= sid (session-id-from state))
                               (= navigation (:navigation-generation state)))
                          (assoc :history history))))
               history))))

      :export
      (-> (mutation! app "session.export"
                     (non-nil-map {:session-id sid
                                   :path (when (:path data) (.resolve path-module (workspace app) (:path data)))
                                   :format (:format data)}))
          (.then decode))

      :reconnect (connect! app true)

      (rejected (error "unknown-action" "Unknown TUI command" {:action action})))))

(defn command!
  "Execute a renderer action after pending startup/reconnect has settled. Host replies
  bypass that barrier so initialization can ask for input. Mutations are never retried."
  ([app action] (command! app action {}))
  ([app action data]
   (try
     (let [execute (fn [_]
                     (when @(:closed? app)
                       (throw (error "closed" "TUI application is closed" {})))
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
       (rejected command-error)))))
