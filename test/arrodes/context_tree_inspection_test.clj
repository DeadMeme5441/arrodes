(ns arrodes.context-tree-inspection-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [arrodes.commands :as commands]
            [arrodes.context-tree :as tree]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.session-test :as sessions]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.context-tree :as tree-store]
            [arrodes.summaries :as summaries]))

(def ^:private tree-config
  (assoc sessions/config :settings
         {:context-policy :summary-tree :summary-provider :openai
          :summary-model "inspection-fixture" :summary-node-bytes 16384
          :summary-view-bytes 4096 :summary-timeout-ms 10000
          :auto-title? false :auto-compact? false :provider-retries 0}))

(defn- create! [rt]
  (:id (runtime/create-session! rt {:name "Read-only context" :config tree-config})))

(defn- no-inference [calls]
  (fn [request _]
    (swap! calls conj request)
    (throw (ex-info "Inspection must not infer" {}))))

(defn- seed! [rt sid]
  (:entries (store/commit! (:store rt) sid
                           {::command/entries [(sessions/message-entry :user "Original evidence")
                                               (sessions/message-entry :assistant "Original answer")]})))

(deftest persisted-inspection-reopens-without-inference-or-evaluator
  (let [directory (sessions/temp-directory)
        calls (atom [])
        options {:cwd directory :home (str directory "/home") :data-dir (str directory "/data")
                 :complete-fn (no-inference calls)}]
    (try
      (let [{:keys [sid leaves canonical events]}
            (let [rt (runtime/open! options)]
              (try
                (let [sid (create! rt)
                      _ (seed! rt sid)
                      entries (tree/source-entries (runtime/active-path rt sid))
                      left (assoc (tree/leaf-node entries 0 "Retained original evidence")
                                  :usage {:usage/input-tokens 10 :usage/output-tokens 2}
                                  :cost {:cost/usd 0.01})
                      right (tree/leaf-node entries 1 "Retained original answer")]
                  (doseq [node [left right (tree/parent-node left right "Retained complete turn")]]
                    (tree-store/put-node! (:store rt) sid node))
                  (is (empty? @(:handles rt)))
                  {:sid sid :leaves [left right]
                   :canonical (commands/dispatch! rt "session.tree" {:session-id sid})
                   :events (runtime/events-since rt {:session-id sid})})
                (finally (runtime/close! rt))))
            rt (runtime/open! options)]
        (try
          (let [before (runtime/session rt sid)
                inspected (commands/dispatch! rt "session.context" {:session-id sid})
                view (:view inspected)]
            (is (= :preview (:mode view)))
            (is (= :summary-tree (:policy view)))
            (is (true? (:ready? view)))
            (is (true? (:fits? view)))
            (is (= 2 (:source-count view) (:covered-count view)))
            (is (= (mapv :id leaves) (mapv :id (:nodes view))))
            (is (= (tree/utf8-bytes (tree/render-view (:nodes view))) (:bytes view)))
            (is (<= (:bytes view) (:budget view)))
            (is (= 3 (get-in inspected [:summary :node-count])))
            (is (= (:usage (first leaves)) (get-in inspected [:summary :usage])))
            (is (= (:cost (first leaves)) (get-in inspected [:summary :cost])))
            (is (= {} (runtime/usage rt sid)))
            (is (= before (runtime/session rt sid)))
            (is (= canonical (commands/dispatch! rt "session.tree" {:session-id sid})))
            (is (= events (runtime/events-since rt {:session-id sid})))
            (is (empty? @(:handles rt)))
            (is (empty? @(:context-views rt)))
            (is (empty? @(:slots (:summaries rt))))
            (is (empty? @calls)))
          (finally (runtime/close! rt))))
      (finally (sessions/remove-directory! directory)))))

(deftest persisted-frontier-reopens-and-appends-available-history-without-inference
  (let [directory (sessions/temp-directory)
        calls (atom [])
        options {:cwd directory :home (str directory "/home") :data-dir (str directory "/data")
                 :complete-fn (no-inference calls)}]
    (try
      (let [{:keys [sid parent leaves snapshot canonical events]}
            (let [rt (runtime/open! options)]
              (try
                (let [sid (create! rt)
                      _ (seed! rt sid)
                      entries (tree/source-entries (runtime/active-path rt sid))
                      left (tree/leaf-node entries 0 "Original evidence")
                      right (tree/leaf-node entries 1 "Original answer")
                      parent (tree/parent-node left right "Complete original turn")]
                  (doseq [node [left right parent]]
                    (tree-store/put-node! (:store rt) sid node))
                  (runtime/configure! rt sid {:metadata {:context/view-node-ids [(:id parent)]}})
                  {:sid sid :parent parent :leaves [left right]
                   :snapshot (runtime/session rt sid)
                   :canonical (commands/dispatch! rt "session.tree" {:session-id sid})
                   :events (runtime/events-since rt {:session-id sid})})
                (finally (runtime/close! rt))))
            rt (runtime/open! options)]
        (try
          (let [view (:view (runtime/context-inspect rt sid))]
            (is (= [(:id parent)] (mapv :id (:nodes view))))
            (is (= :preview (:mode view)))
            (is (true? (:ready? view)))
            (is (= 2 (:source-count view) (:covered-count view)))
            (is (= 4096 (:budget view)))
            (is (= (tree/utf8-bytes (tree/render-view (:nodes view))) (:bytes view)))
            (is (= snapshot (runtime/session rt sid)))
            (is (= canonical (commands/dispatch! rt "session.tree" {:session-id sid})))
            (is (= events (runtime/events-since rt {:session-id sid}))))
          (store/commit! (:store rt) sid
                         {::command/entries [(sessions/message-entry :user "New source evidence")]})
          (let [sources (tree/source-entries (runtime/active-path rt sid))
                current (tree/leaf-node sources 2 "New source summary")
                pending (:view (runtime/context-inspect rt sid))]
            (is (= [(:id parent)] (mapv :id (:nodes pending))))
            (is (= 3 (:source-count pending)))
            (is (= 2 (:covered-count pending) (:next-index pending)))
            (is (false? (:ready? pending)))
            (is (false? (:fits? pending)))
            (is (= :pending-leaf (:reason pending)))
            (tree-store/put-node! (:store rt) sid current)
            (let [ready (:view (runtime/context-inspect rt sid))]
              (is (= [(:id parent) (:id current)] (mapv :id (:nodes ready))))
              (is (true? (:ready? ready)))
              (is (true? (:fits? ready)))
              (is (= 3 (:source-count ready) (:covered-count ready)))
              (is (= 4096 (:budget ready)))
              (is (= (tree/utf8-bytes (tree/render-view (:nodes ready))) (:bytes ready))))
            (doseq [metadata [nil [] 1 "not-a-vector" {:node (:id parent)} [1]
                             ["missing-node"] [(:id parent) "missing-node"]
                             [(:id parent) (:id parent)] [(get-in leaves [1 :id])]]]
              (runtime/configure! rt sid {:metadata {:context/view-node-ids metadata}})
              (let [before (runtime/session rt sid)
                    view (:view (runtime/context-inspect rt sid))]
                (is (= (mapv :id (conj leaves current)) (mapv :id (:nodes view))))
                (is (true? (:ready? view)))
                (is (true? (:fits? view)))
                (is (= before (runtime/session rt sid))))))
          (is (empty? @(:handles rt)))
          (is (empty? @(:context-views rt)))
          (is (empty? @(:slots (:summaries rt))))
          (is (empty? @calls))
          (finally (runtime/close! rt))))
      (finally (sessions/remove-directory! directory)))))

(deftest oversized-frontier-preference-yields-only-to-a-fitting-complete-view
  (let [entries [{:id "first"} {:id "last"}]
        left (tree/leaf-node entries 0 "First")
        right (tree/leaf-node entries 1 "Last")
        parent (tree/parent-node left right (apply str (repeat 1000 "x")))
        nodes (into {} (map (juxt :id identity) [left right parent]))
        budget (tree/utf8-bytes (tree/render-view [left right]))
        fallback (tree/build-view entries nodes budget)]
    (is (= [left right] (tree/restore-frontier fallback entries nodes [(:id parent)] budget)))
    (let [frontier (tree/restore-frontier (tree/build-view entries nodes 1)
                                         entries nodes [(:id parent)] 1)]
      (is (= [parent] frontier))
      (is (false? (:fits? (meta frontier))))
      (is (= :irreducible-budget (:reason (meta frontier))))
      (is (= (tree/utf8-bytes (tree/render-view frontier)) (:bytes (meta frontier)))))))

(deftest available-preview-is-path-scoped-pending-and-byte-bounded
  (let [calls (atom [])]
    (fixtures/with-runtime [rt (no-inference calls)]
      (let [sid (create! rt)
            [original answer] (seed! rt sid)
            base (tree/source-entries (runtime/active-path rt sid))
            left (tree/leaf-node base 0 "Original evidence")
            right (tree/leaf-node base 1 "Original answer")
            parent (tree/parent-node left right "Complete original turn")]
        (doseq [node [left right parent]]
          (tree-store/put-node! (:store rt) sid node))
        (let [alternate (:entries (store/commit! (:store rt) sid
                                                {::command/entries [(sessions/message-entry :user "Off-branch evidence")]}))
              alternate-sources (tree/source-entries (runtime/active-path rt sid))
              alternate-leaf (tree/leaf-node alternate-sources 2 "Off-branch summary")]
          (tree-store/put-node! (:store rt) sid alternate-leaf)
          (store/branch! (:store rt) sid (:id answer) {})
          (store/commit! (:store rt) sid
                         {::command/entries [(sessions/message-entry :user "Current branch evidence")]})
          ;; A service projection is advisory, not proof of active-path coverage.
          (swap! (:projections (:summaries rt)) assoc sid
                 {:view [alternate-leaf] :source-count 1 :bytes 0 :budget 4096 :fits? true})
          (runtime/configure! rt sid {:metadata {:context/view-node-ids [(:id alternate-leaf)]}})
          (let [canonical (commands/dispatch! rt "session.tree" {:session-id sid})
                events (runtime/events-since rt {:session-id sid})
                sources (tree/source-entries (runtime/active-path rt sid))
                current (tree/leaf-node sources 2 (apply str (repeat 1000 "x")))
                inspected (runtime/context-inspect rt sid)
                view (:view inspected)]
            (is (= [(:id original) (:id answer)] (mapv :first-entry-id (:nodes view))))
            (is (= 3 (:source-count view)))
            (is (= 2 (:covered-count view)))
            (is (false? (:ready? view)))
            (is (false? (:fits? view)))
            (is (= :pending-leaf (:reason view)))
            (is (= 2 (:next-index view)))
            (is (not-any? #(= (:id alternate-leaf) (:id %)) (:nodes view)))
            (is (= view (get-in inspected [:summary :view])))
            (is (some #(= (:id (first alternate)) (:id %)) (:entries canonical)))
            (is (= canonical (commands/dispatch! rt "session.tree" {:session-id sid})))
            (is (= events (runtime/events-since rt {:session-id sid})))
            (tree-store/put-node! (:store rt) sid current)
            (let [ready (:view (runtime/context-inspect rt sid))]
              (is (true? (:ready? ready)))
              (is (= 3 (:source-count ready) (:covered-count ready))))
            (testing "An over-budget complete frontier exposes only whole bounded nodes"
              (let [budget (tree/utf8-bytes (tree/render-view [parent]))]
                (runtime/configure! rt sid {:config {:settings {:summary-view-bytes budget}}
                                           :metadata {:context/view-node-ids [(:id parent)]}})
                (let [bounded (:view (runtime/context-inspect rt sid))]
                  (is (= [(:id parent)] (mapv :id (:nodes bounded))))
                  (is (= budget (:bytes bounded) (:budget bounded)))
                  (is (= 3 (:source-count bounded)))
                  (is (= 2 (:covered-count bounded)))
                  (is (false? (:ready? bounded)))
                  (is (false? (:fits? bounded)))
                  (is (= :irreducible-budget (:reason bounded)))
                  (is (> (:required-bytes bounded) (:budget bounded)))))
              (runtime/configure! rt sid {:settings {:summary-view-bytes 1}})
              (let [bounded (:view (runtime/context-inspect rt sid))]
                (is (= [] (:nodes bounded)))
                (is (= 0 (:bytes bounded) (:covered-count bounded)))
                (is (= 3 (:source-count bounded)))
                (is (false? (:ready? bounded)))
                (is (> (:required-bytes bounded) 1))))
            (tree-store/clear-nodes! (:store rt) sid)
            (runtime/configure! rt sid {:settings {:summary-view-bytes 4096}})
            (let [pending (:view (runtime/context-inspect rt sid))]
              (is (= 3 (:source-count pending)))
              (is (= 0 (:covered-count pending) (:bytes pending)))
              (is (= :pending-leaf (:reason pending)))
              (is (false? (:ready? pending))))
            (is (= (tree/source-entries (:entries canonical))
                   (tree/source-entries (runtime/entries rt sid))))
            (is (empty? @(:handles rt)))
            (is (empty? @(:slots (:summaries rt))))
            (is (empty? @calls))))))))

(deftest inspection-keeps-only-current-operation-frozen-views
  (let [host (atom nil) observed (atom [])]
    (fixtures/with-runtime
      [rt (fn [request _]
            (is (not (str/ends-with? (str (get-in request [:request/cache :scope-id])) ":summary-tree")))
            (let [rt @host
                  [sid slot] (first @(:foreground rt))
                  operation-id (:operation-id slot)]
              (when (= 2 (count @observed))
                (runtime/configure! rt sid {:settings {:context-policy :linear :summary-view-bytes 1}}))
              (swap! observed conj
                     {:owner operation-id :published-owner (get-in @(:context-views rt) [sid :operation-id])
                      :inspection (runtime/context-inspect rt sid)
                      :path-source-count (count (tree/source-entries (runtime/active-path rt sid)))})
              (fixtures/answer "Completed without replay")))]
      (reset! host rt)
      (let [sid (create! rt)]
        (seed! rt sid)
        (runtime/run! rt sid "Tree request" {:config {:settings {:summary-view-bytes 6144}}})
        (is (summaries/await-session! (:summaries rt) sid 10000))
        (let [idle (:view (runtime/context-inspect rt sid))]
          (is (= :preview (:mode idle)))
          (is (= 4096 (:budget idle)))
          (is (= (count (tree/source-entries (runtime/active-path rt sid))) (:source-count idle))))
        (runtime/run! rt sid "Linear override" {:config {:settings {:context-policy :linear}}})
        (is (summaries/await-session! (:summaries rt) sid 10000))
        (runtime/run! rt sid "Tree override" {:config {:settings {:context-policy :summary-tree
                                                                 :summary-view-bytes 8192}}})
        (let [[first-run linear-run final-run] @observed
              first-view (get-in first-run [:inspection :view])
              linear-view (get-in linear-run [:inspection :view])
              final-view (get-in final-run [:inspection :view])]
          (is (= 3 (count @observed)))
          (is (= :active (:mode first-view)))
          (is (= (:owner first-run) (:operation-id first-view)))
          (is (= 6144 (:budget first-view)))
          (is (= 2 (:source-count first-view)))
          (is (true? (:ready? first-view)))
          (is (= (:owner first-run) (:published-owner linear-run)))
          (is (not= (:owner linear-run) (:published-owner linear-run)))
          (is (= :preview (:mode linear-view)))
          (is (not (contains? linear-view :operation-id)))
          (is (= 4096 (:budget linear-view)))
          (is (= (:path-source-count linear-run) (:source-count linear-view)))
          (is (= :linear (get-in final-run [:inspection :policy])))
          (is (= 1 (get-in final-run [:inspection :settings :summary-view-bytes])))
          (is (= :active (:mode final-view)))
          (is (= :summary-tree (:policy final-view)))
          (is (= (:owner final-run) (:operation-id final-view)))
          (is (= 8192 (:budget final-view)))
          (is (true? (:ready? final-view)))
          (is (= (:covered-count final-view) (:source-count final-view)))
          (is (< (:source-count final-view) (:path-source-count final-run)))
          (is (= (tree/utf8-bytes (tree/render-view (:nodes final-view))) (:bytes final-view)))
          (is (<= (:bytes final-view) (:budget final-view))))))))
