(ns arrodes.tui-turns-test
  (:require [arrodes.tui-turns :as turns] [clojure.test :refer [deftest is]]))

(deftest assistant-work-starts-before-prose
  (let [rows (turns/annotate [{:id "u" :kind :message :role :user}
                              {:id "thinking" :kind :reasoning}
                              {:id "read" :kind :read-group}
                              {:id "tool" :kind :activity}
                              {:id "answer" :kind :message :role :assistant}
                              {:id "u2" :kind :message :role :user}
                              {:id "stream" :kind :message :role :assistant :streaming? true}])]
    (is (= [true true false false false true true] (mapv :turn-start? rows)))
    (is (= ["u" "thinking" "thinking" "thinking" "thinking" "u2" "stream"] (mapv :turn-id rows)))
    (is (= [:user :assistant :assistant :assistant :assistant :user :assistant] (mapv :turn-role rows)))))

(deftest tool-first-and-prose-only-turns
  (is (= [true true false]
         (mapv :turn-start? (turns/annotate [{:id "u" :kind :message :role :user}
                                            {:id "tool" :kind :activity}
                                            {:id "answer" :kind :message :role :assistant}]))))
  (is (= [true true false true true]
         (mapv :turn-start? (turns/annotate [{:id "u" :kind :message :role :user}
                                            {:id "a" :kind :message :role :assistant}
                                            {:id "a2" :kind :message :role :assistant}
                                            {:id "u2" :kind :message :role :user}
                                            {:id "u3" :kind :message :role :user}])))))
