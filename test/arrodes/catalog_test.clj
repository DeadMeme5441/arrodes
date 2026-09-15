(ns arrodes.catalog-test
  (:require [arrodes.catalog :as catalog]
            [arrodes.setup :as setup]
            [arrodes.provider :as provider]
            [arrodes.runtime :as runtime]
            [arrodes.resources :as resources]
            [clojure.test :refer [deftest is]]))

(deftest model-browser-filters-by-provider-and-preserves-model-details
  (let [models [{:provider :one :id "shared" :context-window 32000 :input [:text :image]
                 :thinking-levels [:none :high]}
                {:provider :two :id "shared" :thinking-levels [:none]}]]
    (is (= 1 (count (catalog/models models :one "SHARED"))))
    (is (= :high (catalog/thinking (first models) :high)))
    (is (= :none (catalog/thinking (first models) :medium)))
    (is (re-find #"32000 context" (catalog/model-details (first models))))
    (is (nil? (catalog/model-details nil)))))

(deftest model-selection-validates-before-writing-and-keeps-scopes-independent
  (let [writes (atom [])
        connected? (atom true)
        rt {:provider ::global :resources ::resources}
        config {:provider "one" :model "shared" :thinking "high"}
        session {:id "conversation" :config {:model "old"}}]
    (with-redefs [provider/status (fn [_] {:providers [{:provider :one :available? @connected?}]})
                  provider/model (fn [_ id model] (when (and (= id :one) (= model "shared"))
                                                   {:id model :thinking-levels [:none :high]}))
                  runtime/provider-manager (fn [_ sid] (is (= "conversation" sid)) ::session)
                  resources/settings (constantly {:session-defaults {:model "old"}})
                  resources/update-settings! (fn [_ changes options] (swap! writes conj [:defaults changes options]))
                  runtime/configure! (fn [_ sid changes] (swap! writes conj [:session sid changes]) session)]
      (is (= :default (:scope (setup/apply-model! rt (assoc config :scope "default" :session-id "conversation")))))
      (is (= [:defaults] (mapv first @writes)))
      (is (= {:provider nil :model nil :thinking nil} (get-in @writes [0 1 :session-defaults])))
      (reset! writes [])
      (is (= session (:session (setup/apply-model! rt (assoc config :scope "session" :session-id "conversation")))))
      (is (= [:session] (mapv first @writes)))
      (reset! writes [])
      (doseq [bad [(assoc config :model "missing") (assoc config :thinking "max")]]
        (is (thrown? clojure.lang.ExceptionInfo (setup/apply-model! rt (assoc bad :scope "default")))))
      (reset! connected? false)
      (is (thrown? clojure.lang.ExceptionInfo (setup/apply-model! rt (assoc config :scope "default"))))
      (is (empty? @writes)))))
