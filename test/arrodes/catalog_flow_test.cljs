(ns arrodes.catalog-flow-test
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-rpc :as rpc]))

(def host
  "(require '[arrodes.rpc :as rpc] '[arrodes.provider :as p])
(let [connected (atom false)
      models [{:provider :fixture :id \"alpha\" :context-window 32000 :thinking-levels [:none :high]}
              {:provider :fixture :id \"beta\" :context-window 64000 :thinking-levels [:none :high]}]]
  (with-redefs [p/status (fn [_] {:providers [{:provider :fixture :name \"Local test provider\" :available? @connected :auth {:type :api-key}}]})
                p/catalog (fn [_] models)
                p/model (fn [_ id model] (some #(when (= model (:id %)) %) models))
                p/refresh! (fn [& _] (if @connected models (throw (ex-info \"Connect first\" {}))))
                p/login! (fn [_ id opts] ((:input opts) {:type :secret :message \"Test API key\"}) (reset! connected true) {:status :logged-in})
                p/logout! (fn [& _] (reset! connected false) {:status :logged-out})]
    (rpc/serve!)))
(shutdown-agents)")

(defn check! [value message] (when-not value (throw (js/Error. message))))

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
                 (app/command! application :provider-login {:provider :fixture :type :api-key})))
        (.then (fn [_]
                 (check! (false? (:available? (first (:providers @(:state application))))) "Cancelled sign-in must not connect")
                 (reset! cancel? false)
                 (app/command! application :provider-login {:provider :fixture :type :api-key})))
        (.then (fn [_]
                 (check! (:available? (first (:providers @(:state application)))) "Successful sign-in must refresh provider status")
                 (check! (nil? (get-in @(:state application) [:view :session])) "Connecting must not select a model")
                 (check! (not (.includes (pr-str @(:state application)) "fixture-secret")) "Credentials must not enter UI state")
                 (app/command! application :provider-models {:provider :fixture})))
        (.then (fn [_] (app/command! application :select-model {:scope :default :provider :fixture :model "alpha" :thinking :high})))
        (.then (fn [_]
                 (reset! sid (get-in @(:state application) [:view :session :id]))
                 (check! @sid "Choosing the first default must create a conversation")
                 (check! (= "alpha" (get-in @(:state application) [:view :session :config :model])) "Session uses selected default")
                 (request "session.evaluate" {:session-id @sid :source "(def kept 42)"})))
        (.then (fn [_] (app/command! application :select-model {:scope :default :provider :fixture :model "beta" :thinking :none})))
        (.then (fn [_]
                 (check! (= "alpha" (get-in @(:state application) [:view :session :config :model])) "Saving a default must not switch the conversation")
                 (app/command! application :select-model {:scope :session :provider :fixture :model "beta" :thinking :high})))
        (.then (fn [_] (request "session.evaluate" {:session-id @sid :source "kept"})))
        (.then (fn [wire]
                 (let [result (js->clj (clj->js wire) :keywordize-keys true)]
                   (check! (= 42 (get-in result [:result :value])) "Switching model must preserve live definitions"))
                 (app/command! application :provider-logout {:provider :fixture})))
        (.then (fn [_]
                 (check! (false? (:available? (first (:providers @(:state application))))) "Disconnect must refresh status")
                 (println "Provider flow passed: first run, cancelled/secret login, discovery, defaults, conversation selection, live REPL preservation, disconnect.")))
        (.finally (fn []
                    (remove-watch (:state application) ::auth)
                    (-> (app/close! application)
                        (.finally (fn [] (.rmSync fs temporary #js {:recursive true :force true})))))))))
