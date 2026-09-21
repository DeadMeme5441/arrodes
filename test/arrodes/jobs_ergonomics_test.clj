(ns arrodes.jobs-ergonomics-test
  (:require [clojure.test :refer [deftest is]]
            [arrodes.artifacts :as artifacts]
            [arrodes.commands :as commands]
            [arrodes.jobs :as jobs]
            [arrodes.jobs-test :refer [eval! await!]]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.store :as store]))

(deftest status-views-are-compact-and-detailed-records-and-native-values-remain-accessible
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def j (jobs/start! {:name \"Useful\"} #(hash-map :ratio 2/3 :payload (vec (range 1000))))) j")
          _ (await! rt sid handle)
          compact (eval! rt sid "(jobs/inspect j)")
          detailed (eval! rt sid "(jobs/inspect j {:detailed? true})")]
      (is (= #{:id :name :status :result-id :duration-ms :result-available?} (set (keys compact))))
      (is (= :completed (:status compact)))
      (is (:result-available? compact))
      (is (<= 0 (:duration-ms compact)))
      (is (:origin detailed))
      (is (:result detailed))
      (is (< (count (pr-str compact)) 350))
      (is (= [compact] (eval! rt sid "(jobs/list)")))
      (is (= [detailed] (eval! rt sid "(jobs/list {:detailed? true})")))
      (is (= compact (eval! rt sid "(jobs/wait j)")))
      (is (= detailed (eval! rt sid "(jobs/wait j {:detailed? true})")))
      (is (= {:ratio 2/3 :payload (vec (range 1000))}
             (eval! rt sid "(jobs/result (first (jobs/list)))")))
      ;; The UI/RPC needs full provenance; the model's compact default does not strip it.
      (is (:origin (commands/dispatch! rt "job.inspect" {:session-id sid :job-id (:id handle)}))))))

(deftest cancellation-is-distinct-from-unrequested-interruption-and-retains-the-cause
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def entered (promise)) (def j (jobs/start! #(do (deliver entered true) (Thread/sleep 60000)))) @entered j")]
      (eval! rt sid "(jobs/cancel! j)")
      (let [record (await! rt sid handle)
            compact (eval! rt sid "(jobs/inspect j)")
            retained (artifacts/result (:store rt) sid (:result-id record))]
        (is (= :cancelled (:status record)))
        (is (= "cancelled" (get-in record [:error :code])))
        (is (= "java.lang.InterruptedException" (get-in record [:error :cause :class])))
        (is (= "cancelled" (get-in retained [:details :code])))
        (is (nil? (:error compact)))
        (is (= :cancelled (:status compact))))
      (let [failed (eval! rt sid "(jobs/start! #(throw (InterruptedException. \"unrequested interruption\")))")
            record (await! rt sid failed)]
        (is (= :failed (:status record)))
        (is (= "job-failed" (get-in record [:error :code])))))))

(deftest cancelled-records-from-the-earlier-writer-keep-their-original-diagnostics
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt)) id (str (java.util.UUID/randomUUID))
          original {:code "job-failed" :message "sleep interrupted"}]
      (store/create-job! (:store rt) {:id id :session-id sid :status :queued :name "Older cancellation" :created-at 1})
      (store/transition-job! (:store rt) sid id #{:queued} {:status :cancelled :error original :finished-at 2})
      (let [record (jobs/inspect-job (:jobs rt) sid id)]
        (is (= "cancelled" (get-in record [:error :code])))
        (is (= original (get-in record [:error :cause])))
        (is (= original (:error (store/job (:store rt) sid id))))))))

(deftest incremental-output-has-independent-cursors-and-preserves-unicode-across-settlement
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def release (promise)) (def written (promise)) (def j (jobs/start! #(do (print \"αβ\\n\") (deliver written true) @release (print \"終わり\\n\") :ok))) @written j")
          first-page (eval! rt sid "(def first-page (jobs/output j)) first-page")
          cursor (:cursor first-page)]
      (is (= "αβ\n" (:text first-page)))
      (is (= {:job-id (:id handle) :offset 3} cursor))
      (is (false? (:eof? first-page)))
      (let [empty-page (eval! rt sid "(jobs/output j {:after (:cursor first-page)})")]
        (is (= "" (:text empty-page)))
        (is (= cursor (:cursor empty-page)))
        (is (false? (:eof? empty-page))))
      (is (= "β\n" (:text (eval! rt sid "(jobs/output j {:tail? true :limit 2})"))))
      (eval! rt sid "(deliver release true)")
      (await! rt sid handle)
      (let [page (jobs/output-job (:jobs rt) sid (:id handle) {:after cursor :limit 2})
            independent (jobs/output-job (:jobs rt) sid (:id handle) {:after cursor :limit 2})
            last-page (jobs/output-job (:jobs rt) sid (:id handle) {:after (:cursor page) :limit 2})]
        (is (= "終わ" (:text page)))
        (is (= page independent))
        (is (:more? page))
        (is (= "り\n" (:text last-page)))
        (is (:eof? last-page))
        (is (= 7 (get-in last-page [:cursor :offset])))
        (is (= "" (:text (jobs/output-job (:jobs rt) sid (:id handle) {:after (:cursor last-page)})))))
      (is (false? (:delivered? (store/job (:store rt) sid (:id handle)))))
      (let [options {:cwd (:cwd rt) :home (:home rt) :data-dir (:data-dir rt)}]
        (runtime/close! rt)
        (let [reopened (runtime/open! options)]
          (try
            (is (= "終わり\n" (:text (jobs/output-job (:jobs reopened) sid (:id handle) {:after cursor}))))
            (is (= "り\n" (:text (jobs/output-job (:jobs reopened) sid (:id handle) {:tail? true :limit 2}))))
            (finally (runtime/close! reopened))))))))

(deftest cursor-validation-rejects-mixed-modes-wrong-jobs-and-skipped-output
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt)) handle (eval! rt sid "(jobs/start! #(print \"abc\"))")
          _ (await! rt sid handle) id (:id handle)
          cursor {:job-id id :offset 3}]
      (doseq [opts [{:after (assoc cursor :job-id "another-job")}
                    {:after (assoc cursor :offset 4)} {:after (assoc cursor :offset -1)}
                    {:after nil} {:tail? true :after cursor} {:offset 0 :after cursor}
                    {:tail? true :offset 0} {:limit 0} {:tail? "yes"}]]
        (is (thrown? clojure.lang.ExceptionInfo (jobs/output-job (:jobs rt) sid id opts))))
      (let [other (:id (fixtures/create-session rt))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"this session"
                             (jobs/output-job (:jobs rt) other id {:after cursor})))))))

(deftest tail-reads-the-retained-suffix-and-keeps-the-truncation-signal
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(jobs/start! #(do (print (apply str (repeat 1048576 \"x\"))) (print \"lost tail\")))")
          _ (await! rt sid handle)
          page (jobs/output-job (:jobs rt) sid (:id handle) {:tail? true :limit 4})]
      (is (= "xxxx" (:text page)))
      (is (= (- 1048576 4) (:offset page)))
      (is (:truncated? page))
      (is (:eof? page)))))

(deftest failure-summaries-are-bounded-without-losing-diagnostics
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(def j (jobs/start! #(throw (ex-info (apply str (repeat 5000 \"e\")) {})))) j")]
      (await! rt sid handle)
      (let [compact (eval! rt sid "(jobs/inspect j)") detailed (eval! rt sid "(jobs/inspect j {:detailed? true})")]
        (is (= :failed (:status compact)))
        (is (= 240 (count (get-in compact [:error :message]))))
        (is (true? (get-in compact [:error :truncated?])))
        (is (= 5000 (count (get-in detailed [:error :message]))))))))

(deftest tail-falls-back-to-exact-character-length-for-earlier-schema-two-records
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          handle (eval! rt sid "(jobs/start! #(print \"αβγ終\\n\"))")
          record (await! rt sid handle)
          prior-record (dissoc (store/job (:store rt) sid (:id handle)) :output-characters)
          serialized (pr-str prior-record)]
      ;; Same schema-2 format, before the optional exact character count was added.
      (store/transact! (:store rt)
        (fn [connection]
          (with-open [statement (.prepareStatement connection "UPDATE jobs SET record=? WHERE id=?")]
            (.setString statement 1 serialized)
            (.setString statement 2 (:id handle))
            (.executeUpdate statement))))
      (let [page (commands/dispatch! rt "job.output" {:session-id sid :job-id (:id handle) :tail? true :limit 2})
            next-page (commands/dispatch! rt "job.output" {:session-id sid :job-id (:id handle) :after (:cursor page)})]
        (is (= "終\n" (:text page)))
        (is (= 3 (:offset page)))
        (is (= 5 (get-in page [:cursor :offset])))
        (is (= "" (:text next-page)))
        (is (:eof? next-page))
        (is (= prior-record (store/job (:store rt) sid (:id handle))))))))
