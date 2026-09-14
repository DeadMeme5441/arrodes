(ns arrodes.tui-app-test
  "Actual JVM regressions for commands issued during connection changes."
  (:require [arrodes.tui-app :as app]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path (js/require "node:path"))
(def ^:private os (js/require "node:os"))

(def ^:private initializing-host
  "(require '[arrodes.rpc :as rpc] '[arrodes.runtime :as runtime])
(let [open-runtime runtime/open!]
  (with-redefs [runtime/open!
                (fn [options]
                  (when-not ((:ui! options) {:kind :confirm :title \"Initialize test runtime\"})
                    (throw (ex-info \"Initialization declined\" {:code :declined})))
                  (open-runtime options))]
    (rpc/serve!)))
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
                     (.then (fn [] (app/command! application :new-session {:name name}))))]
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

(defn exercise! []
  (let [temporary (.mkdtempSync fs (.join path (.tmpdir os) "arrodes-client-test-"))
        script (.join path temporary "host.clj")
        root (or (aget (.-env js/process) "ARRODES_TUI_ROOT") (.cwd js/process))
        options {:runtime-root root :cwd temporary :home (.join path temporary "home") :trust false
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
                         "Successful reconnect must remove the stale connection error")
                 (println "RPC startup passed: queued commands, reconnect, initialization UI, original failure preservation and recovery without replay.")))
        (.finally (fn []
                    (-> (js/Promise.all #js [(app/close! owner) (app/close! contender)])
                        (.then (fn [_] (.rmSync fs temporary #js {:recursive true :force true})))))))))
