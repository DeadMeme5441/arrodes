(ns arrodes.tui
  "Bun entrypoint for the OpenTUI host. Provider and execution work stays in the JVM."
  (:require [arrodes.tui-app :as app]
            [arrodes.tui-view :as view]
            [arrodes.tui-widgets :as widgets]
            [clojure.string :as str]))

(def usage
  (str "Arrodes - conversation-first terminal agent\n\n"
       "Usage: bin/arrodes [options] [prompt]\n\n"
       "  --cwd PATH        Project directory\n"
       "  --home PATH       Private Arrodes configuration/credential home\n"
       "  --data-dir PATH   Session storage directory\n"
       "  --session ID      Resume a stored session\n"
       "  --runtime PATH    Core source checkout (defaults to this repository)\n"
       "  --provider NAME   Provider for a new session (codex-backend)\n"
       "  --model ID        Model for a new session (gpt-5.6-luna)\n"
       "  --thinking LEVEL  Reasoning level (high)\n"
       "  --trust           Load trusted project resources\n"
       "  --no-trust        Do not load executable project resources\n"
       "  --memory          Ephemeral in-memory sessions\n"
       "  --no-mouse        Disable mouse capture\n"
       "  --help            Show help without opening a runtime\n"
       "  --version         Show version\n\n"
       "Enter sends/steers; Ctrl+Q queues follow-up; Esc dismisses/stops.\n"
       "F2 sessions; F3 commands; F6 focus; @ context; / commands.\n"
       "The existing command-line host is available as bin/arrodes-cli.\n"))

(defn- options [arguments]
  (let [path (js/require "node:path")
        launch (.cwd js/process)
        root (or (aget (.-env js/process) "ARRODES_TUI_ROOT") launch)
        rpc-json (aget (.-env js/process) "ARRODES_TUI_RPC_COMMAND")
        rpc-command (when rpc-json (js->clj (js/JSON.parse rpc-json)))]
    (when (and rpc-command (not (and (vector? rpc-command) (seq rpc-command) (every? string? rpc-command))))
      (throw (js/Error. "ARRODES_TUI_RPC_COMMAND must be a non-empty JSON string array")))
    (loop [remaining (seq arguments)
           opts (cond-> {:runtime-root root :cwd launch :provider :codex-backend
                          :model "gpt-5.6-luna" :thinking :high :mouse? true}
                  rpc-command (assoc :rpc-command rpc-command))
           prompt []]
      (if-let [arg (first remaining)]
        (cond
          (= arg "--") (assoc opts :prompt (str/join " " (concat prompt (next remaining))))
          (contains? #{"--help" "-h" "--version" "--memory" "--trust" "--no-trust" "--no-mouse"} arg)
          (recur (next remaining)
                 (case arg "--help" (assoc opts :help? true) "-h" (assoc opts :help? true)
                       "--version" (assoc opts :version? true) "--memory" (assoc opts :memory? true)
                       "--trust" (assoc opts :trust true) "--no-trust" (assoc opts :trust false)
                       "--no-mouse" (assoc opts :mouse? false)) prompt)
          (contains? #{"--cwd" "--home" "--data-dir" "--session" "--runtime" "--provider" "--model" "--thinking"} arg)
          (let [value (second remaining)]
            (when (or (nil? value) (str/starts-with? value "--"))
              (throw (js/Error. (str arg " requires a value"))))
            (recur (nnext remaining)
                   (assoc opts (get {"--cwd" :cwd "--home" :home "--data-dir" :data-dir "--session" :session-id
                                     "--runtime" :runtime-root "--provider" :provider "--model" :model "--thinking" :thinking} arg)
                          (cond (contains? #{"--cwd" "--home" "--data-dir" "--runtime"} arg) (.resolve path launch value)
                                (contains? #{"--provider" "--thinking"} arg) (keyword value)
                                :else value)) prompt))
          (str/starts-with? arg "--") (throw (js/Error. (str "Unknown option: " arg)))
          :else (recur (next remaining) opts (conj prompt arg)))
        (assoc opts :prompt (str/join " " prompt))))))

(defn- launch! [opts]
  (let [application (app/create! opts)
        renderer (atom nil)
        mounted (atom nil)
        closing? (atom false)
        shutdown
        (fn []
          (when (compare-and-set! closing? false true)
            (-> (app/close! application)
                (.catch (fn [error]
                          (set! (.-exitCode js/process) 1)
                          (.write (.-stderr js/process) (str "Core shutdown: " (.-message error) "\n"))))
                (.finally (fn []
                            (when-let [v @mounted] (view/destroy! v))
                            (when-let [r @renderer] (.destroy r)))))))]
    (-> (.createCliRenderer widgets/core
                            #js {:exitOnCtrlC false :exitSignals #js [] :screenMode "alternate-screen"
                                 :useMouse (:mouse? opts) :autoFocus false :targetFps 30 :maxFps 60
                                 :consoleMode "disabled" :externalOutputMode "passthrough"
                                 :backgroundColor (:background widgets/colors) :openConsoleOnError false})
        (.then (fn [r]
                 (reset! renderer r)
                 (reset! mounted (view/mount! application r {:on-quit shutdown}))
                 (.once js/process "SIGTERM" shutdown)
                 (.once js/process "SIGINT" shutdown)
                 (-> (app/start! application)
                     (.then (fn [_]
                              (when (seq (:prompt opts))
                                (swap! (:state application) assoc-in [:ui :draft] (:prompt opts))
                                (app/command! application :submit {:text (:prompt opts) :mode :prompt}))))
                     (.catch (fn [_] nil)))))
        (.catch (fn [error]
                  (set! (.-exitCode js/process) 1)
                  (shutdown)
                  (.write (.-stderr js/process) (str "Cannot open terminal: " (.-message error) "\n")))))))

(defn -main [& arguments]
  (try
    (let [opts (options arguments)]
      (cond
        (:help? opts) (println usage)
        (:version? opts) (println "Arrodes 0.1.0")
        (not (and (.-isTTY (.-stdin js/process)) (.-isTTY (.-stdout js/process))))
        (throw (js/Error. "The TUI needs an interactive terminal. Use clojure -M:host for headless RPC."))
        :else (launch! opts)))
    (catch :default error
      (.write (.-stderr js/process) (str (.-message error) "\n"))
      (set! (.-exitCode js/process) 1))))

(set! *main-cli-fn* -main)
