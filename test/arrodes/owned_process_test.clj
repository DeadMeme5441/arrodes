(ns arrodes.owned-process-test
  (:require [arrodes.owned-process :as owned-process]
            [arrodes.platform :as u]
            [clojure.test :refer [deftest is]])
  (:import (com.sun.jna Native)
           (java.io BufferedReader InputStreamReader)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

(defn- temp-directory []
  (str (Files/createTempDirectory "arrodes-owned-process-" (make-array FileAttribute 0))))

(defn- remove-directory! [directory]
  (with-open [walk (Files/walk (u/path directory) (make-array java.nio.file.FileVisitOption 0))]
    (doseq [file (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator walk)))]
      (Files/deleteIfExists file))))

(defn- dispose! [owned]
  (try
    (owned-process/stop! owned)
    (finally
      (let [process ^Process (:process owned)]
        (doseq [stream [(.getOutputStream process) (.getInputStream process)
                        (.getErrorStream process)]]
          (.close ^java.io.Closeable stream))))))

(deftest failed-signal-accepts-only-proven-native-scope-exit
  (when (Files/isExecutable (u/path "/bin/sh"))
    (doseq [signal [15 9]]
      (let [directory (temp-directory)
            release (str directory "/release")
            owned (owned-process/start!
                   ["/bin/sh" "-c"
                    (str (when (= 9 signal) "trap '' TERM; ")
                         "printf 'ready\\n'; while IFS= read -r line; do :; done; "
                         "while [ ! -f release ]; do sleep 0.01; done")]
                   {:cwd directory})
            process ^Process (:process owned)
            native-var (ns-resolve 'arrodes.owned-process 'posix-call)
            native-call @native-var
            raced? (atom false)]
        (try
          (is (= "ready" (.readLine (BufferedReader.
                                    (InputStreamReader. (.getInputStream process))))))
          (with-redefs-fn
            {native-var
             (fn [library function & arguments]
               (if (and (= "kill" function)
                        (= [(- (.pid process)) signal] (vec arguments)))
                 (do
                   ;; Hold the real shell through the initial liveness probe,
                   ;; then force actual exit before reporting the raced error.
                   (spit release "")
                   (when-not (.waitFor process 5 TimeUnit/SECONDS)
                     (throw (ex-info "EOF fixture did not exit" {:signal signal})))
                   (reset! raced? true)
                   (Native/setLastError 1)
                   -1)
                 (apply native-call library function arguments)))}
            #(is (true? (owned-process/stop! owned))))
          (is @raced?)
          (is (not (.isAlive process)))
          (is (not (owned-process/alive? owned)))
          (is (true? (owned-process/stop! owned)))
          (finally
            (dispose! owned)
            (remove-directory! directory)))))))

(deftest inaccessible-or-unknown-native-probe-is-not-scope-exit
  (when (Files/isExecutable (u/path "/bin/sh"))
    (let [owned (owned-process/start! ["/bin/sh" "-c" "exec sleep 30"])
          process ^Process (:process owned)
          native-var (ns-resolve 'arrodes.owned-process 'posix-call)
          native-call @native-var]
      (try
        (doseq [error [1 22]]
          (with-redefs-fn
            {native-var
             (fn [library function & arguments]
               (if (and (= "kill" function)
                        (= [(- (.pid process)) 0] (vec arguments)))
                 (do (Native/setLastError error) -1)
                 (apply native-call library function arguments)))}
            #(if (= 1 error)
               (is (true? (owned-process/alive? owned)))
               (let [failure (try (owned-process/alive? owned) nil
                                  (catch clojure.lang.ExceptionInfo failure failure))]
                 (is (= "process/native-error" (:error/code (ex-data failure))))
                 (is (= error (:native-error (ex-data failure))))))))
        (is (.isAlive process))
        (finally (dispose! owned))))))

(deftest leader-exit-retains-descendant-ownership-without-signaling-canary
  (when (Files/isExecutable (u/path "/bin/sh"))
    (let [directory (temp-directory)
          canary (owned-process/start! ["/bin/sh" "-c" "exec sleep 30"])
          owned (owned-process/start!
                 ["/bin/sh" "-c"
                  (str "/bin/sh -c 'printf ready > worker.ready; "
                       "while [ ! -f release ]; do sleep 0.01; done; "
                       "printf leaked > leaked.txt' >/dev/null 2>&1 & "
                       "printf '%s' \"$!\" > worker.pid; "
                       "while [ ! -f worker.ready ]; do sleep 0.01; done")]
                 {:cwd directory})
          process ^Process (:process owned)]
      (try
        (is (.waitFor process 5 TimeUnit/SECONDS))
        (is (zero? (.exitValue process)))
        (let [pid (Long/parseLong (slurp (str directory "/worker.pid")))
              worker (.orElse (java.lang.ProcessHandle/of pid) nil)]
          (is (and worker (.isAlive ^java.lang.ProcessHandle worker)))
          (is (owned-process/alive? owned))
          (let [native-var (ns-resolve 'arrodes.owned-process 'posix-call)
                native-call @native-var
                failure
                (with-redefs-fn
                  {native-var
                   (fn [library function & arguments]
                     (if (and (= "kill" function)
                              (= [(- (.pid process)) 15] (vec arguments)))
                       (do (Native/setLastError 1) -1)
                       (apply native-call library function arguments)))}
                  #(try (owned-process/stop! owned) nil
                        (catch clojure.lang.ExceptionInfo failure failure)))]
            (is (= "process/native-error" (:error/code (ex-data failure))))
            (is (= 1 (:native-error (ex-data failure))))
            (is (owned-process/alive? owned))
            (is (.isAlive ^java.lang.ProcessHandle worker)))
          (is (true? (owned-process/stop! owned)))
          (is (not (owned-process/alive? owned)))
          (is (not (.isAlive ^java.lang.ProcessHandle worker))))
        (is (.isAlive ^Process (:process canary)))
        (is (owned-process/alive? canary))
        (spit (str directory "/release") "")
        (is (not (Files/exists (u/path (str directory "/leaked.txt"))
                               (make-array LinkOption 0))))
        (finally
          (try (dispose! owned) (finally (dispose! canary)))
          (remove-directory! directory))))))
