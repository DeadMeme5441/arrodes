(ns arrodes.util
  "Small value, path, and file operations shared by the modules."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files Path Paths StandardCopyOption LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security MessageDigest)
           (java.util UUID HexFormat)))

(defn now [] (System/currentTimeMillis))
(defn id [] (str (UUID/randomUUID)))
(defn fail! [code message data]
  (throw (ex-info message (assoc (or data {}) :error/code (name code)))))
(defn check! [condition code message data]
  (when-not condition (fail! code message data)))
(defn deep-merge
  ([] {})
  ([a] a)
  ([a b] (merge-with (fn [x y] (if (and (map? x) (map? y)) (deep-merge x y) y)) (or a {}) (or b {})))
  ([a b & more] (reduce deep-merge (deep-merge a b) more)))
(defn path ^Path [value]
  (if (instance? Path value) value (Paths/get (str value) (make-array String 0))))
(defn canonical-path [value]
  (str (.normalize (.toAbsolutePath (path value)))))
(defn resolve-path [cwd value]
  (canonical-path (.resolve (path cwd) (str value))))
(defn ensure-dir! [value]
  (Files/createDirectories (path value) (make-array FileAttribute 0))
  (str value))
(defn private-file! [value]
  (try
    (Files/setPosixFilePermissions (path value) (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil))
  (str value))
(defn atomic-write!
  ([file content] (atomic-write! file content {}))
  ([file content {:keys [private?] :or {private? false}}]
   (let [target (.toAbsolutePath (path file))
         parent (.getParent target)
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         temp (Files/createTempFile parent ".arrodes-" ".tmp" (make-array FileAttribute 0))]
     (try
       (when private? (private-file! temp))
       (with-open [out (java.io.FileOutputStream. (.toFile temp))]
         (.write out (.getBytes (str content) java.nio.charset.StandardCharsets/UTF_8))
         (.sync (.getFD out)))
       (Files/move temp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
       (str target)
       (finally (Files/deleteIfExists temp))))))
(defn read-edn
  ([file] (read-edn file nil))
  ([file fallback]
   (if (.isFile (io/file (str file)))
     (with-open [reader (java.io.PushbackReader. (io/reader (str file)))]
       (edn/read {:eof fallback} reader))
     fallback)))
(defn write-edn! [file value]
  (atomic-write! file (str (pr-str value) "\n") {:private? true}))
(defn sha256 [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (if (bytes? value) value (.getBytes (str value) java.nio.charset.StandardCharsets/UTF_8)))))
(defn text-content [content]
  (cond
    (nil? content) ""
    (string? content) content
    (sequential? content) (str/join "" (keep #(when (contains? #{:text "text"} (or (:part/type %) (:type %))) (:text %)) content))
    :else (str content)))
(defn error-map [error]
  {:code (or (:error/code (ex-data error)) "internal-error")
   :message (or (ex-message error) (str error))
   :data (dissoc (ex-data error) :error/code)})
(defn cancelled? [token]
  (or (.isInterrupted (Thread/currentThread))
      (cond (nil? token) false
            (fn? token) (boolean (token))
            (instance? clojure.lang.IDeref token) (boolean @token)
            :else (boolean token))))
(defn check-cancelled! [token]
  (when (cancelled? token) (fail! :cancelled "Operation cancelled" {})))
(defn bounded-writer
  "Capture Writer overloads without allowing printed output to grow past limit."
  [limit]
  (let [buffer (StringBuffer. (int (min 1024 limit)))
        truncated? (atom false)
        append! (fn [value offset length]
                  (locking buffer
                    (let [accepted (int (min (max 0 (- limit (.length buffer))) length))]
                      (when (pos? accepted)
                        (if (string? value)
                          (.append buffer ^String value (int offset) (int (+ offset accepted)))
                          (.append buffer ^chars value (int offset) accepted)))
                      (when (< accepted length) (reset! truncated? true)))))
        writer (proxy [java.io.Writer] []
                 (write
                   ([value]
                    (cond
                      (number? value) (append! (str (char (bit-and (int value) 65535))) 0 1)
                      (string? value) (append! value 0 (count value))
                      :else (append! value 0 (alength ^chars value))))
                   ([value offset length] (append! value offset length)))
                 (flush [])
                 (close [])
                 (toString [] (str buffer)))]
    {:writer writer :buffer buffer :truncated? truncated?}))
(defn bounded-string
  ([value] (bounded-string value 40000))
  ([value limit]
   (let [s (if (string? value) value (binding [*print-length* 200 *print-level* 20] (pr-str value)))]
     (if (> (count s) limit) (str (subs s 0 limit) "\n[Preview truncated]") s))))
(defn home-dir [options]
  (canonical-path (or (:home options) (System/getenv "ARRODES_HOME")
                      (str (System/getProperty "user.home") "/.arrodes-mono"))))
(defn redact [value]
  (cond
    (map? value) (into {} (map (fn [[k v]]
                               [k (if (re-find #"(?i)(api[-_]?key|password|secret|authorization|access[-_]?token|refresh[-_]?token)" (str k))
                                    (when v "[redacted]")
                                    (redact v))]) value))
    (vector? value) (mapv redact value)
    (set? value) (set (map redact value))
    (sequential? value) (mapv redact value)
    :else value))
