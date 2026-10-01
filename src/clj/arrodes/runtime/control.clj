(ns arrodes.runtime.control
  "Session gates, foreground admission, event publication, and host interactions."
  (:require [arrodes.coordination :as coordination]
            [arrodes.store :as store]
            [arrodes.platform :as util]
            [arrodes.value :as value])
  (:import (java.util.concurrent ExecutorService RejectedExecutionException)))

(def ^:dynamic *resetting-session* nil)

(defn ensure-open! [runtime]
  (value/check! (= :open @(:lifecycle runtime)) :runtime-closed
                "Runtime is not open" {:status @(:lifecycle runtime)}))

(defn session-lock [runtime sid]
  (or (get @(:session-locks runtime) sid)
      (get (swap! (:session-locks runtime)
                  #(if (contains? % sid) % (assoc % sid (Object.)))) sid)))

(defn notify-listeners! [runtime event]
  (doseq [[_ listener] @(:listeners runtime)]
    (try (listener event) (catch Throwable _ nil)))
  event)

(defn emit-events! [runtime events]
  (when (seq events)
    (try
      (.execute ^ExecutorService (:publisher runtime)
                ^Runnable
                (fn []
                  (loop []
                    (let [batch (store/events-since (:store runtime)
                                                    {:after @(:published-seq runtime) :limit 1000})]
                      (when (seq batch)
                        (doseq [event batch]
                          (notify-listeners! runtime event)
                          (reset! (:published-seq runtime) (:seq event)))
                        (recur))))))
      (catch RejectedExecutionException _ nil)))
  events)

(defn transient-event! [runtime sid oid type data callback]
  (let [event {:id nil :session-id sid :operation-id oid :type type :data data
               :time (util/now) :durable? false}]
    (notify-listeners! runtime event)
    (when callback
      (try (callback event) (catch Throwable _ nil)))
    event))

(defn commit! [runtime sid command]
  (let [result (store/commit! (:store runtime) sid command)]
    (emit-events! runtime (:events result))
    result))

(def ^:private ui-kinds
  #{:select :confirm :input :notify :capability
    :renderer :widget :set-widget :render :editor})

(defn ui!
  "Invokes the current host callback or fails rather than inventing an answer."
  [runtime request]
  (value/check! (map? request) :invalid-ui-request
                "UI request must be a map" {})
  (value/check! (contains? ui-kinds (:kind request)) :unsupported-ui-request
                "Unsupported UI request kind" {:kind (:kind request)})
  (if-let [callback @(:ui runtime)]
    (callback request)
    (value/fail! :host-unavailable "No host is available for this interaction"
                 {:kind (:kind request)})))

(defn foreground [runtime sid]
  (get @(:foreground runtime) sid))

(defn ensure-idle! [runtime sid]
  ;; Release the session monitor while teardown waits for background workers.
  ;; Admission resumes only after the old evaluator is closed or retained intact.
  (while (and (not= sid *resetting-session*) (contains? @(:resetting runtime) sid))
    (.wait ^Object (session-lock runtime sid) 50)
    (ensure-open! runtime))
  (when-let [slot (foreground runtime sid)]
    (value/fail! :session-busy "Session already has a foreground operation"
                 {:session-id sid :operation-id (:operation-id slot)})))

(defn acquire-foreground! [runtime sid kind oid]
  (locking (session-lock runtime sid)
    (store/session (:store runtime) sid)
    (ensure-idle! runtime sid)
    (locking (:foreground runtime)
      (ensure-open! runtime)
      (when-not (contains? @(:admission runtime) oid)
        (swap! (:admission runtime) coordination/reserve oid (:operation-limit runtime)))
      (let [slot {:operation-id oid :kind kind :phase (atom :starting)
                  :cancelled (atom false) :cancellable? (atom true)
                  :accepting-input? (atom true) :thread (atom nil)
                  :done (promise) :finished (promise) :usage (atom {})}]
        (swap! (:admission runtime) coordination/occupy oid)
        (swap! (:foreground runtime) assoc sid slot)
        (swap! (:operations runtime) assoc oid slot)
        slot))))

(defn release-foreground! [runtime sid oid]
  (locking (session-lock runtime sid)
    (when (= oid (:operation-id (foreground runtime sid)))
      (swap! (:foreground runtime) dissoc sid))))

(defn set-phase! [slot phase]
  (reset! (:phase slot) phase)
  phase)

(defn publish-phase! [runtime sid slot phase callback]
  (set-phase! slot phase)
  (transient-event! runtime sid (:operation-id slot) :operation/phase {:phase phase} callback))


(defn remaining-close-millis [deadline]
  (long (max 0 (quot (- deadline (System/nanoTime)) 1000000))))

(defn latest-event-seq [runtime sid]
  (loop [after 0]
    (let [page (store/events-since (:store runtime)
                                   {:after after :session-id sid :limit 10000})
          cursor (long (or (:seq (peek page)) after))]
      (if (= 10000 (count page))
        (recur cursor)
        cursor))))
