(ns arrodes.rpc-main
  "Standalone headless JSONL RPC process."
  (:require [arrodes.rpc :as rpc]
            [arrodes.value :as value])
  (:gen-class))

(def usage
  (str "Arrodes headless RPC host\n\n"
       "Usage: arrodes --rpc [options]\n\n"
       "  --cwd <directory>       Default project directory\n"
       "  --home <directory>      Arrodes home directory\n"
       "  --data-dir <directory>  Runtime data directory\n"
       "  --memory                Use an in-memory session store\n"
       "  --trust                 Trust project resources\n"
       "  --no-trust              Do not trust project resources\n"
       "  --help, -h              Print this help\n"
       "  --version, -v           Print the host version\n"))

(defn- take-value
  [flag arguments]
  (value/check! (seq arguments) :usage (str flag " requires a value") {:option flag})
  [(first arguments) (next arguments)])

(defn- parse-args
  [arguments]
  (loop [remaining (seq arguments)
         options {}]
    (if-let [argument (first remaining)]
      (case argument
        "--cwd" (let [[item rest] (take-value argument (next remaining))]
                  (recur rest (assoc options :cwd item)))
        "--home" (let [[item rest] (take-value argument (next remaining))]
                   (recur rest (assoc options :home item)))
        "--data-dir" (let [[item rest] (take-value argument (next remaining))]
                       (recur rest (assoc options :data-dir item)))
        "--memory" (recur (next remaining) (assoc options :memory? true))
        "--trust" (recur (next remaining) (assoc options :trust true))
        "--no-trust" (recur (next remaining) (assoc options :trust false))
        "--help" (recur (next remaining) (assoc options :help? true))
        "-h" (recur (next remaining) (assoc options :help? true))
        "--version" (recur (next remaining) (assoc options :version? true))
        "-v" (recur (next remaining) (assoc options :version? true))
        (value/fail! :usage "Unknown RPC host option" {:option argument}))
      options)))

(defn -main
  [& arguments]
  (try
    (let [{:keys [help? version?] :as options} (parse-args arguments)]
      (cond
        help? (do (print usage) (flush))
        version? (println rpc/version)
        :else (rpc/serve! (dissoc options :help? :version?))))
    (catch Throwable error
      (binding [*out* *err*]
        (println (str "Error: " (or (ex-message error) (str error)))))
      (System/exit 1))
    (finally
      (shutdown-agents))))
