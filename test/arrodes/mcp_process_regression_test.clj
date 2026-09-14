(ns arrodes.mcp-process-regression-test
  (:require [arrodes.mcp :as mcp]
            [arrodes.platform :as u]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-directory []
  (str (Files/createTempDirectory "arrodes-mcp-process-" (make-array FileAttribute 0))))

(defn- remove-directory! [directory]
  (with-open [walk (Files/walk (u/path directory) (make-array java.nio.file.FileVisitOption 0))]
    (doseq [file (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator walk)))]
      (Files/deleteIfExists file))))

(defn- process-alive? [pid]
  (some-> (java.lang.ProcessHandle/of (long pid)) (.orElse nil) (.isAlive)))

(defn- wait-until [predicate timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
        :else false))))

(deftest resistant-stdio-server-is-owned-through-reconnect-and-close
  (when (Files/isExecutable (u/path "/bin/sh"))
    (let [directory (temp-directory)
          script (u/path (str directory "/server.sh"))
          pid-file (u/path (str directory "/server.pid"))
          source (str/join
                  "\n"
                  ["trap '' TERM"
                   (str "echo $$ > '" pid-file ".tmp' && mv '" pid-file ".tmp' '" pid-file "'")
                   "while IFS= read -r line; do"
                   "  case \"$line\" in"
                   "    *'\"method\":\"initialize\"'*)"
                   "      id=$(printf '%s' \"$line\" | sed -E 's/.*\"id\":(\"[^\"]*\"|[0-9]+).*/\\1/')"
                   "      version=$(printf '%s' \"$line\" | sed -E 's/.*\"protocolVersion\":\"([^\"]+)\".*/\\1/')"
                   "      printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"%s\",\"capabilities\":{},\"serverInfo\":{\"name\":\"resistant\",\"version\":\"1\"}}}\\n' \"$id\" \"$version\" ;;"
                   "  esac"
                   "done"
                   "while :; do sleep 1; done"])]
      (try
        (spit (str script) source)
        (let [pool (mcp/create! directory
                                {:mcp/servers
                                 {"resistant" {:transport :stdio
                                               :command "/bin/sh"
                                               :args [(str script)]
                                               :timeout-ms 5000}}})]
          (try
            (mcp/catalog! pool "resistant")
            (is (wait-until #(Files/exists pid-file (make-array LinkOption 0)) 1000))
            (let [first-pid (Long/parseLong (str/trim (Files/readString pid-file)))]
              (is (process-alive? first-pid))
              (mcp/invoke! pool {:action "reconnect" :server "resistant"})
              (is (wait-until #(not= first-pid
                                    (Long/parseLong (str/trim (Files/readString pid-file))))
                              1000))
              (is (not (process-alive? first-pid)))
              (let [second-pid (Long/parseLong (str/trim (Files/readString pid-file)))
                    result (mcp/close! pool)]
                (is (:cleanup-complete? result))
                (is (empty? (:errors result)))
                (is (wait-until #(not (process-alive? second-pid)) 1000))))
            (finally (mcp/close! pool))))
        (finally (remove-directory! directory))))))
