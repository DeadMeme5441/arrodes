(ns arrodes.ui-api-test
  (:require [arrodes.commands :as commands]
            [arrodes.runtime :as runtime]
            [arrodes.session-test :as fixtures]
            [arrodes.store :as store]
            [arrodes.platform :as u]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(defn- answer [text]
  {:response/provider :openai
   :response/model "gpt-4o-mini"
   :response/parts [{:part/type :text :text text}]
   :response/finish-reason :stop
   :response/provider-data {}})

(defmacro with-runtime [[binding complete-fn] & body]
  `(let [directory# (fixtures/temp-directory)
         ~binding (runtime/open! {:cwd directory#
                                  :home (str directory# "/home")
                                  :data-dir (str directory# "/data")
                                  :complete-fn ~complete-fn})]
     (try
       ~@body
       (finally
         (runtime/close! ~binding)
         (fixtures/remove-directory! directory#)))))

(defn- create-session [rt]
  (runtime/create-session! rt {:name "UI API"
                               :config fixtures/config}))

(deftest committed-entry-events-are-in-the-entry-transaction
  (fixtures/with-store [database]
    (let [sid (:id (fixtures/new-session database))
          before (store/events-since database {:session-id sid})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/commit! database sid
                                  {:entries [(fixtures/message-entry :user "must roll back")]
                                   :events [{:type "not-a-keyword"}]})))
      (is (empty? (store/entries database sid)))
      (is (= before (store/events-since database {:session-id sid})))
      (let [committed (:entries
                       (store/commit! database sid
                                      {:entries [(fixtures/message-entry :user "first")
                                                 (fixtures/message-entry :assistant "second")]}))
            replay (store/events-since database {:session-id sid})
            entry-events (filterv #(= :entry/committed (:type %)) replay)]
        (is (= committed (mapv #(get-in % [:data :entry]) entry-events)))
        (is (= (:id (first committed)) (:parent-id (second committed))))
        (is (every? #(and (:id %) (:session-id %) (:seq %) (:created-at %)) committed))))))

(deftest session-view-observes-each-committed-entry-at-a-consistent-replay-anchor
  (with-runtime [rt (fn [_ _] (answer "done"))]
    (let [sid (:id (create-session rt))
          observations (atom [])
          unsubscribe
          (runtime/subscribe!
           rt
           (fn [event]
             (when (and (= sid (:session-id event))
                        (= :entry/committed (:type event)))
               (swap! observations conj
                      {:event event :view (runtime/session-view rt sid)}))))]
      (try
        (runtime/run! rt sid "work" {})
        (finally (unsubscribe)))
      (is (seq @observations))
      (doseq [{:keys [event view]} @observations]
        (is (= (:cursor view) (get-in view [:state :event-seq])))
        (is (<= (:seq event) (:cursor view)))
        (is (= (get-in event [:data :entry])
               (some #(when (= (get-in event [:data :entry :id]) (:id %)) %)
                     (:entries view)))))
      (let [{:keys [cursor] :as view} (runtime/session-view rt sid)]
        (is (= (runtime/active-path rt sid) (:entries view)))
        (is (empty? (runtime/events-since rt {:session-id sid :after cursor})))))))

(deftest queue-item-commands-preserve-neighbors-and-reject-delivered-ids
  (with-runtime [rt (fn [_ _] (answer "unused"))]
    (let [database (:store rt)
          sid (:id (create-session rt))
          ids (vec (repeatedly 3 u/id))
          raw-items (mapv (fn [id text]
                            {:id id :kind :follow-up :content text :options {}})
                          ids ["first" "second" "third"])]
      (store/commit! database sid {:queue-enqueue raw-items})
      (let [before (store/pending database sid)
            update-response
            (commands/dispatch! rt "session.queue.update"
                                {:session-id sid :queue-id (second ids)
                                 :content "edited"})
            after-update (store/pending database sid)
            drop-response
            (commands/dispatch! rt "session.queue.drop"
                                {:session-id sid :queue-id (second ids)})
            after-drop (store/pending database sid)
            queue-events (->> (runtime/events-since rt {:session-id sid})
                              (filter #(contains? #{:queue/updated :queue/removed}
                                                  (:type %)))
                              vec)]
        (is (= (mapv #(select-keys % [:id :seq :kind :options :created-at]) before)
               (mapv #(select-keys % [:id :seq :kind :options :created-at]) after-update)))
        (is (= "edited" (:content (:item update-response))))
        (is (= (:item update-response) (:removed drop-response)))
        (is (= [(first ids) (nth ids 2)] (mapv :id after-drop)))
        (is (= [{:item (:item update-response)}
                {:id (second ids)}]
               (mapv :data queue-events))))
      (store/commit! database sid {:queue-deliver [(first ids)]})
      (testing "delivery wins permanently at the queue transaction boundary"
        (is (thrown? clojure.lang.ExceptionInfo
                     (commands/dispatch! rt "session.queue.update"
                                         {:session-id sid :queue-id (first ids)
                                          :content "too late"})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (commands/dispatch! rt "session.queue.drop"
                                         {:session-id sid :queue-id (first ids)}))))
      (is (= [(nth ids 2)] (mapv :id (store/pending database sid)))))))

(deftest host-normalizes-only-known-user-content-part-enums
  (let [requests (atom [])]
    (with-runtime [rt (fn [request _]
                        (swap! requests conj request)
                        (answer "accepted"))]
      (let [sid (:id (create-session rt))
            metadata {:type "opaque" :provider "unchanged"}
            prompt [{:part/type "text" :text "inspect" :metadata metadata}
                    {:part/type "image"
                     :image/mime-type "image/png"
                     :image/data "iVBORw0KGgo="}]
            operation (commands/dispatch! rt "session.run"
                                          {:session-id sid :prompt prompt})]
        (is (= :completed (:status (runtime/wait! rt (:id operation) 10000))))
        (let [content (-> @requests first :request/messages last :message/content)]
          (is (= [:text :image] (mapv :part/type content)))
          (is (= metadata (:metadata (first content))))
          (is (= "image/png" (:image/mime-type (second content)))))))))

(deftest native-result-inspection-does-not-confuse-keywords-with-strings
  (with-runtime [rt (fn [_ _] (answer "unused"))]
    (let [sid (:id (create-session rt))
          result (runtime/evaluate! rt sid "(array-map :status :ready \"status\" \"literal\")")
          inspected (commands/dispatch! rt "result.inspect"
                                        {:session-id sid :result-id (get-in result [:result :id])})]
      (is (= {:status :ready "status" "literal"} (edn/read-string (:value-edn inspected)))))))
