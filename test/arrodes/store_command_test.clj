(ns arrodes.store-command-test
  (:require [arrodes.platform :as util]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.db :as db]
            [clojure.test :refer [deftest is testing]]))

(deftest invalid-envelopes-cannot-consume-queued-input-or-change-history
  (let [database (db/open! {:memory? true})]
    (try
      (let [sid (:id (store/create-session! database
                         {:cwd (System/getProperty "java.io.tmpdir") :name "Contract boundary"}))
            qid (util/id)
            entry {:kind :message :data {:message/role :user :message/content "Deliver once"}}]
        (store/commit! database sid
                       {::command/queue-enqueue [{:id qid :kind :steering :content "Deliver once"}]})
        (let [before (store/session database sid)
              events (store/events-since database {:session-id sid})
              valid {::command/expected-revision (:revision before)
                     ::command/queue-deliver [qid]
                     ::command/entries [entry]}]
          (doseq [invalid [(assoc valid :entries [entry])
                           (assoc valid ::command/queue-delvier [qid])
                           (assoc valid ::command/entries entry)
                           (assoc valid ::command/expected-revision "1")]]
            (testing (pr-str (keys invalid))
              (let [error (try (store/commit! database sid invalid) nil
                              (catch clojure.lang.ExceptionInfo error error))]
                (is (= "invalid-store-command" (:error/code (ex-data error)))))
              (is (= before (store/session database sid)))
              (is (= events (store/events-since database {:session-id sid})))
              (is (= [qid] (mapv :id (store/pending database sid))))
              (is (= [] (store/entries database sid)))))
          (store/commit! database sid valid)
          (is (= [] (store/pending database sid)))
          (is (= [{:message/role :user :message/content "Deliver once"}]
                 (store/context-messages database sid)))))
      (finally (db/close! database)))))
