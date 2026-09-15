(ns arrodes.catalog-flow-test
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-rpc :as rpc]))

(def host
  "(require '[arrodes.rpc :as rpc] '[arrodes.provider :as p])
(let [connected (atom #{})
      root-models [{:provider :fixture :id \"alpha\" :context-window 32000 :thinking-levels [:none :high]}
                   {:provider :fixture :id \"beta\" :context-window 64000 :thinking-levels [:none :high]}]
      project-models [{:provider :project-fixture :id \"alpha\" :context-window 128000 :thinking-levels [:none :high]}]
      other-project-models [{:provider :project-fixture :id \"beta\" :context-window 96000 :thinking-levels [:none]}]
      managers (atom {})
      manager-label (fn [manager]
                      (if-not (:parent manager)
                        :root
                        (let [identity (System/identityHashCode manager)]
                          (locking managers
                            (or (get @managers identity)
                              (let [label (if (empty? @managers) :first :second)]
                                (swap! managers assoc identity label)
                                label))))))
      provider-id (fn [manager] (if (:parent manager) :project-fixture :fixture))
      models-for (fn [manager]
                   (conj (case (manager-label manager)
                           :root root-models
                           :first project-models
                           other-project-models)
                         {:provider :shared :id \"shared-model\" :thinking-levels [:none :high]}))]
  (with-redefs [p/status (fn [manager]
                          (let [id (provider-id manager)]
                            {:providers [{:provider id
                                          :name (if (= id :fixture) \"Local test provider\" \"Project test provider\")
                                          :available? (contains? @connected id)
                                          :auth {:type :api-key}}
                                         {:provider :shared :name \"Shared provider\" :available? true :auth {:type :none}}]}))
                p/catalog (fn [manager] (models-for manager))
                p/model (fn [manager id model]
                          (some #(when (and (= id (:provider %)) (= model (:id %))) %)
                                (models-for manager)))
                p/refresh! (fn [manager & [id]]
                             (let [provider (or id (provider-id manager))]
                               (when (= :first (manager-label manager)) (Thread/sleep 300))
                               (if (contains? @connected provider)
                                 (filterv #(= provider (:provider %)) (models-for manager))
                                 (throw (ex-info \"Connect first\" {})))))
                p/login! (fn [_ id opts]
                           (try
                             ((:input opts) {:type :secret :message \"Test API key\"})
                             {:status :logged-in}
                             (finally
                               ;; Reproduce cancellation after the credential was committed.
                               (swap! connected conj id))))
                p/logout! (fn [_ id] (swap! connected disj id) {:status :logged-out})]
    (rpc/serve!)))
(shutdown-agents)")

(defn check! [value message] (when-not value (throw (js/Error. message))))

(defn- provider [state id]
  (some #(when (= id (:provider %)) %) (:providers state)))

(defn- expect-error! [promise code message]
  (.then promise
         (fn [_] (throw (js/Error. message)))
         (fn [failure]
           (check! (= code (:code (ex-data failure))) message)
           failure)))

(defn- sessions [wire]
  (:sessions (js->clj (clj->js wire) :keywordize-keys true)))

(defn exercise! []
  (let [fs (js/require "node:fs") path (js/require "node:path") os (js/require "node:os")
        temporary (.mkdtempSync fs (.join path (.tmpdir os) "arrodes-catalog-"))
        script (.join path temporary "host.clj")
        root (or (aget (.-env js/process) "ARRODES_TUI_ROOT") (.cwd js/process))
        application (app/create! {:runtime-root root :cwd temporary :home (.join path temporary "home") :trust false
                                  :handshake-timeout-ms 60000
                                  :rpc-command ["clojure" "-Srepro" "-Sdeps"
                                                "{:paths [\"src/clj\" \"src/cljc\" \"hosts/rpc\" \"resources\"]}"
                                                "-M" script]})
        cancel? (atom true) responded (atom #{}) sid (atom nil)
        request (fn [method params] (rpc/request! @(:client application) method params))]
    (.writeFileSync fs script host)
    (add-watch (:state application) ::auth
               (fn [_ _ _ state]
                 (doseq [{:keys [id request]} (:host-requests state)]
                   (when-not (contains? @responded id)
                     (swap! responded conj id)
                     (check! (:secret? request) "API-key input must remain secret")
                     (if @cancel?
                       (app/command! application :host-cancel {:id id})
                       (app/command! application :host-response {:id id :result "fixture-secret"}))))))
    (-> (app/start! application)
        (.then (fn [_]
                 (check! (= :providers (get-in @(:state application) [:ui :overlay :kind])) "First run must open Providers")
                 (check! (nil? (get-in @(:state application) [:view :session])) "No session before selecting a model")
                 (-> (request "session.list" {})
                     (.then (fn [wire]
                              (check! (empty? (sessions wire)) "Setup must begin without a conversation")
                              (expect-error! (app/command! application :new-session {}) "setup-required"
                                             "New session must be rejected until provider setup completes"))))))
        (.then (fn [_] (request "session.list" {})))
        (.then (fn [wire]
                 (check! (empty? (sessions wire)) "Rejected new session must not create a conversation")
                 (app/command! application :provider-login {:provider :fixture :type :api-key})))
        (.then (fn [_]
                 (check! (:available? (provider @(:state application) :fixture))
                         "Cancelled sign-in must reconcile a credential committed before cancellation")
                 (app/command! application :provider-logout {:provider :fixture})))
        (.then (fn [_]
                 (check! (false? (:available? (provider @(:state application) :fixture)))
                         "Disconnect must refresh provider status")
                 (reset! cancel? false)
                 (app/command! application :provider-login {:provider :fixture :type :api-key})))
        (.then (fn [_]
                 (check! (:available? (provider @(:state application) :fixture)) "Successful sign-in must refresh provider status")
                 (check! (nil? (get-in @(:state application) [:view :session])) "Connecting must not select a model")
                 (check! (not (.includes (pr-str @(:state application)) "fixture-secret")) "Credentials must not enter UI state")
                 (app/command! application :provider-models {:provider :fixture})))
        (.then (fn [_] (app/command! application :select-model {:scope :default :provider :fixture :model "alpha" :thinking :high})))
        (.then (fn [_]
                 (check! (nil? (get-in @(:state application) [:view :session :id])) "Choosing a default must not create a session")
                 (check! (= "alpha" (get-in @(:state application) [:view :session :config :model])) "Composer must reflect the chosen model")
                 (app/command! application :select-model {:scope :session :provider :fixture :model "beta" :thinking :none})))
        (.then (fn [_]
                 (check! (= "beta" (get-in @(:state application) [:view :session :config :model])) "Choosing a model before Send must update only the composer")
                 (request "session.list" {})))
        (.then (fn [wire]
                 (check! (empty? (sessions wire)) "Model setup must leave session storage empty")
                 ;; The remaining catalog tests explicitly create an RPC fixture session.
                 (request "session.create" {})))
        (.then (fn [wire]
                 (reset! sid (:id (js->clj (clj->js wire) :keywordize-keys true)))
                 (app/command! application :switch-session {:id @sid})))
        (.then (fn [_] (request "session.evaluate" {:session-id @sid :source "(def kept 42)"})))
        (.then (fn [_]
                 (expect-error! (app/command! application :select-model {:scope :default :provider :fixture :model "beta" :thinking :none})
                                "provider-unavailable" "A default unavailable in this session must fail before changing settings")))
        (.then (fn [_]
                 (check! (= "alpha" (get-in @(:state application) [:view :session :config :model])) "Rejected default selection must preserve the conversation")
                 (app/command! application :providers {})))
        (.then (fn [_]
                 (check! (provider @(:state application) :project-fixture)
                         "Active conversation must use its project provider catalog")
                 (check! (nil? (provider @(:state application) :fixture))
                         "Session catalog must not fall back to the root provider manager")
                 (app/command! application :provider-login {:provider :project-fixture :type :api-key})))
        (.then (fn [_]
                 (check! (:available? (provider @(:state application) :project-fixture))
                         "Project provider sign-in must refresh through the active conversation")
                 (app/command! application :provider-models {:provider :project-fixture})))
        (.then (fn [_]
                 (let [models (filterv #(= :project-fixture (:provider %)) (:models @(:state application)))]
                   (check! (and (= 1 (count models))
                                (= :project-fixture (:provider (first models)))
                                (= "alpha" (:id (first models))))
                           "Project model refresh must use the session provider manager"))
                 (app/command! application :select-model
                               {:scope :default :provider :shared :model "shared-model" :thinking :high})))
        (.then (fn [_]
                 (check! (= {:provider :shared :model "shared-model" :thinking :high}
                            (select-keys (get-in @(:state application) [:view :session :config]) [:provider :model :thinking]))
                         "Saving a default must also update the current session")
                 (app/command! application :select-model
                               {:scope :session :provider :project-fixture :model "alpha" :thinking :high})))
        (.then (fn [_]
                 (check! (= :project-fixture (get-in @(:state application) [:view :session :config :provider]))
                         "Selecting an equal model id from another provider must switch provider")
                 (check! (= "alpha" (get-in @(:state application) [:view :session :config :model]))
                         "Project model selection must retain the selected model id")
                 (request "session.evaluate" {:session-id @sid :source "kept"})))
        (.then (fn [wire]
                 (let [result (js->clj (clj->js wire) :keywordize-keys true)]
                   (check! (= 42 (get-in result [:result :value])) "Switching model must preserve live definitions"))
                 (request "session.create"
                          {:cwd temporary
                           :name "Other project catalog"
                           :config {:provider :project-fixture :model "beta" :thinking :none}})))
        (.then (fn [wire]
                 (let [other-id (:id (js->clj (clj->js wire) :keywordize-keys true))
                       slow-refresh (app/command! application :provider-models {:provider :project-fixture})]
                   (-> (js/Promise. (fn [resolve _] (js/setTimeout resolve 50)))
                       (.then (fn [_] (app/command! application :switch-session {:id other-id})))
                       (.then (fn [_]
                                (check! (= "beta" (:id (first (:models @(:state application)))))
                                        "New conversation must load its own catalog")
                                slow-refresh))
                       (.then (fn [_]
                                (check! (= other-id (get-in @(:state application) [:view :session :id]))
                                        "Late catalog response must not navigate back")
                                (check! (= "beta" (:id (first (:models @(:state application)))))
                                        "Late catalog response must not overwrite the active conversation")))))))
        (.then (fn [_]
                 (app/command! application :provider-logout {:provider :project-fixture})))
        (.then (fn [_]
                 (check! (false? (:available? (provider @(:state application) :project-fixture)))
                         "Project provider disconnect must refresh through the active conversation")
                 (println "Provider flow passed: setup guard, cancellation reconciliation, session catalogs, navigation race, defaults, provider/model identity, live REPL preservation, disconnect.")))
        (.finally (fn []
                    (remove-watch (:state application) ::auth)
                    (-> (app/close! application)
                        (.finally (fn [] (.rmSync fs temporary #js {:recursive true :force true})))))))))
