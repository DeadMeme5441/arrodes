(ns arrodes.session-test
  (:require [arrodes.artifacts :as artifacts]
            [arrodes.commands :as commands]
            [arrodes.runtime :as runtime]
            [arrodes.store :as store]
            [arrodes.platform :as u]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path)
           (java.nio.file.attribute FileAttribute)))

(def config {:provider :openai :model "gpt-4o-mini" :thinking :medium
             :tools :all :instructions "Initial instructions" :settings {}})
(defn temp-directory []
  (str (Files/createTempDirectory "arrodes-session-test-" (make-array FileAttribute 0))))
(defn remove-directory! [directory]
  (with-open [walk (Files/walk (u/path directory) (make-array java.nio.file.FileVisitOption 0))]
    (doseq [file (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator walk)))]
      (Files/deleteIfExists file))))
(defmacro with-store [[binding] & body]
  `(let [directory# (temp-directory)
         ~binding (store/open! {:path (str directory# "/sessions.sqlite")
                               :artifact-dir (str directory# "/artifacts")})]
     (try ~@body
          (finally (store/close! ~binding) (remove-directory! directory#)))))
(defn new-session [database]
  (store/create-session! database {:cwd (System/getProperty "java.io.tmpdir") :name "Session boundary" :config config}))
(defn message-entry [role text]
  {:kind :message :data {:message/role role :message/content text}})
(defn context-text [database sid]
  (mapv #(select-keys % [:message/role :message/content]) (store/context-messages database sid)))

(deftest branch-selection-restores-configuration-without-rewriting-history
  (with-store [database]
    (let [session (new-session database)
          sid (:id session)
          first-write (store/commit! database sid {:entries [(message-entry :user "Original request")
                                                            (message-entry :assistant "Original answer")]})
          old-head (:head (:session first-write))
          old-entries (:entries first-write)]
      (store/configure! database sid {:config {:model "gpt-6-astra" :instructions "Alternate branch"}})
      (store/commit! database sid {:entries [(message-entry :user "Alternate request")]})
      (store/branch! database sid old-head {})
      (is (= "gpt-4o-mini" (get-in (store/session database sid) [:config :model])))
      (is (= "Initial instructions" (get-in (store/session database sid) [:config :instructions])))
      (is (= ["Original request" "Original answer"] (mapv :message/content (store/context-messages database sid))))
      (is (= old-entries (subvec (store/entries database sid) 0 2))))))

(deftest stale-queue-delivery-cannot-consume-intent-without-its-message
  (with-store [database]
    (let [session (new-session database)
          sid (:id session)
          qid (u/id)]
      (store/commit! database sid {:queue-enqueue [{:id qid :kind :steering :content "Keep this request" :options {}}]})
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/commit! database sid {:expected-revision (:revision session)
                                               :queue-deliver [qid]
                                               :entries [(message-entry :user "Keep this request")]})))
      (is (= [qid] (mapv :id (store/pending database sid))))
      (is (empty? (store/context-messages database sid)))
      (store/commit! database sid {:expected-revision (:revision (store/session database sid))
                                  :queue-deliver [qid]
                                  :entries [(message-entry :user "Keep this request")]})
      (is (empty? (store/pending database sid)))
      (is (= [{:message/role :user :message/content "Keep this request"}] (context-text database sid))))))

(deftest fork-preserves-compaction-context-with-fresh-identities
  (with-store [database]
    (let [sid (:id (new-session database))
          committed (store/commit! database sid {:entries [(message-entry :user "Old request")
                                                         (message-entry :assistant "Old answer")
                                                         (message-entry :user "Retain this request")]})
          first-kept (:id (last (:entries committed)))]
      (store/commit! database sid {:entries [{:kind :compaction :data {:summary "Earlier work" :first-kept-entry-id first-kept}}]})
      (let [forked (store/fork! database sid {:name "Fork"})
            fork-id (:id forked)]
        (is (= (context-text database sid) (context-text database fork-id))))
      (let [cloned (store/clone! database sid {:name "Clone"})]
        (is (= (context-text database sid) (context-text database (:id cloned))))))))

(deftest invalid-import-does-not-leave-a-partial-session
  (with-store [database]
    (let [sid (:id (new-session database))]
      (store/commit! database sid {:entries [(message-entry :user "Preserve me")]})
      (let [packet (store/export-session database sid)
            before (store/list-sessions database {})
            cycle (update packet :entries #(assoc-in % [0 :parent-id] (:id (first %))))]
        (is (thrown? clojure.lang.ExceptionInfo (store/import-session! database cycle {})))
        (is (= before (store/list-sessions database {})))
        (is (= [{:message/role :user :message/content "Preserve me"}] (context-text database sid)))))))

(deftest artifacts-are-session-authorized-and-canonical-metadata-is-owned
  (with-store [database]
    (let [a (:id (new-session database))
          b (:id (new-session database))
          artifact (artifacts/put! database a "Private retained output"
                                   {:name "output" :id "forged" :session-id b :sha256 "forged" :bytes 0})]
      (is (= a (:session-id artifact)))
      (is (= (u/sha256 "Private retained output") (:sha256 artifact)))
      (is (thrown? clojure.lang.ExceptionInfo (artifacts/read! database b (:id artifact) {})))
      (is (= "Private retained output" (:content (artifacts/read! database a (:id artifact) {})))))))

(deftest interrupted-tool-result-recovery-is-idempotent
  (with-store [database]
    (let [sid (:id (new-session database))
          oid (u/id)
          qid (u/id)]
      (store/commit! database sid
                     {:session {:status :running}
                      :operation {:id oid :session-id sid :kind :run :status :running :created-at (u/now)}
                      :queue-enqueue [{:id qid :kind :follow-up :content "Later request" :options {}}]
                      :entries [(message-entry :user "Perform an action")
                                {:kind :message
                                 :data {:message/role :assistant :message/content ""
                                        :message/tool-calls [{:tool-call/id "effect-one" :tool-call/name "write"
                                                             :tool-call/arguments {:path "result.txt" :content "value"}}]}}]})
      (store/recover! database)
      (let [messages (store/context-messages database sid)
            events (store/events-since database {:after 0 :limit 500})]
        (is (= [:user :assistant :tool] (mapv :message/role messages)))
        (is (= "effect-one" (:message/tool-call-id (last messages))))
        (is (= :interrupted (:status (store/operation database oid))))
        (is (= [qid] (mapv :id (store/pending database sid))))
        (store/recover! database)
        (is (= messages (store/context-messages database sid)))
        (is (= events (store/events-since database {:after 0 :limit 500})))))))

(deftest file-backed-session-and-artifact-survive-reopen
  (let [directory (temp-directory)
        options {:path (str directory "/sessions.sqlite") :artifact-dir (str directory "/artifacts")}
        first-store (store/open! options)
        sid (:id (new-session first-store))]
    (try
      (store/commit! first-store sid {:entries [(message-entry :user "A durable request")]})
      (let [artifact (artifacts/put! first-store sid "A durable result" {:name "result"})
            cursor (:cursor (artifacts/read! first-store sid (:id artifact) {:limit 2}))]
        (store/close! first-store)
        (let [reopened (store/open! options)]
          (try
            (is (= [{:message/role :user :message/content "A durable request"}] (context-text reopened sid)))
            (is (= "A durable result" (:content (artifacts/read! reopened sid (:id artifact) {}))))
            (is (= "durable result" (:content (artifacts/read! reopened sid (:id artifact) {:after cursor}))))
            (finally (store/close! reopened)))))
      (finally (store/close! first-store) (remove-directory! directory)))))

(deftest file-artifact-pages-preserve-text-and-binary-offset-units
  (with-store [database]
    (let [sid (:id (new-session database))
          text-artifact (artifacts/put! database sid "Aé中🙂Z" {:name "unicode"})
          text-page (artifacts/read! database sid (:id text-artifact)
                                     {:offset 2 :limit 4})
          final-text-page (artifacts/read! database sid (:id text-artifact)
                                           {:offset (:next-offset text-page) :limit 2})
          binary (byte-array [0 1 2 3 -1 127])
          binary-artifact (artifacts/put! database sid binary
                                          {:name "binary" :kind :binary})
          binary-page (artifacts/read! database sid (:id binary-artifact)
                                       {:offset 2 :limit 3})]
      (is (= "é中🙂" (:content text-page)))
      (is (= 6 (:next-offset text-page)))
      (is (true? (:truncated? text-page)))
      (is (= "Z" (:content final-text-page)))
      (is (nil? (:next-offset final-text-page)))
      (is (= :base64 (:encoding binary-page)))
      (is (= [1 2 3]
             (vec (.decode (java.util.Base64/getDecoder) ^String (:content binary-page)))))
      (is (= 5 (:next-offset binary-page)))
      (is (true? (:truncated? binary-page))))))

(deftest artifact-cursors-page-retained-content-without-consuming-other-readers
  (with-store [database]
    (let [sid (:id (new-session database))
          other (:id (new-session database))
          text (artifacts/put! database sid "Aé中🙂Z" {})
          binary (artifacts/put! database sid (byte-array [0 1 2 3 -1 127]) {:kind :binary})
          empty-artifact (artifacts/put! database sid "" {})
          text-first (artifacts/read! database sid (:id text) {:limit 3})
          text-second (artifacts/read! database sid (:id text) {:after (:cursor text-first) :limit 2})
          text-last (artifacts/read! database sid (:id text) {:after (:cursor text-second) :limit 2})
          binary-first (artifacts/read! database sid (:id binary) {:limit 2})
          binary-second (artifacts/read! database sid (:id binary) {:after (:cursor binary-first) :limit 2})
          binary-last (artifacts/read! database sid (:id binary) {:after (:cursor binary-second) :limit 2})
          decode-page #(vec (.decode (java.util.Base64/getDecoder) ^String (:content %)))
          error-code (fn [session artifact opts]
                       (try (artifacts/read! database session artifact opts)
                            nil
                            (catch clojure.lang.ExceptionInfo error
                              (:error/code (ex-data error)))))]
      (is (= "Aé中🙂Z" (str (:content text-first) (:content text-second) (:content text-last))))
      (is (= text-second (artifacts/read! database sid (:id text) {:after (:cursor text-first) :limit 2})))
      (is (= {:session-id sid :artifact-id (:id text) :offset 4} (:cursor text-first)))
      (is (= (:next-offset text-first) (get-in text-first [:cursor :offset])))
      (is (nil? (:cursor text-last)))
      (is (= [0 1 2 3 -1 127] (vec (mapcat decode-page [binary-first binary-second binary-last]))))
      (is (= binary-second (artifacts/read! database sid (:id binary) {:after (:cursor binary-first) :limit 2})))
      (is (nil? (:cursor binary-last)))
      (is (nil? (:cursor (artifacts/read! database sid (:id empty-artifact) {}))))
      (is (= "" (:content (artifacts/read! database sid (:id text) {:offset 100}))))
      (is (nil? (:cursor (artifacts/read! database sid (:id text) {:offset 100}))))
      (doseq [cursor [nil {} {:session-id sid :artifact-id (:id text) :offset 0}
                      (assoc (:cursor text-first) :offset 100)
                      (assoc (:cursor text-first) :extra true)
                      (assoc (:cursor text-first) :artifact-id (:id binary))
                      (assoc (:cursor text-first) :session-id other)]]
        (is (= "invalid-artifact-cursor"
               (error-code sid (:id text) {:after cursor}))))
      (is (= "invalid-artifact-page"
             (error-code sid (:id text) {:after (:cursor text-first) :offset 4})))
      (is (= "artifact-forbidden"
             (error-code other (:id text) {:after (:cursor text-first)}))))))

(deftest artifact-continuation-crosses-repl-and-rpc-with-native-cursors
  (let [directory (temp-directory)
        rt (runtime/open! {:cwd directory :home (str directory "/home")
                           :data-dir (str directory "/data")
                           :complete-fn (fn [_ _] nil)})]
    (try
      (let [sid (:id (runtime/create-session! rt {:name "Artifact paging" :config config}))
            artifact (artifacts/put! (:store rt) sid "abcdef" {})
            params {:session-id sid :artifact-id (:id artifact)}
            first-page (commands/dispatch! rt "artifact.read" (assoc params :limit 2))
            repl-page (:value (runtime/evaluate!
                               rt sid (pr-str (list 'artifact-page (:id artifact)
                                                    {:after (:cursor first-page) :limit 2}))))
            wire-cursor (-> repl-page :cursor commands/public-value json/write-str
                            (json/read-str :key-fn keyword))
            last-page (commands/dispatch! rt "artifact.read"
                                          (assoc params :after wire-cursor :limit 2))]
        (is (= "abcdef" (str (:content first-page) (:content repl-page) (:content last-page))))
        (is (nil? (:cursor last-page)))
        (is (= "invalid-artifact-page"
               (try (commands/dispatch! rt "artifact.read"
                                        (assoc params :after wire-cursor :offset 1))
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      (:error/code (ex-data error)))))))
      (finally (runtime/close! rt) (remove-directory! directory)))))

(deftest file-artifact-page-detects-same-size-corruption
  (with-store [database]
    (let [sid (:id (new-session database))
          artifact (artifacts/put! database sid "integrity" {:name "corruption"})
          path (-> (u/path (:artifact-dir database))
                   (.resolve (subs (:sha256 artifact) 0 2))
                   (.resolve (:sha256 artifact)))]
      (Files/write path (.getBytes "corrupt!!" StandardCharsets/UTF_8)
                   (make-array OpenOption 0))
      (let [error (try
                    (artifacts/read! database sid (:id artifact) {:offset 1 :limit 1})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= "artifact-corrupt" (:error/code (ex-data error))))))))
