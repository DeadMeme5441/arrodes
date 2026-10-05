(ns arrodes.context-tree-rpc-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.commands :as commands]
            [arrodes.context-tree :as context-tree]
            [arrodes.platform :as u]
            [arrodes.rpc :as rpc]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as runtime-fixtures]
            [arrodes.session-test :as fixtures]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.context-tree :as tree-store]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io PipedReader PipedWriter StringWriter Writer)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error (:error/code (ex-data error)))))

(defn- unused-provider [calls]
  (fn [request _]
    (swap! calls conj request)
    (throw (ex-info "Inspection must not request inference" {}))))

(deftest configure-and-context-inspection-share-canonical-session-settings
  (let [calls (atom [])]
    (runtime-fixtures/with-runtime [rt (unused-provider calls)]
      (let [sid (:id (runtime-fixtures/create-session rt))
            params {:session-id sid}
            registry (runtime/registry rt sid)
            _ (store/commit! (:store rt) sid
                             {::command/entries [(fixtures/message-entry :user "Retained evidence")
                                                 (fixtures/message-entry :assistant "Retained answer")]})
            original-head (:head (runtime/session rt sid))
            alternate (first (:entries (store/commit!
                                       (:store rt) sid
                                       {::command/entries [(fixtures/message-entry :user "Alternate branch evidence")]})))
            _ (store/branch! (:store rt) sid original-head {})
            linear (commands/dispatch! rt "session.context" params)
            configured (commands/dispatch!
                        rt "session.configure"
                        (assoc params :config
                               {:settings {:context-policy "summary-tree"
                                           :summary-provider "openai"
                                           :summary-model "exact-summary-fixture"
                                           :summary-node-bytes 256
                                           :summary-view-bytes 4096
                                           :summary-max-attempts 2
                                           :summary-timeout-ms 1000
                                           :fixture-setting {:literal "unchanged"}}}))
            canonical (get-in configured [:config :settings])
            tree-before (commands/dispatch! rt "session.tree" params)
            view-before (commands/dispatch! rt "session.view" params)
            state-before (commands/dispatch! rt "session.state" params)
            events-before (runtime/events-since rt {:session-id sid})
            inspected (commands/dispatch! rt "session.context" params)]
        (is (= :linear (:policy linear)))
        (is (= :summary-tree (:policy inspected)))
        (is (= :summary-tree (:context-policy canonical)))
        (is (= :openai (:summary-provider canonical)))
        (is (= "exact-summary-fixture" (:summary-model canonical)))
        (is (= (select-keys canonical context-tree/setting-keys) (:settings inspected)))
        (is (= {:literal "unchanged"} (:fixture-setting canonical)))
        (is (= canonical (get-in (runtime/session rt sid) [:config :settings])))
        (is (= :idle (get-in inspected [:summary :status])))
        (is (= 0 (get-in inspected [:summary :node-count])))
        (is (map? (:view inspected)))
        (is (= 2 (get-in inspected [:view :source-count])))
        (is (= :preview (get-in inspected [:view :mode])))
        (is (= 0 (get-in inspected [:view :covered-count])))
        (is (= :pending-leaf (get-in inspected [:view :reason])))
        (is (= 4096 (get-in inspected [:view :budget])))
        (is (false? (get-in inspected [:view :ready?])))
        (is (vector? (get-in inspected [:view :nodes])))
        (is (<= 0 (get-in inspected [:view :bytes]) (get-in inspected [:view :budget])))
        (is (boolean? (get-in inspected [:view :fits?])))
        (is (= {} (get-in inspected [:summary :usage])))
        (is (= {} (get-in inspected [:summary :cost])))
        (is (identical? registry (runtime/registry rt sid)))
        (is (= tree-before (commands/dispatch! rt "session.tree" params)))
        (is (= view-before (commands/dispatch! rt "session.view" params)))
        (is (= state-before (commands/dispatch! rt "session.state" params)))
        (is (= events-before (runtime/events-since rt {:session-id sid})))
        (is (= (:entries tree-before) (runtime/entries rt sid)))
        (is (= (:entries view-before) (runtime/active-path rt sid)))
        (is (some #(= (:id alternate) (:id %)) (:entries tree-before)))
        (is (not-any? #(= (:id alternate) (:id %)) (:entries view-before)))
        (is (some #{"session.context"} (:methods (commands/dispatch! rt "runtime.inspect" {}))))
        (commands/dispatch! rt "session.configure" (assoc params :config {:settings {:context-policy "linear"}}))
        (is (= :linear (:policy (commands/dispatch! rt "session.context" params))))
        (is (identical? registry (runtime/registry rt sid)))
        (is (empty? @calls))))))

(deftest completed-summary-usage-is-inspected-separately-from-main-context
  (let [calls (atom [])]
    (runtime-fixtures/with-runtime [rt (unused-provider calls)]
      (let [sid (:id (runtime-fixtures/create-session rt))
            params {:session-id sid}
            _ (store/commit! (:store rt) sid
                             {::command/entries [(fixtures/message-entry :user "Original evidence")]})
            _ (commands/dispatch! rt "session.configure"
                                  (assoc params :config {:settings {:context-policy "summary-tree"}}))
            sources (context-tree/source-entries (runtime/active-path rt sid))
            node (assoc (context-tree/leaf-node sources 0 "Attributed original evidence")
                        :provider :openai :model "retained-summary-fixture" :created-at 123
                        :usage {:usage/input-tokens 10 :usage/output-tokens 2}
                        :cost {:cost/usd 0.01})
            _ (tree-store/put-node! (:store rt) sid node)
            usage-before (runtime/usage rt sid)
            tree-before (commands/dispatch! rt "session.tree" params)
            events-before (runtime/events-since rt {:session-id sid})
            inspected (commands/dispatch! rt "session.context" params)]
        (is (= :idle (get-in inspected [:summary :status])))
        (is (= 1 (get-in inspected [:summary :node-count])))
        (is (= (:usage node) (get-in inspected [:summary :usage])))
        (is (= (:cost node) (get-in inspected [:summary :cost])))
        (is (true? (get-in inspected [:view :ready?])))
        (is (true? (get-in inspected [:view :fits?])))
        (is (= [(:id node)] (mapv :id (get-in inspected [:view :nodes]))))
        (is (= :preview (get-in inspected [:view :mode])))
        (is (= 1 (get-in inspected [:view :source-count]) (get-in inspected [:view :covered-count])))
        (is (<= 0 (get-in inspected [:view :bytes]) (get-in inspected [:view :budget])))
        (is (= usage-before (runtime/usage rt sid)))
        (is (= tree-before (commands/dispatch! rt "session.tree" params)))
        (is (= events-before (runtime/events-since rt {:session-id sid})))
        (is (empty? @(:handles rt)))
        (is (empty? @(:slots (:summaries rt))))
        (is (empty? @calls))))))

(deftest invalid-context-settings-fail-before-mutation
  (let [calls (atom [])]
    (runtime-fixtures/with-runtime [rt (unused-provider calls)]
      (let [sid (:id (runtime-fixtures/create-session rt))
            params {:session-id sid}
            session-before (runtime/session rt sid)
            tree-before (commands/dispatch! rt "session.tree" params)
            events-before (runtime/events-since rt {:session-id sid})]
        (doseq [settings [{:context-policy "unknown"} {:summary-provider 42} {:summary-model " "}
                          {:summary-node-bytes 0} {:summary-view-bytes 16777217}
                          {:summary-max-attempts 11} {:summary-timeout-ms -1}
                          {:summary-node-bytes 1.5}]]
          (testing (pr-str settings)
            (is (= "invalid-config"
                   (error-code #(commands/dispatch! rt "session.configure"
                                                    (assoc params :config {:settings settings})))))
            (is (= session-before (runtime/session rt sid)))
            (is (= tree-before (commands/dispatch! rt "session.tree" params)))
            (is (= events-before (runtime/events-since rt {:session-id sid}))))))
      (is (= "invalid-params" (error-code #(commands/dispatch! rt "session.context" {}))))
      (is (= "session-not-found"
             (error-code #(commands/dispatch! rt "session.context" {:session-id (u/id)}))))
      (is (empty? @calls)))))

(defn- retrieval-fixture! [rt sid]
  (let [[original current] (:entries (store/commit!
                                     (:store rt) sid
                                     {::command/entries [(fixtures/message-entry :user "Original evidence")
                                                         (fixtures/message-entry :user "Current question")]}))
        receipt {:session-id sid :context-entry-ids [(:id current)]
                 :source-entry-ids [(:id original)] :mode :read :query "why?"
                 :operation-id (u/id) :call-id "source-call" :available? true}
        native {:history/retrieval receipt :ratio 8/13 :symbol 'retained
                :literal {:id (:id original) :session-id sid}}
        descriptor (artifacts/put-result! (:store rt) sid
                                         {:kind :inline :value native :content "Quoted original; new result 42"
                                          :details {:history/retrievals [receipt]}})]
    (store/commit! (:store rt) sid
                   {::command/entries [{:kind :evaluation
                                        :data {:source "(history/read original) (+ 20 22)"
                                               :result {:id "retained-evaluation" :content (:content descriptor)
                                                        :details (:details descriptor) :result descriptor :error? false}}}]})
    {:receipt receipt :native native}))

(defn- export-packet [rt sid]
  (-> (commands/dispatch! rt "session.export" {:session-id sid :format "jsonl"})
      :content commands/import-content))

(deftest jsonl-transfer-preserves-conditional-versions-and-typed-native-references
  (let [calls (atom [])]
    (runtime-fixtures/with-runtime [rt (unused-provider calls)]
      (let [sid (:id (runtime-fixtures/create-session rt))
            _ (store/commit! (:store rt) sid {::command/entries [(fixtures/message-entry :user "Ordinary v1")]})
            ordinary (export-packet rt sid)
            ordinary-import (commands/dispatch! rt "session.import" {:content (commands/export-jsonl ordinary)})]
        (is (= 1 (:version ordinary)))
        (is (= ordinary (commands/import-content (commands/export-jsonl ordinary))))
        (is (= 1 (:version (export-packet rt (:id ordinary-import)))))
        (is (= "Ordinary v1" (get-in (first (runtime/entries rt (:id ordinary-import))) [:data :message/content])))
        (let [{:keys [receipt native]} (retrieval-fixture! rt sid)
              packet (export-packet rt sid)
              imported (commands/dispatch! rt "session.import" {:content (commands/export-jsonl packet)})
              imported-id (:id imported)
              entries (runtime/entries rt imported-id)
              descriptor (get-in (last entries) [:data :result :result])
              retained (commands/dispatch! rt "result.inspect" {:session-id imported-id :result-id (:id descriptor)})
              native-copy (edn/read-string (:value-edn retained))
              reference (:history/retrieval native-copy)
              by-id (into {} (map (juxt :id identity)) entries)]
          (is (= 2 (:version packet)))
          (is (= packet (commands/import-content (commands/export-jsonl packet))))
          (is (= 2 (:version (export-packet rt imported-id))))
          (is (= imported-id (:session-id reference)))
          (is (= "Original evidence" (get-in (get by-id (first (:source-entry-ids reference))) [:data :message/content])))
          (is (= "Current question" (get-in (get by-id (first (:context-entry-ids reference))) [:data :message/content])))
          (is (= receipt (:source-reference reference)))
          (is (true? (:available? reference)))
          (is (not (contains? reference :operation-id)))
          (is (not (contains? reference :call-id)))
          (is (= (:literal native) (:literal native-copy)))
          (is (= 8/13 (:ratio native-copy)))
          (is (= 'retained (:symbol native-copy)))
          (is (= [reference] (get-in (last entries) [:data :result :details :history/retrievals])))
          (is (= "(history/read original) (+ 20 22)" (get-in (last entries) [:data :source])))
          (let [before (runtime/list-sessions rt {})
                source-before (runtime/entries rt sid)]
            (doseq [bad [(assoc packet :version 1)
                         (assoc-in packet [:entries (dec (count (:entries packet))) :data :result :details
                                           :history/retrievals 0 :source-entry-ids] ["not-a-uuid"])
                         (assoc-in packet [:entries (dec (count (:entries packet))) :data :result :details
                                           :history/retrievals 0 :source-entry-ids] [(u/id)])]]
              (is (= "invalid-import"
                     (error-code #(commands/dispatch! rt "session.import" {:content (commands/export-jsonl bad)}))))
              (is (= before (runtime/list-sessions rt {})))
              (is (= source-before (runtime/entries rt sid)))))))
      (is (empty? @calls)))))

(deftest jsonl-rejects-unsupported-or-conflicting-envelope-versions
  (doseq [[envelope metadata] [[3 3] [1 2] [2 1]]]
    (let [header {:type "session" :format "arrodes-session" :version envelope :encoding "edn"
                  :data (pr-str {:format "arrodes-session" :version metadata})}]
      (is (= "invalid-import" (error-code #(commands/import-content (json/write-str header))))))))

(defn- with-transport [calls f]
  (let [directory (fixtures/temp-directory)
        input (PipedReader.)
        requests (PipedWriter. input)
        frames (LinkedBlockingQueue.)
        buffer (StringBuilder.)
        output (proxy [Writer] []
                 (write [characters offset length]
                   (.append buffer (String. ^chars characters (int offset) (int length))))
                 (flush []
                   (doseq [line (remove str/blank? (str/split (str buffer) #"\n"))]
                     (.offer frames (json/read-str line :key-fn keyword)))
                   (.setLength buffer 0))
                 (close []))
        diagnostics (StringWriter.)
        server (future (rpc/serve! {:in input :out output :err diagnostics
                                    :cwd directory :home (str directory "/home")
                                    :data-dir (str directory "/data")
                                    :complete-fn (unused-provider calls)}))
        read-frame! (fn []
                      (or (.poll frames 10 TimeUnit/SECONDS)
                          (throw (ex-info "RPC frame was not delivered" {:diagnostics (str diagnostics)}))))
        request! (fn [id method params]
                   (.write requests (str (json/write-str {:type "request" :id id :method method :params params}) "\n"))
                   (.flush requests)
                   (loop []
                     (let [frame (read-frame!)]
                       (if (and (= "response" (:type frame)) (= id (:id frame)))
                         frame
                         (recur)))))]
    (try
      (f read-frame! request!)
      (finally
        (.close requests)
        (let [result (deref server 10000 ::timeout)]
          (when (= ::timeout result)
            (future-cancel server)
            (throw (ex-info "RPC server did not close" {:diagnostics (str diagnostics)}))))
        (.close input)
        (fixtures/remove-directory! directory)))))

(deftest protocol-one-context-inspection-and-validation-use-real-dispatch
  (let [calls (atom [])]
    (with-transport
      calls
      (fn [read-frame! request!]
        (let [hello (read-frame!)
              initialized (request! "initialize" "initialize" {})
              created (request! "create" "session.create" {:name "RPC context" :config fixtures/config})
              sid (get-in created [:result :id])
              params {:session-id sid}]
          (is (= "hello" (:type hello)))
          (is (= 1 (:protocol hello)))
          (is (= 1 (get-in initialized [:result :protocol])))
          (is (some #{"session.context"} (get-in initialized [:result :methods])))
          (is (string? sid))
          (is (= "linear" (get-in (request! "linear" "session.context" params) [:result :policy])))
          (let [configured (request! "configure" "session.configure"
                                     (assoc params :config {:settings {:context-policy "summary-tree"
                                                                      :summary-provider "openai"
                                                                      :summary-model "exact-summary-fixture"}}))
                tree-before (get-in (request! "tree-before" "session.tree" params) [:result])
                inspected (request! "context" "session.context" params)]
            (is (= "summary-tree" (get-in configured [:result :config :settings :context-policy])))
            (is (= "summary-tree" (get-in inspected [:result :policy])))
            (is (= "openai" (get-in inspected [:result :settings :summary-provider])))
            (is (= "exact-summary-fixture" (get-in inspected [:result :settings :summary-model])))
            (is (= "idle" (get-in inspected [:result :summary :status])))
            (is (= 0 (get-in inspected [:result :summary :node-count])))
            (is (true? (get-in inspected [:result :view :ready?])))
            (is (true? (get-in inspected [:result :view :fits?])))
            (is (= 0 (get-in inspected [:result :view :source-count])))
            (is (= [] (get-in inspected [:result :view :nodes])))
            (is (= "preview" (get-in inspected [:result :view :mode])))
            (is (= 0 (get-in inspected [:result :view :covered-count])))
            (is (= tree-before (:result (request! "tree-after" "session.tree" params))))
            (is (= "invalid-config"
                   (get-in (request! "invalid" "session.configure"
                                     (assoc params :config {:settings {:summary-max-attempts 0}})) [:error :code])))
            (is (= "invalid-params" (get-in (request! "missing" "session.context" {}) [:error :code])))
            (is (= "session-not-found"
                   (get-in (request! "unknown" "session.context" {:session-id (u/id)}) [:error :code])))
            (is (empty? @calls)))
          (is (= "closed" (get-in (request! "shutdown" "shutdown" {}) [:result :status]))))))))
