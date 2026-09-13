(ns arrodes.cache-test
  (:require [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [clojure.test :refer [deftest is]]))

(deftest ordinary-continuation-preserves-the-cacheable-request-prefix
  (let [requests (atom [])
        complete (fn [request _]
                   (swap! requests conj request)
                   (fixtures/answer "Recorded the request"))]
    (fixtures/with-runtime [rt complete]
      (let [sid (:id (fixtures/create-session rt))]
        (runtime/run! rt sid "Read this as the beginning of an ongoing conversation." {})
        (runtime/run! rt sid "Continue the same conversation." {})
        (let [[first-request second-request] @requests
              prefix (:request/messages first-request)
              continued (:request/messages second-request)]
          (is (= prefix (subvec continued 0 (count prefix))))
          (is (= (:request/tools first-request) (:request/tools second-request)))
          (is (true? (get-in first-request [:request/cache :enabled?])))
          (is (string? (get-in first-request [:request/cache :scope-id])))
          (is (= (get-in first-request [:request/cache :scope-id])
                 (get-in second-request [:request/cache :scope-id]))))))))
