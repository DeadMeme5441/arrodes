(ns arrodes.runtime
  "Runtime composition, public session orchestration, and owned lifecycle."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [arrodes.capabilities :as capabilities]
            [arrodes.agents :as agents]
            [arrodes.coordination :as coordination]
            [arrodes.jobs :as jobs]
            [arrodes.provider :as provider]
            [arrodes.resources :as resources]
            [arrodes.run :as run]
            [arrodes.runtime.control :as control]
            [arrodes.runtime.handles :as handles]
            [arrodes.runtime.model :as model]
            [arrodes.runtime.operations :as operations]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.db :as db]
            [arrodes.store.jobs :as store-jobs]
            [arrodes.store.recovery :as recovery]
            [arrodes.store.transfer :as transfer]
            [arrodes.titles :as titles]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.util.concurrent ExecutorService Executors TimeUnit)))


(defn set-ui!
  "Installs or removes the single host-interaction callback."
  [runtime callback]
  (value/check! (or (nil? callback) (fn? callback)) :invalid-ui-callback
                "UI callback must be a function or nil" {})
  (reset! (:ui runtime) callback)
  runtime)

(defn ui!
  "Invokes the current host callback or fails rather than inventing an answer."
  [runtime request]
  (control/ui! runtime request))

(defn subscribe!
  "Subscribes to durable and transient runtime events and returns an idempotent unsubscribe function."
  [runtime listener]
  (control/ensure-open! runtime)
  (value/check! (fn? listener) :invalid-listener "Event listener must be a function" {})
  (let [id (util/id)]
    (swap! (:listeners runtime) assoc id (bound-fn [event] (listener event)))
    (let [removed? (atom false)]
      (fn []
        (when (compare-and-set! removed? false true)
          (swap! (:listeners runtime) dissoc id))
        nil))))


(defn registry
  "Returns the lazily-created session registry; construction has one owner outside the gate."
  [runtime sid]
  (:registry (handles/handle! runtime sid)))

(defn resource-manager [runtime sid]
  (:resources (handles/handle! runtime sid)))

(defn provider-manager [runtime sid]
  (:provider (handles/handle! runtime sid)))


(defn reserve! [runtime oid]
  (locking (:foreground runtime)
    (control/ensure-open! runtime)
    (swap! (:admission runtime) coordination/reserve oid (:operation-limit runtime)))
  oid)

(defn release! [runtime oid]
  (let [released? (locking (:foreground runtime)
                    (when (= :reserved (get @(:admission runtime) oid))
                      (swap! (:admission runtime) dissoc oid)
                      true))]
    (when released? (agents/settled! (:agents runtime) nil)))
  nil)



(declare state start! start-continue! cancel-operation! wait! delete! launch-agent!)

(defn open!
  "Opens the durable runtime, repairs interrupted work, and creates its owned executor."
  [{:keys [cwd home data-dir memory? settings trust complete-fn ui! command!]
    :or {cwd "." settings {}}}]
  (let [cwd (util/real-path cwd)
        home (util/home-dir {:home home})
        _ (util/ensure-current-home! home data-dir)
        project (util/open-project! home cwd)
        data-dir (util/canonical-path
                  (or data-dir (util/resolve-path (:directory project) "data")))
        opened (atom [])]
    (try
      (util/ensure-dir! home)
      (when-not memory? (util/ensure-dir! data-dir))
      (let [store (db/open! {:path (when-not memory? (str data-dir "/sessions.sqlite"))
                                :memory? memory?
                                :artifact-dir (when-not memory? (str data-dir "/artifacts"))})
            _ (swap! opened conj #(db/close! store))
            recovery-events (recovery/recover! store)
            provider-settings (handles/root-provider-settings cwd home settings)
            provider (provider/create! {:home home :settings provider-settings
                                        :complete-fn complete-fn})
            _ (swap! opened conj #(provider/close! provider))
            root-resources (resources/create! {:cwd cwd :home home :settings settings :trust trust})
            _ (swap! opened conj #(resources/close! root-resources))
            limit (long (max 1 (min 128 (or (:operation-limit settings) 32))))
            publisher (Executors/newSingleThreadExecutor)
            _ (swap! opened conj #(.shutdownNow ^ExecutorService publisher))
            executor (Executors/newCachedThreadPool)
            _ (swap! opened conj #(.shutdownNow ^ExecutorService executor))
            agents-holder (atom nil)
            runtime {:store store :provider provider :resources root-resources
                     :cwd cwd :home home :data-dir data-dir :project project
                     :trust trust :initial-settings settings
                     :settings (atom (resources/settings root-resources))
                     :handles (atom {}) :failed-handles (atom {}) :handle-attempts (atom {})
                     :operations (atom {}) :listeners (atom {})
                     :foreground (atom {}) :admission (atom {}) :operation-limit limit
                     :settlement-failures (atom {})
                     :resetting (atom #{}) :session-locks (atom {})
                     :close-lock (Object.) :ui (atom ui!) :command! command!
                     :executor executor :publisher publisher
                     :published-seq (atom (store/latest-event-seq store))
                     :titles (titles/create!)
                     :lifecycle (atom :open) :recovery-events recovery-events}
            jobs-manager (jobs/create! store
                                       (bound-fn [event]
                                         (if (:seq event)
                                           (control/emit-events! runtime [event])
                                           (control/notify-listeners! runtime event)))
                                       (fn [sid work] (locking (control/session-lock runtime sid) (work)))
                                       settings)
            runtime (assoc runtime :jobs jobs-manager)
            callbacks {:with-session (fn [sid f] (locking (control/session-lock runtime sid) (f)))
                       :emit! (fn [events] (control/emit-events! runtime events))
                       :state (fn [sid] (state @agents-holder sid))
                       :start! (fn [sid prompt opts] (start! @agents-holder sid prompt opts))
                       :continue! (fn [sid opts] (start-continue! @agents-holder sid opts))
                       :cancel! (fn [oid] (cancel-operation! @agents-holder oid))
                       :wait! (fn [oid timeout-ms] (wait! @agents-holder oid timeout-ms))
                       :registry (fn [sid] (registry @agents-holder sid))
                       :reserve! (fn [oid] (reserve! @agents-holder oid))
                       :release! (fn [oid] (release! @agents-holder oid))
                       :launch! (fn [sid oid opts] (launch-agent! @agents-holder sid oid opts))
                       :cancel-jobs! (fn [sid] (jobs/cancel-session! (:jobs @agents-holder) sid))
                       :await-jobs! (fn [sid timeout-ms]
                                      (jobs/await-session! (:jobs @agents-holder) sid timeout-ms))
                       :delete! (fn [sid] (delete! @agents-holder sid))}
            agent-manager (agents/create! runtime callbacks)
            runtime (assoc runtime :agents agent-manager)]
        (reset! agents-holder runtime)
        (reset! opened [])
        runtime)
      (catch Throwable error
        (doseq [cleanup (reverse @opened)]
          (try (cleanup) (catch Throwable _ nil)))
        (throw error)))))


(defn create-session! [runtime opts]
  (control/ensure-open! runtime)
  (let [cwd (util/real-path (or (:cwd opts) (:cwd runtime)))
        defaults (handles/cwd-session-defaults runtime cwd)
        config (value/deep-merge run/default-config defaults (:config opts))]
    (store/create-session! (:store runtime)
                           (assoc opts :cwd cwd :config config
                                  :metadata (assoc (or (:metadata opts) {}) :title/source
                                                   (if (contains? opts :name) :user :default))))))

(defn list-sessions
  ([runtime] (list-sessions runtime {}))
  ([runtime opts] (store/list-sessions (:store runtime) opts)))

(defn session [runtime sid] (store/session (:store runtime) sid))
(defn entries [runtime sid] (store/entries (:store runtime) sid))
(defn active-path [runtime sid] (store/active-path (:store runtime) sid))
(defn pending [runtime sid] (store/pending (:store runtime) sid))
(defn operation [runtime oid] (store/operation (:store runtime) oid))
(defn operations
  ([runtime] (store/operations (:store runtime) {}))
  ([runtime opts] (store/operations (:store runtime) opts)))
(defn events-since [runtime opts] (store/events-since (:store runtime) opts))


(defn usage [runtime sid]
  (run/usage-from-entries (store/active-path (:store runtime) sid)))

(defn state [runtime sid]
  (locking (control/session-lock runtime sid)
    (db/store-read (:store runtime)
      (fn [_]
        (let [snapshot (store/session (:store runtime) sid)
              slot (control/foreground runtime sid)
              operation (when slot
                          (store/operation (:store runtime) (:operation-id slot)))
              event-seq (control/latest-event-seq runtime sid)
              registry (get-in @(:handles runtime) [sid :registry])
              selection (get-in snapshot [:config :tools])]
          {:session snapshot
           :phase (if slot @(:phase slot) :idle)
           :operation-id (:operation-id slot)
           :operation operation
           :queues (store/pending (:store runtime) sid)
           :jobs (vec (vals (into {} (map (juxt :id identity))
                                  (concat (jobs/list-jobs (:jobs runtime) sid {})
                                          (store-jobs/active-jobs (:store runtime) sid)))))
           :usage (usage runtime sid)
           :event-seq event-seq
           :repl (when registry
                   {:namespace (str (:namespace registry)) :generation (:generation registry)})
           :tools {:selection selection
                   :capabilities (if registry (capabilities/catalog registry) [])}})))))

(defn session-view
  "Returns the durable session projection, authoritative control/foreground operation,
   active entries, and replay cursor from one control/session-lock boundary."
  [runtime sid]
  (control/ensure-open! runtime)
  (locking (control/session-lock runtime sid)
    (let [snapshot (state runtime sid)]
      {:state snapshot
       :entries (store/active-path (:store runtime) sid)
       :cursor (:event-seq snapshot)})))

(defn configure! [runtime sid changes]
  (control/ensure-open! runtime)
  (let [registry (get-in @(:handles runtime) [sid :registry])
        requested-tools (get-in changes [:config :tools])]
    (when (and registry requested-tools)
      (capabilities/selected-catalog registry requested-tools))
    (let [result (locking (control/session-lock runtime sid)
                   (let [result (store/configure! (:store runtime) sid changes)]
                     (when registry
                       (capabilities/set-tools! registry
                                                (get-in result [:session :config :tools])))
                     result))
          snapshot (:session result)]
      (control/emit-events! runtime (:events result))
      snapshot)))

(defn label! [runtime sid entry-id label opts]
  (control/ensure-open! runtime)
  (value/check! (and (string? label) (not (str/blank? label))) :invalid-label
                "Label must be a non-empty string" {})
  (locking (control/session-lock runtime sid)
    (let [snapshot (store/session (:store runtime) sid)
          exists? (some #(= entry-id (:id %)) (store/entries (:store runtime) sid))
          labels (conj (vec (remove #(= entry-id (:entry-id %)) (:labels snapshot)))
                       {:entry-id entry-id :label label})]
      (value/check! exists? :entry-not-found "Label target does not exist in this session"
                    {:session-id sid :entry-id entry-id})
      (control/commit! runtime sid
               {::command/expected-revision (:expected-revision opts)
                ::command/entries [{:kind :label :data {:entry-id entry-id :label label}}]
                ::command/session {:labels labels}
                ::command/events [{:type :session/labeled
                          :data {:entry-id entry-id :label label}}]}))))

(defn- common-prefix-length [left right]
  (loop [index 0]
    (if (and (< index (count left)) (< index (count right))
             (= (:id (nth left index)) (:id (nth right index))))
      (recur (inc index))
      index)))



(defn- branch-session! [runtime sid leaf opts]
  (control/ensure-open! runtime)
  (let [{:keys [source abandoned slot events]}
        (locking (control/session-lock runtime sid)
          (control/ensure-idle! runtime sid)
          (let [snapshot (store/session (:store runtime) sid)
                _ (when (contains? opts :expected-revision)
                    (value/check! (= (:expected-revision opts) (:revision snapshot))
                                  :stale-revision "Session revision has changed"
                                  {:session-id sid :expected (:expected-revision opts)
                                   :actual (:revision snapshot)}))
                source {:snapshot snapshot
                        :current-path (store/active-path (:store runtime) sid)
                        :target-path (if leaf
                                       (store/active-path (:store runtime) sid leaf)
                                       [])}
                prefix (common-prefix-length (:current-path source) (:target-path source))
                abandoned (subvec (vec (:current-path source)) prefix)]
            (if (and (:summarize? opts) (seq abandoned))
              {:source source :abandoned abandoned
               :slot (:slot (operations/operation-start! runtime sid :compact))}
              (let [branched (store/branch! (:store runtime) sid leaf opts)]
                {:events (:events branched)}))))]
    (let [result (if slot
                   (operations/execute-operation!
                    runtime sid slot
                    #(model/summarize-branch! runtime sid slot leaf abandoned source opts))
                   (control/emit-events! runtime events))]
      (handles/close-handle! runtime sid)
      (store/session (:store runtime) sid))))

(defn branch! [runtime sid leaf opts]
  (handles/with-session-reset! runtime sid
    #(let [result (branch-session! runtime sid leaf opts)]
       (store-jobs/acknowledge-all-jobs! (:store runtime) sid)
       result)))

(defn fork! [runtime sid opts]
  (control/ensure-open! runtime)
  (locking (control/session-lock runtime sid)
    (transfer/fork! (:store runtime) sid opts)))

(defn clone! [runtime sid opts]
  (control/ensure-open! runtime)
  (locking (control/session-lock runtime sid)
    (transfer/clone! (:store runtime) sid opts)))

(defn delete! [runtime sid]
  (handles/with-session-reset! runtime sid
    #(do
       (handles/close-handle! runtime sid)
       (locking (control/session-lock runtime sid)
         (store/delete-session! (:store runtime) sid)))))

(defn import! [runtime packet opts]
  (control/ensure-open! runtime)
  (transfer/import-session! (:store runtime) packet
                         (merge {:cwd (:cwd runtime)} opts)))

(defn export! [runtime sid]
  (transfer/export-session (:store runtime) sid))

(defn reload! [runtime sid]
  (handles/with-session-reset! runtime sid
    #(do
       (handles/close-handle! runtime sid)
       (registry runtime sid)
       {:session-id sid :status :reloaded})))

(defn- begin-blocking! [runtime sid kind work & [opts]]
  (let [{:keys [slot]} (operations/operation-start! runtime sid kind)]
    (when (and (contains? #{:run :continue} kind) (not (:automatic? opts)))
      (agents/resume! (:agents runtime) sid))
    (operations/execute-operation! runtime sid slot #(work slot))))

(defn run!
  ([runtime sid prompt] (run! runtime sid prompt {}))
  ([runtime sid prompt opts]
   (begin-blocking! runtime sid :run
                    #(model/run-loop! runtime sid % prompt opts) opts)))

(defn continue!
  ([runtime sid] (continue! runtime sid {}))
  ([runtime sid opts]
   (begin-blocking! runtime sid :continue
                    #(model/run-loop! runtime sid % nil opts) opts)))

(defn compact!
  ([runtime sid] (compact! runtime sid {}))
  ([runtime sid opts]
   (begin-blocking! runtime sid :compact
                    #(model/compact-operation! runtime sid % opts))))

(defn start!
  ([runtime sid prompt] (start! runtime sid prompt {}))
  ([runtime sid prompt opts]
   (let [{:keys [slot operation]} (operations/operation-start! runtime sid :run)]
     (when-not (:automatic? opts) (agents/resume! (:agents runtime) sid))
     (operations/submit-operation! runtime sid slot #(model/run-loop! runtime sid slot prompt opts))
     operation)))

(defn start-continue!
  ([runtime sid] (start-continue! runtime sid {}))
  ([runtime sid opts]
   (let [{:keys [slot operation]} (operations/operation-start! runtime sid :continue)]
     (when-not (:automatic? opts) (agents/resume! (:agents runtime) sid))
     (operations/submit-operation! runtime sid slot #(model/run-loop! runtime sid slot nil opts))
     operation)))

(defn launch-agent! [runtime sid oid opts]
  (let [slot
        (locking (control/session-lock runtime sid)
          (let [op (store/operation (:store runtime) oid)]
            (value/check! (and (= sid (:session-id op)) (= :queued (:status op)))
                          :operation-not-queued "Child launch requires its queued operation"
                          {:session-id sid :operation-id oid :status (:status op)})
            (let [slot (control/acquire-foreground! runtime sid :run oid)]
              (try
                (control/commit! runtime sid {::command/session {:status :running}
                                      ::command/operation {:id oid :status :running}
                                      ::command/events [{:operation-id oid :type :operation/started
                                                :data {:kind :run}}]})
                slot
                (catch Throwable error
                  (control/release-foreground! runtime sid oid)
                  (swap! (:operations runtime) dissoc oid)
                  (swap! (:admission runtime) dissoc oid)
                  (deliver (:finished slot) true)
                  (throw error))))))]
    (operations/submit-operation! runtime sid slot #(model/run-loop! runtime sid slot nil opts))
    (store/operation (:store runtime) oid)))

(defn start-compact!
  ([runtime sid] (start-compact! runtime sid {}))
  ([runtime sid opts]
   (let [{:keys [slot operation]} (operations/operation-start! runtime sid :compact)]
     (operations/submit-operation! runtime sid slot #(model/compact-operation! runtime sid slot opts))
     operation)))


(defn steer-operation!
  ([runtime oid content] (steer-operation! runtime oid content {}))
  ([runtime oid content opts] (operations/queue-operation! runtime oid :steering content opts)))

(defn follow-up-operation!
  ([runtime oid content] (follow-up-operation! runtime oid content {}))
  ([runtime oid content opts] (operations/queue-operation! runtime oid :follow-up content opts)))

(defn steer!
  ([runtime sid content] (steer! runtime sid content {}))
  ([runtime sid content opts]
   (if-let [oid (:operation-id (control/foreground runtime sid))]
     (steer-operation! runtime oid content opts)
     (value/fail! :session-not-running "Session has no foreground operation" {:session-id sid}))))

(defn follow-up!
  ([runtime sid content] (follow-up! runtime sid content {}))
  ([runtime sid content opts]
   (if-let [oid (:operation-id (control/foreground runtime sid))]
     (follow-up-operation! runtime oid content opts)
     (value/fail! :session-not-running "Session has no foreground operation" {:session-id sid}))))

(defn update-queue!
  "Updates one still-pending queue item while preserving its ID and order."
  [runtime sid qid content]
  (control/ensure-open! runtime)
  (locking (control/session-lock runtime sid)
    (let [result (store/update-queue! (:store runtime) sid qid
                                      (run/prompt-content content))]
      (control/emit-events! runtime (:events result))
      (:item result))))

(defn drop-queue!
  "Removes and returns one still-pending queue item."
  [runtime sid qid]
  (control/ensure-open! runtime)
  (locking (control/session-lock runtime sid)
    (let [result (store/drop-queue! (:store runtime) sid qid)]
      (control/emit-events! runtime (:events result))
      (:removed result))))

(defn clear-queue! [runtime sid]
  (control/ensure-open! runtime)
  (locking (control/session-lock runtime sid)
    (let [items (store/pending (:store runtime) sid)]
      (when (seq items)
        (control/commit! runtime sid
                 {::command/queue-deliver (mapv :id items)
                  ::command/events [{:operation-id (:operation-id (control/foreground runtime sid))
                            :type :queue/cleared :data {:ids (mapv :id items)}}]}))
      items)))

(defn cancel-operation! [runtime oid]
  (let [sid (:session-id (store/operation (:store runtime) oid))
        {:keys [operation thread]}
        (locking (control/session-lock runtime sid)
          (let [op (store/operation (:store runtime) oid)]
            (if (run/terminal-operation? op)
              {:operation op}
              (let [slot (control/foreground runtime sid)]
                (value/check! (= oid (:operation-id slot)) :operation-not-active
                              "Operation is not the session's current foreground operation"
                              {:operation-id oid :session-id sid
                               :current-operation-id (:operation-id slot)})
                (if-not @(:cancellable? slot)
                  {:operation op}
                  (let [record (if (= :cancelling (:status op))
                                 op
                                 (:operation
                                  (control/commit! runtime sid
                                           {::command/operation {:id oid :status :cancelling}
                                            ::command/events [{:operation-id oid :type :operation/cancelling
                                                      :data {}}]})))]
                    (agents/pause! (:agents runtime) sid)
                    (reset! (:cancelled slot) true)
                    {:operation record :thread @(:thread slot)}))))))]
    (when (and thread (not (identical? thread (Thread/currentThread))))
      (.interrupt ^Thread thread))
    operation))

(defn cancel! [runtime sid]
  (if-let [oid (:operation-id (control/foreground runtime sid))]
    (cancel-operation! runtime oid)
    (do
      (store/session (:store runtime) sid)
      (agents/pause! (:agents runtime) sid)
      {:session-id sid :operation-id nil :status :idle})))


(defn wait!
  ([runtime oid] (wait! runtime oid nil))
  ([runtime oid timeout-ms]
   (if-let [slot (get @(:operations runtime) oid)]
     (let [finished (if (nil? timeout-ms)
                      @(:finished slot)
                      (deref (:finished slot) (long (max 0 timeout-ms)) ::timeout))]
       (if (= ::timeout finished)
         (store/operation (:store runtime) oid)
         @(:done slot)))
     (store/operation (:store runtime) oid))))

(defn evaluate!
  ([runtime sid source] (evaluate! runtime sid source {}))
  ([runtime sid source opts]
   (begin-blocking!
    runtime sid :evaluate
    (fn [slot]
      (control/set-phase! slot :evaluating)
      (let [registry (registry runtime sid)
            result (capabilities/evaluate!
                    registry source
                    {:cancelled? (:cancelled slot) :on-event (:on-event opts)
                     :on-progress #(control/transient-event! runtime sid (:operation-id slot)
                                                     :tool-progress % (:on-event opts))
                     :context {:runtime runtime :session-id sid
                               :operation-id (:operation-id slot)}})]
        (locking (control/session-lock runtime sid)
          (util/check-cancelled! (:cancelled slot))
          (control/commit! runtime sid
                   {::command/entries [{:kind :evaluation
                               :data {:source source :result (operations/durable-invocation result)}}]
                    ::command/events [{:operation-id (:operation-id slot)
                              :type :session/evaluated
                              :data {:result (:result result) :error? (:error? result)}}]}))
        result)))))

(defn invoke!
  ([runtime sid name arguments] (invoke! runtime sid name arguments {}))
  ([runtime sid name arguments opts]
   (begin-blocking!
    runtime sid :invoke
    (fn [slot]
      (control/set-phase! slot :invoking)
      (let [registry (registry runtime sid)
            result (capabilities/invoke!
                    registry {:id (util/id) :name name :arguments arguments}
                    {:cancelled? (:cancelled slot) :on-event (:on-event opts)
                     :on-progress #(control/transient-event! runtime sid (:operation-id slot)
                                                     :tool-progress % (:on-event opts))
                     :context {:runtime runtime :session-id sid
                               :operation-id (:operation-id slot)}})]
        (locking (control/session-lock runtime sid)
          (util/check-cancelled! (:cancelled slot))
          (control/commit! runtime sid
                   {::command/entries [{:kind :custom
                               :data {:type :invocation :name name :arguments arguments
                                      :result (operations/durable-invocation result)}}]
                    ::command/events [{:operation-id (:operation-id slot)
                              :type :capability/invoked
                              :data {:name name :result (:result result)
                                     :error? (:error? result)}}]}))
        result)))))



(defn close!
  "Cancels owned work and closes resources only after every operation driver
   has actually exited. An incomplete close retains every handle and the store
   so a later close attempt can finish safely."
  [runtime]
  (locking (:close-lock runtime)
    (let [prior @(:lifecycle runtime)]
      (if (= :closed prior)
        {:status :closed :already-closed? true :foreground-complete? true
         :executor-terminated? true :errors []}
        (let [slots (locking (:foreground runtime)
                      (compare-and-set! (:lifecycle runtime) :open :closing)
                      (vec (vals @(:operations runtime))))
              current (Thread/currentThread)
              cancellation-errors (atom [])]
          (doseq [slot slots]
            (try
              (cancel-operation! runtime (:operation-id slot))
              (catch Throwable error
                (swap! cancellation-errors conj (value/error-map error))
                (reset! (:cancelled slot) true)
                (when-let [thread @(:thread slot)]
                  (when-not (identical? thread current)
                    (.interrupt ^Thread thread))))))
            ;; Graceful shutdown prevents an operation that invoked close! from
            ;; interrupting its own caller thread. Explicit cancellation above
            ;; still interrupts every other running driver.
          (let [agent-close (agents/close! (:agents runtime))]
          (jobs/stop! (:jobs runtime))
          (titles/stop! (:titles runtime))
          (.shutdown ^ExecutorService (:executor runtime))
          (let [timeout-ms (long (max 0 (or (:close-timeout-ms (:initial-settings runtime))
                                            10000)))
                deadline (+ (System/nanoTime) (* timeout-ms 1000000))
                self-slots (filterv #(identical? current @(:thread %)) slots)
                _ (doseq [slot slots
                          :when (not (some #(identical? slot %) self-slots))]
                    (operations/await-operation! slot deadline))
                jobs-complete? (jobs/await-session! (:jobs runtime) nil (control/remaining-close-millis deadline))
                incomplete (filterv #(not (realized? (:finished %))) slots)
                settlements-complete? (operations/reconcile-settlements! runtime)
                handles-ready? (handles/await-handles! runtime deadline)
                foreground-complete? (empty? incomplete)
                executor-terminated?
                (if foreground-complete?
                  (try
                    (and (.awaitTermination ^ExecutorService (:executor runtime)
                                            (control/remaining-close-millis deadline)
                                            TimeUnit/MILLISECONDS)
                         (titles/await-closed! (:titles runtime) (control/remaining-close-millis deadline)))
                    (catch InterruptedException _
                      (.interrupt current)
                      false))
                  (.isTerminated ^ExecutorService (:executor runtime)))]
            (if-not (and foreground-complete? executor-terminated? jobs-complete?
                         settlements-complete? handles-ready? (= :closed (:status agent-close)))
              {:status :closing :already-closed? false
               :foreground-complete? foreground-complete?
               :active-operation-ids (mapv :operation-id incomplete)
               :jobs-complete? jobs-complete?
               :executor-terminated? executor-terminated?
               :store-closed? false :handles-closed? false
               :errors
               (into @cancellation-errors
                     (cond-> []
                       (not jobs-complete?)
                       (conj {:code "jobs-timeout" :message "Background jobs have not exited; live state retained"})
                       (not foreground-complete?)
                       (conj {:code "foreground-timeout"
                              :message "Foreground execution did not finish before the close deadline"})
                       (not handles-ready?)
                       (conj {:code "handle-timeout"
                              :message "Session evaluator activation or cleanup is still running"})
                       (not settlements-complete?)
                       (conj {:code "settlement-failed"
                              :message "A foreground outcome could not be durably reconciled"})
                       (not= :closed (:status agent-close))
                       (conj {:code "agents-timeout"
                              :message "Agent wake coordinator has not exited"})
                       (not executor-terminated?)
                       (conj {:code "executor-timeout"
                              :message "Operation executor did not terminate before the close deadline"})))}
              (let [errors (atom @cancellation-errors)
                    handles (mapv (fn [sid]
                                    (try (handles/close-handle! runtime sid)
                                         (catch Throwable error
                                           (swap! errors conj (value/error-map error)) nil)))
                                  (distinct (concat (keys @(:handles runtime))
                                                    (keys @(:failed-handles runtime)))))
                    root (try (resources/close! (:resources runtime))
                              (catch Throwable error
                                (swap! errors conj (value/error-map error)) nil))]
                (if (or (seq @(:handles runtime)) (seq @(:failed-handles runtime))
                        (not= :closed (:status root)))
                  {:status :closing :already-closed? false
                   :foreground-complete? true :executor-terminated? true
                   :store-closed? false :handles-closed? false
                   :handles handles :resources root
                   :errors (into @errors (:errors root))}
                  (do
                    (.shutdown ^ExecutorService (:publisher runtime))
                    (let [published? (try (.awaitTermination ^ExecutorService (:publisher runtime)
                                                              (control/remaining-close-millis deadline)
                                                              TimeUnit/MILLISECONDS)
                                          (catch InterruptedException _
                                            (.interrupt current)
                                            false))]
                      (if-not published?
                        {:status :closing :store-closed? false :handles-closed? true
                         :foreground-complete? true :executor-terminated? true
                         :errors (conj @errors {:code "publisher-timeout"
                                                :message "Durable event publication has not exited"})}
                        (let [provider (try (provider/close! (:provider runtime))
                                            (catch Throwable error
                                              (swap! errors conj (value/error-map error)) nil))
                              store (try (db/close! (:store runtime))
                                         (catch Throwable error
                                           (swap! errors conj (value/error-map error)) nil))
                              closed? (empty? @errors)]
                          (when closed?
                            (reset! (:listeners runtime) {})
                            (reset! (:lifecycle runtime) :closed))
                          {:status (if closed? :closed :closing) :already-closed? false
                           :foreground-complete? true :executor-terminated? true
                           :handles handles :resources root :provider provider :store store
                           :errors @errors}))))))))))))))
