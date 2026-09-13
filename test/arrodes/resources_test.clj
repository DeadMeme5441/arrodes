(ns arrodes.resources-test
  (:require [arrodes.capabilities :as capabilities]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.session-test :as fixtures]
            [arrodes.store :as store]
            [arrodes.util :as u]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest project-trust-gates-settings-without-discarding-initialization-overrides
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        cwd (str directory "/project")]
    (try
      (u/ensure-dir! home)
      (u/ensure-dir! (str cwd "/.arrodes-mono"))
      (u/write-edn! (str home "/settings.edn") {:example/options {:global 1 :shared :global}})
      (u/write-edn! (str cwd "/.arrodes-mono/settings.edn") {:example/options {:project 2 :shared :project}})
      (let [untrusted (resources/create! {:cwd cwd :home home :trust false
                                          :settings {:example/options {:initial 3}}})
            trusted (resources/create! {:cwd cwd :home home :trust true
                                        :settings {:example/options {:initial 3}}})]
        (try
          (is (= {:global 1 :shared :global :initial 3} (:example/options (resources/settings untrusted))))
          (is (= {:global 1 :project 2 :shared :project :initial 3} (:example/options (resources/settings trusted))))
          (resources/reload! trusted)
          (is (= 3 (get-in (resources/settings trusted) [:example/options :initial])))
          (finally (resources/close! untrusted) (resources/close! trusted))))
      (finally (fixtures/remove-directory! directory)))))

(deftest failed-extension-activation-withdraws-its-capabilities
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        extension-dir (str directory "/.arrodes-mono/extensions")
        database (store/open! {:memory? true})
        provider-manager (provider/create! {:home home :settings {}})
        session (store/create-session! database {:cwd directory :name "Extension rollback" :config fixtures/config})
        sid (:id session)
        registry (capabilities/create! {:session-id sid :cwd directory :store database :config fixtures/config
                                        :get-session #(store/session database sid) :emit! (fn [_])})]
    (try
      (u/ensure-dir! extension-dir)
      (spit (str extension-dir "/failure.clj")
            "(fn [api]\n  ((:register-tool! api) {:name \"leaked_tool\" :description \"Activation rollback probe\" :parameters {:type \"object\" :properties {}} :fn (fn [_] 42)})\n  (spit (str (:cwd api) \"/activation-marker\") \"registered\")\n  (throw (ex-info \"Intentional initialization failure\" {})))\n")
      (let [manager (resources/create! {:cwd directory :home home :trust true})
            before (set (map :name (capabilities/catalog registry)))
            outcome (try
                      {:value (resources/activate! manager registry
                                                   {:session-id sid :get-session (fn [& _] (store/session database sid))
                                                    :command! (fn [& _] (throw (ex-info "No command needed" {})))
                                                    :provider provider-manager :emit! (fn [_]) :ui! (fn [& _] nil)
                                                    :append-entry! (fn [entry] (store/commit! database sid {:entries [entry]}))})}
                      (catch Throwable error {:error error}))]
        (try
          (is (= before (set (map :name (capabilities/catalog registry)))))
          (is (= "registered" (slurp (str directory "/activation-marker"))))
          (is (or (:error outcome) (seq (:errors (:value outcome))) (seq (:errors (resources/catalog manager)))))
          (finally (resources/close! manager))))
      (finally (capabilities/close! registry) (provider/close! provider-manager)
               (store/close! database) (fixtures/remove-directory! directory)))))
