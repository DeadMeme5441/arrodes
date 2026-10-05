(ns arrodes.repl-presentation-test
  (:require [arrodes.capabilities :as capabilities]
            [arrodes.context-tree :as tree]
            [arrodes.provider-repl :as provider-repl]
            [arrodes.runtime :as runtime]
            [arrodes.runtime-test :as fixtures]
            [arrodes.value :as value]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk.providers.codex.auth :as codex-auth]
            [llm.sdk.providers.codex.responses :as codex])
  (:import (java.nio.file Files Paths)
           (java.util Base64)))

(def ^:private png-base64
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=")

(defn- put-image! [rt sid name]
  (let [path (Paths/get (:cwd (runtime/registry rt sid)) (into-array String [name]))]
    (Files/write path (.decode (Base64/getDecoder) png-base64)
                 (make-array java.nio.file.OpenOption 0))))

(defn- images [content]
  (if (vector? content) (filterv #(= :image (:part/type %)) content) []))

(defn- tool-content [request]
  (->> (:request/messages request)
       (filter #(= :tool (:message/role %))) last :message/content))

(defn- codex-body [request]
  ;; Only auth is replaced; the installed SDK performs the real wire conversion.
  (with-redefs [codex-auth/request-auth (fn [_] {:headers {"Authorization" "Bearer offline"}})]
    (:body (codex/build-request-codex
            {:profile/id :codex-backend
             :profile/base-url "https://chatgpt.com/backend-api/codex"}
            request))))

(deftest actual-model-read-reaches-next-request-and-sdk-with-native-bytes
  (let [requests (atom [])
        provider (fn [request _]
                   (swap! requests conj request)
                   (if (= 1 (count @requests))
                     (fixtures/calls (fixtures/tool-call "image-read"
                                       "(def native-image (read {:path \"one.png\"})) native-image"))
                     (fixtures/answer "Inspected the image")))]
    (fixtures/with-runtime [rt provider]
      (let [sid (:id (fixtures/create-session rt))]
        (put-image! rt sid "one.png")
        (runtime/run! rt sid "Inspect the image")
        (let [request (second @requests)
              content (tool-content request)
              wire (->> (:input (codex-body request))
                        (filter #(= "function_call_output" (:type %))) last :output)]
          (is (= [{:part/type :image :image/mime-type "image/png" :image/data png-base64}]
                 (images content)))
          (is (str/includes? (value/text-content content) "Read image file one.png"))
          (is (str/includes? (value/text-content content) "Retained result:"))
          (is (not (str/includes? (value/text-content content) "#object")))
          (is (not (str/includes? (value/text-content content) png-base64)))
          (is (= [{:type "input_image" :image_url (str "data:image/png;base64," png-base64)}]
                 (filterv #(= "input_image" (:type %)) wire)))
          (is (some #(and (= "input_text" (:type %))
                          (str/includes? (:text %) "one.png")) wire)))
        (is (= [true true]
               (:value (runtime/evaluate! rt sid
                          "[(bytes? native-image) (java.util.Arrays/equals native-image (read {:path \"one.png\"}))]"))))))))

(deftest image-presentation-preserves-certified-historical-quotation
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))]
      (runtime/run! rt sid "old-evidence-only-in-the-original")
      (put-image! rt sid "one.png")
      (let [entry-id (:id (first (runtime/entries rt sid)))
            result (runtime/evaluate! rt sid
                     (str "(read {:path \"one.png\"}) (println \"fresh observation\") "
                          "(history/read " (pr-str entry-id) ")"))
            source (tree/source-text (last (runtime/entries rt sid)))]
        (is (= 1 (count (images (:content result)))))
        (is (some? (get-in result [:details :history/quoted-return :history/retrieval])))
        (is (str/includes? source "fresh observation"))
        (is (str/includes? source "one.png"))
        (is (not (str/includes? source "old-evidence-only-in-the-original")))))))

(deftest composed-joined-reads-and-later-failure-reach-the-next-provider-request
  (doseq [[source failed?]
          [["(let [a (future (read {:path \"one.png\"})) b (future (read {:path \"two.png\"}))] {:first @a :second @b :note \"compared both\"})" false]
           ["(read {:path \"one.png\"})\n(println \"first image loaded\")\n(binding [*out* *err*] (println \"prior warning\"))\n(read {:path \"two.png\"})\n(throw (ex-info \"later failed\" {}))" true]]]
    (let [requests (atom [])
          provider (fn [request _]
                     (swap! requests conj request)
                     (if (= 1 (count @requests))
                       (fixtures/calls (fixtures/tool-call "composed" source))
                       (fixtures/answer "Observed both results")))]
      (fixtures/with-runtime [rt provider]
        (let [sid (:id (fixtures/create-session rt))]
          (put-image! rt sid "one.png")
          (put-image! rt sid "two.png")
          (runtime/run! rt sid "Compare images")
          (let [content (tool-content (second @requests))
                text (value/text-content content)]
            (is (= 2 (count (images content))))
            (is (every? #(= png-base64 (:image/data %)) (images content)))
            (is (str/includes? text "one.png"))
            (is (str/includes? text "two.png"))
            (if failed?
              (do (is (str/includes? text "later failed"))
                  (is (str/includes? text "first image loaded"))
                  (is (str/includes? text "prior warning"))
                  (is (str/includes? text "Form 5"))
                  (is (str/includes? text "Evaluation failed; effects may remain")))
              (is (str/includes? text "compared both")))))))))

(deftest image-effects-are-bounded-and-do-not-walk-native-values
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          registry (runtime/registry rt sid)]
      (put-image! rt sid "one.png")
      (let [overflow (runtime/evaluate! rt sid
                       "(dotimes [_ 17] (read {:path \"one.png\"}))")]
        (is (:error? overflow))
        (is (= "presentation-too-large" (get-in overflow [:details :data :evaluation/cause :code])))
        (is (= 16 (count (images (:content overflow)))))
        (is (str/includes? (value/text-content (:content overflow)) "not added to provider input")))
      (is (string? (:content (runtime/evaluate! rt sid "42"))))
      (runtime/evaluate! rt sid "(def earlier-image (read {:path \"one.png\"}))")
      (let [nested (runtime/evaluate! rt sid "{:unrelated (repeat 10000 earlier-image) :answer 42}")]
        (is (empty? (images (:content nested))))
        (is (not (:error? nested))))
      (is (bytes? (capabilities/invoke-value! registry "read" {:path "one.png"}))))))

(deftest session-job-cancellation-and-late-future-bindings-do-not-leak-presentation
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          other (:id (fixtures/create-session rt))
          registry (runtime/registry rt sid)
          cancelled (atom false)
          entered (promise)
          release (promise)]
      (put-image! rt sid "one.png")
      (capabilities/register! registry
        {:name "cancel-now" :description "Cancel this test invocation"
         :parameters {:type "object"} :execution :parallel :permission :read
         :fn (fn [_] (reset! cancelled true))})
      (let [failure (capabilities/evaluate! registry
                      "(read {:path \"one.png\"}) (println \"before cancellation\") (cancel-now {})"
                      {:cancelled? cancelled})]
        (is (= "cancelled" (get-in failure [:details :code])))
        (is (= 1 (count (images (:content failure)))))
        (is (= "before cancellation\n" (get-in failure [:details :data :stdout]))))
      (is (empty? (images (:content (runtime/evaluate! rt other "42")))))
      (let [job-result (runtime/evaluate! rt sid
                         "(def image-job (jobs/start! #(read {:path \"one.png\"}))) (jobs/wait image-job {:timeout-ms 10000}) (bytes? (jobs/result image-job))")]
        (is (= true (:value job-result)))
        (is (empty? (images (:content job-result)))))
      (capabilities/register! registry
        {:name "delayed-image" :description "Read after this evaluation settles"
         :parameters {:type "object"} :execution :parallel :permission :read
         :fn (fn [_]
               (deliver entered true)
               (fixtures/await! release)
               (capabilities/invoke-value! registry "read" {:path "one.png"}))})
      (try
        (let [first-result (runtime/evaluate! rt sid
                             "(def late-image (future (delayed-image {}))) :returned")]
          (fixtures/await! entered)
          (is (empty? (images (:content first-result))))
          (deliver release true)
          (let [next-result (runtime/evaluate! rt sid "(bytes? @late-image)")]
            (is (= true (:value next-result)))
            (is (empty? (images (:content next-result))))))
        (finally (deliver release true))))))

(deftest unsupported-and-oversized-reads-fail-honestly-without-image-payload
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          cwd (:cwd (runtime/registry rt sid))]
      (Files/write (Paths/get cwd (into-array String ["large.png"]))
                   (byte-array (inc (* 5 1024 1024))) (make-array java.nio.file.OpenOption 0))
      (Files/write (Paths/get cwd (into-array String ["unsupported.bin"]))
                   (byte-array [(unchecked-byte 255)]) (make-array java.nio.file.OpenOption 0))
      (doseq [[path code] [["large.png" "file-too-large"] ["unsupported.bin" "unsupported-file"]]]
        (let [failure (runtime/evaluate! rt sid (str "(read {:path " (pr-str path) "})"))]
          (is (:error? failure))
          (is (= code (get-in failure [:details :data :evaluation/cause :code])))
          (is (empty? (images (:content failure)))))))))

(deftest result-and-agent-and-job-reference-rendering-preserves-rich-parts
  (let [content [{:part/type :text :text "Evidence"}
                 {:part/type :image :image/mime-type "image/png" :image/data png-base64}]
        result {:id "call" :content content :result {:id 7 :kind :live :details {}}}
        ordinary (provider-repl/result-message result)
        messages [ordinary
                  (assoc ordinary :message/job-id "job")
                  (assoc ordinary :message/agent {:from "peer" :kind :completion})]]
    (doseq [message (provider-repl/messages messages)]
      (is (= (images content) (images (:message/content message))))
      (is (str/includes? (value/text-content (:message/content message)) "7")))))

(deftest aggregate-image-byte-bound-retains-prior-effects-and-native-results
  (fixtures/with-runtime [rt (fn [_ _] (fixtures/answer "Done"))]
    (let [sid (:id (fixtures/create-session rt))
          bytes (byte-array (* 5 1024 1024))
          original (.decode (Base64/getDecoder) png-base64)
          path (Paths/get (:cwd (runtime/registry rt sid)) (into-array String ["padded.png"]))]
      (System/arraycopy original 0 bytes 0 (alength original))
      (Files/write path bytes (make-array java.nio.file.OpenOption 0))
      (let [failure (runtime/evaluate! rt sid
                      "(def first-large (read {:path \"padded.png\"})) (read {:path \"padded.png\"})")
            cause (get-in failure [:details :data :evaluation/cause])]
        (is (:error? failure))
        (is (= "presentation-too-large" (:code cause)))
        (is (= 1 (count (images (:content failure)))))
        (is (= (* 5 1024 1024)
               (:value (runtime/evaluate! rt sid "(alength first-large)"))))
        (is (empty? (images (:content (runtime/evaluate! rt sid "(bytes? first-large)")))))))))
