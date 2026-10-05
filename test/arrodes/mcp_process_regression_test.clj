(ns arrodes.mcp-process-regression-test
  (:require [arrodes.mcp :as mcp]
            [arrodes.owned-process :as owned-process]
            [arrodes.platform :as u]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (com.sun.jna Native)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

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

(deftest native-mcp-contract-keeps-schemas-results-and-outcome-provenance
  (when (Files/isExecutable (u/path "/bin/sh"))
    (let [directory (temp-directory)
          script (str directory "/native.sh")
          source (str/join "\n"
                   ["while IFS= read -r line; do"
                    "  id=$(printf '%s' \"$line\" | sed -E 's/.*\"id\":(\"[^\"]*\"|[0-9]+).*/\\1/')"
                    "  case \"$line\" in"
                    "    *'\"method\":\"initialize\"'*)"
                    "      version=$(printf '%s' \"$line\" | sed -E 's/.*\"protocolVersion\":\"([^\"]+)\".*/\\1/')"
                    "      printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"%s\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"native\",\"version\":\"1\"}}}\\n' \"$id\" \"$version\" ;;"
                    "    *'tools'*'list'*)"
                    "      printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"tools\":[{\"name\":\"probe\",\"description\":\"Native output\",\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}}]}}\\n' \"$id\" ;;"
                    "    *'tools'*'call'*)"
                    "      case \"$line\" in"
                    "        *'\"slow\":true'*) ;;"
                    "        *'\"bad\":true'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"Known remote error\"}]}}\\n' \"$id\" ;;"
                    "        *) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"structuredContent\":{\"count\":42},\"content\":[{\"type\":\"text\",\"text\":\"Forty two\"},{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"AA==\"}]}}\\n' \"$id\" ;;"
                    "      esac ;;"
                    "  esac"
                    "done"])]
      (try
        (spit script source)
        (let [pool (mcp/create! directory {:mcp/servers {"native" {:transport :stdio :command "/bin/sh"
                                                                :args [script] :timeout-ms 1500}}})]
          (try
            (let [descriptor (mcp/invoke! pool {:action "describe" :server "native" :name "probe"})
                  gateway (mcp/gateway-descriptor pool "test")
                  result ((:fn gateway) {:action "call" :server "native" :name "probe" :arguments {}})]
              (is (= "integer" (get-in descriptor [:outputSchema :properties :count :type])))
              (is (= {:count 42} (get-in result [:value :structuredContent])))
              (is (= ["text" "image"] (mapv :type (get-in result [:value :content]))))
              (is (= {:gateway :mcp :action "call" :server "native" :name "probe"} (:details result))))
            (doseq [[arguments outcome] [[{:bad true} :reported-error] [{:slow true} :unknown]]]
              (let [error (try (mcp/invoke! pool {:action "call" :server "native" :name "probe"
                                                 :arguments arguments})
                               nil (catch clojure.lang.ExceptionInfo e e))]
                (is (= outcome (:outcome (ex-data error))))
                (is (= "native" (:server (ex-data error))))
                (is (= "probe" (:tool (ex-data error))))))
            (finally (mcp/close! pool))))
        (finally (remove-directory! directory))))))

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

(deftest raced-stdio-exit-closes-but-live-permission-failure-retains-ownership
  (when (Files/isExecutable (u/path "/bin/sh"))
    (doseq [exit-before-error? [true false]]
      (let [directory (temp-directory)
            script (str directory "/server.sh")
            release (str directory "/release")
            source (str/join
                    "\n"
                    ["while IFS= read -r line; do"
                     "  case \"$line\" in"
                     "    *'\"method\":\"initialize\"'*)"
                     "      id=$(printf '%s' \"$line\" | sed -E 's/.*\"id\":(\"[^\"]*\"|[0-9]+).*/\\1/')"
                     "      version=$(printf '%s' \"$line\" | sed -E 's/.*\"protocolVersion\":\"([^\"]+)\".*/\\1/')"
                     "      printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"%s\",\"capabilities\":{},\"serverInfo\":{\"name\":\"gated\",\"version\":\"1\"}}}\\n' \"$id\" \"$version\" ;;"
                     "  esac"
                     "done"
                     "while [ ! -f release ]; do sleep 0.01; done"])]
        (try
          (spit script source)
          (let [pool (mcp/create! directory
                                  {:mcp/servers
                                   {"gated" {:transport :stdio :command "/bin/sh"
                                             :args [script] :timeout-ms 5000}}})]
            (try
              (mcp/catalog! pool "gated")
              (let [owned (get-in @(:states pool) ["gated" :owned])
                    process ^Process (:process owned)
                    native-var (ns-resolve 'arrodes.owned-process 'posix-call)
                    native-call @native-var
                    failures (atom 0)
                    report
                    (with-redefs-fn
                      {native-var
                       (fn [library function & arguments]
                         (if (and (= "kill" function)
                                  (= [(- (.pid process)) 15] (vec arguments)))
                           (do
                             (swap! failures inc)
                             (when exit-before-error?
                               ;; The native scope really exits; only the raced
                               ;; signal result is injected, never its liveness.
                               (spit release "")
                               (when-not (.waitFor process 5 TimeUnit/SECONDS)
                                 (throw (ex-info "MCP EOF fixture did not exit" {}))))
                             (Native/setLastError 1)
                             -1)
                           (apply native-call library function arguments)))}
                      #(mcp/close! pool))]
                (is (pos? @failures))
                (is (:closed? report))
                (if exit-before-error?
                  (do
                    (is (:cleanup-complete? report))
                    (is (empty? (:errors report)))
                    (is (nil? (get-in @(:states pool) ["gated" :owned])))
                    (is (not (.isAlive process)))
                    (is (not (owned-process/alive? owned))))
                  (do
                    (is (false? (:cleanup-complete? report)))
                    (is (= "mcp/cleanup-failed" (get-in report [:errors 0 :code])))
                    (is (str/includes? (get-in report [:errors 0 :message])
                                       "kill(process-group) failed with native error 1"))
                    (is (= :cleanup-failed (get-in @(:states pool) ["gated" :status])))
                    (is (identical? owned (get-in @(:states pool) ["gated" :owned])))
                    (is (.isAlive process))
                    (is (owned-process/alive? owned))))
                ;; A real subsequent cleanup, with signaling restored, must
                ;; release the same retained owner rather than invent success.
                (let [retry (mcp/close! pool)]
                  (is (:already-closed? retry))
                  (is (:cleanup-complete? retry))
                  (is (empty? (:errors retry)))
                  (is (not (owned-process/alive? owned)))))
              (finally
                (let [report (mcp/close! pool)]
                  (when-not (:cleanup-complete? report)
                    (throw (ex-info "MCP regression cleanup did not finish" report)))))))
          (finally (remove-directory! directory)))))))
