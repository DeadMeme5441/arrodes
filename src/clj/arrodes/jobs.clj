(ns arrodes.jobs
  "Session-owned background functions. Durable records are distinct from live execution."
  (:refer-clojure :exclude [list])
  (:require [clojure.string :as str]
            [arrodes.artifacts :as artifacts]
            [arrodes.capabilities :as capabilities]
            [arrodes.platform :as util]
            [arrodes.store :as store]
            [arrodes.value :as value])
  (:import (java.io Writer)
           (java.util.concurrent Executors ExecutorService RejectedExecutionException)))

(def ^:private output-limit (* 1024 1024))
(defn terminal? [record] (contains? store/job-terminal-statuses (:status record)))

(defn create! [store emit! with-session settings]
  {:store store :emit! emit! :with-session with-session :lock (Object.) :slots (atom {}) :blocked (atom #{})
   :closed? (atom false) :executor (Executors/newCachedThreadPool)
   :limit (long (max 1 (min 128 (or (:job-limit settings) 32))))})

(defn- publish! [manager outcome]
  (doseq [event (:events outcome)] ((:emit! manager) event))
  (:job outcome))

(defn- transition! [manager sid id expected changes]
  ((:with-session manager) sid
    #(publish! manager (store/transition-job! (:store manager) sid id expected changes))))

(defn normalize-record
  "Present cancellation consistently, preserving older recorded causes without rewriting history."
  [record]
  (if (and (= :cancelled (:status record)) (not= "cancelled" (get-in record [:error :code])))
    (assoc record :error (cond-> {:code "cancelled" :message "Job was cancelled"}
                          (:error record) (assoc :cause (:error record))))
    record))

(defn inspect-job [manager sid id]
  (let [record (normalize-record (store/job (:store manager) sid id))]
    (if-let [rid (:result-id record)]
      (assoc record :result (dissoc (artifacts/result (:store manager) sid rid) :value))
      record)))

(defn- brief-error [error]
  (let [message (str (:message error))]
    (cond-> {:code (:code error) :message (subs message 0 (min 240 (count message)))}
      (> (count message) 240) (assoc :truncated? true))))

(defn summary
  "Small native status view. Detailed records and results remain separately inspectable."
  [record]
  (cond-> (select-keys record [:id :name :status :result-id])
    (:started-at record)
    (assoc :duration-ms (max 0 (- (or (:finished-at record) (util/now)) (:started-at record))))
    (:result record)
    (assoc :result-available? (get-in record [:result :available?]))
    (:output-truncated? record) (assoc :output-truncated? true)
    (and (:error record) (not= :cancelled (:status record)))
    (assoc :error (brief-error (:error record)))))

(defn- detailed-option! [opts allowed]
  (value/check! (and (map? opts) (every? allowed (keys opts))
                     (or (not (contains? opts :detailed?)) (boolean? (:detailed? opts))))
                :invalid-job-options "Unsupported job options or non-boolean :detailed?" {})
  (:detailed? opts))

(defn list-jobs [manager sid opts]
  (mapv #(inspect-job manager sid (:id %)) (store/jobs (:store manager) sid opts)))

(defn snapshot [manager sid opts]
  (store/store-read (:store manager)
    (fn [_]
      (let [page (list-jobs manager sid opts)]
        {:jobs page :active-jobs (store/active-jobs (:store manager) sid)
         :next-before (when (= (count page) (or (:limit opts) 100)) (:id (last page)))}))))

(declare cancel-job! await-job)

(defn- cancel-slot! [manager slot]
  (let [{:keys [sid id cancelled thread]} slot]
    (reset! cancelled true)
    (let [record (transition! manager sid id #{:queued :running}
                              (if (= :queued (:status (store/job (:store manager) sid id)))
                                {:status :cancelled :finished-at (util/now)
                                 :error {:code "cancelled" :message "Job was cancelled before execution"}}
                                {:status :cancelling}))]
      (doseq [child (vals @(:children slot))] (cancel-slot! manager child))
      (when-let [worker @thread]
        (when-not (identical? worker (Thread/currentThread)) (.interrupt ^Thread worker)))
      record)))

(defn cancel-job! [manager sid id]
  (locking (:lock manager)
    (let [record (store/job (:store manager) sid id)]
      (if-let [slot (get @(:slots manager) id)]
        (if (terminal? record) record (cancel-slot! manager slot))
        record))))

(defn- validate-wait! [manager id]
  (loop [current (:job-id capabilities/*invocation-context*)]
    (when current
      (value/check! (not= current id) :job-wait-cycle "A job cannot wait for itself or an ancestor" {:job-id id})
      (recur (:parent-id (get @(:slots manager) current))))))

(defn await-job [manager sid id timeout-ms]
  (store/job (:store manager) sid id)
  (value/check! (and (integer? timeout-ms) (<= 0 timeout-ms 300000)) :invalid-timeout
                "Job wait timeout must be 0..300000 milliseconds" {})
  (validate-wait! manager id)
  (when-let [slot (get @(:slots manager) id)]
    (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
      (loop []
        (util/check-cancelled! (:cancelled? capabilities/*invocation-context*))
        (when (and (not (realized? (:done slot))) (< (System/nanoTime) deadline))
          (deref (:done slot) (long (max 1 (min 50 (quot (- deadline (System/nanoTime)) 1000000)))) nil)
          (recur)))))
  (inspect-job manager sid id))

(defn- output! [manager slot incoming]
  ;; Serialize stream-reader and println writes, including their event order.
  (locking (:output slot)
    (let [incoming (str incoming) {:keys [text truncated?]} @(:output slot)
          remaining (max 0 (- output-limit (count text)))
          chunk (subs incoming 0 (min remaining (count incoming)))]
      (reset! (:output slot) {:text (str text chunk)
                             :truncated? (or truncated? (> (count incoming) remaining))})
      (when (seq chunk)
        ((:emit! manager) {:id nil :session-id (:sid slot) :type :job/output :time (util/now)
                          :data {:job-id (:id slot) :content chunk}})))))

(defn- writer [manager slot]
  (proxy [Writer] []
    (write
      ([x] (output! manager slot (cond (number? x) (str (char x)) (string? x) x :else (String. ^chars x))))
      ([x offset length]
       (output! manager slot (if (string? x) (subs x offset (+ offset length)) (String. ^chars x offset length)))))
    (flush []) (close [])))

(defn- error-data [error]
  (when error
    {:code (str (or (:error/code (ex-data error)) "job-failed"))
     :message (let [message (str (or (ex-message error) (.getName (class error))))]
                (subs message 0 (min 8000 (count message))))}))

(defn- cancellation-error [cause]
  (ex-info "Job was cancelled"
           (cond-> {:error/code "cancelled"}
             cause (assoc :cause (assoc (error-data cause) :class (.getName (class cause)))))
           cause))

(defn- run-job! [manager registry slot f bindings context]
  (let [{:keys [sid id cancelled]} slot]
    (try
      (let [entered? (locking (:lock manager)
                       (when (= :queued (:status (store/job (:store manager) sid id)))
                         (reset! (:thread slot) (Thread/currentThread))
                         (transition! manager sid id #{:queued} {:status :running :started-at (util/now)})
                         true))]
        (when entered?
          (let [out (writer manager slot)
                invocation (merge (select-keys context [:runtime :tool-selection])
                                  {:registry registry :session-id sid :job-id id :call-id id
                                   :cancelled? cancelled
                                   :on-progress #(when-let [text (:content %)] (output! manager slot text))})
                outcome (try
                          {:value (with-bindings (assoc bindings
                                                  #'capabilities/*invocation-context* invocation
                                                  #'*out* out #'*err* out)
                                    (util/check-cancelled! cancelled)
                                    (f))}
                          (catch Throwable error {:error error}))]
            ;; Never finalize/close a parent while its owned children still execute.
            (Thread/interrupted)
            (locking (:lock manager)
              (reset! (:accepting? slot) false)
              (when (or @cancelled (:error outcome))
                (doseq [child (vals @(:children slot))] (cancel-slot! manager child))))
            (doseq [child (vals @(:children slot))]
              (loop []
                (when-not (realized? (:done child))
                  (try (deref (:done child) 50 nil) (catch InterruptedException _ nil))
                  (recur))))
            (Thread/interrupted)
            (let [captured @(:output slot)
                  artifact (artifacts/put! (:store manager) sid (:text captured)
                                          {:kind :text :name (str "job-" id ".log")})]
              ;; Cancellation and successful settlement race under one gate. The
              ;; retained descriptor and job record must describe the same outcome.
              (locking (:lock manager)
                (let [error (if @cancelled (cancellation-error (:error outcome)) (:error outcome))
                      result (capabilities/retain-job-result! registry id (:value outcome) error)]
                  (transition! manager sid id #{:running :cancelling}
                               (cond-> {:status (cond @cancelled :cancelled error :failed :else :completed)
                                        :finished-at (util/now) :result-id (:id result)
                                        :output-artifact-id (:id artifact)
                                        :output-characters (count (:text captured))
                                        :output-truncated? (:truncated? captured)}
                                 error (assoc :error (cond-> (error-data error)
                                                      (:cause (ex-data error))
                                                      (assoc :cause (:cause (ex-data error)))))))))))))
      (catch Throwable error
        ;; Persistence/retention failure must never replay the function.
        (Thread/interrupted)
        (try (transition! manager sid id #{:queued :running :cancelling}
                          {:status :failed :finished-at (util/now) :error (error-data error)})
             (catch Throwable _ nil)))
      (finally
        (reset! (:thread slot) nil)
        (locking (:lock manager)
          (when (terminal? (store/job (:store manager) sid id))
            (swap! (:slots manager) dissoc id)
            (when-let [parent (get @(:slots manager) (:parent-id slot))]
              (swap! (:children parent) dissoc id))))
        (deliver (:done slot) true)))))

(defn start-job! [manager registry opts f context]
  (value/check! (fn? f) :invalid-job "jobs/start! requires a zero-argument function" {})
  (value/check! (and (map? opts) (every? #{:name} (keys opts))) :invalid-job
                "Job options support only :name" {})
  (let [label (or (:name opts) "Background computation")
        _ (value/check! (and (string? label) (not (str/blank? label)) (<= (count label) 200))
                        :invalid-job "Job name must contain 1..200 characters" {})
        sid (:session-id registry)
        id (util/id)
        parent-id (:job-id context)
        bindings (get-thread-bindings)]
    (locking (:lock manager)
      (value/check! (and (not @(:closed? manager)) (not (contains? @(:blocked manager) sid)))
                    :jobs-unavailable "Session jobs are shutting down or the evaluator is being replaced" {})
      (value/check! (< (count @(:slots manager)) (:limit manager)) :job-limit
                    "Background job capacity reached; wait for or cancel existing work" {:limit (:limit manager)})
      (when parent-id
        (let [parent (get @(:slots manager) parent-id)]
          (value/check! (and parent @(:accepting? parent) (not @(:cancelled parent)))
                        :parent-job-finished "Parent job no longer accepts children" {})))
      (let [record {:id id :session-id sid :name label :kind :clojure :status :queued :revision 0
                    :parent-job-id parent-id :created-at (util/now)
                    :origin {:operation-id (:operation-id context) :evaluation-id (:call-id context)
                             :generation (:generation registry)
                             :head (:head (store/session (:store manager) sid))}}
            slot {:id id :sid sid :parent-id parent-id :cancelled (atom false) :thread (atom nil)
                  :done (promise) :children (atom {}) :accepting? (atom true)
                  :output (atom {:text "" :truncated? false})}]
        ((:with-session manager) sid #(publish! manager (store/create-job! (:store manager) record)))
        (swap! (:slots manager) assoc id slot)
        (when parent-id (swap! (:children (get @(:slots manager) parent-id)) assoc id slot))
        (try
          (.submit ^ExecutorService (:executor manager)
                   ^Runnable (fn [] (run-job! manager registry slot f bindings context)))
          (catch RejectedExecutionException error
            (transition! manager sid id #{:queued} {:status :failed :finished-at (util/now) :error (error-data error)})
            (swap! (:slots manager) dissoc id)
            (deliver (:done slot) true)))
        {:id id :session-id sid}))))

(defn- output-options! [id opts]
  (let [{:keys [offset limit after tail?] :or {offset 0 limit 4096}} opts]
    (value/check! (and (map? opts) (every? #{:offset :limit :after :tail?} (keys opts))
                       (or (not (contains? opts :tail?)) (boolean? tail?))
                       (integer? offset) (<= 0 offset Long/MAX_VALUE)
                       (integer? limit) (<= 1 limit 32768)
                       (<= (+ (if (contains? opts :offset) 1 0)
                              (if (contains? opts :after) 1 0) (if tail? 1 0)) 1))
                  :invalid-output-page "Choose offset, after cursor, or tail?; limit must be 1..32768" {})
    (when (contains? opts :after)
      (value/check! (and (map? after) (= #{:job-id :offset} (set (keys after)))
                         (= id (:job-id after)) (integer? (:offset after))
                         (<= 0 (:offset after) Long/MAX_VALUE))
                    :invalid-output-cursor "Output cursor must belong to this job and have a non-negative offset" {}))
    {:offset (if after (:offset after) offset) :limit limit :tail? tail? :after? (some? after)}))

(defn output-job [manager sid id opts]
  (let [{:keys [offset limit tail? after?]} (output-options! id opts)]
    (store/store-read (:store manager)
      (fn [_]
        (let [record (store/job (:store manager) sid id)
              live (get @(:slots manager) id)
              captured (when live @(:output live))
              artifact-id (:output-artifact-id record)
              ;; Earlier schema-2 records have no character count. A bounded read
              ;; obtains the exact length; UTF-8 byte counts cannot stand in for it.
              older-text (when (and (nil? captured) artifact-id (nil? (:output-characters record)))
                           (:content (artifacts/read! (:store manager) sid artifact-id {:offset 1 :limit output-limit})))
              length (or (some-> captured :text count) (:output-characters record) (some-> older-text count))
              _ (when (and after? length)
                  (value/check! (<= offset length) :invalid-output-cursor "Cursor is beyond the retained output" {}))
              start (if length (if tail? (max 0 (- length limit)) (min offset length)) offset)
              end (if length (+ start (min limit (- length start))) start)
              text (cond
                     captured (subs (:text captured) start end)
                     older-text (subs older-text start end)
                     artifact-id (:content (artifacts/read! (:store manager) sid artifact-id {:offset (inc start) :limit limit}))
                     :else "")]
          (cond-> {:text text :offset start :next-offset end
                   :cursor {:job-id id :offset end}
                   :more? (boolean (and length (< end length)))
                   :eof? (and (terminal? record) (or (nil? length) (= end length)))
                   :truncated? (boolean (if captured (:truncated? captured) (:output-truncated? record)))}
            artifact-id (assoc :artifact-id artifact-id)
            (and (nil? length) (= :interrupted (:status record))) (assoc :unavailable? true)))))))

(defn block-session! [manager sid]
  (locking (:lock manager) (swap! (:blocked manager) conj sid)))
(defn unblock-session! [manager sid]
  (locking (:lock manager) (swap! (:blocked manager) disj sid)))

(defn cancel-session! [manager sid]
  (locking (:lock manager)
    (doseq [slot (vals @(:slots manager)) :when (= sid (:sid slot))]
      (when-not (terminal? (store/job (:store manager) sid (:id slot))) (cancel-slot! manager slot)))))

(defn await-session! [manager sid timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))
        slots (filter #(or (nil? sid) (= sid (:sid %))) (vals @(:slots manager)))]
    (doseq [slot slots]
      (when-not (identical? @(:thread slot) (Thread/currentThread))
        (deref (:done slot) (long (max 0 (quot (- deadline (System/nanoTime)) 1000000))) nil)))
    (empty? (filter #(or (nil? sid) (= sid (:sid %))) (vals @(:slots manager))))))

(defn stop! [manager]
  (locking (:lock manager)
    (reset! (:closed? manager) true)
    (doseq [sid (distinct (map :sid (vals @(:slots manager))))] (cancel-session! manager sid))
    (.shutdown ^ExecutorService (:executor manager))))

(defn- environment []
  (let [context capabilities/*invocation-context* registry (:registry context) manager (:job-manager registry)]
    (value/check! manager :jobs-unavailable "Job functions require an active Arrodes REPL invocation" {})
    [manager registry context]))
(defn- job-id [registry handle]
  (when (and (map? handle) (contains? handle :session-id))
    (value/check! (= (:session-id registry) (:session-id handle)) :job-not-found "Job belongs to another session" {}))
  (if (map? handle) (:id handle) handle))

(defn start!
  "Start a zero-argument function without waiting. Returns a session-owned handle. Optional {:name string}."
  ([f] (start! {} f))
  ([opts f] (let [[manager registry context] (environment)] (start-job! manager registry opts f context))))
(defn inspect
  "Compact status by default. Pass {:detailed? true} for origin, diagnostics, and retained descriptors."
  ([handle] (inspect handle {}))
  ([handle opts]
   (let [detailed? (detailed-option! opts #{:detailed?})
         [manager registry] (environment)
         record (inspect-job manager (:session-id registry) (job-id registry handle))]
     (if detailed? record (summary record)))))
(defn list
  "List compact statuses newest first. Options: :limit, :before, :detailed? (default false)."
  ([] (list {}))
  ([opts]
   (let [detailed? (detailed-option! opts #{:limit :before :detailed?})
         [manager registry] (environment)
         records (list-jobs manager (:session-id registry) (dissoc opts :detailed?))]
     (if detailed? records (mapv summary records)))))
(defn cancel!
  "Request cancellation of this job and owned children; return compact status. Inspect details separately."
  [handle]
  (let [[manager registry] (environment)]
    (summary (normalize-record (cancel-job! manager (:session-id registry) (job-id registry handle))))))
(defn wait
  "Wait up to :timeout-ms (default 1000, max 300000). Returns compact status; :detailed? opts into the full record."
  ([handle] (wait handle {}))
  ([handle opts]
   (let [detailed? (detailed-option! opts #{:timeout-ms :detailed?})
         [manager registry] (environment)
         record (await-job manager (:session-id registry) (job-id registry handle) (get opts :timeout-ms 1000))]
     (if detailed? record (summary record)))))
(defn output
  "Read output by :offset, {:after (:cursor previous-page)}, or {:tail? true}. :limit defaults to 4096 (max 32768) characters. Cursors are independent, reusable, and job-scoped; tail reads the end of retained output."
  ([handle] (output handle {}))
  ([handle opts] (let [[manager registry] (environment)] (output-job manager (:session-id registry) (job-id registry handle) opts))))
(defn result
  "Return a successful job's native value without waiting. Failed/cancelled/unfinished jobs throw."
  [handle]
  (let [[manager registry] (environment) sid (:session-id registry) id (job-id registry handle)
        record (store/job (:store manager) sid id)]
    (value/check! (= :completed (:status record)) :job-not-completed
                  "Job has not completed successfully; inspect its status and error" {:job record})
    (let [native-value (capabilities/result-value registry (:result-id record))]
      (store/acknowledge-jobs! (:store manager) sid [id])
      native-value)))
