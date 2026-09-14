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
  (let [settings (atom {})
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
                                            "Choose a model" "fixture-model"
                                            "Choose a thinking level" :none
                                            (throw (AssertionError. (str "Unexpected choice " (:title request)))))
                                  nil))]
      (let [result (setup/run! rt {:provider "fixture"})]
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
