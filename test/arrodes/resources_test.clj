(ns arrodes.resources-test
  (:require [arrodes.capabilities :as capabilities]
            [arrodes.provider :as provider]
            [arrodes.packages :as packages]
            [arrodes.resources :as resources]
            [arrodes.session-test :as fixtures]
            [arrodes.store :as store]
            [arrodes.platform :as u]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files)))

(deftest project-trust-gates-settings-without-discarding-initialization-overrides
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        cwd (str directory "/project")]
    (try
      (u/ensure-dir! cwd)
      (u/write-edn! (str home "/config/settings.edn")
                    {:example/options {:global 1 :shared :global}})
      (u/write-edn! (str (u/project-dir home cwd) "/settings.edn")
                    {:example/options {:project 2 :shared :project}})
      (let [untrusted (resources/create! {:cwd cwd :home home :trust false
                                          :settings {:example/options {:initial 3}}})
            trusted (resources/create! {:cwd cwd :home home :trust true
                                        :settings {:example/options {:initial 3}}})]
        (try
          (is (= {:global 1 :shared :global :initial 3} (:example/options (resources/settings untrusted))))
          (is (= {:global 1 :project 2 :shared :project :initial 3} (:example/options (resources/settings trusted))))
          (resources/reload! trusted)
          (is (= 3 (get-in (resources/settings trusted) [:example/options :initial])))
          (resources/update-settings! trusted {:written-under-home true} {:scope :project})
          (is (true? (:written-under-home
                      (u/read-edn (str (u/project-dir home cwd) "/settings.edn")))))
          (is (not (.exists (io/file cwd ".arrodes"))))
          (finally (resources/close! untrusted) (resources/close! trusted))))
      (finally (fixtures/remove-directory! directory)))))

(deftest failed-extension-activation-withdraws-its-capabilities
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        extension-dir (str (u/project-dir home directory) "/extensions")
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

(defn- marker-source [marker]
  (str "(fn [api]\n  (spit (str (:cwd api) " (pr-str (str "/" marker))
       ") \"activated\")\n  nil)\n"))

(defn- write-marker-extension! [path marker]
  (io/make-parents path)
  (spit path (marker-source marker))
  path)

(defn- make-extension-package! [path entry marker]
  (u/ensure-dir! path)
  (u/write-edn! (str path "/arrodes.edn") {:name "fixture" :extensions [entry]})
  (write-marker-extension! (str path "/" (if (string? entry) entry (:path entry))) marker)
  path)

(deftest disabled-extensions-exclude-lower-precedence-discovery
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        project-extension-root (str (u/project-dir home directory) "/extensions")
        markers ["global-disabled.marker" "project-disabled.marker"
                 "package-disabled.marker" "explicit-disabled.marker"]
        database (store/open! {:memory? true})
        provider-manager (provider/create! {:home home :settings {}})
        session (store/create-session! database {:cwd directory :name "Disabled extensions"
                                                 :config fixtures/config})
        sid (:id session)
        registry (capabilities/create! {:session-id sid :cwd directory :store database
                                        :config fixtures/config
                                        :get-session #(store/session database sid)
                                        :emit! (fn [_])})]
    (try
      (write-marker-extension! (str home "/extensions/global-disabled.clj")
                               "global-disabled.marker")
      (write-marker-extension! (str project-extension-root "/project-disabled.clj")
                               "project-disabled.marker")
      (write-marker-extension! (str project-extension-root "/explicit-disabled.clj")
                               "explicit-disabled.marker")
      (u/write-edn! (str home "/config/settings.edn")
                    {:extensions [{:path "extensions/global-disabled.clj" :enabled? false}
                                  {:path "extensions/missing.clj" :enabled? false}]})
      (u/write-edn! (str (u/project-dir home directory) "/settings.edn")
                    {:extensions [{:path "extensions/project-disabled.clj"
                                   :enabled? false}
                                  {:path "extensions/missing.clj"
                                   :enabled? false}]})
      (packages/install!
       home directory
       (make-extension-package! (str directory "/sources/enabled-package")
                                "package-disabled.clj" "package-disabled.marker")
       {:scope :global :name "enabled-package"})
      (packages/install!
       home directory
       (make-extension-package! (str directory "/sources/disabled-package")
                                {:path "package-disabled.clj" :enabled? false}
                                "package-disabled.marker")
       {:scope :project :name "disabled-package"})
      (let [manager
            (resources/create!
             {:cwd directory :home home :trust true
              :settings {:extensions [{:path (str project-extension-root
                                                   "/explicit-disabled.clj")
                                       :enabled? false}
                                      {:path (str project-extension-root
                                                  "/missing-explicit.clj")
                                       :enabled? false}]}})]
        (try
          (resources/activate!
           manager registry
           {:session-id sid
            :get-session (fn [& _] (store/session database sid))
            :command! (fn [& _] nil)
            :provider provider-manager
            :emit! (fn [_] nil)
            :ui! (fn [& _] nil)
            :append-entry! (fn [entry] (store/commit! database sid {:entries [entry]}))})
          (is (every? #(not (.exists (io/file directory %))) markers))
          (is (empty? (set/intersection
                       (set (map :name (:extensions (resources/catalog manager))))
                       #{"global-disabled" "project-disabled"
                         "package-disabled" "explicit-disabled"})))
          (finally (resources/close! manager))))
      (finally
        (capabilities/close! registry)
        (provider/close! provider-manager)
        (store/close! database)
        (fixtures/remove-directory! directory)))))

(deftest disabled-prompt-excludes-default-discovery
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        prompt (str home "/prompts/probe.md")]
    (try
      (io/make-parents prompt)
      (spit prompt "Prompt body")
      (u/write-edn! (str home "/config/settings.edn")
                    {:prompts [{:path "prompts/probe.md" :enabled? false}
                               {:path "prompts/missing.md" :enabled? false}]})
      (let [manager (resources/create! {:cwd directory :home home :trust true})]
        (try
          (is (not-any? #(= "probe" (:name %))
                        (:prompts (resources/catalog manager))))
          (finally (resources/close! manager))))
      (finally (fixtures/remove-directory! directory)))))

(deftest settings-secret-validation-descends-into-sets
  (let [directory (fixtures/temp-directory)]
    (try
      (let [outcome
            (try
              {:manager
               (resources/create!
                {:cwd directory :home (str directory "/home") :trust true
                 :settings {:nested #{{:api-token "not-an-environment-reference"}}}})}
              (catch clojure.lang.ExceptionInfo error {:error error}))]
        (try
          (is (nil? (:manager outcome)))
          (is (= "secret-forbidden" (:error/code (ex-data (:error outcome)))))
          (finally
            (when-let [manager (:manager outcome)]
              (resources/close! manager)))))
      (finally (fixtures/remove-directory! directory)))))

(deftest concurrent-global-settings-patches-preserve-both-writes
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        target (u/resolve-path (u/resolve-path home "config") "settings.edn")]
    (try
      (u/ensure-dir! home)
      (let [first-manager (resources/create! {:cwd directory :home home :trust true})
            second-manager (resources/create! {:cwd directory :home home :trust true})
            original-write u/write-edn!
            write-count (atom 0)
            first-entered (promise)
            second-entered (promise)
            release-first (promise)
            first-written (promise)]
        (try
          (with-redefs [u/write-edn!
                        (fn [path value]
                          (if (= target (u/canonical-path path))
                            (case (swap! write-count inc)
                              1 (do
                                  (deliver first-entered true)
                                  @release-first
                                  (let [result (original-write path value)]
                                    (deliver first-written true)
                                    result))
                              2 (do
                                  (deliver second-entered true)
                                  @first-written
                                  (original-write path value))
                              (original-write path value))
                            (original-write path value)))]
            (let [first-write (future (resources/update-settings!
                                       first-manager {:first true} {:scope :global}))]
              (is (= true (deref first-entered 10000 ::timeout)))
              (let [second-write (future (resources/update-settings!
                                          second-manager {:second true} {:scope :global}))]
                (deref second-entered 100 ::blocked)
                (deliver release-first true)
                (is (map? (deref first-write 10000 ::timeout)))
                (is (map? (deref second-write 10000 ::timeout)))
                (is (= {:first true :second true} (u/read-edn target {}))))))
          (finally
            (resources/close! first-manager)
            (resources/close! second-manager))))
      (finally (fixtures/remove-directory! directory)))))

(deftest failed-settings-reload-cannot-roll-back-a-concurrent-success
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        target (u/resolve-path (u/resolve-path home "config") "settings.edn")]
    (try
      (u/ensure-dir! home)
      (u/write-edn! target {:base true})
      (let [failed-manager (resources/create! {:cwd directory :home home :trust true})
            successful-manager (resources/create! {:cwd directory :home home :trust true})
            original-reload resources/reload!
            failed-reload-entered (promise)
            successful-reload-entered (promise)
            release-failed-reload (promise)]
        (try
          (with-redefs [resources/reload!
                        (fn [manager]
                          (cond
                            (identical? manager failed-manager)
                            (do
                              (deliver failed-reload-entered true)
                              @release-failed-reload
                              (throw (ex-info "Intentional settings reload failure" {})))

                            (identical? manager successful-manager)
                            (do
                              (deliver successful-reload-entered true)
                              (original-reload manager))

                            :else (original-reload manager)))]
            (let [failed-write
                  (future
                    (try
                      (resources/update-settings! failed-manager {:failed true} {:scope :global})
                      (catch Throwable error error)))]
              (is (= true (deref failed-reload-entered 10000 ::timeout)))
              (let [successful-write
                    (future (resources/update-settings!
                             successful-manager {:successful true} {:scope :global}))
                    raced? (not= ::blocked
                                 (deref successful-reload-entered 100 ::blocked))]
                (when raced?
                  (is (map? (deref successful-write 10000 ::timeout))))
                (deliver release-failed-reload true)
                (is (instance? clojure.lang.ExceptionInfo
                               (deref failed-write 10000 ::timeout)))
                (is (map? (deref successful-write 10000 ::timeout)))
                (is (= {:base true :successful true} (u/read-edn target {}))))))
          (finally
            (resources/close! failed-manager)
            (resources/close! successful-manager))))
      (finally (fixtures/remove-directory! directory)))))

(deftest project-discovery-is-canonical-pure-and-worktree-aware
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        repository (str directory "/checkout")
        subdir (str repository "/src/deep")
        link (str directory "/linked-subdir")]
    (try
      (u/ensure-dir! (str repository "/.git"))
      (u/ensure-dir! subdir)
      (let [root-info (u/project-info home repository)
            subdir-info (u/project-info home subdir)]
        (is (= root-info subdir-info))
        (is (= (u/real-path repository) (:root root-info)))
        (is (not (.exists (io/file home))))
        (try
          (Files/createSymbolicLink (u/path link) (u/path subdir)
                                    (make-array java.nio.file.attribute.FileAttribute 0))
          (is (= root-info (u/project-info home link)))
          (catch UnsupportedOperationException _)
          (catch java.nio.file.FileSystemException _)))
      (let [first (str directory "/one/repo")
            second (str directory "/two/repo")]
        (doseq [worktree [first second]]
          (u/ensure-dir! worktree)
          (spit (str worktree "/.git") "gitdir: elsewhere\n"))
        (is (not= (:id (u/project-info home first))
                  (:id (u/project-info home second)))))
      (finally (fixtures/remove-directory! directory)))))

(deftest project-trust-is-exact-root-and-repository-context-stays-read-only
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        outer (str directory "/outer")
        inner (str outer "/nested")
        subdir (str outer "/src")]
    (try
      (u/ensure-dir! (str outer "/.git"))
      (u/ensure-dir! (str inner "/.git"))
      (u/ensure-dir! subdir)
      (spit (str outer "/AGENTS.md") "Outer instructions")
      (let [outer-manager (resources/create! {:cwd subdir :home home})]
        (try
          (resources/trust! outer-manager subdir true)
          (is (= {:trusted? true :configured? true :required? true}
                 (select-keys (resources/project-trust outer-manager)
                              [:trusted? :configured? :required?])))
          (finally (resources/close! outer-manager))))
      (let [inner-manager (resources/create! {:cwd inner :home home})]
        (try
          (is (= {:trusted? false :configured? false}
                 (select-keys (resources/project-trust inner-manager)
                              [:trusted? :configured?])))
          (finally (resources/close! inner-manager))))
      (is (not (.exists (io/file outer ".arrodes"))))
      (finally (fixtures/remove-directory! directory)))))

(deftest unsupported-home-layout-is-rejected-without-moving-files
  (let [directory (fixtures/temp-directory) home (str directory "/home")]
    (try
      (u/write-edn! (str home "/settings.edn") {:old true})
      (u/write-edn! (str home "/config/settings.edn") {:current true})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported application home"
                           (u/ensure-current-home! home nil)))
      (is (= {:old true} (u/read-edn (str home "/settings.edn"))))
      (is (= {:current true} (u/read-edn (str home "/config/settings.edn"))))
      (is (not (.exists (io/file home "config/migration.edn"))))
      (finally (fixtures/remove-directory! directory)))))
