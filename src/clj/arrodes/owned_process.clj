(ns arrodes.owned-process
  "Small native boundary for atomically owned subprocess scopes. POSIX children
  start in a new process group; Windows children start suspended in a kill-on-close job."
  (:require [clojure.string :as str])
  (:import (com.sun.jna Memory Native NativeLibrary Pointer StringArray WString)
           (com.sun.jna.platform.win32 BaseTSD$SIZE_T Kernel32
                                         WinBase$PROCESS_INFORMATION
                                         WinBase$SECURITY_ATTRIBUTES WinBase$STARTUPINFO
                                         WinDef$DWORD WinNT$HANDLE WinNT$HANDLEByReference)
           (com.sun.jna.ptr IntByReference PointerByReference)
           (java.io FileInputStream FileOutputStream InputStream OutputStream)
           (java.util.concurrent TimeUnit)))

(def ^:private launch-lock (Object.))
(def ^:private posix? (not (.startsWith (.toLowerCase (System/getProperty "os.name")) "windows")))
(def ^:private graceful-wait-ms 500)
(def ^:private forced-wait-ms 2000)

(defn- native-error [operation code]
  (ex-info (str operation " failed with native error " code)
           {:error/code "process/native-error" :operation operation :native-error code}))

(defn- wait-until [predicate millis]
  (let [deadline (+ (System/nanoTime) (* millis 1000000))]
    (loop []
      (cond
        (not (predicate)) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
        :else false))))

(defn- process-proxy [pid stdin stdout stderr await! poll! terminate!]
  (proxy [Process] []
    (getOutputStream [] stdin)
    (getInputStream [] stdout)
    (getErrorStream [] stderr)
    (waitFor
      ([] (await! nil))
      ([timeout unit]
       (some? (await! (.toMillis ^TimeUnit unit (long timeout))))))
    (exitValue []
      (or (poll!) (throw (IllegalThreadStateException. "Process is still running"))))
    (destroy [] (terminate! false))
    (destroyForcibly [] (terminate! true) this)
    (isAlive [] (nil? (poll!)))
    (pid [] (long pid))))

(defn- posix-slot []
  ;; Darwin's spawn types are opaque pointers; glibc exposes fixed structs.
  (if (= "Mac OS X" (System/getProperty "os.name"))
    (PointerByReference.)
    (Memory. 1024)))

(defn- posix-call [library function & arguments]
  (.invokeInt (.getFunction ^NativeLibrary library function) (object-array arguments)))

(defn- close-fd! [library fd]
  (when (and fd (not (neg? fd)))
    (posix-call library "close" (int fd))))

(defn- posix-pipe! [library]
  (let [fds (int-array 2)
        result (posix-call library "pipe" fds)]
    (when-not (zero? result)
      (throw (native-error "pipe" (Native/getLastError))))
    ;; Prevent unrelated concurrent execs from inheriting either endpoint.
    (doseq [fd fds]
      (when (neg? (posix-call library "fcntl" fd 2 1))
        (doseq [opened fds] (close-fd! library opened))
        (throw (native-error "fcntl(FD_CLOEXEC)" (Native/getLastError)))))
    [(aget fds 0) (aget fds 1)]))

(defn- posix-exit [status]
  (let [signal (bit-and status 0x7f)]
    (if (zero? signal)
      (bit-and (bit-shift-right status 8) 0xff)
      (+ 128 signal))))

(defn- posix-start! [argv {:keys [cwd env]}]
  (locking launch-lock
    (let [library (NativeLibrary/getInstance "c")
          pipes (atom [])
          actions (posix-slot)
          attributes (posix-slot)
          actions? (atom false)
          attributes? (atom false)
          spawned-pid (atom nil)
          streams (atom [])]
      (try
        (let [stdin-pipe (posix-pipe! library)
              _ (swap! pipes into stdin-pipe)
              stdout-pipe (posix-pipe! library)
              _ (swap! pipes into stdout-pipe)
              stderr-pipe (posix-pipe! library)
              _ (swap! pipes into stderr-pipe)
              [stdin-read stdin-write] stdin-pipe
              [stdout-read stdout-write] stdout-pipe
              [stderr-read stderr-write] stderr-pipe
              check! (fn [operation result]
                       (when-not (zero? result)
                         (throw (native-error operation result))))
              _ (check! "posix_spawn_file_actions_init"
                        (posix-call library "posix_spawn_file_actions_init" actions))
              _ (reset! actions? true)
              _ (doseq [[fd target] [[stdin-read 0] [stdout-write 1] [stderr-write 2]]]
                  (check! "posix_spawn_file_actions_adddup2"
                          (posix-call library "posix_spawn_file_actions_adddup2"
                                      actions fd target)))
              _ (doseq [fd @pipes]
                  (check! "posix_spawn_file_actions_addclose"
                          (posix-call library "posix_spawn_file_actions_addclose" actions fd)))
              _ (when cwd
                  (check! "posix_spawn_file_actions_addchdir_np"
                          (posix-call library "posix_spawn_file_actions_addchdir_np"
                                      actions (str cwd))))
              _ (check! "posix_spawnattr_init"
                        (posix-call library "posix_spawnattr_init" attributes))
              _ (reset! attributes? true)
              _ (check! "posix_spawnattr_setpgroup"
                        (posix-call library "posix_spawnattr_setpgroup" attributes 0))
              _ (check! "posix_spawnattr_setflags"
                        (posix-call library "posix_spawnattr_setflags" attributes (short 2)))
              pid-ref (IntByReference.)
              command (StringArray. (into-array String argv))
              environment (into (into {} (System/getenv)) env)
              environ (StringArray. (into-array String
                                                 (map (fn [[key value]] (str key "=" value))
                                                      environment)))
              result (posix-call library "posix_spawnp" pid-ref (str (first argv))
                                 actions attributes command environ)
              _ (check! "posix_spawnp" result)
              pid (.getValue pid-ref)
              _ (when-not (> pid 1)
                  (throw (native-error "posix_spawnp(pid)" pid)))
              _ (reset! spawned-pid pid)
              stdin (FileOutputStream. (str "/dev/fd/" stdin-write))
              _ (swap! streams conj stdin)
              stdout (FileInputStream. (str "/dev/fd/" stdout-read))
              _ (swap! streams conj stdout)
              stderr (FileInputStream. (str "/dev/fd/" stderr-read))
              _ (swap! streams conj stderr)
              _ (doseq [fd @pipes] (close-fd! library fd))
              _ (reset! pipes [])
              state (atom nil)
              state-lock (Object.)
              poll! (fn []
                      (or @state
                          (locking state-lock
                            (or @state
                                (let [status (IntByReference.)
                                      waited (posix-call library "waitpid" pid status 1)]
                                  (cond
                                    (= waited pid) (let [exit (posix-exit (.getValue status))]
                                                     (reset! state exit) exit)
                                    (zero? waited) nil
                                    (= 10 (Native/getLastError)) @state
                                    :else (throw (native-error "waitpid" (Native/getLastError)))))))))
              await! (fn [timeout-ms]
                       (if (some? timeout-ms)
                         (when (wait-until #(nil? (poll!)) timeout-ms) @state)
                         (loop []
                           (or (poll!) (do (Thread/sleep 10) (recur))))))
              signal! (fn [signal]
                        (let [result (posix-call library "kill" (- pid) signal)]
                          (when (and (neg? result) (not= 3 (Native/getLastError)))
                            (throw (native-error "kill(process-group)" (Native/getLastError))))))
              scope-alive? (fn []
                             (poll!)
                             (let [result (posix-call library "kill" (- pid) 0)
                                   error (Native/getLastError)]
                               (or (zero? result) (= 1 error))))
              process (process-proxy pid stdin stdout stderr await! poll!
                                     (fn [force?] (signal! (if force? 9 15))))]
          {:process process :kind :posix-process-group
           :scope-alive? scope-alive? :terminate! signal!})
        (catch Throwable error
          (when-let [pid @spawned-pid]
            (try (posix-call library "kill" (- pid) 9) (catch Throwable _ nil))
            (try (let [status (IntByReference.)]
                   (posix-call library "waitpid" pid status 0))
                 (catch Throwable _ nil)))
          (doseq [stream @streams]
            (try (.close ^java.io.Closeable stream) (catch Throwable _ nil)))
          (throw error))
        (finally
          (doseq [fd @pipes] (close-fd! library fd))
          (when @actions?
            (posix-call library "posix_spawn_file_actions_destroy" actions))
          (when @attributes?
            (posix-call library "posix_spawnattr_destroy" attributes)))))))

(defn- windows-command-line [argv]
  (letfn [(quote-arg [value]
            (let [value (str value)]
              (if (and (not (.isEmpty value)) (not (re-find #"[\s\"]" value)))
                value
                (str "\""
                     (loop [chars (seq value) slashes 0 out (StringBuilder.)]
                       (if-let [character (first chars)]
                         (cond
                           (= character \\) (recur (next chars) (inc slashes) out)
                           (= character \") (do (.append out (apply str (repeat (inc (* 2 slashes)) \\)))
                                                (.append out character)
                                                (recur (next chars) 0 out))
                           :else (do (.append out (apply str (repeat slashes \\)))
                                     (.append out character)
                                     (recur (next chars) 0 out)))
                         (do (.append out (apply str (repeat (* 2 slashes) \\))) (str out))))
                     "\""))))]
    (str/join " " (map quote-arg argv))))
(defn- windows-input-stream [^WinNT$HANDLE handle]
  (let [kernel Kernel32/INSTANCE
        closed? (atom false)]
    (proxy [InputStream] []
      (read
        ([] (let [one (byte-array 1)
                  n (.read ^InputStream this one 0 1)]
              (if (neg? n) -1 (bit-and (aget one 0) 0xff))))
        ([buffer]
         (.read ^InputStream this buffer 0 (alength ^bytes buffer)))
        ([buffer offset length]
         (cond
           (zero? length) 0
           @closed? -1
           :else
           (let [temporary (byte-array length)
                 read-count (IntByReference.)]
             (if (.ReadFile kernel handle temporary length read-count nil)
               (let [n (.getValue read-count)]
                 (if (zero? n)
                   -1
                   (do (System/arraycopy temporary 0 buffer offset n) n)))
               (let [error (.GetLastError kernel)]
                 (if (contains? #{109 232} error)
                   -1
                   (throw (native-error "ReadFile" error)))))))))
      (close []
        (when (compare-and-set! closed? false true)
          (.CloseHandle kernel handle))))))

(defn- windows-output-stream [^WinNT$HANDLE handle]
  (let [kernel Kernel32/INSTANCE
        closed? (atom false)]
    (proxy [OutputStream] []
      (write
        ([value]
         (if (bytes? value)
           (.write ^OutputStream this value 0 (alength ^bytes value))
           (.write ^OutputStream this (byte-array [(unchecked-byte value)]) 0 1)))
        ([buffer offset length]
         (when @closed? (throw (java.io.IOException. "Stream is closed")))
         (loop [written-total 0]
           (when (< written-total length)
             (let [remaining (- length written-total)
                   temporary (byte-array remaining)
                   written (IntByReference.)]
               (System/arraycopy buffer (+ offset written-total)
                                 temporary 0 remaining)
               (when-not (.WriteFile kernel handle temporary remaining written nil)
                 (throw (native-error "WriteFile" (.GetLastError kernel))))
               (let [n (.getValue written)]
                 (when (zero? n)
                   (throw (java.io.IOException. "WriteFile made no progress")))
                 (recur (+ written-total n))))))))
      (flush [] nil)
      (close []
        (when (compare-and-set! closed? false true)
          (.CloseHandle kernel handle))))))

(defn- windows-environment [overrides]
  (let [values (doto (java.util.TreeMap. String/CASE_INSENSITIVE_ORDER)
                 (.putAll (System/getenv)))
        _ (when overrides (.putAll values overrides))
        entries (map (fn [[key value]] (str key "=" value)) values)
        characters (.toCharArray (str (str/join "\u0000" entries) "\u0000\u0000"))
        memory (Memory. (* 2 (alength characters)))]
    (.write memory 0 characters 0 (alength characters))
    memory))

(defn- windows-startup-info [native-library handles]
  (let [size-memory (doto (Memory. Native/POINTER_SIZE) (.clear))
        initialize (.getFunction ^NativeLibrary native-library
                                 "InitializeProcThreadAttributeList")
        _ (.invokeInt initialize (object-array [nil 1 0 size-memory]))
        size (if (= 8 Native/POINTER_SIZE)
               (.getLong size-memory 0)
               (long (bit-and 0xffffffff (.getInt size-memory 0))))
        _ (when-not (pos? size)
            (throw (native-error "InitializeProcThreadAttributeList(size)"
                                 (Native/getLastError))))
        attributes (Memory. size)
        initialized (.invokeInt initialize
                                (object-array [attributes 1 0 size-memory]))
        _ (when (zero? initialized)
            (throw (native-error "InitializeProcThreadAttributeList"
                                 (Native/getLastError))))
        handle-array (Memory. (* Native/POINTER_SIZE (count handles)))
        _ (doseq [[index ^WinNT$HANDLE handle] (map-indexed vector handles)]
            (.setPointer handle-array (* index Native/POINTER_SIZE)
                         (.getPointer handle)))
        updated (.invokeInt
                 (.getFunction ^NativeLibrary native-library
                               "UpdateProcThreadAttribute")
                 (object-array
                  [attributes 0 (Pointer/createConstant 0x00020002)
                   handle-array
                   (BaseTSD$SIZE_T. (* Native/POINTER_SIZE (count handles)))
                   nil nil]))
        _ (when (zero? updated)
            (let [error (Native/getLastError)]
              (.invokeVoid
               (.getFunction ^NativeLibrary native-library
                             "DeleteProcThreadAttributeList")
               (object-array [attributes]))
              (throw (native-error "UpdateProcThreadAttribute" error))))
        base (WinBase$STARTUPINFO.)
        total (+ (.size base) Native/POINTER_SIZE)
        _ (do
            (set! (.-cb base) (WinDef$DWORD. total))
            (set! (.-dwFlags base) 0x100)
            (set! (.-hStdInput base) (nth handles 0))
            (set! (.-hStdOutput base) (nth handles 1))
            (set! (.-hStdError base) (nth handles 2))
            (.write base))
        extended (doto (Memory. total) (.clear))]
    (.write extended 0 (.getByteArray (.getPointer base) 0 (.size base))
            0 (.size base))
    (.setPointer extended (.size base) attributes)
    {:memory extended :attributes attributes :handle-array handle-array}))

(defn- windows-start! [argv {:keys [cwd env]}]
  (locking launch-lock
    (let [kernel Kernel32/INSTANCE
          native-library (NativeLibrary/getInstance "kernel32")
          handles (atom [])
          close! (fn [handle]
                   (when handle
                     (.CloseHandle kernel ^WinNT$HANDLE handle)
                     (swap! handles #(vec (remove (partial identical? handle) %)))))
          pipe! (fn []
                  (let [read-ref (WinNT$HANDLEByReference.)
                        write-ref (WinNT$HANDLEByReference.)
                        security (WinBase$SECURITY_ATTRIBUTES.)]
                    (set! (.-bInheritHandle security) true)
                    (.write security)
                    (when-not (.CreatePipe kernel read-ref write-ref security 0)
                      (throw (native-error "CreatePipe" (.GetLastError kernel))))
                    (let [pair [(.getValue read-ref) (.getValue write-ref)]]
                      (swap! handles into pair)
                      pair)))
          job-pointer (.invokePointer (.getFunction native-library "CreateJobObjectW")
                                     (object-array [nil nil]))
          _ (when (or (nil? job-pointer) (= Pointer/NULL job-pointer))
              (throw (native-error "CreateJobObjectW" (.GetLastError kernel))))
          job (WinNT$HANDLE. job-pointer)
          _ (swap! handles conj job)]
      (try
        (let [[stdin-read stdin-write] (pipe!)
              [stdout-read stdout-write] (pipe!)
              [stderr-read stderr-write] (pipe!)
              _ (doseq [parent [stdin-write stdout-read stderr-read]]
                  (when-not (.SetHandleInformation kernel parent 1 0)
                    (throw (native-error "SetHandleInformation" (.GetLastError kernel)))))
              limits (doto (Memory. 144) (.clear) (.setInt 16 0x2000))
              set-result (.invokeInt (.getFunction native-library "SetInformationJobObject")
                                     (object-array [job 9 limits 144]))
              _ (when (zero? set-result)
                  (throw (native-error "SetInformationJobObject" (.GetLastError kernel))))
              process-info (WinBase$PROCESS_INFORMATION.)
              environment (windows-environment env)
              command-line (char-array (str (windows-command-line argv) "\u0000"))
              {startup-memory :memory
               attribute-list :attributes} (windows-startup-info
                                             native-library
                                             [stdin-read stdout-write stderr-write])
              creation-error (atom nil)
              created?
              (try
                (let [result
                      (.invokeInt
                       (.getFunction native-library "CreateProcessW")
                       (object-array
                        [nil command-line nil nil 1
                         (bit-or 0x4 0x400 0x80000)
                         environment (some-> cwd str WString.)
                         startup-memory process-info]))]
                  (when (zero? result)
                    (reset! creation-error (.GetLastError kernel)))
                  (not (zero? result)))
                (finally
                  (.invokeVoid
                   (.getFunction native-library "DeleteProcThreadAttributeList")
                   (object-array [attribute-list]))))
              _ (when-not created?
                  (throw (native-error "CreateProcessW" @creation-error)))
              _ (.read process-info)
              _ (swap! handles into [(.-hProcess process-info) (.-hThread process-info)])
              assigned (.invokeInt (.getFunction native-library "AssignProcessToJobObject")
                                   (object-array [job (.-hProcess process-info)]))
              _ (when (zero? assigned)
                  (.TerminateProcess kernel (.-hProcess process-info) 1)
                  (throw (native-error "AssignProcessToJobObject" (.GetLastError kernel))))
              resumed (.invokeInt (.getFunction native-library "ResumeThread")
                                  (object-array [(.-hThread process-info)]))
              _ (when (= -1 resumed)
                  (.invokeInt (.getFunction native-library "TerminateJobObject")
                              (object-array [job 1]))
                  (throw (native-error "ResumeThread" (.GetLastError kernel))))
              _ (close! (.-hThread process-info))
              _ (doseq [child [stdin-read stdout-write stderr-write]] (close! child))
              stdin (windows-output-stream stdin-write)
              stdout (windows-input-stream stdout-read)
              stderr (windows-input-stream stderr-read)
              _ (swap! handles #(vec (remove (set [stdin-write stdout-read stderr-read]) %)))
              state (atom nil)
              state-lock (Object.)
              job-lock (Object.)
              job-closed? (atom false)
              finish! (fn []
                        (or @state
                            (locking state-lock
                              (or @state
                                  (let [exit (IntByReference.)]
                                    (when-not (.GetExitCodeProcess
                                               kernel (.-hProcess process-info) exit)
                                      (throw (native-error "GetExitCodeProcess"
                                                           (.GetLastError kernel))))
                                    (let [value (.getValue exit)]
                                      (when-not (= 259 value)
                                        (reset! state value)
                                        (close! (.-hProcess process-info))
                                        value)))))))
              await! (fn [timeout-ms]
                       (or @state
                           (locking state-lock
                             (or @state
                                 (let [result
                                       (.WaitForSingleObject
                                        kernel (.-hProcess process-info)
                                        (if (some? timeout-ms)
                                          (int (min 0xffffffff timeout-ms)) -1))]
                                   (cond
                                     (= result 0) (finish!)
                                     (= result 0x102) nil
                                     :else
                                     (throw (native-error
                                             "WaitForSingleObject"
                                             (.GetLastError kernel)))))))))
              scope-alive?
              (fn []
                (finish!)
                (locking job-lock
                  (if @job-closed?
                    false
                    (let [accounting (doto (Memory. 48) (.clear))
                          queried
                          (.invokeInt
                           (.getFunction native-library
                                         "QueryInformationJobObject")
                           (object-array [job 1 accounting 48 nil]))]
                      (when (zero? queried)
                        (throw (native-error "QueryInformationJobObject"
                                             (.GetLastError kernel))))
                      (if (zero? (.getInt accounting 40))
                        (do
                          (finish!)
                          (reset! job-closed? true)
                          (close! job)
                          false)
                        true)))))
              terminate-job!
              (fn [_]
                (locking job-lock
                  (when-not @job-closed?
                    (when (zero?
                           (.invokeInt
                            (.getFunction native-library "TerminateJobObject")
                            (object-array [job 1])))
                      (throw (native-error "TerminateJobObject"
                                           (.GetLastError kernel)))))))
              process (process-proxy (.longValue (.-dwProcessId process-info))
                                     stdin stdout stderr await! finish! terminate-job!)]
          {:process process :kind :windows-job
           :scope-alive? scope-alive? :terminate! terminate-job!})
        (catch Throwable error
          (doseq [handle (reverse @handles)]
            (try (.CloseHandle kernel ^WinNT$HANDLE handle) (catch Throwable _ nil)))
          (throw error))))))

(defn start!
  "Starts argv in an OS-owned scope. env overlays the inherited environment."
  ([argv] (start! argv {}))
  ([argv options]
   (when-not (and (vector? argv) (seq argv) (every? string? argv))
     (throw (ex-info "argv must be a non-empty vector of strings"
                     {:error/code "process/invalid-command"})))
   (if posix? (posix-start! argv options) (windows-start! argv options))))

(defn alive? [owned]
  (boolean ((:scope-alive? owned))))

(defn stop!
  "Closes stdin, requests graceful scope termination, escalates, and confirms exit."
  [owned]
  (try (.close (.getOutputStream ^Process (:process owned))) (catch Throwable _ nil))
  (when (alive? owned)
    (if (= :posix-process-group (:kind owned))
      ((:terminate! owned) 15)
      ;; Windows has no general graceful signal. EOF above is the graceful phase.
      nil)
    (when-not (wait-until #(alive? owned) graceful-wait-ms)
      ((:terminate! owned) 9)
      (when-not (wait-until #(alive? owned) forced-wait-ms)
        (throw (ex-info "Owned subprocess scope did not terminate"
                        {:error/code "process/cleanup-incomplete"
                         :ownership (:kind owned)
                         :pid (.pid ^Process (:process owned))})))))
  true)
