(ns arrodes.summaries-test
  (:require [arrodes.context-tree :as tree]
            [arrodes.platform :as util]
            [arrodes.provider :as provider]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.context-tree :as context]
            [arrodes.store.db :as db]
            [arrodes.summaries :as summaries]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent CountDownLatch ThreadPoolExecutor TimeUnit)))

(def config {:provider :openai :model "main-fixture" :thinking :high :tools :all
             :instructions "Main instructions must not become compactor instructions"
             :settings {:context-policy :summary-tree :summary-provider :codex-backend
                        :summary-node-bytes 96 :summary-view-bytes 128000
                        :summary-timeout-ms 10000}})

(defn- answer [text]
  {:response/provider :codex-backend :response/model "gpt-6-luna"
   :response/parts [{:part/type :text :text text}] :response/finish-reason :stop
   :response/usage {:usage/input-tokens 7 :usage/output-tokens 3 :usage/total-tokens 10}
   :response/cost {:cost/usd 0.02}})

(defn- new-session [database]
  (:id (store/create-session! database {:config config :name "Summary owner"
                                        :cwd (System/getProperty "java.io.tmpdir")})))

(defn- append! [database sid texts]
  (:entries (store/commit! database sid
                          {::command/entries
                           (mapv (fn [text] {:kind :message :data {:message/role :user
                                                                  :message/content text}}) texts)})))

(defn- sources [database sid] (tree/source-entries (store/active-path database sid)))
(defn- error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:error/code (ex-data error)))))
(defn- ready! [manager sid entries cfg]
  (let [result (summaries/ensure-ready! manager sid entries cfg {})]
    (is (summaries/await-session! manager sid 5000))
    ;; This helper intentionally awaits maintenance for tests inspecting the
    ;; whole persisted tree; foreground readiness itself returns sooner.
    (assoc result :nodes (context/nodes (:store manager) sid))))

(defmacro with-service [[database manager complete] & body]
  `(let [~database (db/open! {:memory? true})
         ~manager (summaries/create! {:store ~database :provider-manager (fn [sid#] {:sid sid#})})]
     (with-redefs [provider/complete! ~complete]
       (try ~@body
            (finally
              (summaries/stop! ~manager)
              (is (summaries/await-closed! ~manager 5000))
              (db/close! ~database))))))

(defn- remove-directory! [directory]
  (with-open [walk (Files/walk (util/path directory) (make-array java.nio.file.FileVisitOption 0))]
    (doseq [file (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator walk)))]
      (Files/deleteIfExists file))))

(defn- await-release! [^CountDownLatch release]
  ;; This fixture deliberately ignores interruption to exercise actual-exit ownership.
  (loop []
    (when-not (try (.await release 50 TimeUnit/MILLISECONDS)
                   (catch InterruptedException _ false))
      (recur))))

(deftest opening-inspecting-and-linear-requests-never-infer
  (let [calls (atom 0)]
    (with-service [database manager (fn [& _] (swap! calls inc) (answer "unexpected"))]
      (let [sid (new-session database)]
        (append! database sid [(apply str (repeat 1000 "original "))])
        (is (= :idle (:status (summaries/inspect manager sid))))
        (is (= 0 (:node-count (summaries/inspect manager sid))))
        (is (nil? (summaries/request! manager sid (assoc-in config [:settings :context-policy] :linear))))
        (is (= 0 @calls))
        (store/configure! database sid {:settings {:context-policy :linear}})
        (is (nil? (summaries/request! manager sid config)))
        (is (= 0 @calls))
        (is (empty? @(:slots manager)))))))

(deftest fitting-leaves-and-parents-are-free-and-originals-are-preserved
  (with-service [database manager (fn [& _] (throw (ex-info "Free node called a provider" {})))]
    (let [sid (new-session database) entries (append! database sid ["résumé" "second"])
          cfg (assoc-in config [:settings :summary-node-bytes] 4096)
          history (store/entries database sid)
          result (ready! manager sid entries cfg)
          parent (get (:nodes result) (tree/node-key entries 0 2))]
      (is (= entries (:entries result)))
      (is (= 3 (count (:nodes result))))
      (is (= (str (tree/source-text (first entries)) "\n\n" (tree/source-text (second entries))) (:text parent)))
      (is (= (tree/utf8-bytes (:text result)) (:bytes result)))
      (is (:fits? (meta (:view result))))
      (is (= history (store/entries database sid)))
      (is (= {} (:usage (summaries/inspect manager sid))))
      (is (= {} (:cost (summaries/inspect manager sid))))
      (is (every? #(not (contains? % :model)) (vals (:nodes result)))))))

(deftest clearing-derived-nodes-invalidates-frontier-and-rebuilds-navigation
  (with-service [database manager (fn [& _] (throw (ex-info "Cache rebuild should be free" {})))]
    (let [sid (new-session database)
          entries (append! database sid ["one" "two"])
          cfg (assoc-in config [:settings :summary-node-bytes] 4096)
          before (ready! manager sid entries cfg)]
      (context/clear-nodes! database sid)
      (is (empty? (context/nodes database sid)))
      (is (nil? (:view (summaries/inspect manager sid))))
      (let [rebuilt (ready! manager sid entries cfg)]
        (is (= (:text before) (:text rebuilt)))
        (is (= 3 (count (:nodes rebuilt))))
        (doseq [node (:view rebuilt)]
          (is (= node (context/node database sid (:id node))))))
      (context/clear-nodes! database sid)
      (append! database sid ["three" "four"])
      (summaries/request! manager sid cfg)
      (is (summaries/await-session! manager sid 5000))
      (let [current (sources database sid)
            rebuilt (ready! manager sid current cfg)
            root (context/node database sid (tree/node-key current 0 4))]
        (is (= current (:entries rebuilt)))
        (is (= 7 (count (:nodes rebuilt))))
        (is (= 4 (:count root)))
        (is (some? (context/node database sid (:left-id root))))
        (is (some? (context/node database sid (:right-id root))))
        (is (:fits? (meta (:view rebuilt))))))))

(deftest inference-is-contextual-tools-free-and-separately-metered
  (let [requests (atom []) owners (atom []) summary (apply str (repeat 60 "s"))]
    (with-service [database manager
                   (fn [owner request options]
                     (swap! owners conj (:sid owner))
                     (swap! requests conj [request options])
                     (answer summary))]
      (let [sid (new-session database)
            entries (append! database sid (mapv #(str "original-" % " " (apply str (repeat 300 "x"))) (range 4)))
            result (ready! manager sid entries config)
            rendered-inputs (mapv #(get-in % [0 :request/messages 1 :message/content]) @requests)
            report (summaries/inspect manager sid)]
        (is (= 7 (count @requests)))
        (is (= (vec (repeat 7 sid)) @owners))
        (is (str/includes? (second rendered-inputs) summary))
        (is (not (str/includes? (second rendered-inputs) "original-0")))
        (is (some #(str/includes? % (str summary "\n\n" summary)) rendered-inputs))
        (is (every? #(not (str/includes? % "<node")) rendered-inputs))
        (is (every? #(not (str/includes? % "ct:")) rendered-inputs))
        (doseq [[request options] @requests]
          (is (= "gpt-6-luna" (:request/model request)))
          (is (= :codex-backend (:provider options)))
          (is (nil? (:request/tools request)))
          (is (nil? (:request/reasoning request)))
          (is (= {:enabled? true :scope-id (str sid ":summary-tree")} (:request/cache request)))
          (is (pos-int? (:request/max-tokens request)))
          (is (not (str/includes? (get-in request [:request/messages 0 :message/content]) (:instructions config)))))
        (is (= {:usage/input-tokens 49 :usage/output-tokens 21 :usage/total-tokens 70} (:usage report)))
        (is (< (Math/abs (- 0.14 (get-in report [:cost :cost/usd]))) 0.000001))
        (is (= 7 (:usage-node-count report)))
        (is (= 7 (:cost-node-count report)))
        (is (every? #(= "gpt-6-luna" (:model %)) (vals (:nodes result))))
        (is (empty? (store/operations database {:session-id sid})))))))

(deftest retries-share-one-conversation-and-retain-shortest-complete-output
  (let [requests (atom []) attempts (atom 0)]
    (with-service [database manager
                   (fn [_ request _]
                     (swap! requests conj request)
                     (answer (case (swap! attempts inc) 1 (apply str (repeat 200 "a")) "complete short evidence")))]
      (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])
            result (ready! manager sid entries config)
            node (get (:nodes result) (tree/node-key entries 0 1))]
        (is (= 2 @attempts))
        (is (= 2 (count (:request/messages (first @requests)))))
        (is (= 4 (count (:request/messages (second @requests)))))
        (is (= :assistant (get-in @requests [1 :request/messages 2 :message/role])))
        (is (= "complete short evidence" (:text node)))
        (is (= 20 (get-in node [:usage :usage/total-tokens])))
        (is (= 0.04 (get-in node [:cost :cost/usd])))))))

(deftest node-bytes-is-a-target-and-shortest-complete-attempt-is-retained
  (let [calls (atom 0) texts [(apply str (repeat 150 "a"))
                            (apply str (repeat 180 "b"))
                            (apply str (repeat 170 "c"))]]
    (with-service [database manager
                   (fn [& _]
                     (let [attempt (swap! calls inc) response (answer (nth texts (dec attempt)))]
                       (if (= 3 attempt) (assoc response :response/finish-reason :length) response)))]
      (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])
            result (ready! manager sid entries config)
            node (get (:nodes result) (tree/node-key entries 0 1))]
        (is (= 3 @calls))
        (is (= (first texts) (:text node)))
        (is (= 150 (:bytes node)))
        (is (:fits? (meta (:view result))))
        (is (<= (:bytes result) (get-in config [:settings :summary-view-bytes])))
        (is (= 30 (get-in node [:usage :usage/total-tokens])))))))

(deftest oversized-complete-parent-does-not-block-fitting-preceding-children
  (let [requests (atom [])
        leaf-text (apply str (repeat 60 "s"))
        parent-text (apply str (repeat 2000 "p"))]
    (with-service [database manager
                   (fn [_ request _]
                     (swap! requests conj request)
                     (answer (if (str/includes? (get-in request [:request/messages 1 :message/content])
                                                (str leaf-text "\n\n" leaf-text))
                               parent-text leaf-text)))]
      (let [sid (new-session database)
            entries (append! database sid (vec (repeat 2 (apply str (repeat 500 "x")))))
            cfg (assoc-in config [:settings :summary-view-bytes] 1500)
            before (ready! manager sid entries cfg)
            parent (get (:nodes before) (tree/node-key entries 0 2))]
        (is (= 5 (count @requests)))
        (is (= 2000 (:bytes parent)))
        (is (= [1 1] (mapv :count (:view before))))
        (append! database sid [(str "third original " (apply str (repeat 500 "x")))])
        (let [after (ready! manager sid (sources database sid) cfg)
              request-text (get-in (peek @requests) [:request/messages 1 :message/content])]
          (is (= 6 (count @requests)))
          (is (str/includes? request-text leaf-text))
          (is (not (str/includes? request-text parent-text)))
          (is (= 3 (reduce + (map :count (:view after)))))
          (is (:fits? (meta (:view after))))
          (is (<= (:bytes after) 1500)))))))

(deftest oversized-old-parent-does-not-poison-a-view-before-a-reducing-parent-arrives
  (let [merge-entered (promise) release (CountDownLatch. 1)
        leaf-text (apply str (repeat 60 "l"))
        old-text (apply str (repeat 2000 "o"))
        new-text "Newer complete evidence"
        root-text (apply str (repeat 3000 "r"))]
    (with-service [database manager
                   (fn [_ request _]
                     (if (str/includes? (get-in request [:request/messages 1 :message/content]) old-text)
                       (answer root-text)
                       (do (deliver merge-entered true) (await-release! release) (answer new-text))))]
      (try
        (let [sid (new-session database)
              entries (append! database sid ["one" "two" "three" "four"])
              leaves (mapv #(tree/leaf-node entries % leaf-text) (range 4))
              older (tree/parent-node (nth leaves 0) (nth leaves 1) old-text)
              newer (tree/parent-node (nth leaves 2) (nth leaves 3) new-text)
              expected [(nth leaves 0) (nth leaves 1) newer]
              budget (tree/utf8-bytes (tree/render-view expected))
              cfg (assoc-in config [:settings :summary-view-bytes] budget)]
          (doseq [node (conj leaves older)] (context/put-node! database sid node))
          (is (> (tree/utf8-bytes (tree/render-view leaves)) budget))
          (let [waiting (future (summaries/ensure-ready! manager sid entries cfg {}))]
            (is (= true (deref merge-entered 5000 nil)))
            (is (= ::waiting (deref waiting 25 ::waiting)))
            (is (= (mapv :id leaves) (mapv :id (get-in (summaries/inspect manager sid) [:view :nodes]))))
            (.countDown release)
            (let [result (deref waiting 5000 ::timeout)]
              (is (= (mapv :id expected) (mapv :id (:view result))))
              (is (= entries (:entries result)))
              (is (:fits? (meta (:view result))))
              (is (= budget (:bytes result))))
            (is (summaries/await-session! manager sid 5000))
            (let [nodes (context/nodes database sid)
                  root (get nodes (tree/node-key entries 0 4))]
              (is (= 3000 (:bytes root)))
              (is (= (mapv :id expected) (mapv :id (tree/fit-view leaves nodes 4 budget)))))))
        (finally (.countDown release))))))

(deftest fitting-ancestor-is-planned-through-expanding-intermediate-parents
  (let [entries (mapv #(hash-map :id (str "source-" %)) (range 4))
        leaves (mapv #(tree/leaf-node entries % (apply str (repeat 60 "l"))) (range 4))
        older (tree/parent-node (nth leaves 0) (nth leaves 1) (apply str (repeat 2000 "o")))
        newer (tree/parent-node (nth leaves 2) (nth leaves 3) (apply str (repeat 2000 "n")))
        root (tree/parent-node older newer "Complete ancestor evidence")
        nodes (into {} (map (juxt :id identity)) (conj leaves older newer root))
        budget (tree/utf8-bytes (tree/render-view [root]))
        pending (tree/build-view entries (dissoc nodes (:id root)) budget)]
    (is (> (tree/utf8-bytes (tree/render-view leaves)) budget))
    (is (= leaves pending))
    (is (false? (:fits? (meta pending))))
    (is (= :pending-parent (:reason (meta pending))))
    (doseq [view [(tree/fit-view leaves nodes 4 budget)
                 (tree/append-view pending entries nodes budget)]]
      (is (= [root] view))
      (is (:fits? (meta view)))
      (is (= budget (:bytes (meta view)))))))

(deftest bounded-failures-are-visible-and-not-retried-on-every-append
  (let [calls (atom 0) valid? (atom false)]
    (with-service [database manager (fn [& _] (swap! calls inc) (answer (if @valid? "valid evidence" "")))]
      (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])
            history (store/entries database sid)]
        (is (= "summary-incomplete" (error-code #(summaries/ensure-ready! manager sid entries config {}))))
        (is (summaries/await-session! manager sid 5000))
        (is (= 3 @calls))
        (is (= :failed (:status (summaries/inspect manager sid))))
        (is (= "summary-incomplete" (get-in (summaries/inspect manager sid) [:error :code])))
        (is (= 30 (get-in (summaries/inspect manager sid) [:unpersisted-usage :usage/total-tokens])))
        (is (= 0.06 (get-in (summaries/inspect manager sid) [:unpersisted-cost :cost/usd])))
        (is (empty? (context/nodes database sid)))
        (is (= history (store/entries database sid)))
        (append! database sid ["another user message"])
        (summaries/request! manager sid config)
        (is (summaries/await-session! manager sid 5000))
        (is (= 3 @calls))
        (is (= :failed (:status (summaries/inspect manager sid))))
        (is (= "summary-incomplete" (error-code #(summaries/ensure-ready! manager sid entries config {}))))
        (is (summaries/await-session! manager sid 5000))
        (is (= 6 @calls))
        (summaries/request! manager sid config)
        (is (summaries/await-session! manager sid 5000))
        (is (= 6 @calls))
        (reset! valid? true)
        (ready! manager sid entries config)
        (is (= 7 @calls))
        (is (= 10 (get-in (summaries/inspect manager sid) [:usage :usage/total-tokens])))
        (is (= 60 (get-in (summaries/inspect manager sid) [:unpersisted-usage :usage/total-tokens])))))))

(deftest reading-complete-prefix-does-not-rearm-later-background-failure
  (let [calls (atom 0) valid? (atom false)]
    (with-service [database manager
                   (fn [& _] (swap! calls inc) (answer (if @valid? "recovered evidence" "")))]
      (let [sid (new-session database)
            cfg (assoc-in config [:settings :summary-node-bytes] 4096)
            frozen (append! database sid ["already complete"])]
        (ready! manager sid frozen cfg)
        (append! database sid [(apply str (repeat 10000 "x"))])
        (summaries/request! manager sid cfg)
        (is (summaries/await-session! manager sid 5000))
        (is (= 3 @calls))
        (is (= frozen (:entries (ready! manager sid frozen cfg))))
        (append! database sid ["later input"])
        (summaries/request! manager sid cfg)
        (is (summaries/await-session! manager sid 5000))
        (is (= 3 @calls))
        (is (= "summary-incomplete" (get-in (summaries/inspect manager sid) [:error :code])))
        (reset! valid? true)
        (let [current (sources database sid)]
          (is (= current (:entries (ready! manager sid current cfg)))))
        (is (= 4 @calls))))))

(deftest empty-and-truncated-completions-never-become-summaries
  (doseq [response [(answer "   ") (assoc (answer "truncated evidence") :response/finish-reason :length)
                    (assoc (answer "incomplete evidence") :response/finish-reason :incomplete)]]
    (let [calls (atom 0)]
      (with-service [database manager (fn [& _] (swap! calls inc) response)]
        (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])]
          (is (= "summary-incomplete" (error-code #(summaries/ensure-ready! manager sid entries config {}))))
          (is (summaries/await-session! manager sid 5000))
          (is (= 3 @calls))
          (is (empty? (context/nodes database sid)))
          (is (= 30 (get-in (summaries/inspect manager sid) [:unpersisted-usage :usage/total-tokens]))))))))

(deftest provider-errors-preserve-reported-partial-usage-without-inventing-a-node
  (with-service [database manager
                 (fn [& _] (throw (ex-info "Transient provider failure"
                                           {:error/code "provider/unavailable"
                                            :partial-response (answer "unfinished")})))]
    (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])]
      (is (= "provider/unavailable" (error-code #(summaries/ensure-ready! manager sid entries config {}))))
      (is (summaries/await-session! manager sid 5000))
      (is (= :failed (:status (summaries/inspect manager sid))))
      (is (= 10 (get-in (summaries/inspect manager sid) [:unpersisted-usage :usage/total-tokens])))
      (is (= 0.02 (get-in (summaries/inspect manager sid) [:unpersisted-cost :cost/usd])))
      (is (empty? (context/nodes database sid))))))

(deftest impossible-view-budgets-fail-with-complete-original-coverage-not-clipping
  (with-service [database manager (fn [& _] (throw (ex-info "Should be free" {})))]
    (let [sid (new-session database) entries (append! database sid ["one" "two"])
          cfg (-> config (assoc-in [:settings :summary-node-bytes] 4096)
                  (assoc-in [:settings :summary-view-bytes] 1))]
      (is (= "summary-view-budget" (error-code #(summaries/ensure-ready! manager sid entries cfg {}))))
      (is (summaries/await-session! manager sid 5000))
      (is (= 3 (:node-count (summaries/inspect manager sid))))
      (is (false? (get-in (summaries/inspect manager sid) [:view :fits?])))
      (is (= 2 (get-in (summaries/inspect manager sid) [:view :source-count])))
      (is (= entries (sources database sid))))))

(deftest append-keeps-the-existing-frontier-and-coalesces-snapshots
  (let [entered (promise) release (CountDownLatch. 1) calls (atom 0)]
    (with-service [database manager
                   (fn [& _] (swap! calls inc) (deliver entered true) (await-release! release) (answer "retained prefix"))]
      (try
        (let [sid (new-session database)
              cfg (assoc-in config [:settings :summary-node-bytes] 4096)]
          (append! database sid [(apply str (repeat 10000 "x"))])
          (summaries/request! manager sid cfg)
          (is (= true (deref entered 5000 nil)))
          (doseq [index (range 20)]
            (append! database sid [(str "append " index)])
            (summaries/request! manager sid cfg))
          (is (= 1 (count @(:slots manager))))
          (is (= 0 (.size (.getQueue ^ThreadPoolExecutor (:executor manager)))))
          (.countDown release)
          (is (summaries/await-session! manager sid 5000))
          (is (= 1 @calls))
          (is (= 21 (get-in (summaries/inspect manager sid) [:view :source-count])))
          (let [before (ready! manager sid (sources database sid) cfg)
                existing (:view before)]
            (append! database sid ["next original"])
            (let [after (ready! manager sid (sources database sid) cfg)]
              (is (= existing (subvec (:view after) 0 (count existing))))
              (is (= 1 @calls)))))
        (finally (.countDown release))))))

(deftest background-appends-read-only-new-ancestry-and-retain-identities
  (with-service [database manager (fn [& _] (throw (ex-info "Small append should be free" {})))]
    (let [sid (new-session database)
          cfg (assoc-in config [:settings :summary-node-bytes] 4096)
          entries (append! database sid ["one" "two"])]
      (ready! manager sid entries cfg)
      (append! database sid ["new tail three" "new tail four"])
      (with-redefs [store/active-path (fn [& _] (throw (ex-info "Append rescanned canonical history" {})))]
        (summaries/request! manager sid cfg)
        (is (summaries/await-session! manager sid 5000)))
      (let [report (summaries/inspect manager sid)
            retained (get @(:projections manager) sid)]
        (is (= :idle (:status report)))
        (is (= 4 (get-in report [:view :source-count])))
        (is (= 7 (:node-count report)))
        (is (= (mapv :id (sources database sid)) (mapv :id (:source-identities retained))))
        (is (every? #(= #{:id} (set (keys %))) (:source-identities retained)))
        (is (not (contains? (:view report) :source-identities)))))))

(deftest branch-and-frozen-prefixes-reuse-only-their-original-lineage
  (let [calls (atom 0)]
    (with-service [database manager (fn [& _] (swap! calls inc) (answer (apply str (repeat 60 "s"))))]
      (let [sid (new-session database)
            originals (append! database sid (vec (repeat 4 (apply str (repeat 500 "x")))))
            old (ready! manager sid originals config)]
        (is (= 7 @calls))
        (store/branch! database sid (:id (second originals)) {})
        (summaries/cancel-session! manager sid)
        (is (summaries/await-session! manager sid 5000))
        (append! database sid [(str "different branch " (apply str (repeat 500 "x")))])
        (let [replacement (sources database sid)
              current (ready! manager sid replacement config)]
          (is (= 8 @calls))
          (is (= (get (:nodes old) (tree/node-key originals 0 2))
                 (get (:nodes current) (tree/node-key replacement 0 2))))
          (is (every? #(= (:id %) (tree/node-key replacement (:start %) (:count %))) (:view current)))
          (let [frozen (ready! manager sid originals config)]
            (is (= 8 @calls))
            (is (= originals (:entries frozen)))
            (is (= (:text old) (:text frozen)))
            (is (= replacement (sources database sid)))))))))

(deftest complete-frozen-prefix-bypasses-slow-native-suffix-catch-up
  (let [entered (promise) release (CountDownLatch. 1) calls (atom 0) options (atom nil)
        marker "native suffix must remain current"]
    (with-service [database manager
                   (fn [_ request opts]
                     (swap! calls inc)
                     (if (str/includes? (get-in request [:request/messages 1 :message/content]) marker)
                       (do (reset! options opts) (deliver entered true)
                           (await-release! release) (answer "later summary"))
                       (answer "frozen evidence")))]
      (try
        (let [sid (new-session database)
              frozen (append! database sid [(apply str (repeat 500 "old "))])
              before (ready! manager sid frozen config)
              suffix (:entries
                      (store/commit! database sid
                                     {::command/entries
                                      [{:kind :message :data {:message/role :user
                                                             :message/content (str marker (apply str (repeat 500 "x")))}}
                                       {:kind :message :data {:message/role :assistant
                                                             :message/content "settled native assistant evidence"}}]}))]
          (summaries/request! manager sid config)
          (is (= true (deref entered 5000 nil)))
          (let [result (try (summaries/ensure-ready! manager sid frozen config {:timeout-ms 20})
                            (catch clojure.lang.ExceptionInfo error {:error (ex-data error)}))]
            (is (= frozen (:entries result)))
            (is (= (:text before) (:text result)))
            (is (= 1 (reduce + 0 (map :count (:view result)))))
            (is (every? #(not (str/includes? (or (:text result) "") (:id %))) suffix))
            (is (not (str/includes? (or (:text result) "") marker)))
            (is (= 2 @calls))
            (is (true? (:worker-active? (summaries/inspect manager sid))))
            (is (false? (util/cancelled? (:cancelled? @options))))
            (is (nil? @(-> @(:slots manager) (get sid) :foreground))))
          (.countDown release)
          (is (summaries/await-session! manager sid 5000)))
        (finally (.countDown release))))))

(deftest cancellation-is-cooperative-but-waits-for-exit-before-resuming
  (let [entered (promise) token (atom false) mode (atom :wait)]
    (with-service [database manager
                   (fn [_ _ options]
                     (if (= :wait @mode)
                       (do (deliver entered true)
                           (loop [] (util/check-cancelled! (:cancelled? options)) (Thread/sleep 10) (recur)))
                       (answer "resumed evidence")))]
      (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])
            waiting (future (error-code #(summaries/ensure-ready! manager sid entries config {:cancelled? token})))]
        (is (= true (deref entered 5000 nil)))
        (reset! token true)
        (is (= "summary-cancelled" (deref waiting 5000 ::timeout)))
        (is (summaries/await-session! manager sid 5000))
        (is (empty? (context/nodes database sid)))
        (reset! mode :resume)
        (is (str/includes? (:text (ready! manager sid entries config)) "resumed evidence"))
        (is (= "summary-cancelled"
               (error-code #(summaries/ensure-ready! manager sid entries config {:cancelled? (atom true)}))))))))

(deftest queued-cancellation-releases-admission-and-cannot-retire-new-request
  (let [entered (CountDownLatch. 2) release (CountDownLatch. 1) calls (atom [])]
    (with-service [database manager
                   (fn [owner _ _]
                     (swap! calls conj (:sid owner))
                     (.countDown entered)
                     (await-release! release)
                     (answer "queued evidence"))]
      (try
        (let [[first-sid second-sid queued-sid :as ids]
              (mapv (fn [_]
                      (let [sid (new-session database)]
                        (append! database sid [(apply str (repeat 500 "x"))])
                        sid))
                    (range 3))]
          (summaries/request! manager first-sid config)
          (summaries/request! manager second-sid config)
          (is (.await entered 5 TimeUnit/SECONDS))
          (summaries/request! manager queued-sid config)
          (let [cancelled-slot (get @(:slots manager) queued-sid)
                cancelled-task @(:pending cancelled-slot)]
            (is (= 1 (.size (.getQueue ^ThreadPoolExecutor (:executor manager)))))
            (summaries/cancel-session! manager queued-sid)
            (is (summaries/await-session! manager queued-sid 0))
            (is (realized? (:done cancelled-slot)))
            (is (= "summary-cancelled"
                   (some-> (deref (:done cancelled-task) 20 nil) :error ex-data :error/code)))
            (is (nil? (get @(:slots manager) queued-sid)))
            (is (= 0 (.size (.getQueue ^ThreadPoolExecutor (:executor manager)))))
            (is (= 2 (count @calls))))
          (summaries/request! manager queued-sid config)
          (let [old-slot (get @(:slots manager) queued-sid)
                runnable @(:runnable old-slot)
                late-entered (promise)
                late-worker (locking (:lock manager)
                              ;; Simulate executor dequeue immediately before the
                              ;; runnable can win the service's start/cancel lock.
                              (is (.remove ^ThreadPoolExecutor (:executor manager) ^Runnable runnable))
                              (let [worker (future (deliver late-entered true) (.run ^Runnable runnable))]
                                (is (= true (deref late-entered 5000 nil)))
                                (summaries/cancel-session! manager queued-sid)
                                (is (summaries/await-session! manager queued-sid 0))
                                (summaries/request! manager queued-sid config)
                                worker))
                new-slot (get @(:slots manager) queued-sid)]
            (is (not (identical? old-slot new-slot)))
            (is (nil? (deref late-worker 5000 ::timeout)))
            (is (identical? new-slot (get @(:slots manager) queued-sid)))
            (is (false? (realized? (:done new-slot))))
            (is (= 1 (.size (.getQueue ^ThreadPoolExecutor (:executor manager)))))
            (is (= 2 (count @calls))))
          (.countDown release)
          (doseq [sid ids] (is (summaries/await-session! manager sid 5000)))
          (is (= 1 (count (filter #{queued-sid} @calls))))
          (is (= 3 (count @calls))))
        (finally (.countDown release))))))

(deftest node-deadlines-interrupt-work-and-do-not-trigger-paid-append-retries
  (let [entered (promise) calls (atom 0) wait? (atom true)]
    (with-service [database manager
                   (fn [& _]
                     (swap! calls inc)
                     (when @wait? (deliver entered true) (Thread/sleep 10000))
                     (answer "deadline recovery"))]
      (let [sid (new-session database)
            entries (append! database sid [(apply str (repeat 500 "x"))])
            cfg (assoc-in config [:settings :summary-timeout-ms] 250)]
        (summaries/request! manager sid cfg)
        (is (= true (deref entered 5000 nil)))
        (is (summaries/await-session! manager sid 5000))
        (is (= :failed (:status (summaries/inspect manager sid))))
        (is (= "summary-timeout" (get-in (summaries/inspect manager sid) [:error :code])))
        (is (empty? (context/nodes database sid)))
        (append! database sid ["later input"])
        (summaries/request! manager sid cfg)
        (is (summaries/await-session! manager sid 5000))
        (is (= 1 @calls))
        (reset! wait? false)
        (ready! manager sid entries cfg)
        (is (= 2 @calls))))))

(deftest timeout-and-stop-do-not-mistake-noncooperative-cancellation-for-exit
  (let [entered (promise) release (CountDownLatch. 1)]
    (with-service [database manager
                   (fn [& _] (deliver entered true) (await-release! release) (answer "late evidence"))]
      (try
        (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])
              waiting (future (error-code #(summaries/ensure-ready! manager sid entries config {:timeout-ms 200})))]
          (is (= true (deref entered 5000 nil)))
          (is (= "summary-timeout" (deref waiting 5000 ::timeout)))
          (is (false? (summaries/await-session! manager sid 20)))
          (let [admission (summaries/request! manager sid config)]
            (is (= :deferred (:status admission)))
            (is (= "summary-cancelling" (get-in admission [:error :code]))))
          ;; Admission retirement cannot release a still-running provider owner.
          (swap! (:slots manager) dissoc sid)
          (is (= :deferred (:status (summaries/request! manager sid config))))
          (is (= "summary-cancelling"
                 (error-code #(summaries/ensure-ready! manager sid entries config {}))))
          (summaries/stop! manager)
          (is (= "summary-closed" (error-code #(summaries/request! manager sid config))))
          (is (false? (summaries/await-closed! manager 20)))
          (is (= :closed (:status (summaries/inspect manager sid))))
          (is (true? (:worker-active? (summaries/inspect manager sid))))
          (is (= entries (sources database sid)))
          (.countDown release)
          (is (summaries/await-closed! manager 5000))
          (is (empty? (context/nodes database sid))))
        (finally (.countDown release))))))

(deftest session-admission-and-provider-concurrency-are-bounded
  (let [entered (CountDownLatch. 2) release (CountDownLatch. 1) calls (atom 0)]
    (with-service [database manager
                   (fn [& _] (swap! calls inc) (.countDown entered) (await-release! release) (answer "bounded evidence"))]
      (try
        (let [ids (mapv (fn [_] (let [sid (new-session database)]
                                (append! database sid [(apply str (repeat 500 "x"))]) sid)) (range 35))]
          (doseq [sid (take 2 ids)] (summaries/request! manager sid config))
          (is (.await entered 5 TimeUnit/SECONDS))
          (doseq [sid (take 32 (drop 2 ids))] (summaries/request! manager sid config))
          (let [admission (summaries/request! manager (last ids) config)]
            (is (= :deferred (:status admission)))
            (is (= "summary-capacity" (get-in admission [:error :code]))))
          (is (= "summary-capacity"
                 (error-code #(summaries/ensure-ready! manager (last ids)
                                                       (sources database (last ids)) config {}))))
          (is (= 34 (count @(:slots manager))))
          (is (= 2 @calls))
          (is (= 32 (.size (.getQueue ^ThreadPoolExecutor (:executor manager)))))
          (summaries/stop! manager)
          (.countDown release)
          (is (summaries/await-closed! manager 5000))
          (is (= 2 @calls)))
        (finally (.countDown release))))))

(deftest durable-restart-opens-without-calls-and-reuses-completed-nodes
  (let [directory (str (Files/createTempDirectory "arrodes-summary-restart-" (make-array FileAttribute 0)))
        options {:path (str directory "/sessions.sqlite")}
        database (db/open! options)
        manager (summaries/create! {:store database :provider-manager (fn [_] {})})
        calls (atom 0)]
    (try
      (with-redefs [provider/complete! (fn [& _] (swap! calls inc) (answer "durable evidence"))]
        (let [sid (new-session database) entries (append! database sid [(apply str (repeat 500 "x"))])
              result (ready! manager sid entries config)
              history (store/entries database sid)]
          (is (= 1 @calls))
          (summaries/stop! manager)
          (is (summaries/await-closed! manager 5000))
          (db/close! database)
          (let [reopened (db/open! options)
                resumed (summaries/create! {:store reopened :provider-manager (fn [_] {})})]
            (try
              (is (= :idle (:status (summaries/inspect resumed sid))))
              (is (= 1 (:node-count (summaries/inspect resumed sid))))
              (is (= 1 @calls))
              (is (= (:text result) (:text (ready! resumed sid (sources reopened sid) config))))
              (is (= 1 @calls))
              (is (= history (store/entries reopened sid)))
              (is (= 10 (get-in (summaries/inspect resumed sid) [:usage :usage/total-tokens])))
              (finally (summaries/stop! resumed) (is (summaries/await-closed! resumed 5000)) (db/close! reopened))))))
      (finally
        (summaries/stop! manager)
        (summaries/await-closed! manager 5000)
        (db/close! database)
        (remove-directory! directory)))))

(deftest measured-retry-feedback-trims-candidates-without-storing-a-cut-prefix
  (let [requests (atom [])
        oversized (str (apply str (repeat 95 "a")) "雪🙂" (apply str (repeat 20 " remaining")))
        attempts (atom 0)]
    (with-service [database manager
                   (fn [_ request _]
                     (swap! requests conj request)
                     (answer (if (= 1 (swap! attempts inc)) (str " \n" oversized "\n ") " \ncomplete evidence\n ")))]
      (let [sid (new-session database)
            entries (append! database sid [(apply str (repeat 500 "x"))])
            cfg (-> config
                    (assoc-in [:settings :summary-model] "configured-summary-model")
                    (assoc-in [:settings :cache] {:enabled? false}))
            result (ready! manager sid entries cfg)
            node (get (:nodes result) (tree/node-key entries 0 1))
            feedback (get-in @requests [1 :request/messages 3 :message/content])]
        (is (= 2 @attempts))
        (is (str/includes? feedback (str (tree/utf8-bytes oversized) " UTF-8 bytes")))
        (is (str/includes? feedback (str (apply str (repeat 95 "a")) "| <- LIMIT")))
        (is (not (str/includes? feedback "\uFFFD")))
        (is (= oversized (get-in @requests [1 :request/messages 2 :message/content])))
        (is (= "complete evidence" (:text node)))
        (is (= (tree/utf8-bytes "complete evidence") (:bytes node)))
        (doseq [request @requests]
          (is (= "configured-summary-model" (:request/model request)))
          (is (= {:enabled? false :scope-id (str sid ":summary-tree")} (:request/cache request))))))))

(deftest next-leaf-and-frozen-readiness-do-not-wait-for-an-unused-parent
  (let [merge-entered (promise) next-leaf-entered (promise)
        release (CountDownLatch. 1)
        leaf-text (apply str (repeat 60 "l"))]
    (with-service [database manager
                   (fn [_ request _]
                     (let [input (get-in request [:request/messages 1 :message/content])]
                       (cond
                         (str/includes? input (str leaf-text "\n\n" leaf-text))
                         (do (deliver merge-entered input) (await-release! release))

                         (str/includes? input "third-original")
                         (do (deliver next-leaf-entered true)
                             (when-not (string? (deref merge-entered 5000 nil))
                               (throw (ex-info "Next leaf could not overlap its preceding merge" {})))))
                       (answer leaf-text)))]
      (try
        (let [sid (new-session database)]
          (append! database sid (mapv #(str % " " (apply str (repeat 500 "x")))
                                      ["first-original" "second-original"]))
          (summaries/request! manager sid config)
          (is (string? (deref merge-entered 5000 nil)))
          (append! database sid [(str "third-original " (apply str (repeat 500 "x")))])
          (summaries/request! manager sid config)
          (is (= true (deref next-leaf-entered 5000 nil)))
          (let [entries (sources database sid)
                originals (store/entries database sid)
                waiting (future (summaries/ensure-ready! manager sid entries config {}))
                result (deref waiting 5000 ::timeout)]
            (is (= entries (:entries result)))
            (is (= [1 1 1] (mapv :count (:view result))))
            (is (:fits? (meta (:view result))))
            (is (= (tree/utf8-bytes (:text result)) (:bytes result)))
            (is (not (str/includes? (:text result) "third-original")))
            (is (nil? (context/node database sid (tree/node-key entries 0 2))))
            (is (false? (summaries/await-session! manager sid 0)))
            (is (true? (:worker-active? (summaries/inspect manager sid))))
            (is (= originals (store/entries database sid)))
            (.countDown release)
            (is (summaries/await-session! manager sid 5000))
            (is (some? (context/node database sid (tree/node-key entries 0 2))))))
        (finally (.countDown release))))))

(deftest clearing-cache-during-a-held-coordinator-rebuilds-a-truthful-frozen-view
  (let [merge-entered (promise) release (CountDownLatch. 1)
        rebuilding? (atom false) leaf-calls (atom 0) parent-calls (atom 0)
        old-text (apply str (repeat 60 "o")) new-text (apply str (repeat 60 "n"))]
    (with-service [database manager
                   (fn [_ request _]
                     (if (str/includes? (get-in request [:request/messages 1 :message/content])
                                        "Compress this original evidence")
                       (do (swap! leaf-calls inc) (answer (if @rebuilding? new-text old-text)))
                       (do (swap! parent-calls inc)
                           (deliver merge-entered true)
                           (await-release! release)
                           (answer "Complete parent evidence"))))]
      (try
        (let [sid (new-session database)
              entries (append! database sid (vec (repeat 2 (apply str (repeat 500 "x")))))
              before (summaries/ensure-ready! manager sid entries config {})
              slot (get @(:slots manager) sid)
              originals (store/entries database sid)]
          (is (= true (deref merge-entered 5000 nil)))
          (is (= 2 @leaf-calls))
          (context/clear-nodes! database sid)
          (reset! rebuilding? true)
          (is (nil? (:view (summaries/inspect manager sid))))
          (let [waiting (future (summaries/ensure-ready! manager sid entries config {}))
                rebuilt (deref waiting 5000 ::timeout)]
            (is (= entries (:entries rebuilt)))
            (is (:fits? (meta (:view rebuilt))))
            (is (not= (:text before) (:text rebuilt)))
            (is (str/includes? (:text rebuilt) new-text))
            (is (not (str/includes? (:text rebuilt) old-text)))
            (is (= 4 @leaf-calls))
            (is (= 1 @parent-calls))
            (doseq [node (vals (:nodes rebuilt))]
              (is (= node (context/node database sid (:id node)))))
            (is (identical? slot (get @(:slots manager) sid)))
            (is (= #{slot} (get @(:exits manager) sid)))
            (is (false? (summaries/await-session! manager sid 0)))
            (is (= originals (store/entries database sid))))
          (.countDown release)
          (is (summaries/await-session! manager sid 5000))
          (is (= 3 (:node-count (summaries/inspect manager sid))))
          (is (= 4 @leaf-calls))
          (is (= 1 @parent-calls)))
        (finally (.countDown release))))))

(deftest healthy-chronological-catch-up-can-outlast-a-node-deadline
  (let [calls (atom 0) leaf-text (apply str (repeat 60 "s"))]
    (with-service [database manager
                   (fn [& _]
                     (swap! calls inc)
                     (Thread/sleep 150)
                     (answer leaf-text))]
      (let [sid (new-session database)
            entries (append! database sid (vec (repeat 4 (apply str (repeat 500 "x")))))
            cfg (assoc-in config [:settings :summary-timeout-ms] 500)
            started (System/nanoTime)
            result (summaries/ensure-ready! manager sid entries cfg {})
            elapsed-ms (/ (- (System/nanoTime) started) 1000000)]
        (is (> elapsed-ms 500))
        (is (= entries (:entries result)))
        (is (:fits? (meta (:view result))))
        (is (summaries/await-session! manager sid 5000))
        (is (= 7 @calls))
        (is (= :idle (:status (summaries/inspect manager sid))))
        (is (= 7 (:node-count (summaries/inspect manager sid))))))))

(deftest node-failure-retains-unused-parent-ownership-and-partial-accounting
  (let [merge-entered (promise) release (CountDownLatch. 1)
        fail? (atom true) calls (atom 0) leaf-text (apply str (repeat 60 "e"))]
    (with-service [database manager
                   (fn [_ request _]
                     (swap! calls inc)
                     (let [input (get-in request [:request/messages 1 :message/content])]
                       (cond
                         (str/includes? input (str leaf-text "\n\n" leaf-text))
                         (do (deliver merge-entered true) (await-release! release))

                         (and @fail? (str/includes? input "failing-original"))
                         (do
                           (when-not (= true (deref merge-entered 5000 nil))
                             (throw (ex-info "Parent did not start independently" {})))
                           (throw (ex-info "Actual provider failure"
                                           {:error/code "provider/unavailable"
                                            :partial-response (answer "uncommitted completion")}))))
                       (answer leaf-text)))]
      (try
        (let [sid (new-session database)
              entries (append! database sid (mapv #(str % " " (apply str (repeat 500 "x")))
                                                  ["first-original" "second-original" "failing-original"]))
              waiting (future (error-code #(summaries/ensure-ready! manager sid entries config {})))]
          (is (= true (deref merge-entered 5000 nil)))
          (is (= "provider/unavailable" (deref waiting 5000 ::timeout)))
          (is (false? (summaries/await-session! manager sid 0)))
          (is (true? (:worker-active? (summaries/inspect manager sid))))
          (is (= 10 (get-in (summaries/inspect manager sid) [:unpersisted-usage :usage/total-tokens])))
          (is (nil? (context/node database sid (tree/node-key entries 2 1))))
          (let [slot (get @(:slots manager) sid)]
            (reset! fail? false)
            (let [retrying (future (summaries/ensure-ready! manager sid entries config {}))
                  result (deref retrying 5000 ::timeout)]
              (is (= entries (:entries result)))
              (is (:fits? (meta (:view result))))
              (is (= 5 @calls))
              (is (identical? slot (get @(:slots manager) sid)))
              (is (= #{slot} (get @(:exits manager) sid)))
              (is (false? (summaries/await-session! manager sid 0)))))
          (.countDown release)
          (is (summaries/await-session! manager sid 5000))
          (is (= :idle (:status (summaries/inspect manager sid))))
          (is (= 5 @calls)))
        (finally (.countDown release))))))

(deftest ready-node-pump-bounds-concurrent-merges-without-queuing-every-node
  (let [release (CountDownLatch. 1) entered (CountDownLatch. 7)
        active (atom 0) peak (atom 0) leaf-text (apply str (repeat 60 "q"))]
    (with-service [database manager
                   (fn [_ request _]
                     (let [n (swap! active inc)]
                       (swap! peak max n)
                       (try
                         (when (str/includes? (get-in request [:request/messages 1 :message/content])
                                               (str leaf-text "\n\n" leaf-text))
                           (.countDown entered)
                           (await-release! release))
                         (answer leaf-text)
                         (finally (swap! active dec)))))]
      (try
        (let [sid (new-session database)
              entries (append! database sid (vec (repeat 14 (apply str (repeat 500 "x")))))
              waiting (future (summaries/ensure-ready! manager sid entries config {}))]
          (is (.await entered 5 TimeUnit/SECONDS))
          (let [result (deref waiting 5000 ::timeout)]
            (is (= entries (:entries result)))
            (is (:fits? (meta (:view result)))))
          (is (<= @peak 8))
          (is (false? (summaries/await-session! manager sid 0)))
          (.countDown release)
          (is (summaries/await-session! manager sid 5000))
          (is (<= @peak 8)))
        (finally (.countDown release))))))

(deftest independent-parent-failures-do-not-reject-ready-views-or-rearm-each-other
  (let [calls (atom 0) merge-calls (atom 0) leaf-text (apply str (repeat 60 "m"))]
    (with-service [database manager
                   (fn [_ request _]
                     (swap! calls inc)
                     (if (str/includes? (get-in request [:request/messages 1 :message/content])
                                        (str leaf-text "\n\n" leaf-text))
                       (do
                         (swap! merge-calls inc)
                         (throw (ex-info "Independent parent failure" {:error/code "provider/unavailable"})))
                       (answer leaf-text)))]
      (let [sid (new-session database)
            entries (append! database sid (vec (repeat 4 (apply str (repeat 500 "x")))))
            result (summaries/ensure-ready! manager sid entries config {})]
        (is (= entries (:entries result)))
        (is (:fits? (meta (:view result))))
        (is (summaries/await-session! manager sid 5000))
        (is (= 2 @merge-calls))
        (is (= 6 @calls))
        (is (= :failed (:status (summaries/inspect manager sid))))
        (is (= (:text result) (:text (summaries/ensure-ready! manager sid entries config {}))))
        (append! database sid ["A short later original"])
        (summaries/request! manager sid config)
        (is (summaries/await-session! manager sid 5000))
        (is (= 2 @merge-calls))
        (is (= 6 @calls))
        (is (= 5 (get-in (summaries/inspect manager sid) [:view :source-count])))
        (is (:fits? (meta (:view (summaries/ensure-ready! manager sid (sources database sid) config {})))))))))
