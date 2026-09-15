(ns arrodes.tui.controller.attachments
  "Bounded file completion and attachment loading."
  (:require [clojure.string :as str]
            [arrodes.tui.controller.client :as client]))

(declare fs max-completion-results max-completion-entries max-completion-depth max-text-attachment-bytes max-image-attachment-bytes ignored-directories image-mime-types extension read-attachment add-attachment! normalized-relative directory-entries completion-score complete-files)

(def fs (js/require "fs"))

(def max-completion-results 200)

(def max-completion-entries 20000)

(def max-completion-depth 24)

(def max-text-attachment-bytes (* 8 1024 1024))

(def max-image-attachment-bytes (* 5 1024 1024))

(def ignored-directories
  #{".git" ".hg" ".svn" ".cache" ".gradle" ".idea" ".next" ".turbo"
    "build" "coverage" "dist" "node_modules" "out" "target"})


(def image-mime-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "webp" "image/webp" "bmp" "image/bmp"})


(defn extension [file]
  (let [name (.toLowerCase (str (.basename client/path-module file)))
        dot (.lastIndexOf name ".")]
    (when (and (pos? dot) (< dot (dec (count name))))
      (.slice name (inc dot)))))


(defn read-attachment [app requested]
  (when-not (and (string? requested) (not (str/blank? requested)))
    (throw (client/error "invalid-path" "Attachment path must be non-empty" {})))
  (let [cwd (client/workspace app)
        candidate (if (.isAbsolute client/path-module requested)
                    requested
                    (.resolve client/path-module cwd requested))
        canonical (.realpathSync fs candidate)
        stat (.statSync fs canonical)]
    (when-not (.isFile stat)
      (throw (client/error "unsupported-file" "Attachment must be a regular file"
                    {:path canonical})))
    (let [size (.-size stat)
          mime (get image-mime-types (extension canonical))
          limit (if mime max-image-attachment-bytes max-text-attachment-bytes)]
      (when (> size limit)
        (throw (client/error "file-too-large" "Attachment exceeds the exact-read limit"
                      {:path canonical :bytes size :limit limit})))
      (let [bytes (.readFileSync fs canonical)
            name (.basename client/path-module canonical)]
        (if mime
          {:path canonical :name name :kind :image :mime-type mime :bytes size
           :part {:part/type :image
                  :image/mime-type mime
                  :image/data (.toString bytes "base64")}}
          (let [decoder (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true})
                text (try
                       (.decode decoder bytes)
                       (catch :default _
                         (throw (client/error "unsupported-file"
                                       "Text attachment is not valid UTF-8"
                                       {:path canonical}))))]
            {:path canonical :name name :kind :text :bytes size
             :part {:part/type :text
                    :text (str "Attached file: " (.relative client/path-module cwd canonical) "\n\n" text)}}))))))


(defn add-attachment! [app requested]
  (let [attachment (read-attachment app requested)]
    (swap! (:state app) update-in [:ui :attachments]
           (fn [attachments]
             (conj (filterv #(not= (:path attachment) (:path %)) attachments)
                   attachment)))
    attachment))


(defn normalized-relative [value]
  (str/replace value "\\" "/"))


(defn directory-entries [directory limit]
  (try
    (let [handle (.opendirSync fs directory)]
      (try
        (loop [remaining limit
               entries []]
          (if (zero? remaining)
            entries
            (if-let [entry (.readSync handle)]
              (recur (dec remaining) (conj entries entry))
              entries)))
        (finally
          (.closeSync handle))))
    (catch :default _ [])))


(defn completion-score [query candidate]
  (let [path (:path candidate)
        lower (.toLowerCase path)
        base (.toLowerCase (.basename client/path-module path))]
    (cond
      (= lower query) 0
      (.startsWith base query) 1
      (.startsWith lower query) 2
      (not (neg? (.indexOf base query))) 3
      :else 4)))


(defn complete-files [app query]
  (let [root (client/workspace app)
        query (-> (or query "") str .toLowerCase (str/replace "\\" "/"))]
    (loop [stack [[root "" 0]]
           traversed 0
           matches []]
      (if (or (empty? stack)
              (>= traversed max-completion-entries))
        (->> matches
             (sort-by (juxt #(completion-score query %) :path))
             (take max-completion-results)
             vec)
        (let [[directory relative depth] (peek stack)
              stack (pop stack)
              entries (directory-entries directory
                                         (- max-completion-entries traversed))
              [stack matches traversed]
              (reduce
               (fn [[pending found seen] entry]
                 (if (>= seen max-completion-entries)
                   [pending found seen]
                   (let [name (.-name entry)
                         directory? (.isDirectory entry)
                         ignored? (contains? ignored-directories name)
                         absolute (.join client/path-module directory name)
                         rel (normalized-relative (if (str/blank? relative)
                                                    name
                                                    (.join client/path-module relative name)))
                         display rel
                         match? (or (str/blank? query)
                                    (not (neg? (.indexOf (.toLowerCase display) query))))
                         pending (if (and directory? (not ignored?) (< depth max-completion-depth))
                                   (conj pending [absolute rel (inc depth)])
                                   pending)
                         found (if (and (not directory?) (not ignored?) match?)
                                 (conj found {:path display :directory? false})
                                 found)]
                     [pending found (inc seen)])))
               [stack matches traversed]
               entries)]
          (recur stack traversed matches))))))

