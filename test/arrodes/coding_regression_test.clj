(ns arrodes.coding-regression-test
  (:require [arrodes.coding :as coding]
            [arrodes.owned-process :as owned-process]
            [arrodes.platform :as u]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-directory []
  (str (Files/createTempDirectory "arrodes-coding-" (make-array FileAttribute 0))))

(defn- remove-directory! [directory]
  (with-open [walk (Files/walk (u/path directory) (make-array java.nio.file.FileVisitOption 0))]
    (doseq [file (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator walk)))]
      (Files/deleteIfExists file))))

(defmacro with-temp [[name] & body]
  `(let [~name (temp-directory)]
     (try ~@body (finally (remove-directory! ~name)))))

(defn- tools [cwd progress & [cancelled?]]
  (into {} (map (juxt :name identity))
        (coding/descriptors {:cwd cwd
                             :current-context (constantly {:on-progress progress :cancelled? cancelled?})
                             :put-artifact! (fn [& _] nil)})))

(defn- invoke [descriptors name arguments]
  ((:fn (get descriptors name)) arguments))

(deftest edits-follow-existing-symbolic-links-consistently
  (with-temp [directory]
    (let [target (u/path (str directory "/actual.txt"))
          alias (u/path (str directory "/alias.txt"))
          descriptors (tools directory nil)]
      (spit (str target) "before")
      (Files/createSymbolicLink alias (.getFileName target) (make-array FileAttribute 0))
      (invoke descriptors "edit" {:path "alias.txt"
                                   :edits [{:oldText "before" :newText "after"}]})
      (is (Files/isSymbolicLink alias))
      (is (= "after" (Files/readString target)))
      (invoke descriptors "write" {:path "alias.txt" :content "written"})
      (is (Files/isSymbolicLink alias))
      (is (= "written" (Files/readString target))))))

(deftest exact-edit-rejects-overlapping-occurrences-without-writing
  (with-temp [directory]
    (let [file (u/path (str directory "/overlap.txt"))
          descriptors (tools directory nil)]
      (spit (str file) "ababa")
      (let [error (try
                    (invoke descriptors "edit"
                            {:path "overlap.txt"
                             :edits [{:oldText "aba" :newText "X"}]})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= "edit-mismatch" (:error/code (ex-data error))))
        (is (= "ababa" (Files/readString file)))))))

(deftest grep-ignores-nonregular-walk-entries
  (with-temp [directory]
    (let [descriptors (tools directory nil)
          real-directory (u/path (str directory "/real-directory"))]
      (Files/createDirectory real-directory (make-array FileAttribute 0))
      (spit (str directory "/match.txt") "needle\n")
      (Files/createSymbolicLink (u/path (str directory "/directory-link"))
                                (.getFileName real-directory) (make-array FileAttribute 0))
      (Files/createSymbolicLink (u/path (str directory "/dangling-link"))
                                (u/path "missing") (make-array FileAttribute 0))
      (when (Files/isExecutable (u/path "/usr/bin/mkfifo"))
        (let [process (.start (ProcessBuilder.
                               ^java.util.List ["/usr/bin/mkfifo"
                                                (str directory "/named-pipe")]))]
          (is (zero? (.waitFor process)))))
      (let [result (invoke descriptors "grep" {:path "." :pattern "needle"})]
        (is (= 1 (get-in result [:details :matches])))
        (is (= "match.txt:1: needle" (:content result)))))))

(deftest internal-globstar-matches-zero-or-many-directories
  (with-temp [directory]
    (let [descriptors (tools directory nil)]
      (Files/createDirectories (u/path (str directory "/src/deep")) (make-array FileAttribute 0))
      (spit (str directory "/src/root.clj") "root")
      (spit (str directory "/src/deep/nested.clj") "deep")
      (is (= ["src/deep/nested.clj" "src/root.clj"]
             (:value (invoke descriptors "find" {:path "." :pattern "src/**/*.clj"})))))))

(deftest streamed-output-decodes-split-utf8-sequences-incrementally
  (when (Files/isExecutable (u/path "/bin/bash"))
    (with-temp [directory]
      (let [progress (atom [])
            descriptors (tools directory #(swap! progress conj %))
            result (invoke descriptors "bash"
                           {:command "printf '\\342'; sleep 0.05; printf '\\202\\254'"})
            streamed (apply str (keep #(when (= :stdout (:stream %)) (:content %)) @progress))]
        (is (= "€" (get-in result [:value :stdout])))
        (is (= "€" streamed))))))

(deftest cancellation-owns-workers-after-their-launcher-reparents-them
  (when (Files/isExecutable (u/path "/bin/bash"))
    (with-temp [directory]
      (let [exists? #(Files/exists (u/path (str directory "/" %)) (make-array LinkOption 0))
            deadline (+ (System/nanoTime) (* 30 1000000000))
            check-deadline! #(when (> (System/nanoTime) deadline)
                               (throw (ex-info "Process fixture did not reach its gate" {})))
            cancelled? (fn []
                         (check-deadline!)
                         (when (exists? "startup.ready")
                           ;; Launch after this cancellation check begins: an ancestry
                           ;; snapshot cannot observe the short-lived intermediary.
                           (spit (str directory "/launch") "")
                           (loop []
                             (check-deadline!)
                             (when-not (and (exists? "worker.ready") (exists? "detached.ready"))
                               (Thread/sleep 10)
                               (recur)))
                           true))
            descriptors (tools directory nil cancelled?)
            error (try
                    (invoke descriptors "bash"
                            {:command (str "printf ready > startup.ready; "
                                           "while [ ! -f launch ]; do sleep 0.01; done; "
                                           "/bin/sh -c '(printf ready > worker.ready; "
                                           "while [ ! -f release ]; do sleep 0.01; done; "
                                           "printf leaked > leaked.txt) >/dev/null 2>&1 &' ; "
                                           "printf ready > detached.ready; sleep 30")})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= "cancelled" (:error/code (ex-data error))))
        (is (and (exists? "worker.ready") (exists? "detached.ready")))
        (spit (str directory "/release") "")
        (Thread/sleep 300)
        (is (not (exists? "leaked.txt")))))))

(deftest windows-job-owns-background-worker
  (when (.startsWith (.toLowerCase (System/getProperty "os.name")) "windows")
    (with-temp [directory]
      (let [marker (str/replace (str directory "/leaked.txt") "/" "\\")
            command (str "start \"\" /b cmd.exe /d /s /c "
                         "\"ping -n 5 127.0.0.1 >nul & echo leaked>" marker "\""
                         " & ping -n 30 127.0.0.1 >nul")
            owned (owned-process/start! ["cmd.exe" "/d" "/s" "/c" command]
                                        {:cwd directory})]
        (Thread/sleep 250)
        (owned-process/stop! owned)
        (is (not (owned-process/alive? owned)))
        (Thread/sleep 4500)
        (is (not (Files/exists (u/path marker) (make-array LinkOption 0))))))))
