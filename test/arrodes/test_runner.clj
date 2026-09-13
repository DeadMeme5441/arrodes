(ns arrodes.test-runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- test-namespaces []
  (->> (file-seq (io/file "test" "arrodes"))
       (filter #(.isFile %))
       (map #(.getPath %))
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> % (str/replace "\\" "/") (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "") (str/replace "/" ".")
                 (str/replace "_" "-") symbol))
       sort vec))
(defn -main [& _]
  (let [namespaces (test-namespaces)]
    (when (empty? namespaces) (throw (ex-info "No behavioral tests were found" {})))
    (doseq [namespace namespaces] (require namespace))
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (shutdown-agents)
      (when (pos? (+ fail error)) (System/exit 1)))))
