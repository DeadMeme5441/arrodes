(ns arrodes.compaction-stream-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.tui-model :as tui]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- summary-request? [request]
  (str/starts-with? (str (:message/content (last (:request/messages request))))
                    "Summarize the supplied conversation faithfully"))

(defn- stream! [options text]
  (doseq [event [{:event/type :stream/start}
                 {:event/type :stream/reasoning-delta :event/delta (str text " reasoning")}
                 {:event/type :stream/content-delta :event/delta text}]]
    ((:on-event options) event)))

(deftest internal-summaries-never-enter-the-reply-stream
  (doseq [action [:compact :branch]]
    (let [requests (atom [])
          events (atom [])
          callbacks (atom [])
          visible-text (atom [])
          view (atom (tui/hydrate {:state {} :entries [] :cursor 0} []))
          provider (fn [request options]
                     (swap! requests conj request)
                     (let [text (if (summary-request? request) "INTERNAL SUMMARY" "Normal reply")]
                       (stream! options text)
                       (fixtures/answer text)))]
      (fixtures/with-runtime [rt provider]
        (let [sid (:id (fixtures/create-session rt))
              options {:on-event #(swap! callbacks conj %)}
              unsubscribe (runtime/subscribe! rt
                            (fn [event]
                              (swap! events conj event)
                              (swap! view tui/apply-event event)
                              (swap! visible-text into (keep :text (tui/rows @view)))))]
          (try
            (runtime/run! rt sid "First prompt" options)
            (let [first-head (:head (runtime/session rt sid))]
              (runtime/run! rt sid "Second prompt" options)
              (if (= action :compact)
                (runtime/compact! rt sid (assoc options :config {:settings {:compaction-keep-entries 1}}))
                (runtime/branch! rt sid first-head (assoc options :summarize? true))))
            (is (some summary-request? @requests))
            (is (some #(str/includes? % "Normal reply") @visible-text))
            (is (not-any? #(str/includes? % "INTERNAL SUMMARY") @visible-text))
            (doseq [received [@events @callbacks]]
              (let [deltas (keep #(when (= :provider-event (:type %))
                                   (get-in % [:data :event/delta])) received)]
                (is (some #(= "Normal reply" %) deltas))
                (is (not-any? #(str/includes? % "INTERNAL SUMMARY") deltas))))
            (is (some #(= "INTERNAL SUMMARY" (get-in % [:data :summary]))
                      (runtime/entries rt sid)))
            (runtime/run! rt sid "Continue" options)
            (is (some #(str/includes? (str (:message/content %)) "INTERNAL SUMMARY")
                      (:request/messages (last @requests))))
            (finally (unsubscribe))))))))

(deftest failed-or-cancelled-compaction-does-not-leak-partial-text
  (doseq [outcome [:failed :cancelled]]
    (let [runtime* (atom nil) sid* (atom nil)
          events (atom []) callbacks (atom [])
          provider (fn [request options]
                     (if (summary-request? request)
                       (do
                         (stream! options "PARTIAL INTERNAL SUMMARY")
                         (if (= outcome :cancelled)
                           (do (runtime/cancel! @runtime* @sid*)
                               (fixtures/answer "PARTIAL INTERNAL SUMMARY"))
                           (throw (ex-info "Synthetic summary failure" {}))))
                       (fixtures/answer "Normal reply")))]
      (fixtures/with-runtime [rt provider]
        (let [sid (:id (fixtures/create-session rt))
              unsubscribe (runtime/subscribe! rt #(swap! events conj %))]
          (reset! runtime* rt)
          (reset! sid* sid)
          (try
            (runtime/run! rt sid "First prompt")
            (runtime/run! rt sid "Second prompt")
            (let [head (:head (runtime/session rt sid))]
              (is (thrown? clojure.lang.ExceptionInfo
                           (runtime/compact! rt sid {:on-event #(swap! callbacks conj %)
                                                    :config {:settings {:compaction-keep-entries 1}}})))
              (is (= head (:head (runtime/session rt sid)))))
            (is (not-any? #(= :compaction (:kind %)) (runtime/entries rt sid)))
            (doseq [received [@events @callbacks]]
              (is (not-any? #(= :provider-event (:type %)) received)))
            (finally (unsubscribe))))))))
