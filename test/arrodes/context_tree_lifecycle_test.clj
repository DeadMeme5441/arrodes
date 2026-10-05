(ns arrodes.context-tree-lifecycle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [arrodes.capabilities :as capabilities]
            [arrodes.context-tree :as tree]
            [arrodes.platform :as util]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as sessions]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.context-tree :as context]
            [arrodes.summaries :as summaries])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(def tree-config
  (assoc sessions/config :settings
         {:context-policy :summary-tree :summary-provider :openai
          :summary-model "gpt-4o-mini" :summary-node-bytes 96
          :summary-view-bytes 128000 :summary-timeout-ms 10000
          :auto-title? false :auto-compact? false :provider-retries 0}))

(defn- create! [rt]
  (:id (runtime/create-session! rt {:name "Context lifecycle" :config tree-config})))

(defn- summary-request? [request]
  (str/ends-with? (str (get-in request [:request/cache :scope-id])) ":summary-tree"))

(defn- seed! [rt sid]
  (store/commit! (:store rt) sid
                 {::command/entries
                  [{:kind :message :data {:message/role :user
                                         :message/content (apply str (repeat 500 "original evidence "))}}
                   {:kind :message :data {:message/role :assistant
                                         :message/content "Prior answer"}}]}))

(defn- await! [ready]
  (let [result (deref ready 5000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Expected lifecycle boundary was not reached" {})))
    result))

(defn- await-release! [^CountDownLatch release]
  ;; Noncooperative fixtures distinguish cancellation admission from actual exit.
  (loop []
    (when-not (try (.await release 50 TimeUnit/MILLISECONDS)
                   (catch InterruptedException _ false))
      (recur))))

(deftest explicit-context-configuration-signals-repeated-values-without-starting-work
  (fixtures/with-runtime [rt (fn [& _] (throw (ex-info "Configuration inferred" {})))]
    (let [sid (create! rt)]
      (runtime/configure! rt sid {:config {:settings {:context-policy :summary-tree}}})
      (is (= {:context-policy 1} (get @(:context-setting-revisions rt) sid)))
      (runtime/configure! rt sid {:settings {:context-policy :summary-tree :summary-view-bytes 128000}})
      (let [versions {:context-policy 2 :summary-view-bytes 1}]
        (is (= versions (get @(:context-setting-revisions rt) sid)))
        (runtime/configure! rt sid {:name "Renamed only" :settings {:context-policy :linear}})
        (runtime/configure! rt sid {:config {:settings {:temperature 0.2}}})
        (is (= versions (get @(:context-setting-revisions rt) sid)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (runtime/configure! rt sid {:expected-revision -1
                                                :settings {:context-policy :linear}})))
        (is (= versions (get @(:context-setting-revisions rt) sid))))
      (is (empty? @(:handles rt)))
      (is (empty? @(:slots (:summaries rt))))
      (runtime/delete! rt sid)
      (is (nil? (get @(:context-setting-revisions rt) sid))))))

(deftest native-effects-return-real-values-while-summary-maintenance-is-deferred
  (let [entered (promise) release (CountDownLatch. 1) summary-calls (atom 0)
        effects (atom 0) events (atom [])
        complete (fn [request _]
                   (when (summary-request? request)
                     (when (= 1 (swap! summary-calls inc))
                       (deliver entered true)
                       (await-release! release)))
                   (fixtures/answer "compressed"))]
    (fixtures/with-runtime [rt complete]
      (let [sid (create! rt)
            registry (runtime/registry rt sid)
            generation (:generation registry)
            unsubscribe (runtime/subscribe! rt #(swap! events conj %))]
        (try
          (capabilities/register! registry
                                  {:name "native_effect" :description "Count an actual native effect"
                                   :parameters {:type "object" :properties {}}
                                   :execution :parallel :permission :read
                                   :fn (fn [_] (swap! effects inc))})
          (seed! rt sid)
          (summaries/request! (:summaries rt) sid tree-config)
          (is (true? (await! entered)))
          (is (= :idle (:status (runtime/cancel! rt sid))))
          (is (false? (summaries/await-session! (:summaries rt) sid 0)))
          (is (= 1 (:value (runtime/evaluate! rt sid "(native_effect)"))))
          (is (= 2 (:value (runtime/invoke! rt sid "native_effect" {}))))
          (is (= 2 @effects))
          (is (= [:completed :completed]
                 (mapv :status (sort-by :created-at (runtime/operations rt {:session-id sid})))))
          (is (= 1 (count (filter #(= :evaluation (:kind %)) (runtime/entries rt sid)))))
          (is (= 1 (count (filter #(= :invocation (get-in % [:data :type]))
                                 (runtime/entries rt sid)))))
          (is (= 2 (count (filter #(and (= :context/summary (:type %))
                                       (= :deferred (get-in % [:data :status]))
                                       (= "summary-cancelling" (get-in % [:data :error :code])))
                                 @events))))
          (is (= 1 @summary-calls))
          (is (= generation (:generation (runtime/registry rt sid))))
          (.countDown release)
          (is (summaries/await-session! (:summaries rt) sid 5000))
          (is (= 42 (:value (runtime/evaluate! rt sid "(+ 40 2)"))))
          (is (summaries/await-session! (:summaries rt) sid 5000))
          (is (= 2 @effects))
          (is (= 5 (get-in (runtime/context-inspect rt sid) [:summary :view :source-count])))
          (finally
            (.countDown release)
            (unsubscribe)))))))

(deftest native-effects-return-real-values-when-background-admission-is-full
  (let [entered (CountDownLatch. 2) release (CountDownLatch. 1)
        summary-calls (atom 0) effects (atom 0) events (atom [])
        complete (fn [request _]
                   (when (summary-request? request)
                     (swap! summary-calls inc)
                     (.countDown entered)
                     (await-release! release))
                   (fixtures/answer "compressed"))]
    (fixtures/with-runtime [rt complete]
      (let [ids (mapv (fn [_] (create! rt)) (range 35))
            sid (peek ids)
            unsubscribe (runtime/subscribe! rt #(swap! events conj %))]
        (try
          (doseq [owner (take 34 ids)]
            (seed! rt owner))
          (doseq [owner (take 2 ids)]
            (runtime/registry rt owner)
            (summaries/request! (:summaries rt) owner tree-config))
          (is (.await entered 5 TimeUnit/SECONDS))
          (doseq [owner (take 32 (drop 2 ids))]
            (summaries/request! (:summaries rt) owner tree-config))
          (capabilities/register! (runtime/registry rt sid)
                                  {:name "capacity_effect" :description "Count an actual native effect"
                                   :parameters {:type "object" :properties {}}
                                   :execution :parallel :permission :read
                                   :fn (fn [_] (swap! effects inc))})
          (is (= 1 (:value (runtime/evaluate! rt sid "(capacity_effect)"))))
          (is (= 2 (:value (runtime/invoke! rt sid "capacity_effect" {}))))
          (is (= 2 @effects))
          (is (= [:completed :completed]
                 (mapv :status (runtime/operations rt {:session-id sid}))))
          (is (= 2 (count (filter #(and (= sid (:session-id %))
                                       (= :context/summary (:type %))
                                       (= :deferred (get-in % [:data :status]))
                                       (= "summary-capacity" (get-in % [:data :error :code])))
                                 @events))))
          (is (false? (get-in (runtime/context-inspect rt sid) [:summary :worker-active?])))
          (is (= 2 @summary-calls))
          (summaries/stop! (:summaries rt))
          (.countDown release)
          (is (summaries/await-closed! (:summaries rt) 5000))
          (is (= 2 @summary-calls))
          (is (= 2 @effects))
          (finally
            (summaries/stop! (:summaries rt))
            (.countDown release)
            (unsubscribe)))))))

(deftest delayed-cancellation-and-disable-cannot-cancel-successor-context
  (doseq [mode [:foreground :idle :configure]]
    (testing (name mode)
      (let [old-entered (promise) new-entered (promise) dispatch-entered (promise)
            old-release (CountDownLatch. 1) new-release (CountDownLatch. 1)
            dispatch-release (CountDownLatch. 1) summary-calls (atom 0)
            new-options (atom nil) delay-once? (atom true)
            dispatch summaries/cancel-session!
            complete (fn [request options]
                       (when (summary-request? request)
                         (if (= 1 (swap! summary-calls inc))
                           (do (deliver old-entered true) (await-release! old-release))
                           (do (reset! new-options options)
                               (deliver new-entered true)
                               (await-release! new-release))))
                       (fixtures/answer "compressed"))]
        (fixtures/with-runtime [rt complete]
          (let [sid (create! rt)]
            (try
              (runtime/registry rt sid)
              (seed! rt sid)
              (with-redefs [summaries/cancel-session!
                            (fn
                              ([manager session-id] (dispatch manager session-id))
                              ([manager session-id targets]
                               (when (and (not (Thread/holdsLock (:lock manager)))
                                          (compare-and-set! delay-once? true false))
                                 (deliver dispatch-entered targets)
                                 (await-release! dispatch-release))
                               (dispatch manager session-id targets)))]
                (let [old-operation (when (= mode :foreground)
                                      (runtime/start! rt sid "Cancel this driver"))]
                  (when-not old-operation
                    (summaries/request! (:summaries rt) sid tree-config))
                  (is (true? (await! old-entered)))
                  (let [cancellation (future
                                       (if old-operation
                                         (runtime/cancel-operation! rt (:id old-operation))
                                         (if (= mode :configure)
                                           (runtime/configure! rt sid {:settings {:context-policy :linear}})
                                           (runtime/cancel! rt sid))))
                        targets (await! dispatch-entered)]
                    (is (= 1 (count targets)))
                    (.countDown old-release)
                    (is (summaries/await-session! (:summaries rt) sid 5000))
                    (when old-operation
                      (is (= :cancelled (:status (runtime/wait! rt (:id old-operation) 5000)))))
                    (let [successor (runtime/start! rt sid "A successor keeps its own context"
                                                    (if (= mode :configure)
                                                      {:config {:settings {:context-policy :summary-tree}}} {}))]
                      (is (true? (await! new-entered)))
                      (.countDown dispatch-release)
                      (is (= (if old-operation :cancelling :idle) (:status (await! cancellation))))
                      (is (false? (util/cancelled? (:cancelled? @new-options))))
                      (is (= :running (:status (runtime/operation rt (:id successor)))))
                      (.countDown new-release)
                      (is (= :completed (:status (runtime/wait! rt (:id successor) 5000))))
                      (is (summaries/await-session! (:summaries rt) sid 5000))
                      (is (not= "summary-cancelled"
                                (get-in (runtime/context-inspect rt sid) [:summary :error :code])))))))
              (finally
                (.countDown old-release)
                (.countDown new-release)
                (.countDown dispatch-release)))))))))

(deftest reset-and-close-retain-resources-until-summary-worker-actually-exits
  (let [directory (sessions/temp-directory)
        entered (promise) release (CountDownLatch. 1)
        complete (fn [request _]
                   (when (summary-request? request)
                     (deliver entered true)
                     (await-release! release))
                   (fixtures/answer "compressed"))
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :settings {:close-timeout-ms 25} :complete-fn complete})
        sid (create! rt)
        registry (runtime/registry rt sid)
        owned-provider (runtime/provider-manager rt sid)
        owned-resources (runtime/resource-manager rt sid)
        provider-close provider/close! resources-close resources/close!
        closed (atom [])]
    (try
      (seed! rt sid)
      (summaries/request! (:summaries rt) sid tree-config)
      (is (true? (await! entered)))
      (with-redefs [provider/close! (fn [manager]
                                    (when (identical? manager owned-provider)
                                      (swap! closed conj :provider))
                                    (provider-close manager))
                    resources/close! (fn [manager]
                                       (when (identical? manager owned-resources)
                                         (swap! closed conj :resources))
                                       (resources-close manager))]
        (doseq [reset! [runtime/reload! runtime/delete!]]
          (let [error (try (reset! rt sid) nil
                           (catch clojure.lang.ExceptionInfo error (:error/code (ex-data error))))]
            (is (= "summaries-still-running" error))
            (is (identical? registry (runtime/registry rt sid)))
            (is (= sid (:id (runtime/session rt sid))))
            (is (empty? @closed))))
        (let [report (runtime/close! rt)]
          (is (= :closing (:status report)))
          (is (false? (:summaries-complete? report)))
          (is (false? (:store-closed? report)))
          (is (empty? @closed))
          (is (= sid (:id (runtime/session rt sid)))))
        (.countDown release)
        (is (summaries/await-session! (:summaries rt) sid 5000))
        (is (= :closed (:status (runtime/close! rt))))
        (is (= [:resources :provider] @closed)))
      (finally
        (.countDown release)
        (runtime/close! rt)
        (sessions/remove-directory! directory)))))

(deftest main-request-uses-ready-summary-view-while-an-unused-parent-remains-owned
  (let [merge-entered (promise) leaf-entered (promise)
        merge-release (CountDownLatch. 1) leaf-release (CountDownLatch. 1)
        main-requests (atom []) leaf-text (apply str (repeat 60 "v"))
        complete (fn [request _]
                   (if (summary-request? request)
                     (let [input (get-in request [:request/messages 1 :message/content])]
                       (cond
                         (str/includes? input "last-historical-original")
                         (do (deliver leaf-entered true) (await-release! leaf-release))

                         (not-any? #(str/includes? input %)
                                   ["first-historical-original" "second-historical-original"])
                         (do (deliver merge-entered true) (await-release! merge-release)))
                       (fixtures/answer leaf-text))
                     (do (swap! main-requests conj request)
                         (fixtures/answer "Ready context was sufficient"))))]
    (fixtures/with-runtime [rt complete]
      (let [sid (create! rt)]
        (try
          (store/commit! (:store rt) sid
                         {::command/entries
                          (mapv (fn [[role marker]]
                                  {:kind :message
                                   :data {:message/role role
                                          :message/content (str marker " " (apply str (repeat 500 "x")))}})
                                [[:user "first-historical-original"]
                                 [:user "second-historical-original"]
                                 [:assistant "last-historical-original"]])})
          (runtime/registry rt sid)
          (summaries/request! (:summaries rt) sid tree-config)
          (is (true? (await! merge-entered)))
          (is (true? (await! leaf-entered)))
          (let [operation (runtime/start! rt sid "Use the frozen historical prefix")]
            (.countDown leaf-release)
            (is (= :completed (:status (runtime/wait! rt (:id operation) 5000))))
            (is (= 1 (count @main-requests)))
            (let [history (some #(when (str/includes? (str (:message/content %)) "<historical-context>")
                                  (:message/content %))
                                (:request/messages (first @main-requests)))]
              (is (string? history))
              (is (str/includes? history leaf-text))
              (is (not (str/includes? history "last-historical-original"))))
            (is (false? (summaries/await-session! (:summaries rt) sid 0)))
            (is (true? (:worker-active? (summaries/inspect (:summaries rt) sid)))))
          (.countDown merge-release)
          (is (summaries/await-session! (:summaries rt) sid 5000))
          (finally
            (.countDown leaf-release)
            (.countDown merge-release)))))))

(deftest overflow-adopts-a-parent-completed-during-native-effects-without-replay
  (let [merge-entered (promise) parent-persisted (promise)
        merge-release (CountDownLatch. 1) parent-id (atom nil)
        main-requests (atom []) effects (atom 0)
        leaf-text (apply str (repeat 60 "v")) parent-text "Frozen prior evidence"
        put-node! context/put-node!
        complete (fn [request _]
                   (if (summary-request? request)
                     (if (str/includes? (get-in request [:request/messages 1 :message/content])
                                        "Compress this original evidence")
                       (fixtures/answer leaf-text)
                       (do (deliver merge-entered true)
                           (await-release! merge-release)
                           (fixtures/answer parent-text)))
                     (do
                       (swap! main-requests conj request)
                       (case (count @main-requests)
                         1 (assoc (fixtures/calls (fixtures/tool-call "late-parent-once" "(late_parent_effect)"))
                                  :response/provider-data {:fixture/replay "Native tool exchange"})
                         2 (throw (ex-info "Maximum context length exceeded"
                                           {:status 400 :body {:error {:code "context_length_exceeded"}}}))
                         (fixtures/answer "Recovered with the completed historical parent")))))]
    (fixtures/with-runtime [rt complete]
      (with-redefs [context/put-node!
                    (fn [database sid node]
                      (let [result (put-node! database sid node)]
                        (when (= @parent-id (:id node)) (deliver parent-persisted true))
                        result))]
        (let [sid (create! rt)
              cfg (assoc-in tree-config [:settings :auto-compact?] true)
              historical (:entries
                          (store/commit! (:store rt) sid
                                         {::command/entries
                                          (mapv (fn [[role marker]]
                                                  {:kind :message
                                                   :data {:message/role role
                                                          :message/content (str marker " "
                                                                                (apply str (repeat 500 "x")))}})
                                                [[:user "Prior request"]
                                                 [:assistant "Prior completed answer"]])}))]
          (try
            (runtime/configure! rt sid {:config cfg})
            (reset! parent-id (tree/node-key historical 0 2))
            (capabilities/register!
             (runtime/registry rt sid)
             {:name "late_parent_effect" :description "Count one native effect and release historical work"
              :parameters {:type "object" :properties {}} :execution :parallel :permission :read
              :fn (fn [_]
                    (let [result (swap! effects inc)]
                      (.countDown merge-release)
                      (await! parent-persisted)
                      result))})
            (summaries/request! (:summaries rt) sid cfg)
            (is (true? (await! merge-entered)))
            (is (nil? (context/node (:store rt) sid @parent-id)))
            (let [operation (runtime/start! rt sid "Execute the native effect once and retain its result")
                  result (runtime/wait! rt (:id operation) 5000)
                  historical-message (fn [request]
                                       (some #(when (str/includes? (str (:message/content %))
                                                                  "<historical-context>") %)
                                             (:request/messages request)))
                  native-messages (fn [request]
                                    (filterv #(not= % (historical-message request)) (:request/messages request)))]
              (is (= :completed (:status result)))
              (is (= 3 (count @main-requests)))
              (is (= 1 @effects))
              (let [[initial rejected recovered] @main-requests]
                (is (str/includes? (:message/content (historical-message initial)) leaf-text))
                (is (= (historical-message initial) (historical-message rejected)))
                (is (str/includes? (:message/content (historical-message recovered)) parent-text))
                (is (not (str/includes? (:message/content (historical-message recovered)) leaf-text)))
                (is (= (native-messages rejected) (native-messages recovered)))
                (is (some #(= "late-parent-once" (:message/tool-call-id %)) (native-messages recovered)))
                (is (some #(= "Native tool exchange" (get-in % [:message/provider-data :fixture/replay]))
                          (native-messages recovered))))
              (is (= [@parent-id] (get-in (runtime/session rt sid) [:metadata :context/view-node-ids])))
              (is (= 1 (count (filter #(= "late-parent-once" (get-in % [:data :message/tool-call-id]))
                                     (runtime/entries rt sid)))))
              (is (= historical (filterv #(contains? (set (map :id historical)) (:id %))
                                         (runtime/active-path rt sid))))
              (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid))))
            (finally (.countDown merge-release))))))))
