(ns arrodes.context-tree-store-test
  (:require [arrodes.context-tree :as tree]
            [arrodes.platform :as util]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.context-tree :as context]
            [arrodes.store.db :as db]
            [arrodes.store.records :as records]
            [arrodes.store.sql :as sql]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(def config {:provider :openai :model "gpt-4o-mini" :thinking :medium
             :tools :all :instructions "Context cache test" :settings {}})

(defmacro with-memory-store [[binding] & body]
  `(let [~binding (db/open! {:memory? true})]
     (try ~@body (finally (db/close! ~binding)))))

(defn- new-session [database]
  (:id (store/create-session! database {:name "Context cache" :config config
                                        :cwd (System/getProperty "java.io.tmpdir")})))

(defn- append! [database sid texts]
  (:entries (store/commit! database sid
                          {::command/entries
                           (mapv (fn [text] {:kind :message
                                            :data {:message/role :user :message/content text}})
                                 texts)})))

(defn- error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:error/code (ex-data error)))))

(defn- remove-directory! [directory]
  (with-open [walk (Files/walk (util/path directory)
                               (make-array java.nio.file.FileVisitOption 0))]
    (doseq [file (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator walk)))]
      (Files/deleteIfExists file))))

(deftest completed-nodes-are-immutable-and-account-only-for-reported-usage
  (with-memory-store [database]
    (let [sid (new-session database)
          entries (append! database sid ["first" "second"])
          left (assoc (tree/leaf-node entries 0 "résumé")
                      :provider :openai :model "fixture-summary" :created-at 123
                      :usage {:usage/input-tokens 10 :usage/cached-input-tokens 4}
                      :cost {:cost/usd 0.01})
          right (tree/leaf-node entries 1 "second")
          parent (assoc (tree/parent-node left right "both")
                        :usage {:usage/output-tokens 2} :cost {:cost/usd 0.02})
          history (store/entries database sid)]
      (is (= {} (context/nodes database sid)))
      (is (= left (context/put-node! database sid left)))
      (is (= left (context/put-node! database sid left)))
      (is (= "context-node-conflict"
             (error-code #(context/put-node! database sid (assoc left :model "other")))))
      (is (= "context-node-conflict"
             (error-code #(context/put-node! database sid
                                            (assoc left :text "replacement" :bytes 11)))))
      (is (= right (context/put-node! database sid right)))
      (is (= parent (context/put-node! database sid parent)))
      (is (= {(:id left) left (:id right) right (:id parent) parent}
             (context/nodes database sid)))
      (is (= left (context/node database sid (:id left))))
      (is (= {:node-count 3 :leaf-count 2
              :summary-bytes (+ (:bytes left) (:bytes right) (:bytes parent))
              :usage {:usage/input-tokens 10 :usage/cached-input-tokens 4 :usage/output-tokens 2}
              :cost {:cost/usd (+ 0.01 0.02)} :usage-node-count 2 :cost-node-count 2}
             (context/status database sid)))
      (is (= history (store/entries database sid)))
      (is (empty? (store/operations database {:session-id sid}))))))

(deftest puts-check-source-authorization-before-linking-other-sessions
  (with-memory-store [database]
    (let [owner (new-session database)
          foreign (new-session database)
          source (append! database owner ["owned original"])
          leaf (tree/leaf-node source 0 "owned")]
      (context/put-node! database owner leaf)
      (is (nil? (context/node database foreign (:id leaf))))
      (is (= "context-source-forbidden"
             (error-code #(context/put-node! database foreign leaf))))
      (is (= "context-source-forbidden"
             (error-code #(context/put-node! database owner
                                            (tree/leaf-node [{:id (util/id)}] 0 "missing")))))
      (is (= "session-not-found" (error-code #(context/nodes database (util/id)))))
      (is (= "session-not-found" (error-code #(context/node database (util/id) (:id leaf)))))
      (is (= "session-not-found" (error-code #(context/status database (util/id)))))
      (is (= "session-not-found" (error-code #(context/clear-nodes! database (util/id)))))
      (is (= {} (context/nodes database foreign))))))

(deftest spans-and-children-must-be-completed-and-match-their-originals
  (with-memory-store [database]
    (let [sid (new-session database)
          entries (append! database sid ["first" "second" "third"])
          left (tree/leaf-node entries 0 "one")
          right (tree/leaf-node entries 1 "two")
          parent (tree/parent-node left right "one and two")]
      (doseq [invalid [(assoc left :id "invented")
                       (assoc left :bytes 0)
                       (assoc left :left-id (:id right))
                       (assoc left :count 3)
                       (assoc left :start 1)
                       (assoc left :provider nil)
                       (assoc left :usage 42)
                       (assoc left :cost 0.01)
                       (assoc left :created-at -1)
                       (assoc left :unknown true)]]
        (is (some? (error-code #(context/put-node! database sid invalid)))))
      (is (= "invalid-context-node" (error-code #(context/put-node! database sid parent))))
      (context/put-node! database sid left)
      (is (= "invalid-context-node" (error-code #(context/put-node! database sid parent))))
      (context/put-node! database sid right)
      (is (= "invalid-context-node"
             (error-code #(context/put-node! database sid (assoc parent :right-id (:id left))))))
      (is (= "invalid-context-node"
             (error-code #(context/put-node! database sid (assoc parent :start 1)))))
      (is (= parent (context/put-node! database sid parent)))
      (is (= 3 (:node-count (context/status database sid)))))))

(deftest branch-positions-ignore-global-sequence-and-administrative-entries
  (with-memory-store [database]
    (let [sid (new-session database)
          prefix (append! database sid ["shared"])]
      (store/configure! database sid {:thinking :high})
      (store/commit! database sid {::command/entries [{:kind :custom-context :data {}}]})
      (append! database sid ["abandoned"])
      (let [original (tree/source-entries (store/active-path database sid))
            old-left (tree/leaf-node original 0 "shared")
            old-right (tree/leaf-node original 1 "abandoned")]
        (context/put-node! database sid old-left)
        (context/put-node! database sid old-right)
        (store/branch! database sid (:id (first prefix)) {})
        (append! database sid ["replacement"])
        (let [replacement (tree/source-entries (store/active-path database sid))
              new-right (tree/leaf-node replacement 1 "replacement")]
          (is (> (:seq (second replacement)) 2))
          (is (= old-left (tree/leaf-node replacement 0 "shared")))
          (with-redefs [records/all-entries (fn [& _] (throw (ex-info "Full history scan" {})))]
            (is (= new-right (context/put-node! database sid new-right)))
            (is (= (tree/parent-node old-left new-right "shared and replacement")
                   (context/put-node! database sid
                                      (tree/parent-node old-left new-right "shared and replacement")))))
          (store/branch! database sid nil {})
          (let [other (append! database sid ["different root" "different second"])
                other-left (tree/leaf-node other 0 "different")
                other-right (tree/leaf-node other 1 "different second")]
            (context/put-node! database sid other-left)
            (context/put-node! database sid other-right)
            (is (= "invalid-context-branch"
                   (error-code #(context/put-node! database sid
                                                  (tree/parent-node old-left other-right "wrong branch")))))
            (is (= old-right (context/node database sid (:id old-right))))))))))

(deftest administrative-records-cannot-be-used-as-tree-sources
  (with-memory-store [database]
    (let [sid (new-session database)
          entry (first (:entries (store/configure! database sid {:thinking :high})))
          empty-context (first (:entries
                                (store/commit! database sid
                                               {::command/entries [{:kind :custom-context :data {}}]})))]
      (doseq [source [entry empty-context]]
        (is (= "invalid-context-node"
               (error-code #(context/put-node! database sid (tree/leaf-node [source] 0 "not a source"))))))
      (is (empty? (context/nodes database sid))))))

(deftest clearing-and-session-deletion-remove-only-owned-derived-data
  (with-memory-store [database]
    (let [sid (new-session database)
          other (new-session database)
          entries (append! database sid ["first" "second"])
          other-source (append! database other ["unrelated"])
          left (tree/leaf-node entries 0 "one")
          right (tree/leaf-node entries 1 "two")
          parent (tree/parent-node left right "both")
          other-node (tree/leaf-node other-source 0 "unrelated")
          history (store/entries database sid)]
      (doseq [node [left right parent]] (context/put-node! database sid node))
      (context/put-node! database other other-node)
      (is (= {:cleared 3} (context/clear-nodes! database sid)))
      (is (= history (store/entries database sid)))
      (is (= other-node (context/node database other (:id other-node))))
      (doseq [node [left right parent]] (context/put-node! database sid node))
      (store/delete-session! database sid)
      (is (= 0 (db/store-read database
                 #(sql/scalar % "SELECT COUNT(*) FROM context_nodes WHERE session_id=?" [sid]))))
      (is (= other-node (context/node database other (:id other-node)))))))

(deftest current-format-restart-reuses-nodes-without-history-or-operation-mutation
  (let [directory (str (Files/createTempDirectory "arrodes-context-store-"
                                                 (make-array FileAttribute 0)))
        path (str directory "/sessions.sqlite")
        database (db/open! {:path path})]
    (try
      (let [sid (new-session database)
            entries (append! database sid ["original"])
            node (assoc (tree/leaf-node entries 0 "retained résumé")
                        :provider :openai :model "fixture-summary" :created-at 7
                        :usage {:usage/total-tokens 12} :cost {:cost/usd 0.03})
            history (store/entries database sid)
            snapshot (store/session database sid)]
        (context/put-node! database sid node)
        (db/close! database)
        (let [reopened (db/open! {:path path})]
          (try
            (is (= 6 (db/store-read reopened #(sql/scalar % "PRAGMA user_version" []))))
            (is (= {(:id node) node} (context/nodes reopened sid)))
            (is (= node (context/put-node! reopened sid node)))
            (is (= history (store/entries reopened sid)))
            (is (= snapshot (store/session reopened sid)))
            (is (empty? (store/operations reopened {:session-id sid})))
            (finally (db/close! reopened))))
        (with-open [files (Files/list (util/path directory))]
          (is (not-any? #(re-find #"\.backup$" (str %)) (iterator-seq (.iterator files))))))
      (finally (db/close! database) (remove-directory! directory)))))
