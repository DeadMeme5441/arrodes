(ns arrodes.tui-rpc
  "Protocol-1 JSONL transport for the owned Arrodes host process."
  (:require [clojure.string :as str]))

(def ^:private protocol-version 1)
(def ^:private default-line-limit (* 16 1024 1024))
(def ^:private default-request-timeout-ms 30000)
(def ^:private default-handshake-timeout-ms 15000)
(def ^:private default-shutdown-timeout-ms 5000)

(def ^:private child-process (js/require "child_process"))
(def ^:private string-decoder (js/require "string_decoder"))
(def ^:private buffer-class js/Buffer)

(defn- field [m k]
  (let [string-key (name k)]
    (cond
      (contains? m k) (get m k)
      (contains? m string-key) (get m string-key)
      :else nil)))

(defn- qualified-name [value]
  (if-let [n (namespace value)]
    (str n "/" (name value))
    (name value)))

(defn- wire-value [value]
  (cond
    (keyword? value) (qualified-name value)
    (symbol? value) (str value)
    (map? value) (let [object #js {}]
                   (doseq [[k v] value]
                     (aset object (cond
                                    (keyword? k) (qualified-name k)
                                    (symbol? k) (str k)
                                    :else (str k))
                           (wire-value v)))
                   object)
    (set? value) (into-array (map wire-value value))
    (sequential? value) (into-array (map wire-value value))
    :else value))

(defn- deferred []
  (let [resolve* (atom nil)
        reject* (atom nil)
        settled? (atom false)
        promise (js/Promise. (fn [resolve reject]
                               (reset! resolve* resolve)
                               (reset! reject* reject)))]
    {:promise promise
     :settled? settled?
     :resolve (fn [value]
                (when (compare-and-set! settled? false true)
                  (@resolve* value)))
     :reject (fn [error]
               (when (compare-and-set! settled? false true)
                 (@reject* error)))}))

(defn- resolve! [d value]
  ((:resolve d) value))

(defn- reject! [d error]
  ((:reject d) error))

(defn- error
  ([code message] (error code message {}))
  ([code message data]
   (ex-info message (assoc data :code code))))

(declare force-stop!)

(defn- safe-call [callback & args]
  (when callback
    (try
      (apply callback args)
      (catch :default _ nil))))

(defn- status! [client status & [details]]
  (swap! (:state client) assoc :status status)
  (safe-call (get-in client [:options :on-status]) status details))

(defn- next-id! [client]
  (str (:id-prefix client) "-" (swap! (:next-id client) inc)))

(defn- byte-length [text]
  (.byteLength buffer-class text "utf8"))

(defn- remember-expired [expired id]
  (let [items (conj (vec (remove #(= id %) expired)) id)]
    (if (> (count items) 1024)
      (subvec items (- (count items) 1024))
      items)))

(defn- settle-pending! [client id value remote-error]
  (when-let [{:keys [timer deferred method]} (get-in @(:state client) [:pending id])]
    (when timer (js/clearTimeout timer))
    (swap! (:state client) update :pending dissoc id)
    (if remote-error
      (let [code (or (field remote-error :code) "remote-error")
            message (or (field remote-error :message) "RPC request failed")
            data (field remote-error :data)
            failure (error code message
                           {:remote data
                            :request-id id
                            :unknown-outcome? (boolean (field data :unknown-outcome?))})]
        (when (= method "initialize")
          (swap! (:state client) assoc :failure failure))
        (reject! deferred failure))
      (resolve! deferred value))
    true))

(defn- reject-pending! [client code message]
  (let [pending (:pending @(:state client))]
    (swap! (:state client) assoc :pending {})
    (doseq [[id {:keys [timer deferred mutation? sent? method]}] pending]
      (when timer (js/clearTimeout timer))
      (reject! deferred
               (error code message
                      {:request-id id
                       :method method
                       :unknown-outcome? (boolean (and mutation? sent?))})))))

(defn- terminal! [client generation details]
  (let [snapshot @(:state client)
        failure (or (:failure snapshot)
                    (when-not (= :closing (:status snapshot))
                      (error "connection-closed"
                             (if (= :starting (:status snapshot))
                               "RPC process exited during startup" "RPC process exited")
                             details)))]
    (when (and (= generation (:generation snapshot))
               (not (:terminal? snapshot)))
      (swap! (:state client) assoc :terminal? true :child nil :failure failure)
      (when-let [hello (:hello snapshot)]
        (reject! hello (error "connection-closed" "RPC process closed before handshake" details)))
      (reject-pending! client "connection-closed" "RPC process closed")
      (when-let [exit (:exit snapshot)]
        (resolve! exit details))
      (if (= :closing (:status snapshot))
        (status! client :closed details)
        (status! client :disconnected (assoc details :error failure))))))

(defn- protocol-failure! [client generation code message data]
  (let [snapshot @(:state client)
        failure (error code message data)]
    (when (and (= generation (:generation snapshot))
               (not (:terminal? snapshot))
               (not (:protocol-failed? snapshot)))
      (swap! (:state client) assoc :protocol-failed? true :failure failure)
      (safe-call (get-in client [:options :on-protocol-error]) failure)
      (reject-pending! client code message)
      (status! client :disconnected {:protocol-error code :error failure})
      (when-let [hello (:hello snapshot)] (reject! hello failure))
      (when-let [child (:child snapshot)]
        (.catch (force-stop! client child (:exit snapshot))
                (fn [failure] (safe-call (get-in client [:options :on-protocol-error]) failure)))))))

(defn- handle-message! [client generation message]
  (case (field message :type)
    "hello"
    (let [hello (:hello @(:state client))
          version (field message :protocol)]
      (if (not= protocol-version version)
        (protocol-failure! client generation "unsupported-protocol"
                           (str "Expected protocol " protocol-version ", received " version)
                           {:protocol version})
        (resolve! hello message)))

    "response"
    (let [id (field message :id)]
      (if (contains? (get @(:state client) :pending) id)
        (if (contains? message "error")
          (settle-pending! client id nil (field message :error))
          (settle-pending! client id (field message :result) nil))
        (when (some #(= id %) (:expired @(:state client)))
          (swap! (:state client) update :expired #(vec (remove (fn [x] (= id x)) %))))))

    "event"
    (safe-call (get-in client [:options :on-event]) (field message :event))

    "host-request"
    (safe-call (get-in client [:options :on-host-request])
               {:id (field message :id)
                :request (field message :request)
                :request-id (field message :request-id)})

    "host-cancel"
    (safe-call (get-in client [:options :on-host-cancel])
               (field message :id) (field message :reason))

    (protocol-failure! client generation "invalid-message"
                       "RPC process emitted an unknown message type"
                       {:type (field message :type)})))

(defn- parse-line! [client generation line]
  (let [line (if (and (pos? (count line)) (= "\r" (.slice line -1)))
               (.slice line 0 -1)
               line)]
    (when-not (str/blank? line)
      (if (> (byte-length line) (:line-limit client))
        (protocol-failure! client generation "line-too-large"
                           "RPC process emitted an oversized JSONL record"
                           {:maximum (:line-limit client)})
        (try
          (handle-message! client generation
                           (js->clj (js/JSON.parse line)))
          (catch :default parse-error
            (protocol-failure! client generation "invalid-json"
                               "RPC process emitted invalid JSON"
                               {:cause (.-message parse-error)})))))))

(defn- consume-text! [client generation text]
  (when (and (= generation (:generation @(:state client)))
             (not (:terminal? @(:state client))))
    (let [combined (str (:read-buffer @(:state client)) text)]
      (loop [remaining combined]
        (let [newline (.indexOf remaining "\n")]
          (if (neg? newline)
            (if (> (byte-length remaining) (:line-limit client))
              (protocol-failure! client generation "line-too-large"
                                 "RPC process emitted an oversized JSONL record"
                                 {:maximum (:line-limit client)})
              (swap! (:state client) assoc :read-buffer remaining))
            (do
              (parse-line! client generation (.slice remaining 0 newline))
              (recur (.slice remaining (inc newline))))))))))

(defn- write-message! [client message]
  (try
    (let [{:keys [child terminal? status]} @(:state client)
          line (str (js/JSON.stringify (wire-value message)) "\n")]
      (cond
        (or terminal? (nil? child) (contains? #{:closed :disconnected} status))
        (js/Promise.reject (error "not-connected" "RPC process is not connected" {}))

        (> (byte-length line) (:line-limit client))
        (js/Promise.reject
         (error "line-too-large" "RPC request exceeds the JSONL record limit"
                {:maximum (:line-limit client)}))

        :else
        (js/Promise.
         (fn [resolve reject]
           (let [failure (fn [cause]
                           (reject (error "write-failed" "Could not write to RPC process"
                                          {:cause (.-message cause)})))]
             (try
               (.write (.-stdin child) line "utf8"
                       (fn [cause] (if cause (failure cause) (resolve true))))
               (catch :default cause (failure cause))))))))
    (catch :default cause
      (js/Promise.reject
       (error "serialization-failed" "RPC message could not be encoded"
              {:cause (.-message cause)})))))

(defn request!
  "Send a correlated request. Rejections after a mutation was written carry
  :unknown-outcome? when the host may have accepted the effect."
  ([client method params]
   (request! client method params {}))
  ([client method params {:keys [timeout-ms mutation? allow-starting? allow-closing?]}]
   (let [timeout-ms (or timeout-ms
                        (get-in client [:options :request-timeout-ms])
                        default-request-timeout-ms)
         status (:status @(:state client))
         allowed? (or (= :ready status)
                      (and allow-starting? (= :starting status))
                      (and allow-closing? (= :closing status)))]
     (cond
       (not allowed?)
       (js/Promise.reject (or (:failure @(:state client))
                             (error "not-connected" "RPC client is not ready" {:status status})))

       (or (not (number? timeout-ms)) (not (js/Number.isFinite timeout-ms)) (not (pos? timeout-ms)))
       (js/Promise.reject (error "invalid-deadline" "RPC requests require a finite positive deadline"
                                 {:timeout-ms timeout-ms}))

       :else
       (let [id (next-id! client)
             d (deferred)
             pending {:deferred d :method method :mutation? (boolean mutation?) :sent? false}
             timer (js/setTimeout
                    (fn []
                      (when-let [current (get-in @(:state client) [:pending id])]
                        (swap! (:state client)
                               (fn [state]
                                 (-> state
                                     (update :pending dissoc id)
                                     (update :expired remember-expired id))))
                        (let [unknown? (boolean (and (:mutation? current) (:sent? current)))]
                          (reject! (:deferred current)
                                   (error "deadline-exceeded" "RPC request deadline elapsed"
                                          {:request-id id :method method
                                           :timeout-ms timeout-ms
                                           :unknown-outcome? unknown?})))
                        (.catch (write-message! client {:type "cancel" :id id})
                                (fn [_] nil))))
                    timeout-ms)]
         (swap! (:state client) assoc-in [:pending id] (assoc pending :timer timer))
         (swap! (:state client) assoc-in [:pending id :sent?] true)
         (-> (write-message! client {:type "request" :id id :method method :params (or params {})})
             (.catch (fn [write-error]
                       (when-let [current (get-in @(:state client) [:pending id])]
                         (when-let [request-timer (:timer current)] (js/clearTimeout request-timer))
                         (swap! (:state client) update :pending dissoc id)
                         (let [write-started? (= "write-failed" (:code (ex-data write-error)))
                               unknown? (boolean (and (:mutation? current) write-started?))]
                           (reject! (:deferred current)
                                    (error "write-failed" "RPC request could not be written"
                                           {:request-id id :method method
                                            :cause (.-message write-error)
                                            :unknown-outcome? unknown?})))))))
         (:promise d))))))

(defn cancel-method!
  "Cancel outstanding requests of one explicitly selected action; never resend them."
  [client method]
  (js/Promise.all
   (clj->js (for [[id request] (:pending @(:state client)) :when (= method (:method request))]
              (write-message! client {:type "cancel" :id id})))))

(defn host-response!
  ([client id result]
   (write-message! client {:type "host-response" :id id :result result}))
  ([client id result remote-error]
   (write-message! client (if remote-error
                            {:type "host-response" :id id :error remote-error}
                            {:type "host-response" :id id :result result}))))

(defn host-cancel! [client id]
  (write-message! client {:type "host-cancel" :id id}))

(defn create!
  "Create an unstarted owned-process transport. Options include :command argv,
  :process-cwd, :initialize and event/reverse-request callbacks."
  [options]
  (let [command (vec (or (:command options) ["clojure" "-Srepro" "-M:host"]))
        requested-limit (or (:line-limit options) default-line-limit)]
    (when-not (and (seq command) (every? #(and (string? %) (not (str/blank? %))) command))
      (throw (error "invalid-command" "RPC command must be a non-empty argv vector" {})))
    (when-not (and (number? requested-limit) (pos? requested-limit))
      (throw (error "invalid-line-limit" "RPC line limit must be positive"
                    {:line-limit requested-limit})))
    {:options options
     :command command
     :line-limit (min default-line-limit requested-limit)
     :id-prefix (str (.toString (js/Date.now) 36) "-" (.toString (rand-int 1679616) 36))
     :next-id (atom 0)
     :state (atom {:status :new :generation 0 :terminal? true :child nil
                   :pending {} :expired [] :read-buffer "" :hello nil :exit nil})}))

(defn start!
  "Spawn, await protocol hello, and initialize the runtime."
  [client]
  (let [snapshot @(:state client)]
    (cond
      (= :ready (:status snapshot)) (js/Promise.resolve (:initialize-result snapshot))
      (= :starting (:status snapshot)) (:start-promise snapshot)
      (= :closing (:status snapshot)) (js/Promise.reject (error "closing" "RPC client is closing" {}))
      (and (:child snapshot) (not (:terminal? snapshot)))
      (js/Promise.reject (error "closing" "The previous RPC process has not confirmed exit" {}))
      :else
      (let [generation (inc (:generation snapshot))
            hello (deferred)
            exit (deferred)
            argv (:command client)
            command (first argv)
            args (into-array (rest argv))
            environment (js/Object.assign #js {} (.-env js/process)
                                          (wire-value (or (get-in client [:options :env]) {})))
            spawn-options #js {:cwd (or (get-in client [:options :process-cwd])
                                        (.cwd js/process))
                               :env environment
                               :detached (not= "win32" (.-platform js/process))
                               :stdio #js ["pipe" "pipe" "pipe"]}
            child (.spawn child-process command args spawn-options)
            decoder-constructor (.-StringDecoder string-decoder)
            stdout-decoder (js/Reflect.construct decoder-constructor #js ["utf8"])
            stderr-decoder (js/Reflect.construct decoder-constructor #js ["utf8"])]
        (swap! (:state client) assoc
               :status :starting :generation generation :terminal? false
               :protocol-failed? false :child child :pending {} :expired []
               :read-buffer "" :hello hello :exit exit :stop-promise nil :failure nil)
        (status! client :starting {:generation generation})
        (.on (.-stdin child) "error"
             (fn [stream-error]
               (protocol-failure! client generation "write-failed"
                                  (.-message stream-error) {})))
        (.on (.-stdout child) "data"
             (fn [chunk]
               (consume-text! client generation (.write stdout-decoder chunk))))
        (.on (.-stdout child) "end"
             (fn []
               (let [tail (.end stdout-decoder)]
                 (consume-text! client generation tail)
                 (let [remaining (:read-buffer @(:state client))]
                   (when-not (str/blank? remaining)
                     (parse-line! client generation remaining))))))
        (.on (.-stderr child) "data"
             (fn [chunk]
               (safe-call (get-in client [:options :on-stderr])
                          (.write stderr-decoder chunk))))
        (.on (.-stderr child) "end"
             (fn []
               (let [tail (.end stderr-decoder)]
                 (when-not (str/blank? tail)
                   (safe-call (get-in client [:options :on-stderr]) tail)))))
        (.once child "error"
               (fn [process-error]
                 (if (and (number? (.-pid child)) (pos? (.-pid child)))
                   (protocol-failure! client generation "process-error"
                                      (.-message process-error) {})
                   (terminal! client generation
                              {:code nil :signal nil :spawn-error (.-message process-error)}))))
        (.once child "close"
               (fn [code signal]
                 (terminal! client generation {:code code :signal signal})))
        (let [handshake-timer
              (js/setTimeout
               (fn []
                 (reject! hello (error "handshake-timeout" "RPC hello deadline elapsed" {})))
               (or (get-in client [:options :handshake-timeout-ms]) default-handshake-timeout-ms))
              start-promise
              (-> (:promise hello)
                  (.then (fn [_]
                           (js/clearTimeout handshake-timer)
                           (request! client "initialize" (or (get-in client [:options :initialize]) {})
                                     {:timeout-ms (or (get-in client [:options :initialize-timeout-ms])
                                                      default-request-timeout-ms)
                                      :allow-starting? true})))
                  (.then (fn [result]
                           (let [current @(:state client)]
                             (when-not (and (= generation (:generation current))
                                            (= :starting (:status current))
                                            (not (:terminal? current))
                                            (not (:protocol-failed? current)))
                               (throw (or (:failure current)
                                          (error "connection-closed" "RPC startup was interrupted"
                                                 {:status (:status current)})))))
                           (swap! (:state client) assoc :initialize-result result)
                           (status! client :ready {:generation generation})
                           result))
                  (.catch (fn [start-error]
                            (js/clearTimeout handshake-timer)
                            (let [failure (or (:failure @(:state client)) start-error)]
                              (when (= generation (:generation @(:state client)))
                                (swap! (:state client) assoc :failure failure)
                                (when-not (:terminal? @(:state client))
                                  (.catch (force-stop! client child exit)
                                          (fn [failure] (safe-call (get-in client [:options :on-protocol-error]) failure)))))
                              (throw failure)))))]
          (swap! (:state client) assoc :start-promise start-promise)
          start-promise)))))

(defn- stop-owned-process! [child signal]
  (when-let [pid (.-pid child)]
    (when (and (number? pid) (pos? pid)
               (nil? (.-exitCode child)) (nil? (.-signalCode child)))
      (try
        (if (= "win32" (.-platform js/process))
          (.spawnSync child-process "taskkill" #js ["/PID" (str pid) "/T" "/F"]
                      #js {:stdio "ignore" :timeout 2000})
          ;; POSIX children start a new process group; only this owned group is
          ;; targeted, including shell descendants if graceful RPC close failed.
          (.kill js/process (- pid) signal))
        (catch :default failure
          (when-not (= "ESRCH" (.-code failure)) (throw failure)))))))

(defn- force-stop! [client child exit]
  (cond
    @(:settled? exit) (:promise exit)
    (:stop-promise @(:state client)) (:stop-promise @(:state client))
    :else
    (let [generation (:generation @(:state client))
          current-child? #(and (= generation (:generation @(:state client)))
                               (identical? child (:child @(:state client))))
          timers (atom [])
          clear! (fn [] (doseq [timer @timers] (js/clearTimeout timer)))
          attempt
          (js/Promise.
           (fn [resolve reject]
             (.then (:promise exit)
                    (fn [details] (clear!) (resolve details))
                    (fn [failure] (clear!) (reject failure)))
             (try
               (when (current-child?) (stop-owned-process! child "SIGTERM"))
               (swap! timers conj
                      (js/setTimeout
                       (fn []
                         (when (and (not @(:settled? exit)) (current-child?))
                           (try (stop-owned-process! child "SIGKILL")
                                (catch :default failure (clear!) (reject failure)))))
                       750))
               (swap! timers conj
                      (js/setTimeout
                       (fn []
                         (when-not @(:settled? exit)
                           (clear!)
                           (reject (error "process-exit-timeout"
                                          "The owned core process has not confirmed exit"
                                          {:pid (.-pid child) :status :closing}))))
                       5000))
               (catch :default failure (clear!) (reject failure)))))
          tracked (.catch attempt
                          (fn [failure]
                            (when (current-child?)
                              (swap! (:state client) dissoc :stop-promise))
                            (throw failure)))]
      (swap! (:state client) assoc :stop-promise tracked)
      tracked)))

(defn close!
  "Request finite shutdown, then terminate the owned child if it does not exit."
  [client]
  (let [{:keys [status child exit]} @(:state client)]
    (cond
      (contains? #{:new :closed} status)
      (do (status! client :closed {}) (js/Promise.resolve {:status :closed}))
      (= :closing status)
      (if (and child exit) (force-stop! client child exit)
          (js/Promise.resolve {:status :closed}))

      (nil? child)
      (do (terminal! client (:generation @(:state client)) {:code nil :signal nil})
          (js/Promise.resolve {:status :closed}))

      :else
      (do
        (status! client :closing {})
        (let [shutdown
              (if (= :ready status)
                (request! client "shutdown" {}
                          {:timeout-ms (or (get-in client [:options :shutdown-timeout-ms])
                                           default-shutdown-timeout-ms)
                           :allow-closing? true})
                (js/Promise.reject (error "not-ready" "RPC process did not finish startup" {})))]
          (-> shutdown
              (.then (fn [_]
                       (let [timer (atom nil)
                             elapsed (js/Promise.
                                      (fn [resolve _]
                                        (reset! timer
                                                (js/setTimeout #(resolve ::elapsed)
                                                               (or (get-in client [:options :shutdown-timeout-ms])
                                                                   default-shutdown-timeout-ms)))))]
                         (-> (js/Promise.race #js [(:promise exit) elapsed])
                             (.then #(if (= ::elapsed %)
                                       (force-stop! client child exit)
                                       %))
                             (.finally #(js/clearTimeout @timer)))))
                     (fn [_] (force-stop! client child exit)))
              (.then (fn [details]
                       (status! client :closed details)
                       {:status :closed :process details}))))))))
