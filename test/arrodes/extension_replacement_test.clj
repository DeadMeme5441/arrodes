(ns arrodes.extension-replacement-test
  (:require [arrodes.capabilities :as capabilities]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.session-test :as fixtures]
            [arrodes.store :as store]
            [arrodes.util :as u]
            [clojure.test :refer [deftest is testing]]))

(def ^:private replacement-source
  "(fn [api]\n  ((:register-tool! api)\n   {:name \"read\"\n    :replace? true\n    :description \"Extension read replacement\"\n    :parameters {:type \"object\" :properties {\"path\" {:type \"string\"}} :required [\"path\"] :additionalProperties false}\n    :permission :read\n    :fn (fn [{:keys [path]}] (str \"extension:\" path))})\n  nil)\n")

(def ^:private failing-replacement-source
  "(fn [api]\n  ((:register-tool! api)\n   {:name \"read\"\n    :replace? true\n    :description \"Failing read replacement\"\n    :parameters {:type \"object\" :properties {\"path\" {:type \"string\"}} :required [\"path\"] :additionalProperties false}\n    :permission :read\n    :fn (fn [_] \"replacement-that-must-not-leak\")})\n  (throw (ex-info \"Intentional activation failure\" {})))\n")

(defn- activation-context [database sid provider-manager]
  {:session-id sid
   :get-session (fn [& _] (store/session database sid))
   :command! (fn [& _] nil)
   :provider provider-manager
   :emit! (fn [_] nil)
   :ui! (fn [& _] nil)
   :append-entry! (fn [entry] (store/commit! database sid {:entries [entry]}))})

(defn- with-environment [extension-source body]
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        extension-dir (str directory "/.arrodes-mono/extensions")
        extension-path (str extension-dir "/replacement.clj")
        sample-path (str directory "/sample.txt")
        database (store/open! {:memory? true})
        provider-manager (provider/create! {:home home :settings {}})
        session (store/create-session! database {:cwd directory :name "Extension replacement" :config fixtures/config})
        sid (:id session)
        registry (capabilities/create! {:session-id sid :cwd directory :store database :config fixtures/config
                                        :get-session #(store/session database sid) :emit! (fn [_])})]
    (try
      (u/ensure-dir! extension-dir)
      (spit extension-path extension-source)
      (spit sample-path "original read behavior")
      (let [manager (resources/create! {:cwd directory :home home :trust true})]
        (try
          (body {:directory directory :extension-path extension-path :sample-path sample-path
                 :database database :provider provider-manager :sid sid :registry registry
                 :manager manager :context (activation-context database sid provider-manager)})
          (finally (resources/close! manager))))
      (finally
        (capabilities/close! registry)
        (provider/close! provider-manager)
        (store/close! database)
        (fixtures/remove-directory! directory)))))

(defn- provider-value [registry name arguments]
  (:value (capabilities/invoke! registry {:id (u/id) :name name :arguments arguments} {})))

(defn- repl-value [registry name arguments]
  (let [tool-var (ns-resolve (the-ns (:namespace registry)) (symbol name))]
    (@tool-var arguments)))

(defn- register-unrelated! [registry]
  (capabilities/register! registry
                          {:name "unrelated" :owner "independent-owner"
                           :description "Unrelated owner survival probe"
                           :parameters {:type "object" :properties {}}
                           :fn (fn [_] :survived)}))

(deftest deliberate-extension-replacement-restores-built-in-on-deactivation
  (with-environment
    replacement-source
    (fn [{:keys [sample-path registry manager context]}]
      (register-unrelated! registry)
      (let [activation (resources/activate! manager registry context)
            arguments {:path sample-path}]
        (testing "provider and REPL wrappers observe the same replacement"
          (is (= (str "extension:" sample-path) (provider-value registry "read" arguments)))
          (is (= (str "extension:" sample-path) (repl-value registry "read" arguments))))
        (resources/deactivate! manager (:id activation))
        (testing "deactivation reveals the original built-in and keeps other owners"
          (is (= "original read behavior" (provider-value registry "read" arguments)))
          (is (= "original read behavior" (repl-value registry "read" arguments)))
          (is (= :survived (capabilities/invoke-value! registry "unrelated" {}))))))))

(deftest failed-activation-restores-built-in-and-unrelated-owners
  (with-environment
    failing-replacement-source
    (fn [{:keys [sample-path registry manager context]}]
      (register-unrelated! registry)
      (is (thrown? clojure.lang.ExceptionInfo (resources/activate! manager registry context)))
      (is (= "original read behavior" (capabilities/invoke-value! registry "read" {:path sample-path})))
      (is (= :survived (capabilities/invoke-value! registry "unrelated" {})))
      (is (= "arrodes.builtin"
             (:owner (some #(when (= "read" (:name %)) %) (capabilities/catalog registry))))))))

(deftest failed-reload-reactivates-the-prior-replacement
  (with-environment
    replacement-source
    (fn [{:keys [extension-path sample-path registry manager context]}]
      (resources/activate! manager registry context)
      (is (= (str "extension:" sample-path)
             (capabilities/invoke-value! registry "read" {:path sample-path})))
      (spit extension-path failing-replacement-source)
      (is (thrown? clojure.lang.ExceptionInfo (resources/reload! manager)))
      (is (= (str "extension:" sample-path)
             (capabilities/invoke-value! registry "read" {:path sample-path})))
      (resources/close! manager)
      (is (= "original read behavior"
             (capabilities/invoke-value! registry "read" {:path sample-path}))))))

(deftest repl-reregistration-remains-bounded-and-non-recursive
  (with-environment
    "(fn [_] nil)\n"
    (fn [{:keys [registry]}]
      (let [descriptor "{:name \"echo\" :description \"Echo a value\" :parameters {:type \"object\" :properties {} :additionalProperties true}}"
            first-ack (capabilities/invoke-value!
                       registry "clojure_eval"
                       {:source (str "(defn echo [{:keys [value]}] value)\n"
                                     "(register-tool! #'echo " descriptor ")")})
            second-ack (capabilities/invoke-value!
                        registry "clojure_eval"
                        {:source (str "(register-tool! #'echo (assoc " descriptor " :replace? true))")})
            invocation (capabilities/invoke! registry {:id (u/id) :name "echo" :arguments {:value 42}} {})
            result-id (get-in invocation [:result :id])]
        (is (= {:name "echo" :registered? true} first-ack))
        (is (= {:name "echo" :registered? true} second-ack))
        (is (= 42 (:value invocation)))
        (is (= "ALIAS" (capabilities/invoke-value!
                          registry "clojure_eval"
                          {:source "(alias 'strings 'clojure.string)\n(strings/upper-case \"alias\")"})))
        (is (= 42 (capabilities/invoke-value!
                   registry "clojure_eval"
                   {:source (str "(result " (pr-str result-id) ")")})))
        (let [read-ack
              (capabilities/invoke-value!
               registry "clojure_eval"
               {:source (str "(defn read [_] :repl-read)\n"
                             "(register-tool! #'read "
                             "{:name \"read\" :replace? true :replace-owner? true "
                             ":description \"REPL read replacement\" "
                             ":parameters {:type \"object\" :properties {} :additionalProperties true}})")})]
          (is (= {:name "read" :registered? true} read-ack))
          (is (= :repl-read (capabilities/invoke-value! registry "read" {})))
          (is (= :repl-read
                 (capabilities/invoke-value! registry "clojure_eval" {:source "(read {})"}))))))))

(deftest capability-registration-and-removal-are-atomic
  (with-environment
    "(fn [_] nil)\n"
    (fn [{:keys [registry]}]
      (let [attempts (doall
                      (for [index (range 16)]
                        (future
                          (try
                            (capabilities/register!
                             registry
                             {:name "race_tool" :owner (str "owner-" index)
                              :description "Registration race probe"
                              :parameters {:type "object" :properties {}}
                              :fn (fn [_] index)})
                            :registered
                            (catch clojure.lang.ExceptionInfo _ :duplicate)))))
            outcomes (mapv deref attempts)]
        (is (= 1 (count (filter #{:registered} outcomes))))
        (is (= 15 (count (filter #{:duplicate} outcomes))))
        (let [removals (mapv deref
                             (doall (repeatedly 16
                                                #(future
                                                   (capabilities/unregister! registry "race_tool")))))]
          (is (= 1 (count (filter true? removals))))
          (is (not-any? #(= "race_tool" (:name %)) (capabilities/catalog registry))))))))

(deftest restoration-removes-only-the-attributed-layer
  (with-environment
    "(fn [_] nil)\n"
    (fn [{:keys [sample-path registry]}]
      (let [descriptor (fn [owner value]
                         {:name "read" :owner owner :replace? true :replace-owner? true
                          :description (str owner " replacement")
                          :parameters {:type "object" :properties {} :additionalProperties true}
                          :permission :read
                          :fn (fn [_] value)})
            lower (capabilities/register-restorable! registry (descriptor "lower-owner" :lower))
            upper (capabilities/register-restorable! registry (descriptor "upper-owner" :upper))]
        (is (= :upper (capabilities/invoke-value! registry "read" {})))
        (is (true? (capabilities/restore! registry lower)))
        (is (= :upper (capabilities/invoke-value! registry "read" {})))
        (is (true? (capabilities/restore! registry upper)))
        (is (= "original read behavior"
               (capabilities/invoke-value! registry "read" {:path sample-path})))))))
