(ns arrodes.context-tree-runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [arrodes.agents :as agents]
            [arrodes.capabilities :as capabilities]
            [arrodes.jobs :as jobs]
            [arrodes.platform :as util]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as sessions]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.summaries :as summaries]
            [arrodes.value :as value]))

(def tree-config
  (assoc sessions/config :settings
         {:context-policy :summary-tree :summary-provider :openai
          :summary-model "gpt-4o-mini" :summary-node-bytes 65536
          :summary-view-bytes 128000 :auto-title? false
          :auto-compact? false :provider-retries 0}))

(defn- create! [rt & [config]]
  (:id (runtime/create-session! rt {:name "Tree runtime" :config (or config tree-config)})))

(defn- summary-request? [request]
  (str/ends-with? (str (get-in request [:request/cache :scope-id])) ":summary-tree"))

(defn- historical-message [request]
  (some #(when (str/starts-with? (value/text-content (:message/content %)) "<historical-context>") %)
        (:request/messages request)))

(defn- request-text [request]
  (str/join "\n" (map #(value/text-content (:message/content %)) (:request/messages request))))

(defn- user-entry [rt sid text]
  (some #(when (and (= :message (:kind %))
                   (= :user (get-in % [:data :message/role]))
                   (= text (value/text-content (get-in % [:data :message/content])))) %)
        (runtime/active-path rt sid)))

(defn- seed! [rt sid n]
  (store/commit! (:store rt) sid
                 {::command/entries
                  (vec (mapcat (fn [index]
                                 [{:kind :message
                                   :data {:message/role :user
                                          :message/content (str "Historical task " index ": " (apply str (repeat 1500 "x")))}}
                                  {:kind :message
                                   :data {:message/role :assistant :message/content (str "Historical answer " index)
                                          :message/provider-data {:fixture/replay "opaque old reasoning"}}}])
                               (range n)))}))

(defn- overflow []
  (ex-info "Maximum context length exceeded"
           {:status 400 :body {:error {:code "context_length_exceeded"}}}))

(deftest inspection-and-configuring-policy-do-not-activate-or-infer
  (let [requests (atom [])]
    (fixtures/with-runtime [rt (fn [request _] (swap! requests conj request) (fixtures/answer "Done"))]
      (let [sid (create! rt (assoc tree-config :settings {:auto-title? false}))]
        (is (= :linear (:policy (runtime/context-inspect rt sid))))
        (is (empty? @(:handles rt)))
        (runtime/configure! rt sid {:config {:settings (:settings tree-config)}})
        (let [inspection (runtime/context-inspect rt sid)]
          (is (= :summary-tree (:policy inspection)))
          (is (= "gpt-4o-mini" (get-in inspection [:settings :summary-model])))
          (is (true? (get-in inspection [:view :ready?])))
          (is (true? (get-in inspection [:view :fits?])))
          (is (empty? (get-in inspection [:view :nodes])))
          (is (= 0 (get-in inspection [:view :bytes])))
          (is (= 0 (get-in inspection [:view :source-count])))
          (is (= 0 (get-in inspection [:summary :node-count]))))
        (is (empty? @requests))
        (is (empty? @(:handles rt)))
        (is (= 41 (:value (runtime/evaluate! rt sid "(def retained 41) retained"))))
        (let [generation (:generation (runtime/registry rt sid))]
          (runtime/configure! rt sid {:config {:settings {:context-policy :linear}}})
          (is (= generation (:generation (runtime/registry rt sid))))
          (is (= 42 (:value (runtime/evaluate! rt sid "(inc retained)"))))
          (is (empty? @requests)))))))

(deftest historical-prefix-is-frozen-native-replay-and-bindings-survive-retrieval
  (let [requests (atom []) calls (atom 0) original-id (atom nil) phases (atom [])
        provider (fn [request _]
                   (swap! requests conj request)
                   (case (swap! calls inc)
                     1 (fixtures/calls (fixtures/tool-call "bind" "(def retained (atom 40)) @retained"))
                     2 (fixtures/answer "Bound retained state")
                     3 (assoc (fixtures/calls
                               (fixtures/tool-call "retrieve"
                                 (str "(let [original (history/read " (pr-str @original-id)
                                      ")] (swap! retained + 2) {:original original :answer @retained})")))
                              :response/provider-data {:fixture/replay "native current reasoning"})
                     (fixtures/answer "Retrieved the original; retained state is 42")))]
    (fixtures/with-runtime [rt provider]
      (let [sid (create! rt)]
        (runtime/run! rt sid "Create a retained binding")
        (reset! original-id (:id (user-entry rt sid "Create a retained binding")))
        (let [generation (:generation (runtime/registry rt sid))]
          (runtime/run! rt sid "Read the old request and add two"
                        {:on-event #(when (= :operation/phase (:type %))
                                      (swap! phases conj (get-in % [:data :phase])))})
          (let [first-request (nth @requests 2) second-request (nth @requests 3)
                first-messages (:request/messages first-request)
                second-messages (:request/messages second-request)
                current (:id (user-entry rt sid "Read the old request and add two"))
                tool (some #(when (= "retrieve" (get-in % [:data :message/tool-call-id])) (:data %))
                           (runtime/active-path rt sid))
                receipt (first (:history/retrievals tool))]
            (is (historical-message first-request))
            (is (= first-messages (subvec second-messages 0 (count first-messages))))
            (is (= (:request/tools first-request) (:request/tools second-request)))
            (is (= sid (get-in second-request [:request/cache :scope-id])))
            (is (not (str/includes? (:message/content (historical-message first-request)) "fixture/replay")))
            (is (= "native current reasoning"
                   (get-in (nth second-messages (count first-messages)) [:message/provider-data :fixture/replay])))
            (is (= [current] (:context-entry-ids receipt)))
            (is (= [@original-id] (:source-entry-ids receipt)))
            (is (str/includes? (value/text-content (:message/content tool)) "42"))
            (is (= [:preparing-context :provider] @phases)))
          (is (= generation (:generation (runtime/registry rt sid))))
          (is (= 42 (:value (runtime/evaluate! rt sid "@retained")))))))))

(deftest steering-keeps-the-view-follow-up-starts-a-new-outer-turn
  (let [requests (atom []) calls (atom 0) entered (promise) release (promise)
        provider (fn [request _]
                   (swap! requests conj request)
                   (case (swap! calls inc)
                     1 (fixtures/answer "Earlier turn complete")
                     2 (do (deliver entered true) (fixtures/await! release)
                           (fixtures/calls (fixtures/tool-call "effect" "(swap! retained inc)")))
                     3 (fixtures/answer "Current turn complete")
                     (fixtures/answer "Follow-up complete")))]
    (fixtures/with-runtime [rt provider]
      (let [sid (create! rt)]
        (runtime/evaluate! rt sid "(def retained (atom 0))")
        (runtime/run! rt sid "Earlier task")
        (let [operation (runtime/start! rt sid "Current task")]
          (fixtures/await! entered)
          (runtime/steer-operation! rt (:id operation) "Steer this tool loop")
          (runtime/follow-up-operation! rt (:id operation) "A separate follow-up")
          (deliver release true)
          (is (= :completed (:status (runtime/wait! rt (:id operation) 10000))))
          (let [initial (nth @requests 1) steered (nth @requests 2) follow-up (nth @requests 3)]
            (is (= (historical-message initial) (historical-message steered)))
            (is (str/includes? (request-text steered) "Steer this tool loop"))
            (is (not (str/includes? (request-text steered) "A separate follow-up")))
            (is (not= (historical-message initial) (historical-message follow-up)))
            (is (= "A separate follow-up"
                   (value/text-content (:message/content (last (:request/messages follow-up)))))))
          (is (empty? (runtime/pending rt sid)))
          (is (= 1 (:value (runtime/evaluate! rt sid "@retained")))))))))

(deftest failed-first-request-is-still-a-native-task-on-continue
  (let [requests (atom []) calls (atom 0)]
    (fixtures/with-runtime
      [rt (fn [request _]
            (swap! requests conj request)
            (if (= 1 (swap! calls inc))
              (throw (ex-info "Before request acceptance" {:retryable? false}))
              (fixtures/answer "Recovered task")))]
      (let [sid (create! rt)]
        (is (thrown? clojure.lang.ExceptionInfo (runtime/run! rt sid "The only unfinished task")))
        (runtime/continue! rt sid)
        (is (= 2 @calls))
        (is (nil? (historical-message (last @requests))))
        (is (= "The only unfinished task"
               (value/text-content (:message/content (last (:request/messages (last @requests)))))))
        (is (= 1 (count (filter #(= :user (get-in % [:data :message/role]))
                                 (runtime/active-path rt sid)))))))))

(deftest restart-retains-pending-input-effects-and-native-reset-notice
  (let [directory (sessions/temp-directory)
        options {:cwd directory :home (str directory "/home") :data-dir (str directory "/data")}
        calls (atom 0) requests (atom [])
        first-provider (fn [_ _]
                         (case (swap! calls inc)
                           1 (fixtures/answer "Earlier task completed")
                           2 (assoc (fixtures/calls
                                     (fixtures/tool-call "once"
                                       "(def retained 42) (spit (str cwd \"/effect.txt\") \"effect\\n\" :append true) retained"))
                                    :response/provider-data {:fixture/replay "keep this replay"})
                           (throw (ex-info "Connection failed after effect" {:retryable? false}))))
        rt (runtime/open! (assoc options :complete-fn first-provider))
        sid (create! rt)]
    (try
      (runtime/run! rt sid "Earlier task")
      (is (thrown? clojure.lang.ExceptionInfo (runtime/run! rt sid "Perform exactly one effect")))
      (runtime/close! rt)
      (let [reopened (runtime/open! (assoc options :complete-fn
                                         (fn [request _] (swap! requests conj request) (fixtures/answer "Recovered"))))]
        (try
          (is (empty? @requests))
          (runtime/context-inspect reopened sid)
          (is (empty? @requests))
          (runtime/continue! reopened sid)
          (let [messages (:request/messages (first @requests))
                native (remove #{(historical-message (first @requests))} messages)]
            (is (some #(= "Perform exactly one effect" (value/text-content (:message/content %))) native))
            (is (some #(= "once" (:message/tool-call-id %)) native))
            (is (some #(= "keep this replay" (get-in % [:message/provider-data :fixture/replay])) native))
            (is (some #(str/includes? (value/text-content (:message/content %)) "evaluator has been replaced") native)))
          (is (= "effect\n" (slurp (str directory "/effect.txt"))))
          (is (= 1 (count (filter #(= "once" (get-in % [:data :message/tool-call-id]))
                                   (runtime/entries reopened sid)))))
          (is (nil? (:value (runtime/evaluate! reopened sid "(resolve 'retained)"))))
          (finally (runtime/close! reopened))))
      (finally (runtime/close! rt) (sessions/remove-directory! directory)))))

(deftest overflow-recoarsens-the-actual-request-once-without-replaying-effects
  (let [normal (atom 0) requests (atom [])
        provider (fn [request _]
                   (if (summary-request? request)
                     (fixtures/answer "Concise historical evidence")
                     (do (swap! requests conj request)
                         (case (swap! normal inc)
                           1 (fixtures/calls (fixtures/tool-call "once" "(swap! effects inc)"))
                           2 (throw (overflow))
                           (fixtures/answer "Recovered")))))]
    (fixtures/with-runtime [rt provider]
      (let [sid (create! rt (assoc-in (assoc-in tree-config [:settings :summary-node-bytes] 512)
                                     [:settings :auto-compact?] true))]
        (runtime/evaluate! rt sid "(def effects (atom 0))")
        (seed! rt sid 4)
        (runtime/run! rt sid "Preserve this latest user and its settled tool result")
        (is (= 3 @normal))
        (is (not= (historical-message (nth @requests 1)) (historical-message (nth @requests 2))))
        (is (= (remove #{(historical-message (nth @requests 1))} (:request/messages (nth @requests 1)))
               (remove #{(historical-message (nth @requests 2))} (:request/messages (nth @requests 2)))))
        (is (= 1 (:value (runtime/evaluate! rt sid "@effects"))))
        (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid)))
        (is (seq (get-in (runtime/session rt sid) [:metadata :context/view-node-ids])))))))

(deftest repeated-overflow-disabled-auto-compaction-and-no-boundary-stop-honestly
  (doseq [mode [:retry :disabled :no-history]]
    (testing (name mode)
      (let [normal (atom 0)]
        (fixtures/with-runtime
          [rt (fn [request _]
                (if (summary-request? request) (fixtures/answer "Concise evidence")
                    (do (swap! normal inc) (throw (overflow)))))]
          (let [sid (create! rt (-> tree-config
                                   (assoc-in [:settings :summary-node-bytes] 512)
                                   (assoc-in [:settings :auto-compact?] (not= mode :disabled))))]
            (when (not= mode :no-history) (seed! rt sid 4))
            (let [error (try (runtime/run! rt sid "Keep this task") nil
                             (catch clojure.lang.ExceptionInfo error error))]
              (is (= "context-limit-unresolved" (:error/code (ex-data error))))
              (is (= (if (= mode :retry) 2 1) @normal))
              (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid))))))))))

(deftest manual-compaction-persists-a-frontier-not-a-permanently-smaller-budget
  (let [requests (atom [])]
    (fixtures/with-runtime
      [rt (fn [request _]
            (if (summary-request? request) (fixtures/answer "Concise evidence")
                (do (swap! requests conj request) (fixtures/answer "Done"))))]
      (let [sid (create! rt (assoc-in tree-config [:settings :summary-node-bytes] 512))]
        (seed! rt sid 4)
        (runtime/run! rt sid "Latest completed task")
        (let [before (runtime/context-inspect rt sid)
              compacted (runtime/compact! rt sid)
              persisted (get-in (runtime/session rt sid) [:metadata :context/view-node-ids])
              compacted-view (get-in (runtime/context-inspect rt sid) [:view :nodes])]
          (is (= :summary-tree (:context-policy compacted)))
          (is (< (:bytes-after compacted) (:bytes-before compacted)))
          (is (= 128000 (get-in (runtime/session rt sid) [:config :settings :summary-view-bytes])))
          (is (< (count compacted-view) (count (get-in before [:view :nodes]))))
          (is (seq persisted))
          (is (= persisted (vec (take (count persisted) (map :id compacted-view)))))
          (runtime/run! rt sid "A fresh task after compaction")
          (let [request (last @requests)
                frontier-ids (mapv second (re-seq #"<node id=\"([^\"]+)\""
                                                (:message/content (historical-message request))))]
            (is (= persisted (vec (take (count persisted) frontier-ids))))
            (is (< (count frontier-ids) (count (get-in before [:view :nodes]))))
            (is (= "A fresh task after compaction"
                   (value/text-content (:message/content (last (:request/messages request)))))))
          (is (get-in (runtime/context-inspect rt sid) [:view :fits?])))))))

(deftest disable-during-preparation-finishes-the-frozen-turn-and-next-turn-is-linear
  (let [summary-entered (promise) release (promise) requests (atom []) cancelled (atom false)
        provider (fn [request options]
                   (if (summary-request? request)
                     (do (deliver summary-entered true) (fixtures/await! release)
                         (reset! cancelled (util/cancelled? (:cancelled? options)))
                         (fixtures/answer "Concise historical evidence"))
                     (do (swap! requests conj request) (fixtures/answer "Done"))))]
    (fixtures/with-runtime [rt provider]
      (let [sid (create! rt (assoc-in tree-config [:settings :summary-node-bytes] 512))]
        (seed! rt sid 1)
        (let [operation (runtime/start! rt sid "Finish this admitted tree turn")]
          (fixtures/await! summary-entered)
          (is (= :preparing-context (:phase (runtime/state rt sid))))
          (let [generation (:generation (runtime/registry rt sid))]
            (runtime/configure! rt sid {:config {:settings {:context-policy :linear}}})
            (is (empty? @requests))
            (deliver release true)
            (is (= :completed (:status (runtime/wait! rt (:id operation) 10000))))
            (is (false? @cancelled))
            (is (historical-message (first @requests)))
            (runtime/run! rt sid "Now use ordinary linear context")
            (is (nil? (historical-message (last @requests))))
            (is (= generation (:generation (runtime/registry rt sid))))
            (is (summaries/await-session! (:summaries rt) sid 10000))))))))

(deftest child-policies-are-explicit-and-child-job-deliveries-enter-native-context
  (let [requests (atom [])]
    (fixtures/with-runtime [rt (fn [request _]
                                (swap! requests conj request)
                                (fixtures/answer "Child or root completed"))]
      (let [sid (create! rt (assoc-in tree-config [:settings :temperature] 0.25))]
        (agents/pause! (:agents rt) sid)
        (let [ordinary (agents/start-agent! (:agents rt) sid {:task "Ordinary child task"})
              enabled (agents/start-agent! (:agents rt) sid
                                          {:task "Explicit tree child task"
                                           :config {:settings (:settings tree-config)}})
              job (:value (runtime/evaluate! rt sid "(jobs/start! {:name \"Evidence\"} #(hash-map :answer 42))"))]
          (is (= :completed (:status (runtime/wait! rt (:operation-id ordinary) 10000))))
          (is (= :completed (:status (runtime/wait! rt (:operation-id enabled) 10000))))
          (is (= :linear (:policy (runtime/context-inspect rt (:session-id ordinary)))))
          (is (= :summary-tree (:policy (runtime/context-inspect rt (:session-id enabled)))))
          (is (= 0.25 (get-in (runtime/session rt (:session-id ordinary)) [:config :settings :temperature])))
          (jobs/await-job (:jobs rt) sid (:id job) 10000)
          (runtime/continue! rt sid)
          (let [request (last (filter #(= sid (get-in % [:request/cache :scope-id])) @requests))
                native (remove #{(historical-message request)} (:request/messages request))]
            (is (some #(str/includes? (value/text-content (:message/content %)) "Agent completion from session") native))
            (is (some #(str/includes? (value/text-content (:message/content %)) "Background job \"Evidence\" is completed") native)))
          (is (= 2 (count (filter #(= :completion (get-in % [:data :message/agent :kind]))
                                   (runtime/active-path rt sid)))))
          (is (= 1 (count (filter #(get-in % [:data :message/job-id]) (runtime/active-path rt sid))))))))))

(deftest normal-sessions-use-the-original-native-context-without-summary-work
  (let [requests (atom [])]
    (fixtures/with-runtime [rt (fn [request _] (swap! requests conj request) (fixtures/answer "Done"))]
      (let [sid (create! rt (assoc tree-config :settings {:auto-title? false :auto-compact? false}))]
        (runtime/run! rt sid "First ordinary task")
        (runtime/run! rt sid "Second ordinary task")
        (is (= 2 (count @requests)))
        (is (not-any? historical-message @requests))
        (is (not (str/includes? (request-text (last @requests)) "history/zoom")))
        (is (= 0 (get-in (runtime/context-inspect rt sid) [:summary :node-count])))
        (is (= [:developer :user :assistant :user]
               (mapv :message/role (:request/messages (last @requests)))))))))

(deftest resets-disable-and-shutdown-retain-resources-until-summary-worker-exits
  (doseq [action [:reload :branch :delete :disable :close]]
    (testing (name action)
      (let [directory (sessions/temp-directory) entered (promise) release (atom false)
            provider (fn [request _]
                       (if (summary-request? request)
                         (do (deliver entered true)
                             (while (not @release)
                               (try (Thread/sleep 5) (catch InterruptedException _)))
                             (fixtures/answer "Completed late summary"))
                         (fixtures/answer "Done")))
            rt (runtime/open! {:cwd directory :home (str directory "/home")
                               :data-dir (str directory "/data") :complete-fn provider
                               :settings {:close-timeout-ms 20}})
            sid (create! rt (assoc-in tree-config [:settings :summary-node-bytes] 512))]
        (try
          (runtime/run! rt sid (apply str (repeat 2000 "x")))
          (fixtures/await! entered)
          (let [registry (runtime/registry rt sid)
                outcome (try
                          (case action
                            :reload (runtime/reload! rt sid)
                            :branch (runtime/branch! rt sid nil {})
                            :delete (runtime/delete! rt sid)
                            :disable (runtime/configure! rt sid {:config {:settings {:context-policy :linear}}})
                            :close (runtime/close! rt))
                          (catch clojure.lang.ExceptionInfo error error))]
            (if (= action :close)
              (do (is (= :closing (:status outcome)))
                  (is (false? (:summaries-complete? outcome)))
                  (is (false? (:store-closed? outcome))))
              (is (= "summaries-still-running" (:error/code (ex-data outcome)))))
            (is (= sid (:id (store/session (:store rt) sid))))
            (is (identical? registry (get-in @(:handles rt) [sid :registry])))
            (is (false? (summaries/await-session! (:summaries rt) sid 1)))
            (reset! release true)
            (is (summaries/await-session! (:summaries rt) sid 10000))
            (when (not= action :close)
              (case action
                :reload (is (= :reloaded (:status (runtime/reload! rt sid))))
                :branch (do (runtime/branch! rt sid nil {}) (is (nil? (:head (runtime/session rt sid)))))
                :delete (do (runtime/delete! rt sid) (is (thrown? clojure.lang.ExceptionInfo (runtime/session rt sid))))
                :disable (is (= :linear (:policy (runtime/context-inspect rt sid)))))))
          (is (= :closed (:status (runtime/close! rt))))
          (finally (reset! release true) (runtime/close! rt) (sessions/remove-directory! directory)))))))

(deftest root-generation-settings-propagate-the-approved-context-configuration
  (let [directory (sessions/temp-directory)
        approved {:context-policy :summary-tree :summary-provider :openai
                  :summary-model "gpt-4o-mini" :summary-node-bytes 2048
                  :summary-view-bytes 32000 :summary-max-attempts 2 :summary-timeout-ms 12000}
        requests (atom [])
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :settings (assoc approved :auto-title? false)
                           :complete-fn (fn [request _] (swap! requests conj request) (fixtures/answer "Done"))})]
    (try
      (let [sid (create! rt sessions/config)]
        (is (= approved (select-keys (get-in (runtime/session rt sid) [:config :settings]) (keys approved))))
        (runtime/context-inspect rt sid)
        (is (empty? @requests)))
      (finally (runtime/close! rt) (sessions/remove-directory! directory)))))

(deftest delivered-child-completion-survives-a-failed-first-incorporating-request
  (let [root (atom nil) root-calls (atom 0) requests (atom [])
        provider (fn [request _]
                   (if (= @root (get-in request [:request/cache :scope-id]))
                     (do (swap! requests conj request)
                         (if (= 1 (swap! root-calls inc))
                           (throw (ex-info "First incorporating request failed" {:retryable? false}))
                           (fixtures/answer "Observed child result")))
                     (fixtures/answer "Observed child evidence")))]
    (fixtures/with-runtime [rt provider]
      (let [sid (create! rt)]
        (reset! root sid)
        (seed! rt sid 1)
        (agents/pause! (:agents rt) sid)
        (let [child (agents/start-agent! (:agents rt) sid {:task "Produce evidence"})]
          (is (= :completed (:status (runtime/wait! rt (:operation-id child) 10000))))
          (is (thrown? clojure.lang.ExceptionInfo (runtime/continue! rt sid)))
          (runtime/continue! rt sid)
          (doseq [request @requests]
            (let [native (remove #{(historical-message request)} (:request/messages request))]
              (is (some #(str/includes? (value/text-content (:message/content %))
                                       "Agent completion from session") native))
              (is (some #(str/includes? (value/text-content (:message/content %))
                                       "Observed child evidence") native))))
          (is (= 2 @root-calls))
          (is (= 1 (count (filter #(= :completion (get-in % [:data :message/agent :kind]))
                                   (runtime/active-path rt sid))))))))))

(deftest preparing-history-streams-only-the-main-assistant-answer
  (let [events (atom []) callbacks (atom []) requests (atom [])
        provider (fn [request options]
                   (swap! requests conj request)
                   (let [text (if (summary-request? request) "INTERNAL TREE SUMMARY" "Visible answer")]
                     (when-let [on-event (:on-event options)]
                       (on-event {:event/type :stream/content-delta :event/delta text}))
                     (fixtures/answer text)))]
    (fixtures/with-runtime [rt provider]
      (let [sid (create! rt (assoc-in tree-config [:settings :summary-node-bytes] 512))
            unsubscribe (runtime/subscribe! rt #(swap! events conj %))]
        (try
          (seed! rt sid 1)
          (runtime/run! rt sid "Use the historical evidence" {:on-event #(swap! callbacks conj %)})
          (is (some summary-request? @requests))
          (is (= 1 (count (remove summary-request? @requests))))
          (doseq [received [@events @callbacks]]
            (let [deltas (keep #(when (= :provider-event (:type %))
                                 (get-in % [:data :event/delta])) received)]
              (is (= ["Visible answer"] (vec deltas)))))
          (is (some #(and (= :operation/phase (:type %))
                          (= :preparing-context (get-in % [:data :phase]))) @callbacks))
          (finally (unsubscribe)))))))

(deftest impossible-view-budget-fails-before-main-inference-and-recovery-keeps-current-input
  (let [requests (atom [])]
    (fixtures/with-runtime
      [rt (fn [request _]
            (swap! requests conj request)
            (fixtures/answer (if (summary-request? request) "Concise evidence" "Recovered")))]
      (let [sid (create! rt (-> tree-config
                               (assoc-in [:settings :summary-node-bytes] 512)
                               (assoc-in [:settings :summary-view-bytes] 1)))]
        (seed! rt sid 1)
        (let [error (try (runtime/run! rt sid "Keep the unsubmitted current task") nil
                         (catch clojure.lang.ExceptionInfo error error))]
          (is (= "summary-view-budget" (:error/code (ex-data error)))))
        (is (not-any? #(not (summary-request? %)) @requests))
        (runtime/configure! rt sid {:config {:settings {:summary-view-bytes 128000}}})
        (runtime/continue! rt sid)
        (let [main (last (remove summary-request? @requests))]
          (is (= "Keep the unsubmitted current task"
                 (value/text-content (:message/content (last (:request/messages main)))))))
        (is (= 1 (count (filter #(= "Keep the unsubmitted current task"
                                    (value/text-content (get-in % [:data :message/content])))
                                 (runtime/active-path rt sid)))))))))

(deftest tree-main-and-summary-models-remain-the-exact-selected-identities
  (let [requests (atom [])]
    (fixtures/with-runtime
      [rt (fn [request _]
            (swap! requests conj request)
            (fixtures/answer "Observed evidence"))]
      (let [sid (create! rt (-> tree-config
                               (assoc :model "exact-fixture-main")
                               (assoc-in [:settings :fallback-model?] true)
                               (assoc-in [:settings :summary-model] "exact-fixture-summary")
                               (assoc-in [:settings :summary-node-bytes] 512)))]
        (seed! rt sid 1)
        (runtime/run! rt sid "Use exactly the selected model")
        (is (some summary-request? @requests))
        (is (some #(not (summary-request? %)) @requests))
        (doseq [request @requests]
          (is (= (if (summary-request? request) "exact-fixture-summary" "exact-fixture-main")
                 (:request/model request))))))))

(deftest legacy-linear-markers-do-not-truncate-tree-native-input
  (doseq [mode [:run :continue :manual]]
    (testing (name mode)
      (let [requests (atom []) reject? (atom false)]
        (fixtures/with-runtime
          [rt (fn [request _]
                (swap! requests conj request)
                (if (compare-and-set! reject? true false)
                  (throw (ex-info "Before acceptance" {:error/code "fixture-before-acceptance"}))
                  (fixtures/answer "Completed")))]
          (let [sid (create! rt (assoc-in tree-config [:settings :context-policy] :linear))]
            (runtime/run! rt sid "First linear task")
            (runtime/run! rt sid "Last linear task")
            (runtime/compact! rt sid {:config {:settings {:compaction-keep-entries 1}}})
            (let [marker (last (filter #(= :compaction (:kind %)) (runtime/active-path rt sid)))]
              (is (:first-kept-entry-id (:data marker)))
              (runtime/configure! rt sid {:config {:settings {:context-policy :summary-tree}}})
              (when (= mode :manual)
                (runtime/compact! rt sid))
              (when (= mode :continue)
                (reset! reject? true)
                (let [error (try (runtime/run! rt sid "Keep this native task") nil
                                 (catch clojure.lang.ExceptionInfo error error))]
                  (is (= "fixture-before-acceptance" (:error/code (ex-data error))))))
              (if (= mode :continue)
                (runtime/continue! rt sid)
                (runtime/run! rt sid "Keep this native task"))
              (let [request (last @requests)]
                (is (historical-message request))
                (is (str/includes? (:message/content (historical-message request)) "Last linear task"))
                (is (= "Keep this native task"
                       (value/text-content (:message/content (last (:request/messages request)))))))
              (is (= marker (some #(when (= (:id marker) (:id %)) %) (runtime/entries rt sid)))))))))))

(deftest steering-overrides-survive-unconfigured-follow-ups-and-context-changes-wait
  (doseq [policy [:linear :summary-tree] change [:unchanged :intent :stored :both :repeat]]
    (testing (str (name policy) "/" (name change))
      (let [requests (atom []) views (atom []) runtime-ref (atom nil)
            calls (atom 0) entered (promise) release (promise)
            next-policy (if (= policy :linear) :summary-tree :linear)]
        (fixtures/with-runtime
          [rt (fn [request _]
                (if (summary-request? request)
                  (fixtures/answer "Historical evidence")
                  (do (swap! requests conj request)
                      (swap! views conj (:view (runtime/context-inspect
                                               @runtime-ref (get-in request [:request/cache :scope-id]))))
                      (case (swap! calls inc)
                        1 (do (deliver entered true) (fixtures/await! release)
                              (fixtures/calls (fixtures/tool-call "settled" "(+ 1 2)")))
                        2 (fixtures/answer "Turn complete")
                        (fixtures/answer "Follow-up complete")))))]
          (let [sid (create! rt (assoc-in tree-config [:settings :context-policy]
                                         (if (= change :repeat) next-policy policy)))]
            (reset! runtime-ref rt)
            (seed! rt sid 1)
            (let [operation (runtime/start! rt sid "Original task"
                                           {:config {:model "main-A" :thinking :medium
                                                     :settings {:context-policy policy :summary-view-bytes 64000
                                                                :temperature 0.2 :max-output-tokens 123}}})]
              (fixtures/await! entered)
              (runtime/steer-operation!
               rt (:id operation) "Steered task"
               {:config (cond-> {:model "main-B" :thinking :xhigh :settings {:temperature 0.7}}
                          (contains? #{:intent :both} change)
                          (update :settings assoc :context-policy next-policy :summary-view-bytes 32000))})
              (when (contains? #{:stored :both :repeat} change)
                (runtime/configure! rt sid {:config {:settings {:context-policy next-policy
                                                               :summary-view-bytes 16000}}}))
              (runtime/follow-up-operation! rt (:id operation) "Unconfigured follow-up")
              (deliver release true)
              (is (= :completed (:status (runtime/wait! rt (:id operation) 10000))))
              (let [[initial steered follow-up] @requests
                    expected-policy (if (= change :unchanged) policy next-policy)]
                (is (= ["main-A" "main-B" "main-B"] (mapv :request/model @requests)))
                (is (= [:medium :xhigh :xhigh] (mapv #(get-in % [:request/reasoning :effort]) @requests)))
                (is (= [0.2 0.7 0.7] (mapv :request/temperature @requests)))
                (is (= [123 123 123] (mapv :request/max-tokens @requests)))
                (is (= (historical-message initial) (historical-message steered)))
                (when (= :summary-tree policy)
                  (is (= [64000 64000] (mapv :budget (take 2 @views)))))
                (is (= (= :summary-tree expected-policy) (boolean (historical-message follow-up))))
                (when (= :summary-tree expected-policy)
                  (is (= (case change :unchanged 64000 :intent 32000 :stored 16000 :both 32000 :repeat 16000)
                         (:budget (nth @views 2))))))
              (is (empty? (runtime/pending rt sid))))))))))

(deftest steering-never-makes-the-unfinished-task-a-compaction-boundary
  (doseq [history? [false true]]
    (testing (if history? "one irreducible old record" "no old history")
      (let [requests (atom []) calls (atom 0) entered (promise) release (promise)
            recover? (atom false)]
        (fixtures/with-runtime
          [rt (fn [request _]
                (if (summary-request? request)
                  (fixtures/answer "Historical evidence")
                  (do (swap! requests conj request)
                      (case (swap! calls inc)
                        1 (do (deliver entered true) (fixtures/await! release)
                              (assoc (fixtures/calls (fixtures/tool-call "once" "(swap! effects inc)"))
                                     :response/provider-data {:fixture/replay "native settled replay"}))
                        (if @recover? (fixtures/answer "Recovered") (throw (overflow)))))))]
          (let [sid (create! rt (assoc-in tree-config [:settings :auto-compact?] true))]
            (when history?
              (store/commit! (:store rt) sid
                             {::command/entries [{:kind :message
                                                  :data {:message/role :assistant :message/content "Old final answer"}}]}))
            (runtime/evaluate! rt sid "(def effects (atom 0))")
            (let [operation (runtime/start! rt sid "Perform this original task exactly once")]
              (fixtures/await! entered)
              (runtime/steer-operation! rt (:id operation) "Steering does not finish that task")
              (runtime/follow-up-operation! rt (:id operation) "Keep the pending follow-up too")
              (deliver release true)
              (let [result (runtime/wait! rt (:id operation) 10000)]
                (is (= :failed (:status result)))
                (is (= "context-limit-unresolved" (get-in result [:error :code]))))
              (is (= 2 @calls))
              (is (= ["Keep the pending follow-up too"] (mapv :content (runtime/pending rt sid))))
              (let [failed (last @requests)
                    native (remove #{(historical-message failed)} (:request/messages failed))]
                (is (some #(= "Perform this original task exactly once"
                              (value/text-content (:message/content %))) native))
                (is (some #(= "once" (:message/tool-call-id %)) native))
                (is (some #(= "native settled replay" (get-in % [:message/provider-data :fixture/replay])) native))
                (let [error (try (runtime/compact! rt sid) nil
                                 (catch clojure.lang.ExceptionInfo error error))]
                  (is (= "nothing-to-compact" (:error/code (ex-data error)))))
                (reset! recover? true)
                (runtime/continue! rt sid)
                (let [continued (:request/messages (last @requests))]
                  (is (= (:request/messages failed) (subvec continued 0 (count (:request/messages failed)))))
                  (is (= "Keep the pending follow-up too"
                         (value/text-content (:message/content (last continued))))))
                (is (empty? (runtime/pending rt sid))))
              (is (= 1 (:value (runtime/evaluate! rt sid "@effects"))))
              (is (= 1 (count (filter #(= "once" (get-in % [:data :message/tool-call-id]))
                                       (runtime/active-path rt sid))))))))))))

(deftest malformed-derived-frontiers-fall-back-to-canonical-history
  (doseq [metadata [1 "not-a-vector" {:node "wrong"} [1] ["off-path-node"]
                   (vec (repeat 20 "off-path-node"))]
          continuation? [false true]]
    (testing (str (pr-str metadata) "/" continuation?)
      (let [requests (atom []) reject? (atom continuation?)]
        (fixtures/with-runtime
          [rt (fn [request _]
                (if (summary-request? request)
                  (fixtures/answer "Historical evidence")
                  (do (swap! requests conj request)
                      (if (compare-and-set! reject? true false)
                        (throw (ex-info "Request failed" {:error/code "fixture-failure"}))
                        (fixtures/answer "Done")))))]
          (let [sid (create! rt)]
            (seed! rt sid 1)
            (runtime/configure! rt sid {:metadata {:context/view-node-ids metadata}})
            (if continuation?
              (do
                (let [error (try (runtime/run! rt sid "Current full task") nil
                                 (catch clojure.lang.ExceptionInfo error error))]
                  (is (= "fixture-failure" (:error/code (ex-data error)))))
                (runtime/continue! rt sid))
              (runtime/run! rt sid "Current full task"))
            (let [request (last @requests)]
              (is (str/includes? (:message/content (historical-message request)) "Historical task 0"))
              (is (= "Current full task"
                     (value/text-content (:message/content (last (:request/messages request)))))))
            (is (summaries/await-session! (:summaries rt) sid 5000))
            (is (true? (get-in (runtime/context-inspect rt sid) [:view :fits?])))))))))

(deftest retained-off-branch-node-ids-are-not-trusted-as-a-frontier
  (let [requests (atom [])]
    (fixtures/with-runtime
      [rt (fn [request _] (swap! requests conj request) (fixtures/answer "Done"))]
      (let [sid (create! rt)]
        (store/commit! (:store rt) sid
                       {::command/entries [{:kind :message
                                            :data {:message/role :user :message/content "Other branch's historical task"}}
                                           {:kind :message
                                            :data {:message/role :assistant :message/content "Other branch's final answer"}}]})
        (runtime/run! rt sid "Complete the old branch")
        (let [old-node-id (get-in (runtime/context-inspect rt sid) [:view :nodes 0 :id])]
          (is (string? old-node-id))
          (runtime/branch! rt sid nil {})
          (seed! rt sid 1)
          (runtime/configure! rt sid {:metadata {:context/view-node-ids [old-node-id]}})
          (runtime/run! rt sid "Only use the new active branch")
          (let [history (:message/content (historical-message (last @requests)))]
            (is (str/includes? history "Historical task 0"))
            (is (not (str/includes? history "Other branch's"))))
          (is (not-any? #(= old-node-id (:id %))
                        (get-in (runtime/context-inspect rt sid) [:view :nodes]))))))))

(deftest early-hook-failure-quiesces-deferred-disable-without-masking-the-hook
  (doseq [[kind point stubborn?] [[:run :input false] [:continue :before-run false]
                                [:compact :before-run false] [:run :input true]]]
    (testing (str (name kind) "/" (name point) "/" stubborn?)
      (let [summary-entered (promise) hook-entered (promise) release-hook (promise)
            release-summary (atom false) summary-calls (atom 0)
            directory (sessions/temp-directory)
            rt (runtime/open!
                {:cwd directory :home (str directory "/home") :data-dir (str directory "/data")
                 :settings {:close-timeout-ms 20}
                 :complete-fn
                 (fn [request options]
                   (if (summary-request? request)
                     (do (swap! summary-calls inc)
                         (deliver summary-entered true)
                         (while (and (not @release-summary)
                                     (or stubborn? (not (util/cancelled? (:cancelled? options)))))
                           (try (Thread/sleep 5) (catch InterruptedException _)))
                         (fixtures/answer "Stopped historical work"))
                     (fixtures/answer "Done")))})
            sid (create! rt (assoc-in tree-config [:settings :summary-node-bytes] 512))]
        (try
          (runtime/evaluate! rt sid "(def retained 42)")
          (runtime/run! rt sid (apply str (repeat 2000 "x")))
          (fixtures/await! summary-entered)
          (let [registry (runtime/registry rt sid)]
            (capabilities/add-hook!
             registry point
             {:id "failing-preparation" :owner "fixture"
              :fn (fn [_ _] (deliver hook-entered true) (fixtures/await! release-hook)
                    (throw (ex-info "Primary hook failure" {:fixture/primary true})))})
            (let [result (future
                           (try
                             (case kind
                               :run (runtime/run! rt sid "Input fails before commit")
                               :continue (runtime/continue! rt sid)
                               :compact (runtime/compact! rt sid))
                             (catch Throwable error error)))]
              (fixtures/await! hook-entered)
              (runtime/configure! rt sid {:config {:settings {:context-policy :linear}}})
              (deliver release-hook true)
              (let [error (fixtures/await! result)]
                (is (= "hook-failed" (:error/code (ex-data error))))
                (is (:fixture/primary (ex-data error)))
                (when stubborn?
                  (is (= ["summaries-still-running"]
                         (mapv #(:error/code (ex-data %)) (.getSuppressed ^Throwable error))))))
              (reset! release-summary true)
              (is (summaries/await-session! (:summaries rt) sid 10000))
              (is (= 1 @summary-calls))
              (is (identical? registry (runtime/registry rt sid)))
              (capabilities/remove-hook! registry "failing-preparation")
              (is (= 42 (:value (runtime/evaluate! rt sid "retained"))))))
          (finally
            (deliver release-hook true)
            (reset! release-summary true)
            (runtime/close! rt)
            (sessions/remove-directory! directory)))))))
