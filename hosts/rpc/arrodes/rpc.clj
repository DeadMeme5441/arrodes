(ns arrodes.rpc
  "Bounded, versioned JSONL transport for embedding an Arrodes runtime."
  (:require [arrodes.platform :as u]
            [arrodes.value :as value]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedReader Writer)
           (java.util.concurrent ArrayBlockingQueue Callable FutureTask RejectedExecutionException
                                 ThreadFactory ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))

(def protocol-version 1)
(def version "0.1.5")
(def ^:private default-workers 8)
(def ^:private default-queue-size 128)
(def ^:private default-line-limit (* 16 1024 1024))
(def ^:private default-host-limit 128)
(def ^:private default-host-timeout-ms 300000)
(def ^:private supported-host-kinds
  #{:select :confirm :input :notify :capability :renderer :widget :set-widget :render :editor})

(defn- daemon-thread-factory [connection-id]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str "arrodes-rpc-" connection-id "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- executor [workers queue-size connection-id]
  (ThreadPoolExecutor. workers workers 0 TimeUnit/MILLISECONDS
                       (ArrayBlockingQueue. queue-size)
                       (daemon-thread-factory connection-id)
                       (ThreadPoolExecutor$AbortPolicy.)))

(defn- option-int [options key default minimum maximum]
  (let [value (get options key default)]
    (value/check! (and (integer? value) (<= minimum value maximum)) :invalid-options
                  (str (name key) " is outside its supported range")
                  {:option key :minimum minimum :maximum maximum})
    (int value)))

(defn- qualified-name [value]
  (if-let [namespace (namespace value)]
    (str namespace "/" (name value))
    (name value)))

(defn- wire-key [value]
  (cond
    (keyword? value) (qualified-name value)
    (symbol? value) (str value)
    (string? value) value
    :else (str value)))

(defn- wire-value [value]
  (cond
    (keyword? value) (qualified-name value)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[key item]] [(wire-key key) (wire-value item)])) value)
    (set? value) (mapv wire-value (sort-by pr-str value))
    (sequential? value) (mapv wire-value value)
    :else value))

(defn- error-value [error]
  (let [mapped (if (instance? Throwable error)
                 (value/error-map error)
                 {:code "internal-error" :message (str error) :data {}})]
    (update mapped :data #(or % {}))))

(defn json-str
  "Encode a command/public value without losing namespaced keys or scalar types."
  [public-value value]
  (json/write-str (-> (public-value value) value/redact wire-value)))

(defn- emit-line! [context line]
  (let [^Writer writer (:writer context)]
    (locking (:write-lock context)
      (.write writer ^String line)
      (.write writer "\n")
      (.flush writer))))

(defn- emit! [context value]
  (emit-line! context (json-str (:public-value context) value)))

(defn- emit-error! [context id error]
  (emit! context {:type "response" :id id :error (error-value error)}))

(defn- protocol-error! [context id code message data]
  (emit-error! context id (ex-info message (assoc (or data {}) :error/code code))))

(defn- present-event [context event]
  (let [data (:data event)
        type (or (:event/type data) (:type data) (:type event))
        capability (or (:name data) (:tool/name data) (:tool-call/name data))
        renderers (get @(:renderers context) (:session-id event))
        render (or (get renderers (wire-key type)) (get renderers capability))]
    (if-not render
      event
      (try
        (let [rendered (render event)
              {:keys [writer buffer]} (u/bounded-writer 32768)]
          (binding [*out* writer *print-length* 200 *print-level* 20]
            (cond
              (string? rendered) (print rendered)
              (sequential? rendered) (doseq [line (take 200 rendered)] (println line))
              (some? rendered) (pr rendered)))
          (assoc event :presentation {:content (str buffer)}))
        (catch Throwable error
          (assoc event :presentation {:error (or (ex-message error) "Extension renderer failed")}))))))

(defn- register-renderer! [context {:keys [session-id id render remove?]}]
  (value/check! (and (string? id) (not (str/blank? id))) :invalid-renderer
                "Renderer registration requires a non-empty id" {})
  (value/check! (or remove? (fn? render)) :invalid-renderer
                "Renderer registration requires a native function" {:id id})
  (swap! (:renderers context) update session-id
         (fn [renderers]
           (if remove? (dissoc renderers id) (assoc renderers id render))))
  {:renderer-id id :active? (not remove?)})

(defn- read-line-bounded
  "Read strict LF-delimited JSONL. Returns ::eof or ::too-long as sentinels."
  [^BufferedReader reader limit]
  (let [buffer (StringBuilder.)]
    (loop [length 0 overflow? false]
      (let [character (.read reader)]
        (cond
          (= -1 character)
          (cond overflow? ::too-long
                (zero? length) ::eof
                :else (str buffer))

          (= 10 character)
          (if overflow?
            ::too-long
            (let [line (str buffer)]
              (if (and (pos? (count line)) (= \return (.charAt line (dec (count line)))))
                (subs line 0 (dec (count line)))
                line)))

          overflow? (recur length true)
          (>= length limit) (recur length true)
          :else (do (.append buffer (char character))
                    (recur (inc length) false)))))))

(defn- reserve-host! [context id promise]
  (locking (:pending-host context)
    (value/check! (< (count @(:pending-host context)) (:host-limit context))
                  :host-busy "Too many host requests are awaiting responses"
                  {:maximum (:host-limit context)})
    (swap! (:pending-host context) assoc id promise)))

(defn- await-host! [context id promise timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (try
      (loop []
        (when (or @(:closing? context) (.isInterrupted (Thread/currentThread)))
          (value/fail! :cancelled "Host request was cancelled"
                       {:host-request-id id :local-host-cancel? true}))
        (let [remaining (- deadline (System/currentTimeMillis))]
          (when-not (pos? remaining)
            (value/fail! :host-timeout "Host did not answer in time"
                         {:host-request-id id :timeout-ms timeout-ms :local-host-cancel? true}))
          (let [answer (deref promise (long (min remaining 1000)) ::waiting)]
            (if (= ::waiting answer)
              (recur)
              (case (:status answer)
                :ok (:result answer)
                :cancelled (value/fail! :cancelled "Host request was cancelled" {:host-request-id id})
                :error (let [remote (:error answer)]
                         (value/fail! (keyword (or (:code remote) "host-error"))
                                      (or (:message remote) "Host request failed")
                                      (assoc (or (:data remote) {}) :host-request-id id)))
                (value/fail! :invalid-host-response "Host returned an invalid response" {:host-request-id id}))))))
      (finally
        (swap! (:pending-host context) dissoc id)))))

(defn- host-request! [context request]
  (let [kind (let [value (:kind request)] (if (string? value) (keyword value) value))]
    (value/check! (contains? supported-host-kinds kind) :unsupported-host-request
                  "The connected host does not support this UI request"
                  {:kind kind :supported (sort (map name supported-host-kinds))})
    (cond
      (= :renderer kind) (register-renderer! context request)
      (and @(:closing? context) (:remove? request)
           (contains? #{:widget :set-widget} kind)) {:widget-id (:id request) :visible? false}
      :else
      (let [id (u/id)
            response (promise)
            requested-timeout (:timeout-ms request)
            timeout-ms (if (and (integer? requested-timeout) (pos? requested-timeout))
                         (min requested-timeout (:host-timeout-ms context))
                         (:host-timeout-ms context))]
        (reserve-host! context id response)
        (try
          (emit! context (cond-> {:type "host-request"
                                  :id id
                                  :request (assoc request :kind kind :timeout-ms timeout-ms)}
                           (.get ^ThreadLocal (:request-id context))
                           (assoc :request-id (.get ^ThreadLocal (:request-id context)))))
          (await-host! context id response timeout-ms)
          (catch Throwable error
            (swap! (:pending-host context) dissoc id)
            (if (:local-host-cancel? (ex-data error))
              (do
                (emit! context {:type "host-cancel" :id id
                                :reason (:error/code (ex-data error))})
                (throw (ex-info (ex-message error)
                                (dissoc (ex-data error) :local-host-cancel?)
                                error)))
              (throw error))))))))

(defn- resolve-runtime-api []
  {:open! (requiring-resolve 'arrodes.runtime/open!)
   :close! (requiring-resolve 'arrodes.runtime/close!)
   :subscribe! (requiring-resolve 'arrodes.runtime/subscribe!)})

(defn- initialize! [context params]
  (value/check! (map? params) :invalid-params "Initialize params must be an object" {})
  (locking (:initialize-lock context)
    (value/check! (= :new @(:initialize-state context)) :already-initialized
                  "This connection may initialize a runtime only once"
                  {:state @(:initialize-state context)})
    (reset! (:initialize-state context) :opening)
    (try
      (let [runtime-api (resolve-runtime-api)
            fixed (select-keys (:open-options context)
                               [:cwd :home :data-dir :memory? :settings :trust :complete-fn])
            supplied (select-keys params [:cwd :home :data-dir :memory? :settings :trust])
            runtime-options (-> (merge fixed supplied)
                                (update :cwd #(or % (System/getProperty "user.dir")))
                                (assoc :home (u/home-dir (merge fixed supplied))
                                       :ui! #(host-request! context %)
                                       :command! (:dispatch context)))
            runtime ((:open! runtime-api) runtime-options)]
        (reset! (:runtime-api context) runtime-api)
        (reset! (:runtime context) runtime)
        (let [unsubscribe ((:subscribe! runtime-api)
                           runtime
                           (fn [event]
                             (try
                               (emit! context (cond-> {:type "event" :event (present-event context event)}
                                                (.get ^ThreadLocal (:request-id context))
                                                (assoc :request-id (.get ^ThreadLocal (:request-id context)))))
                               (catch Throwable diagnostic
                                 (binding [*out* *err*]
                                   (println "RPC event serialization failed:" (ex-message diagnostic)))))))]
          (reset! (:unsubscribe context) unsubscribe))
        (reset! (:initialize-state context) :open)
        (deliver (:initialize-done context) :open)
        (assoc ((:dispatch context) runtime "runtime.inspect" {})
               :connection-id (:connection-id context)))
      (catch Throwable error
        (reset! (:initialize-state context) :failed)
        (deliver (:initialize-done context) :failed)
        (throw error)))))

(defn- record-attachment! [context method params result]
  (let [session-id (:session-id params)
        capability-name (:name params)]
    (case method
      "capability.attach"
      (swap! (:attachments context) update session-id (fnil conj #{}) capability-name)

      "capability.detach"
      (swap! (:attachments context)
             (fn [owned]
               (let [remaining (disj (get owned session-id #{}) capability-name)]
                 (if (seq remaining) (assoc owned session-id remaining) (dissoc owned session-id)))))
      nil)
    result))

(defn- invoke-request! [context request]
  (let [method (:method request)
        params (or (:params request) {})]
    (when (= :opening @(:initialize-state context))
      (deref (:initialize-done context) (long (:host-timeout-ms context)) :timeout))
    (value/check! (= :open @(:initialize-state context)) :not-initialized
                  "Send initialize and await its response before invoking commands"
                  {:state @(:initialize-state context)})
    (value/check! (map? params) :invalid-params "Request params must be an object" {})
    (let [params (if (contains? #{"capability.attach" "capability.detach"} method)
                   (assoc params :connection-id (:connection-id context))
                   params)
          result (record-attachment! context method params
                                     ((:dispatch context) @(:runtime context) method params))]
      (if (= "event.replay" method)
        (update result :events #(mapv (partial present-event context) %))
        result))))

(defn- respond-once! [context id responded value error]
  (when-not (.get ^AtomicBoolean responded)
    (let [response (if error
                     {:type "response" :id id :error (error-value error)}
                     {:type "response" :id id :result value})
          line (try
                 (json-str (:public-value context) response)
                 (catch Throwable _
                   (json/write-str
                    {:type "response" :id id
                     :error {:code "serialization-error"
                             :message "Command response could not be serialized"
                             :data {:unknown-outcome? true}}})))]
      (when (.compareAndSet ^AtomicBoolean responded false true)
        (emit-line! context line)))))

(defn- retire-request! [context id token]
  (swap! (:inflight context)
         (fn [requests]
           (if (identical? token (:token (get requests id)))
             (dissoc requests id)
             requests))))

(defn- request-task [context request responded phase token]
  (let [work
        (bound-fn []
          (when (compare-and-set! phase :queued :running)
            (let [id (:id request)
                  ^ThreadLocal correlation (:request-id context)]
              (.set correlation id)
              (try
                (let [result (if (= "initialize" (:method request))
                               (initialize! context (or (:params request) {}))
                               (invoke-request! context request))]
                  (respond-once! context id responded result nil))
                (catch Throwable error
                  (respond-once! context id responded nil error))
                (finally
                  (reset! phase :done)
                  (.remove correlation)
                  (retire-request! context id token))))))]
    (reify Callable
      (call [_] (work)))))

(defn- submit-request! [context request]
  (let [id (:id request)
        method (:method request)]
    (value/check! (and (string? id) (not (str/blank? id))) :invalid-request
                  "Request id must be a non-empty string" {})
    (value/check! (and (string? method) (not (str/blank? method))) :invalid-request
                  "Request method must be a non-empty string" {:id id})
    (let [responded (AtomicBoolean. false)
          phase (atom :queued)
          token (Object.)
          task (FutureTask. ^Callable (request-task context request responded phase token))]
      (locking (:inflight context)
        (value/check! (not (contains? @(:inflight context) id)) :duplicate-id
                      "A request with this id has not finished" {:id id})
        (swap! (:inflight context) assoc id
               {:future task :responded responded :phase phase :token token}))
      (try
        (.execute ^ThreadPoolExecutor (:executor context) task)
        (catch RejectedExecutionException _
          (reset! phase :done)
          (retire-request! context id token)
          (respond-once! context id responded nil
                         (ex-info "The bounded request queue is full"
                                  {:error/code "server-busy"
                                   :maximum (:queue-size context)})))))))

(defn- cancel-request! [context id]
  (if-let [{:keys [future responded phase token]} (get @(:inflight context) id)]
    (when (.compareAndSet ^AtomicBoolean responded false true)
      (let [queued? (compare-and-set! phase :queued :cancelled)]
        (when-not queued? (compare-and-set! phase :running :cancelling))
        (.cancel ^java.util.concurrent.Future future true)
        (emit-error! context id
                     (ex-info "Request cancelled; accepted effects are not undone"
                              {:error/code "cancelled"}))
        (when queued?
          (.remove ^ThreadPoolExecutor (:executor context) ^Runnable future)
          (retire-request! context id token))))
    (protocol-error! context id "unknown-correlation" "No running request has this id" {:id id})))

(defn- accept-host-response! [context message]
  (let [id (:id message)]
    (if-let [waiting (get @(:pending-host context) id)]
      (cond
        (or (= "host-cancel" (:type message)) (true? (:cancelled message)))
        (deliver waiting {:status :cancelled})

        (contains? message :error)
        (deliver waiting {:status :error :error (:error message)})

        (contains? message :result)
        (deliver waiting {:status :ok :result (:result message)})

        :else
        (deliver waiting {:status :error
                          :error {:code "invalid-host-response"
                                  :message "host-response requires result, error, or cancelled"}}))
      (protocol-error! context id "unknown-correlation" "No host request has this id" {:id id}))))

(defn- cancel-host-requests! [context]
  (let [pending (locking (:pending-host context)
                  (let [value @(:pending-host context)]
                    (reset! (:pending-host context) {})
                    value))]
    (doseq [[id response] pending]
      (emit! context {:type "host-cancel" :id id :reason "connection-closing"})
      (deliver response {:status :cancelled}))))

(defn- cleanup-attachments! [context]
  (when-let [runtime @(:runtime context)]
    (doseq [[session-id names] @(:attachments context)
            capability-name names]
      (try
        (let [listed ((:dispatch context) runtime "capability.list" {:session-id session-id})
              descriptor (some #(when (= capability-name (:name %)) %) (:capabilities listed))]
          (when (= (:connection-id context) (:owner descriptor))
            ((:dispatch context) runtime "capability.detach"
                                 {:session-id session-id :name capability-name :connection-id (:connection-id context)})))
        (catch Throwable diagnostic
          (binding [*out* *err*]
            (println "RPC capability cleanup failed:" (ex-message diagnostic)))))))
  (reset! (:attachments context) {}))

(defn- settle! [context]
  (when (.compareAndSet ^AtomicBoolean (:settled? context) false true)
    (let [interrupted? (atom false)
          executor-terminated? (atom false)
          runtime-report (atom {:status :closed :initialized? false})]
      (reset! (:closing? context) true)
      (cancel-host-requests! context)
      (.shutdown ^ThreadPoolExecutor (:executor context))
      (let [terminated? (try
                          (.awaitTermination ^ThreadPoolExecutor (:executor context)
                                             (long (:settle-timeout-ms context)) TimeUnit/MILLISECONDS)
                          (catch InterruptedException _
                            (reset! interrupted? true)
                            false))]
        (reset! executor-terminated? terminated?)
        (when-not terminated?
          (doseq [[id {:keys [future responded]}] @(:inflight context)]
            (.cancel ^java.util.concurrent.Future future true)
            (respond-once! context id responded nil
                           (ex-info "Connection closed" {:error/code "cancelled"})))
          (.shutdownNow ^ThreadPoolExecutor (:executor context))
          (try
            (reset! executor-terminated?
                    (.awaitTermination ^ThreadPoolExecutor (:executor context)
                                       1000 TimeUnit/MILLISECONDS))
            (catch InterruptedException _ (reset! interrupted? true)))))
      (cleanup-attachments! context)
      (when-let [unsubscribe @(:unsubscribe context)]
        (try (unsubscribe) (catch Throwable _ nil)))
      (when-let [runtime @(:runtime context)]
        (try
          (reset! runtime-report ((:close! @(:runtime-api context)) runtime))
          (catch Throwable diagnostic
            (reset! runtime-report {:status :failed :error (error-value diagnostic)})
            (binding [*out* *err*]
              (println "RPC runtime cleanup failed:" (ex-message diagnostic))))))
      (reset! (:runtime context) nil)
      (reset! (:renderers context) {})
      (when @interrupted? (.interrupt (Thread/currentThread)))
      {:status (if (and @executor-terminated?
                        (= :closed (:status @runtime-report)))
                 :closed
                 :closing)
       :connection-id (:connection-id context)
       :executor-terminated? @executor-terminated?
       :runtime @runtime-report})))

(defn- shutdown! [context id]
  (if (and id (not (and (string? id) (not (str/blank? id)))))
    (protocol-error! context id "invalid-request" "Shutdown id must be a non-empty string" {})
    (let [result (settle! context)]
      (emit! context {:type "response" :id id :result result}))))

(defn- handle-message! [context message]
  (value/check! (map? message) :invalid-request "Each JSONL record must be an object" {})
  (case (:type message)
    "request" (if (= "shutdown" (:method message))
                :shutdown
                (do (submit-request! context message) :continue))
    "cancel" (do (cancel-request! context (:id message)) :continue)
    "host-response" (do (accept-host-response! context message) :continue)
    "host-cancel" (do (accept-host-response! context message) :continue)
    (value/fail! :invalid-request "Unknown transport message type"
                 {:type (:type message)
                  :supported ["request" "cancel" "host-response" "host-cancel"]})))

(defn serve!
  "Serve protocol-1 JSONL on stdin/stdout until shutdown or EOF.

  The first emitted record is a hello. The first request must be initialize; it is
  the sole operation that opens the runtime. Domain requests then use
  {type:\"request\",id,method,params}. Client cancellation uses
  {type:\"cancel\",id}. Runtime UI is represented by correlated host-request
  records answered with host-response or host-cancel."
  ([] (serve! {}))
  ([open-options]
   (let [wire-out (or (:out open-options) *out*)
         wire-in (or (:in open-options) *in*)
         diagnostics (or (:err open-options) *err*)
         redirect-system-out? (boolean
                               (get open-options :redirect-system-out?
                                    (nil? (:out open-options))))
         original-system-out System/out
         context-holder (atom nil)]
     (when redirect-system-out? (System/setOut System/err))
     (try
       (binding [*out* diagnostics *err* diagnostics]
         (let [connection-id (u/id)
               workers (option-int open-options :max-concurrency default-workers 1 64)
               queue-size (option-int open-options :max-queue default-queue-size 1 4096)
               public-value (requiring-resolve 'arrodes.commands/public-value)
               dispatch (requiring-resolve 'arrodes.commands/dispatch!)
               context {:writer (io/writer wire-out)
                        :write-lock (Object.)
                        :public-value public-value
                        :dispatch dispatch
                        :open-options open-options
                        :connection-id connection-id
                        :request-id (ThreadLocal.)
                        :executor (executor workers queue-size connection-id)
                        :queue-size queue-size
                        :line-limit (option-int open-options :max-line-bytes default-line-limit
                                                1024 (* 64 1024 1024))
                        :host-limit (option-int open-options :max-host-requests default-host-limit 1 4096)
                        :host-timeout-ms (option-int open-options :host-timeout-ms
                                                     default-host-timeout-ms 1000 900000)
                        :settle-timeout-ms (option-int open-options :settle-timeout-ms
                                                       30000 100 300000)
                        :runtime (atom nil)
                        :runtime-api (atom nil)
                        :unsubscribe (atom nil)
                        :initialize-state (atom :new)
                        :initialize-done (promise)
                        :initialize-lock (Object.)
                        :inflight (atom {})
                        :pending-host (atom {})
                        :attachments (atom {})
                        :renderers (atom {})
                        :closing? (atom false)
                        :settled? (AtomicBoolean. false)}
               reader (BufferedReader. (io/reader wire-in))]
           (reset! context-holder context)
           (emit! context {:type "hello" :protocol protocol-version :version version
                           :connection-id connection-id})
           (loop []
             (let [line (read-line-bounded reader (:line-limit context))]
               (cond
                 (= ::eof line) (settle! context)
                 (= ::too-long line)
                 (do
                   (protocol-error! context nil "line-too-large"
                                    "JSONL record exceeds the connection limit"
                                    {:maximum (:line-limit context)})
                   (recur))
                 (str/blank? line) (recur)
                 :else
                 (let [parsed (try
                                {:message (json/read-str line :key-fn keyword)}
                                (catch Throwable error
                                  (emit-error! context nil error)
                                  {:parse-error? true}))]
                   (if (:parse-error? parsed)
                     (recur)
                     (let [message (:message parsed)
                           action (try
                                    (handle-message! context message)
                                    (catch Throwable error
                                      (emit-error! context (:id message) error)
                                      :continue))]
                       (if (= :shutdown action)
                         (shutdown! context (:id message))
                         (recur))))))))))
       (finally
         (when-let [context @context-holder] (settle! context))
         (when redirect-system-out? (System/setOut original-system-out)))))))
