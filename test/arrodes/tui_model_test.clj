(ns arrodes.tui-model-test
  (:require [arrodes.tui-model :as model]
            [clojure.test :refer [deftest is]]))

(defn- event
  ([seq type data] (event seq "op-1" type data))
  ([seq operation-id type data]
   (cond-> {:operation-id operation-id :type type :data data}
     seq (assoc :seq seq))))

(defn- entry [id parent role content]
  {:id id :session-id "session-1" :parent-id parent :seq 1 :kind :message
   :created-at 1 :data {:message/role role :message/content content}})

(deftest wire-decoding-keeps-arbitrary-payload-maps-opaque
  (let [decoded (model/decode-wire
                 {"type" "capability/started"
                  "data" {"call-id" "call-1"
                          "arguments" {"type" "literal" "nested" {"status" "raw"}}
                          "result" {"kind" "inline"
                                    "value" {"kind" "payload" "status" "unchanged"}}}})]
    (is (= :capability/started (:type decoded)))
    (is (= "call-1" (model/field (:data decoded) "call-id")))
    (is (= {"type" "literal" "nested" {"status" "raw"}}
           (get-in decoded [:data :arguments])))
    (is (= {"kind" "payload" "status" "unchanged"}
           (get-in decoded [:data :result :value])))
    (is (= :inline (get-in decoded [:data :result :kind])))))

(deftest active-path-and-canonical-entry-identity-own-message-rows
  (let [root (entry "root" nil :user "question")
        selected (entry "selected" "root" :assistant "selected answer")
        abandoned (entry "abandoned" "root" :assistant "abandoned answer")
        initial (model/hydrate {:state {:session {:id "session-1" :head "selected"}
                                               :phase :idle :queues []}
                                :entries [root abandoned selected]
                                :cursor 10}
                               [])
        streamed (model/apply-event initial
                                    (event nil :provider-event
                                           {:event/type :stream/content-delta
                                            :event/delta "temporary"}))
        committed-entry (entry "final" "selected" :assistant "durable")
        committed (model/apply-event streamed
                                     (event 11 :entry/committed
                                            {:entry committed-entry}))
        duplicate (model/apply-event committed
                                     (event 11 :entry/committed
                                            {:entry committed-entry}))
        after-redundant-message
        (model/apply-event duplicate
                           (event 12 :message/assistant
                                  {:message {:message/role :assistant
                                             :message/content "durable"}}))]
    (is (= ["root" "selected"] (mapv :id (:entries initial))))
    (is (= "temporary" (:text (last (model/rows streamed)))))
    (is (= ["root" "selected" "final"] (mapv :id (:entries after-redundant-message))))
    (is (= ["question" "selected answer" "durable"]
           (mapv :text (filter #(= :message (:kind %))
                               (model/rows after-redundant-message)))))
    (is (= 12 (:cursor after-redundant-message)))))

(deftest nested-activities-use-observed-parentage-and-conservative-read-groups
  (let [assistant (assoc (entry "assistant" nil :assistant "")
                         :data {:message/role :assistant
                                :message/content ""
                                :message/tool-calls
                                [{:tool-call/id "eval" :tool-call/name "repl"
                                  :tool-call/arguments {:source "(do work)"}}]})
        events [(event 1 :evaluation/started
                       {:call-id "eval" :source "(do work)"})
                (event 2 :capability/started
                       {:call-id "read-1" :parent-call-id "eval"
                        :name "read" :arguments {"path" "one"}})
                (event 3 :capability/completed
                       {:call-id "read-1" :parent-call-id "eval" :name "read"
                        :content "one" :result {:kind :inline} :error? false})
                (event 4 :capability/started
                       {:call-id "read-2" :parent-call-id "eval"
                        :name "read" :arguments {"path" "two"}})
                (event 5 :capability/completed
                       {:call-id "read-2" :parent-call-id "eval" :name "read"
                        :content "two" :result {:kind :inline} :error? false})
                (event 6 :evaluation/completed
                       {:call-id "eval" :content "done"
                        :result {:kind :inline} :error? false})]
        hydrated (model/hydrate {:state {:session {:id "session-1" :head "assistant"}}
                                 :entries [assistant] :cursor 0}
                                events)
        rows (model/rows hydrated)]
    (is (= :evaluation (get-in hydrated [:activities "eval" :kind])))
    (is (= :capability (get-in hydrated [:activities "read-1" :kind])))
    (is (= "eval" (get-in hydrated [:activities "read-1" :parent-id])))
    (is (= [:read-group] (mapv :kind rows)))
    (is (= "read-group:read-1" (:id (first rows))))
    (is (= ["read-1" "read-2"]
           (mapv :id (:activities (first rows)))))))

(deftest failed-evaluation-remains-visible-after-successful-children
  (let [events [(event 1 :evaluation/started
                       {:call-id "eval" :source "(read then fail)"})
                (event 2 :capability/started
                       {:call-id "read" :parent-call-id "eval"
                        :name "read" :arguments {"path" "file"}})
                (event 3 :capability/completed
                       {:call-id "read" :parent-call-id "eval" :name "read"
                        :content "content" :error? false})
                (event 4 :evaluation/completed
                       {:call-id "eval" :content "boom"
                        :details {:error/code "evaluation-failed"} :error? true})]
        rows (model/rows (reduce model/apply-event (model/empty-state) events))]
    (is (= ["activity:read" "activity:eval"] (mapv :id rows)))
    (is (= [:completed :failed] (mapv #(get-in % [:activity :status]) rows)))))

(deftest evaluation-completion-never-settles-an-uncompleted-function
  (let [events [(event 1 :evaluation/started
                       {:call-id "eval" :source "(bash {:command \"work\"})"})
                (event 2 :capability/started
                       {:call-id "shell" :parent-call-id "eval"
                        :name "bash" :arguments {"command" "work"}})
                (event 3 :evaluation/completed
                       {:call-id "eval" :content "caught" :error? false})]
        state (reduce model/apply-event (model/empty-state) events)]
    (is (= :completed (get-in state [:activities "eval" :status])))
    (is (= :running (get-in state [:activities "shell" :status])))))

(deftest progress-queue-operation-and-display-data-stay-bounded-and-safe
  (let [large-output (apply str (repeat 70000 "x"))
        state (-> (model/empty-state)
                  (model/apply-event (event 1 :operation/started {:kind :run}))
                  (model/apply-event (event 2 :capability/started
                                                  {:call-id "shell" :name "bash"
                                                   :arguments {"command" "echo"}}))
                  (model/apply-event (event nil :tool-progress
                                                  {:type :capability/output
                                                   :call-id "shell" :stream :stdout
                                                   :content large-output}))
                  (model/apply-event (event 3 :queue/enqueued
                                                  {:id "queue-1" :kind :steering}))
                  (model/apply-event (event 4 :queue/updated
                                                  {:item {:id "queue-1" :seq 7
                                                          :kind :steering :content "revised"}})))
        removed (model/apply-event state (event 5 :queue/removed {:id "queue-1"}))]
    (is (= {:id "op-1" :kind :run :status :running} (:operation state)))
    (is (= 65536 (count (get-in state [:activities "shell" :content]))))
    (is (true? (get-in state [:activities "shell" :details :progress-truncated?])))
    (is (= "revised" (get-in state [:queue 0 :content])))
    (is (empty? (:queue removed)))
    (is (= "red\n\tok" (model/safe-text "\u001b[31mred\u001b[0m\u0000\n\tok")))))

(deftest hydration-recovers-activity-before-cursor-without-reopening-abandoned-branches
  (let [assistant (assoc (entry "selected" nil :assistant "")
                         :data {:message/role :assistant :message/content ""
                                :message/tool-calls [{:tool-call/id "selected-eval" :tool-call/name "repl"}]})
        events [(event 1 :evaluation/started {:call-id "abandoned-eval" :source "(old)"})
                (event 2 :capability/started {:call-id "old-read" :parent-call-id "abandoned-eval" :name "read"})
                (event 3 :capability/completed {:call-id "old-read" :parent-call-id "abandoned-eval" :name "read" :error? false})
                (event 4 :evaluation/completed {:call-id "abandoned-eval" :error? false})
                (event 5 :evaluation/started {:call-id "selected-eval" :source "(current)"})
                (event 6 :capability/started {:call-id "selected-read" :parent-call-id "selected-eval" :name "read"})
                (event 7 :capability/completed {:call-id "selected-read" :parent-call-id "selected-eval" :name "read" :error? false})
                (event 8 :evaluation/completed {:call-id "selected-eval" :error? false})
                (event 9 :queue/cleared {:ids ["pending"]})
                (event 10 :entry/committed {:entry (entry "abandoned" nil :user "not selected")})]
        hydrated (model/hydrate
                  {:state {:session {:id "session-1" :head "selected" :status :idle}
                           :phase :idle :queues [{:id "pending" :kind :follow-up :content "keep"}]}
                   :entries [assistant] :cursor 10}
                  events)]
    (is (= ["activity:selected-read"] (mapv :id (model/rows hydrated))))
    (is (= ["selected"] (mapv :id (:entries hydrated))))
    (is (= ["pending"] (mapv :id (:queue hydrated))))
    (is (= (model/rows hydrated)
           (model/rows (reduce model/apply-event hydrated events))))))

(deftest copied-transcript-without-event-ledger-still-projects-one-inspectable-execution
  (let [assistant (assoc (entry "request" nil :assistant "")
                         :data {:message/role :assistant :message/content ""
                                :message/tool-calls [{:tool-call/id "eval" :tool-call/name "repl"
                                                     :tool-call/arguments "{\"source\":\"(+ 20 22)\"}"}]})
        result (assoc (entry "result" "request" :tool "=> 42")
                      :data {:message/role :tool :message/tool-call-id "eval"
                             :message/content "=> 42"
                             :message/result {:id 1 :kind :inline :value 42 :available? true}})
        projected (model/rows
                   (model/hydrate {:state {:session {:id "session-1" :head "result" :status :idle}}
                                   :entries [assistant result] :cursor 0} []))]
    (is (= [:activity] (mapv :kind projected)))
    (is (= :evaluation (get-in projected [0 :activity :kind])))
    (is (not-any? #(= :capability (get-in % [:activity :kind])) projected))))
