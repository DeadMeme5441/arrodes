(ns arrodes.tui-app-test
  "Actual JVM regressions for commands issued during connection changes."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-rpc :as rpc]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path (js/require "node:path"))
(def ^:private os (js/require "node:os"))

(def ^:private initializing-host
  "(require '[arrodes.rpc :as rpc] '[arrodes.runtime :as runtime] '[arrodes.commands :as commands])
(let [open-runtime runtime/open!
      dispatch commands/dispatch!
      mode (atom nil)
      catalog-session (atom nil)
      entered (atom (promise))
      release (atom (promise))
      run-release (atom (promise))
      answer (fn [text] {:response/provider :test :response/model \"offline\"
                         :response/parts [{:part/type :text :text text}]
                         :response/finish-reason :stop :response/provider-data {}})
      arm! (fn [next-mode]
             (reset! mode next-mode)
             (reset! entered (promise))
             (reset! release (promise))
             (when (= :queue next-mode) (reset! run-release (promise))))
      await! (fn [gate label]
               (when (= ::timeout (deref gate 10000 ::timeout))
                 (throw (ex-info (str label \" timed out\") {:error/code :test-timeout}))))
      complete-fn (fn [_ _]
                    (when (= :queue @mode) (await! @run-release \"queued run release\"))
                    (answer \"done\"))
      wrapped
      (fn [rt method params]
        (case method
          \"session.name\"
          (case (:name params)
            \"__arm-configure\" (do (arm! :configure) (runtime/session rt (:session-id params)))
            \"__arm-reload\" (do (arm! :reload) (runtime/session rt (:session-id params)))
            \"__arm-ack\" (do (arm! :ack) (runtime/session rt (:session-id params)))
            \"__arm-queue\" (do (arm! :queue) (runtime/session rt (:session-id params)))
            \"__arm-models\" (do (arm! :models) (reset! catalog-session (:session-id params)) (runtime/session rt (:session-id params)))
            \"__arm-providers\" (do (arm! :providers) (reset! catalog-session (:session-id params)) (runtime/session rt (:session-id params)))
            \"__await-entered\" (do (await! @entered \"delayed request admission\")
                                    (runtime/session rt (:session-id params)))
            \"__release-run\" (do (deliver @run-release true) (runtime/session rt (:session-id params)))
            \"__release\" (do (deliver @release true) (runtime/session rt (:session-id params)))
            (dispatch rt method params))

          (\"model.list\" \"auth.status\")
          (let [result (dispatch rt method params)
                kind (if (= method \"model.list\") :models :providers)]
            (if (and (= kind @mode) (= @catalog-session (:session-id params)))
              (do (deliver @entered true) (await! @release \"catalog release\")
                  (reset! mode nil)
                  (update result kind conj {:provider :stale-provider :id \"stale-A\" :name \"Stale A\" :available? true}))
              result))

          \"session.configure\"
          (let [result (dispatch rt method params)]
            (if (= :configure @mode)
              (do (deliver @entered true) (await! @release \"configure release\")
                  (reset! mode nil) result)
              result))

          \"session.reload\"
          (let [result (dispatch rt method params)]
            (if (= :reload @mode)
              (do (deliver @entered true) (await! @release \"reload release\")
                  (reset! mode nil) result)
              result))

          \"session.run\"
          (let [result (dispatch rt method params)]
            (if (= :ack @mode)
              (do (deliver @entered true) (await! @release \"run receipt release\")
                  (reset! mode nil) result)
              result))

          \"operation.steer\"
          (let [result (dispatch rt method params)]
            (when (= :queue @mode)
              (deliver @entered true)
              (await! @run-release \"queued run release\")
              (loop [deadline (+ (System/currentTimeMillis) 10000)]
                (when (seq (runtime/pending rt (:session-id result)))
                  (when (> (System/currentTimeMillis) deadline)
                    (throw (ex-info \"queue delivery timed out\" {:error/code :test-timeout})))
                  (Thread/sleep 10)
                  (recur deadline)))
              (await! @release \"steering receipt release\")
              (reset! mode nil))
            result)

          (dispatch rt method params)))]
  (with-redefs [runtime/open!
                (fn [options]
                  (when-not ((:ui! options) {:kind :confirm :title \"Initialize test runtime\"})
                    (throw (ex-info \"Initialization declined\" {:code :declined})))
                  (open-runtime options))
                commands/dispatch! wrapped]
    (rpc/serve! {:complete-fn complete-fn})))
(shutdown-agents)
")

(defn- check! [condition message]
  (when-not condition (throw (js/Error. message))))

(defn- answer-initialization! [application]
  (let [answered (atom #{})]
    (add-watch (:state application) ::initialize
               (fn [_ _ _ state]
                 (doseq [{:keys [id request]} (:host-requests state)]
                   (when (and (= "Initialize test runtime" (:title request))
                              (not (contains? @answered id)))
                     (swap! answered conj id)
                     (-> (app/command! application :host-response {:id id :result true})
                         (.catch (fn [error]
                                   (js/console.error "Initialization reply failed:" (ex-message error)))))))))))

(defn- create-during-connection! [application connection name]
  (let [creation (-> (js/Promise.resolve nil)
                     (.then (fn [] (app/command! application :new-session {:name name})))
                     (.then (fn [_] (app/command! application :submit {:text (str "First message: " name)}))))]
    (-> (js/Promise.all #js [connection creation])
        (.then (fn [_] (app/command! application :sessions)))
        (.then (fn [sessions]
                 (check! (= 1 (count (filter #(= name (:name %)) sessions)))
                         "A command during connection must create its session exactly once")
                 (check! (= name (get-in @(:state application) [:view :session :name]))
                         "The requested session must become usable after connection"))))))

(defn- rejects-with! [promise code]
  (.then promise
         (fn [_] (throw (js/Error. (str "Expected connection failure " code))))
         (fn [error]
           (check! (= code (:code (ex-data error)))
                   (str "Expected " code ", received " (ex-message error) " " (pr-str (ex-data error)))))))

(defn- await-process-exit! [application]
  (let [deadline (+ (js/Date.now) 10000)]
    (letfn [(poll []
              (cond
                (some-> @(:client application) :state deref :terminal?) (js/Promise.resolve nil)
                (> (js/Date.now) deadline) (js/Promise.reject (js/Error. "Failed startup did not release its process"))
                :else (-> (js/Promise. (fn [resolve] (js/setTimeout resolve 15))) (.then poll))))]
      (poll))))

(defn- control! [application name]
  (app/command! application :rename-session {:name name}))

(defn- until-state! [application predicate message]
  (let [deadline (+ (js/Date.now) 10000)]
    (letfn [(poll []
              (cond
                (predicate @(:state application)) (js/Promise.resolve nil)
                (> (js/Date.now) deadline) (js/Promise.reject (js/Error. message))
                :else (-> (js/Promise. (fn [resolve] (js/setTimeout resolve 10)))
                          (.then poll))))]
      (poll))))

(defn- delayed-navigation! [application arm action data target draft label]
  (-> (control! application arm)
      (.then
       (fn [_]
         (let [pending (app/command! application action data)]
           (-> (control! application "__await-entered")
               (.then (fn [_] (app/command! application :switch-session {:id target})))
               (.then (fn [_]
                        (swap! (:state application) assoc-in [:ui :draft] draft)
                        (control! application "__release")))
               (.then (fn [_] pending))
               (.then (fn [_]
                        (check! (= target (get-in @(:state application) [:view :session :id]))
                                (str label " refresh replaced newer navigation"))
                        (check! (= draft (get-in @(:state application) [:ui :draft]))
                                (str label " refresh transplanted another session draft"))))))))))

(defn- queue-receipt! [application]
  (-> (control! application "__arm-queue")
      (.then (fn [_]
               (app/command! application :submit {:mode :prompt :text "Keep running"})))
      (.then
       (fn [_]
         (swap! (:state application) assoc-in [:ui :draft] "deliver before receipt")
         (let [pending (app/command! application :submit
                                     {:mode :prompt :text "deliver before receipt"
                                      :draft-text "deliver before receipt"})]
           (-> (control! application "__await-entered")
               (.then (fn [_]
                        (until-state! application #(seq (get-in % [:view :queue]))
                                      "Controlled steering item was not enqueued")))
               (.then (fn [_] (control! application "__release-run")))
               (.then (fn [_]
                        (until-state! application #(empty? (get-in % [:view :queue]))
                                      "Controlled steering item was not delivered")))
               (.then (fn [_] (control! application "__release")))
               (.then (fn [_] pending))
               (.then (fn [_]
                        (check! (empty? (get-in @(:state application) [:view :queue]))
                                "A late steering receipt recreated an already delivered queue id")))
               (.then (fn [_]
                        (until-state! application
                                      #(contains? #{:completed :failed :cancelled :interrupted}
                                                  (get-in % [:view :operation :status]))
                                      "Controlled queue operation did not settle")))))))))

(defn- acknowledgement! [application a b]
  (-> (control! application "__arm-ack")
      (.then
       (fn [_]
         (let [attachments [{:path "fixture"
                             :part {:part/type :text :text "attached context"}}]
               _ (swap! (:state application)
                        #(-> %
                             (assoc-in [:ui :draft] "acknowledged")
                             (assoc-in [:ui :attachments] attachments)))
               pending (app/command! application :submit
                                     {:mode :prompt :text "acknowledged"
                                      :draft-text "acknowledged"})]
           (-> (control! application "__await-entered")
               (.then (fn [_] (app/command! application :switch-session {:id b})))
               (.then (fn [_] (app/command! application :switch-session {:id a})))
               (.then (fn [_]
                        (swap! (:state application)
                               #(-> %
                                    (assoc-in [:ui :draft] "newer draft")
                                    (assoc-in [:ui :attachments]
                                              [{:path "newer"
                                                :part {:part/type :text :text "newer"}}])))
                        (control! application "__release")))
               (.then (fn [_] pending))
               (.then
                (fn [_]
                  (check! (= "newer draft" (get-in @(:state application) [:ui :draft]))
                          "Acknowledgement erased newer active-session edits")
                  (check! (= "" (get-in @(:state application) [:ui :drafts a]))
                          "Acknowledgement retained the originating saved draft")
                  (check! (= "" (get-in @(:state application) [:ui :session-ui a :draft]))
                          "Acknowledgement retained the saved session UI draft")
                  (check! (empty? (get-in @(:state application) [:ui :session-ui a :attachments]))
                          "Acknowledgement retained the saved session UI attachments")))
               (.then (fn [_] (app/command! application :switch-session {:id b})))
               (.then (fn [_] (app/command! application :switch-session {:id a})))
               (.then
                (fn [_]
                  (check! (= "newer draft" (get-in @(:state application) [:ui :draft]))
                          "Newer edits did not survive a later round trip")))))))))

(defn- catalog-interleaving! [application a b action]
  (let [before (atom nil)]
    (-> (app/command! application :switch-session {:id a})
        (.then (fn [_] (control! application (if (= action :models) "__arm-models" "__arm-providers"))))
        (.then (fn [_]
                 (let [pending (app/command! application action {})]
                   (-> (control! application "__await-entered")
                       (.then (fn [_] (app/command! application :switch-session {:id b})))
                       (.then (fn [_]
                                (reset! before (get @(:state application) action))
                                (control! application "__release")))
                       (.then (fn [_] pending))
                       (.then (fn [_]
                                (check! (= b (get-in @(:state application) [:view :session :id]))
                                        "Catalog response changed the selected session")
                                (check! (= @before (get @(:state application) action))
                                        "Late catalog response from A overwrote B's catalog"))))))))))

(defn- controller-interleavings! [application]
  (let [a (get-in @(:state application) [:view :session :id])]
    (-> (app/command! application :new-session {:name "Interleaving target"})
        (.then (fn [_] (app/command! application :submit {:text "First message for interleaving checks"})))
        (.then
         (fn [_]
           (let [b (get-in @(:state application) [:view :session :id])]
             (-> (app/command! application :switch-session {:id a})
                 (.then (fn [_]
                          (delayed-navigation! application "__arm-configure" :set-model
                                               {:provider :codex-backend
                                                :model "offline"
                                                :thinking :high}
                                               b "B configure draft" "Model")))
                 (.then (fn [_] (app/command! application :switch-session {:id a})))
                 (.then (fn [_]
                          (delayed-navigation! application "__arm-reload" :reload {}
                                               b "B reload draft" "Reload")))
                 (.then (fn [_] (app/command! application :switch-session {:id a})))
                 (.then (fn [_] (queue-receipt! application)))
                 (.then (fn [_] (acknowledgement! application a b)))
                 (.then (fn [_] (catalog-interleaving! application a b :models)))
                 (.then (fn [_] (catalog-interleaving! application a b :providers))))))))))

(defn- graceful-close! []
  (let [source "process.on('SIGTERM', () => process.exit(99));
process.stdout.write(JSON.stringify({type:'hello', protocol:1}) + '\\n');
let pending = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  pending += chunk;
  const lines = pending.split('\\n'); pending = lines.pop();
  for (const line of lines) {
    if (!line) continue;
    const request = JSON.parse(line);
    process.stdout.write(JSON.stringify({type:'response', id:request.id, result:{}}) + '\\n');
    if (request.method === 'shutdown') {
      process.stdin.pause();
      setTimeout(() => process.exit(0), 500);
    }
  }
});"
        client (rpc/create! {:command [(.-execPath js/process) "-e" source]
                             :initialize {} :shutdown-timeout-ms 2000})]
    (-> (rpc/start! client)
        (.then (fn [_] (rpc/close! client)))
        (.then (fn [report]
                 (check! (= 0 (get-in report [:process :code]))
                         "A successful shutdown acknowledgement must allow natural process exit")
                 (check! (nil? (get-in report [:process :signal]))
                         "Graceful shutdown must not signal the owned process")))
        (.finally (fn [] (rpc/close! client))))))

(defn- fresh-start-and-resume! [options previous]
  (let [old-id (get-in @(:state previous) [:view :session :id])
        fresh (app/create! options)
        resumed (app/create! (assoc options :session-id old-id))
        fresh-id (atom nil)]
    (answer-initialization! fresh)
    (answer-initialization! resumed)
    (-> (app/close! previous)
        (.then (fn [_] (app/start! fresh)))
        (.then (fn [_]
                 (reset! fresh-id (get-in @(:state fresh) [:view :session :id]))
                 (check! (nil? @fresh-id) "Normal startup must not create a session")
                 (check! (some #(= old-id (:id %)) (:sessions @(:state fresh))) "Fresh startup must retain older sessions")
                 (let [before (count (:sessions @(:state fresh)))]
                   (-> (app/command! fresh :new-session {})
                       (.then (fn [_] (app/command! fresh :sessions)))
                       (.then (fn [sessions]
                                (check! (= before (count sessions)) "New must not persist an empty session")
                                (rejects-with! (app/command! fresh :submit {:text "   "}) "empty-prompt")))
                       (.then (fn [_]
                                (swap! (:state fresh) assoc-in [:ui :draft] "Unsent input")
                                (app/command! fresh :reconnect)))))))
        (.then (fn [_]
                 (check! (nil? (get-in @(:state fresh) [:view :session :id])) "Reconnect of an empty composer must not create a session")
                 (check! (= "Unsent input" (get-in @(:state fresh) [:ui :draft])) "Reconnect must preserve unsent composer text")
                 (let [before (count (:sessions @(:state fresh)))]
                   (-> (let [first-send (app/command! fresh :submit {:text "First actual message"})
                             duplicate (rejects-with! (app/command! fresh :submit {:text "First actual message"}) "submission-pending")]
                         (js/Promise.all #js [first-send duplicate]))
                       (.then (fn [_]
                                (until-state! fresh
                                              #(some (fn [entry] (= :user (keyword (get-in entry [:data :message/role]))))
                                                     (get-in % [:view :entries]))
                                              "The first Send must retain a user message")))
                       (.then (fn [_] (app/command! fresh :sessions)))
                       (.then (fn [sessions]
                                (check! (= (inc before) (count sessions)) "First Send must create exactly one session")
                                (check! (get-in @(:state fresh) [:view :session :id]) "First Send must activate the saved session")
                                (app/close! fresh)))))))
        (.then (fn [_] (app/start! resumed)))
        (.then (fn [_]
                 (check! (= old-id (get-in @(:state resumed) [:view :session :id])) "Explicit session startup must resume exactly that session")))
        (.finally (fn [] (js/Promise.all #js [(app/close! fresh) (app/close! resumed)]))))))

(defn exercise! []
  (let [temporary (.mkdtempSync fs (.join path (.tmpdir os) "arrodes-client-test-"))
        script (.join path temporary "host.clj")
        root (or (aget (.-env js/process) "ARRODES_TUI_ROOT") (.cwd js/process))
        options {:runtime-root root :cwd temporary :home (.join path temporary "home")
                 :trust false :setup? false
                 ;; The instrumented fixture cold-loads source before emitting hello.
                 :handshake-timeout-ms 60000
                 :rpc-command ["clojure" "-Srepro" "-Sdeps"
                               "{:paths [\"src/clj\" \"src/cljc\" \"hosts/rpc\" \"resources\"]}"
                               "-M" script]}
        owner (app/create! options)
        contender (app/create! options)]
    (.writeFileSync fs script initializing-host)
    (answer-initialization! owner)
    (answer-initialization! contender)
    (-> (create-during-connection! owner (app/start! owner) "During startup")
        (.then (fn [_]
                 (create-during-connection! owner (app/command! owner :reconnect) "During reconnect")))
        (.then (fn [_]
                 (rejects-with! (create-during-connection! contender (app/start! contender) "Must not be created")
                                "store-in-use")))
        (.then (fn [_] (await-process-exit! contender)))
        (.then (fn [_]
                 (rejects-with! (app/command! contender :sessions) "store-in-use")))
        (.then (fn [_] (app/close! owner)))
        (.then (fn [_] (app/command! contender :reconnect)))
        (.then (fn [_] (app/command! contender :sessions)))
        (.then (fn [sessions]
                 (check! (not-any? #(= "Must not be created" (:name %)) sessions)
                         "A command rejected during failed startup must not replay on reconnect")
                 (check! (= :ready (get-in @(:state contender) [:connection :status]))
                         "Releasing the store owner must allow explicit reconnect")
                 (check! (not= :error (get-in @(:state contender) [:notice :kind]))
                         "Successful reconnect must remove the stale connection error")))
        (.then (fn [_] (app/command! contender :submit {:text "First message after reconnect"})))
        (.then (fn [_] (controller-interleavings! contender)))
        (.then (fn [_] (fresh-start-and-resume! options contender)))
        (.then (fn [_] (graceful-close!)))
        (.then (fn [_]
                 (println "RPC/controller passed: startup recovery, navigation-owned refresh, delivered queue reconciliation and acknowledged draft ownership.")))
        (.finally (fn []
                    (-> (js/Promise.all #js [(app/close! owner) (app/close! contender)])
                        (.then (fn [_] (.rmSync fs temporary #js {:recursive true :force true})))))))))
