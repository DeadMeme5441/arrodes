(ns arrodes.setup-test
  (:require [arrodes.platform :as platform]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.runtime :as runtime]
            [arrodes.setup :as setup]
            [clojure.test :refer [deftest is]]))

(defn- project-info [_ _]
  {:id "fixture-id" :root "/fixture/project" :directory "/fixture/agent-root/projects/fixture-id"})

(deftest explicit-route-still-requires-usable-authentication
  (let [rt {:home "/fixture/home" :cwd "/fixture/project"
            :resources ::resources :provider ::provider :trust false}]
    (with-redefs [resources/settings (constantly {})
                  resources/project-trust (fn [& _] {:root "/fixture/project"
                                                     :trusted? false :configured? false
                                                     :required? false})
                  platform/project-info project-info
                  provider/status (fn [_] {:providers [{:provider :fixture :name "Fixture"
                                                        :available? false
                                                        :auth {:configured? false :type :api-key}}]})]
      (is (false? (:ready? (setup/status rt {:provider "fixture" :model "fixture-model"})))
          "command-line model selection is not proof of credentials"))
    (with-redefs [resources/settings (constantly {})
                  resources/project-trust (fn [& _] {:root "/fixture/project"
                                                     :trusted? false :configured? false
                                                     :required? false})
                  platform/project-info project-info
                  provider/status (fn [_] {:providers [{:provider :fixture :name "Fixture"
                                                        :available? true
                                                        :auth {:configured? true :type :api-key
                                                               :source :environment}}]})]
      (is (:ready? (setup/status rt {:provider "fixture" :model "fixture-model"}))))))

(deftest setup-authenticates-discovers-and-persists-without-retaining-secret
  (let [settings (atom {:provider :github-copilot :model "retired-provider-model"})
        authenticated? (atom false)
        requests (atom [])
        rt {:home "/fixture/home" :cwd "/fixture/project"
            :resources ::resources :provider ::provider :trust false}
        provider-status (fn [_]
                          {:providers [{:provider :fixture :name "Fixture"
                                        :available? @authenticated?
                                        :auth {:configured? @authenticated? :type :api-key
                                               :source (when @authenticated? :stored-api-key)}}]})]
    (with-redefs [resources/settings (fn [_] @settings)
                  resources/update-settings! (fn [_ changes options]
                                               (swap! settings merge changes)
                                               {:settings @settings})
                  resources/project-trust (fn [& _] {:root "/fixture/project"
                                                     :trusted? false :configured? false
                                                     :required? false})
                  resources/trust! (fn [& _] (throw (AssertionError. "trust must not be prompted without project resources")))
                  platform/project-info project-info
                  provider/status provider-status
                  provider/login! (fn [_ provider-id options]
                                    (is (= "local-release-check"
                                           ((:input options) {:type :secret :message "API key"})))
                                    (is (= "local-release-check"
                                           ((:input options) {:type :manual-code :message "Authorization code"})))
                                    (reset! authenticated? true)
                                    {:provider provider-id :status :logged-in})
                  provider/refresh! (fn [_ provider-id]
                                      [{:provider provider-id :id "fixture-model"
                                        :name "Fixture model" :thinking-levels [:none]}])
                  runtime/ui! (fn [_ request]
                                (swap! requests conj request)
                                (case (:kind request)
                                  :input "local-release-check"
                                  :select (case (:title request)
                                            "Choose a provider" :fixture
                                            "Choose a model" "fixture-model"
                                            "Choose a thinking level" :none
                                            (throw (AssertionError. (str "Unexpected choice " (:title request)))))
                                  nil))]
      (let [result (setup/run! rt {})]
        (is (:ready? result))
        (is (= {:provider :fixture :model "fixture-model" :thinking :none} @settings))
        (is (every? :secret? (filter #(= :input (:kind %)) @requests)))
        (is (not-any? #(some #{"local-release-check"} (tree-seq coll? seq %)) @requests)
            "the credential is never retained in UI request state")
        (is (:ready? (setup/status rt {})) "persisted defaults survive a fresh status check")))))

(deftest cancelled-setup-does-not-invent-defaults
  (let [settings (atom {})
        rt {:home "/fixture/home" :cwd "/fixture/project"
            :resources ::resources :provider ::provider :trust false}]
    (with-redefs [resources/settings (fn [_] @settings)
                  resources/update-settings! (fn [& _] (throw (AssertionError. "cancelled setup must not write settings")))
                  resources/project-trust (fn [& _] {:root "/fixture/project"
                                                     :trusted? false :configured? false
                                                     :required? false})
                  platform/project-info project-info
                  provider/status (fn [_] {:providers [{:provider :fixture :name "Fixture"
                                                        :available? false
                                                        :auth {:configured? false :type :api-key}}]})
                  runtime/ui! (fn [& _] (throw (ex-info "cancelled" {:error/code "cancelled"})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled" (setup/run! rt {})))
      (is (empty? @settings)))))

(deftest default-selection-can-also-configure-current-session
  (let [settings (atom {}) configured (atom [])
        rt {:provider :global :resources :resources}
        params {:scope :default :provider :fixture :model "beta" :thinking :high
                :session-id "current"}
        available? (atom true)]
    (with-redefs [provider/status (fn [manager]
                                  {:providers (when (or (= manager :global) @available?)
                                                [{:provider :fixture :available? true}])})
                  provider/model (fn [& _] {:thinking-levels [:none :high]})
                  runtime/provider-manager (fn [_ _] :session)
                  runtime/session (fn [_ sid] {:id sid})
                  runtime/configure! (fn [_ sid changes]
                                       (swap! configured conj sid)
                                       {:id sid :config (:config changes)})
                  resources/settings (fn [_] @settings)
                  resources/update-settings! (fn [_ changes _] (swap! settings merge changes))]
      (let [result (setup/apply-model! rt params)]
        (is (= "beta" (get-in result [:session :config :model])))
        (is (= :high (:thinking @settings)))
        (is (= ["current"] @configured)))
      (reset! configured [])
      (setup/apply-model! rt (dissoc params :session-id))
      (is (empty? @configured) "Initial setup has no current session to configure")
      (reset! available? false)
      (reset! settings {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not available"
                            (setup/apply-model! rt params)))
      (is (empty? @settings) "Validate both scopes before writing either")
      (reset! available? true)
      (with-redefs [runtime/configure! (fn [& _] (throw (ex-info "store failed" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Default saved, but"
                              (setup/apply-model! rt params)))
        (is (= "beta" (:model @settings)))))))

(deftest setup-offers-explicit-authentication-modes
  (doseq [provider-id [:anthropic :claude-profile]
          mode [:api-key :oauth]
          current-type [:api-key :oauth]
          available? [false true]]
    (let [requests (atom [])
          calls (atom [])
          entry {:provider provider-id :name "Claude" :available? available?
                 :auth {:type current-type} :auth-modes [:api-key :oauth]}]
      (with-redefs [runtime/ui! (fn [_ request]
                                 (swap! requests conj request)
                                 (case (:title request)
                                   "Use provider credentials" :login
                                   "Choose authentication method" (name mode)
                                   "Authenticate in your browser" :continue
                                   "Authorization code" "synthetic-code"))
                    provider/login! (fn [_ id options]
                                      (swap! calls conj [id (:type options)])
                                      (when (= :oauth (:type options))
                                        ((:on-event options) {:type :auth-url :url "https://example.invalid/authorize"
                                                              :instructions "Sign in in your browser"})
                                        (is (= "synthetic-code"
                                               ((:input options) {:type :manual-code :message "Authorization code"})))))]
        (#'setup/ensure-auth! {} ::manager entry)
        (is (= [[provider-id mode]] @calls))
        (is (= [:api-key :oauth]
               (mapv :value (:items (first (filter #(= "Choose authentication method" (:title %)) @requests))))))
        (is (every? :secret? (filter #(= :input (:kind %)) @requests)))
        (is (not-any? #(some #{"synthetic-code"} (tree-seq coll? seq %)) @requests))))))

(deftest setup-keeps-single-mode-login-and-explicit-reuse
  (doseq [auth-type [:api-key :oauth]]
    (with-redefs [runtime/ui! (fn [& _] (throw (AssertionError. "Single-mode providers must not gain a method picker")))
                  provider/login! (fn [_ _ options] (is (= auth-type (:type options))))]
      (#'setup/ensure-auth! {} ::manager {:provider (if (= :oauth auth-type) :codex-backend :fixture)
                                        :auth {:type auth-type}})))
  (with-redefs [runtime/ui! (fn [_ request]
                             (is (= "Use provider credentials" (:title request)))
                             :reuse)
                provider/login! (fn [& _] (throw (AssertionError. "Reuse must not sign in")))]
    (#'setup/ensure-auth! {} ::manager {:provider :anthropic :available? true
                                      :auth {:type :oauth} :auth-modes [:api-key :oauth]})))

(deftest cancelling-authentication-method-does-not-start-login
  (with-redefs [runtime/ui! (fn [& _] (throw (ex-info "cancelled" {:error/code "cancelled"})))
                provider/login! (fn [& _] (throw (AssertionError. "Cancelled selection must not log in")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
                          (#'setup/ensure-auth! {} ::manager
                           {:provider :anthropic :auth {:type :api-key}
                            :auth-modes [:api-key :oauth]})))))
