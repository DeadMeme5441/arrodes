(ns arrodes.store.transfer
  "Portable history copies and validated session export/import."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [arrodes.context-tree :as context-tree]
            [arrodes.session :as session-model]
            [arrodes.platform :as util]
            [arrodes.value :as value]
            [arrodes.store.codec :as codec]
            [arrodes.store.db :as db]
            [arrodes.store.files :as files]
            [arrodes.store.records :as records]
            [arrodes.store.sql :as sql])
  (:import (java.sql Connection ResultSet)
           (java.nio.file Files LinkOption)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(def ^:private max-transfer-artifact-bytes (* 256 1024 1024))

(def ^:private retrieval-span-keys
  [:source-first-entry-id :source-last-entry-id :source-count])

(defn- map-retrieval-markers
  "Transforms explicit markers in finite stored EDN, sharing unchanged collections.
  Native artifacts are governed by byte limits, not durable-inline shape limits."
  [item transform]
  (letfn [(visit [item]
            (cond
              (map? item)
              (reduce-kv
               (fn [result key nested]
                 (let [mapped-key (visit key)
                       mapped
                       (case key
                         :history/retrieval (transform nested)
                         :history/retrievals
                         (do
                           (value/check! (vector? nested) :invalid-import
                                        "History retrievals must be a vector" {})
                           (reduce-kv
                            (fn [result index reference]
                              (let [mapped (transform reference)]
                                (if (identical? reference mapped)
                                  result
                                  (assoc result index mapped))))
                            nested nested))
                         (visit nested))]
                   (cond
                     (not (identical? key mapped-key))
                     (assoc (dissoc result key) mapped-key mapped)
                     (not (identical? nested mapped)) (assoc result key mapped)
                     :else result)))
               item item)

              (vector? item)
              (reduce-kv (fn [result index nested]
                           (let [mapped (visit nested)]
                             (if (identical? nested mapped)
                               result
                               (assoc result index mapped))))
                         item item)

              (set? item)
              (reduce (fn [result nested]
                        (let [mapped (visit nested)]
                          (if (identical? nested mapped)
                            result
                            (conj (disj result nested) mapped))))
                      item item)

              (list? item)
              (loop [remaining (seq item) index 0]
                (if-let [remaining remaining]
                  (let [nested (first remaining)
                        mapped (visit nested)]
                    (if (identical? nested mapped)
                      (recur (next remaining) (inc index))
                      (with-meta
                        (apply list (concat (take index item)
                                            [mapped] (map visit (next remaining))))
                        (meta item))))
                  item))

              :else item))]
    (visit item)))

(defn- retrieval-entry? [entry]
  (contains? #{:message :custom-context :evaluation :custom} (:kind entry)))

(defn- validate-retrieval-shape! [reference]
  (value/check! (map? reference) :invalid-import "History retrieval must be a map" {})
  (value/check! (codec/uuid? (:session-id reference)) :invalid-import
               "History retrieval session must be a UUID" {})
  (doseq [key [:context-entry-ids :source-entry-ids]]
    (let [ids (get reference key)]
      (value/check! (and (vector? ids) (every? codec/uuid? ids)
                        (= (count ids) (count (set ids))))
                   :invalid-import "History retrieval entry references must be distinct UUIDs"
                   {:field key})))
  (value/check! (boolean? (:available? reference)) :invalid-import
               "History retrieval availability must be boolean" {})
  (when (contains? reference :query)
    (value/check! (or (nil? (:query reference)) (string? (:query reference)))
                 :invalid-import "History retrieval query must be text" {}))
  (when (contains? reference :context-head-entry-id)
    (value/check! (codec/uuid? (:context-head-entry-id reference)) :invalid-import
                 "History retrieval context head must be a UUID" {}))
  (when (some? (:operation-id reference))
    (value/check! (codec/uuid? (:operation-id reference)) :invalid-import
                 "History retrieval operation must be a UUID" {}))
  (when (some? (:call-id reference))
    (value/check! (and (string? (:call-id reference))
                      (not (str/blank? (:call-id reference))))
                 :invalid-import "History retrieval call must be nonblank text" {}))
  (when (contains? reference :mode)
    (value/check! (contains? #{:view :zoom :read} (:mode reference)) :invalid-import
                 "History retrieval mode must be :view, :zoom or :read" {}))
  (when (some #(contains? reference %) retrieval-span-keys)
    (value/check! (and (every? #(contains? reference %) retrieval-span-keys)
                      (codec/uuid? (:source-first-entry-id reference))
                      (codec/uuid? (:source-last-entry-id reference))
                      (integer? (:source-count reference))
                      (pos? (:source-count reference)))
                 :invalid-import "History retrieval span must contain UUID endpoints and a positive count" {}))
  (codec/encode reference)
  reference)

(defn- validate-retrieval! [reference sid entries id-set]
  (validate-retrieval-shape! reference)
  (let [ids (concat (:context-entry-ids reference) (:source-entry-ids reference)
                    (keep reference [:context-head-entry-id
                                     :source-first-entry-id :source-last-entry-id]))]
    (value/check! (= sid (:session-id reference)) :invalid-import
                 "Active history retrieval belongs to another session" {})
    (value/check! (every? #(contains? id-set %) ids) :invalid-import
                 "Active history retrieval references a missing entry" {})
    (when (contains? reference :source-count)
      (let [sources (context-tree/source-entries
                     (session-model/active-path entries (:source-last-entry-id reference)))
            covered (drop-while #(not= (:source-first-entry-id reference) (:id %)) sources)]
        (value/check! (and (seq covered)
                          (= (:source-last-entry-id reference) (:id (last covered)))
                          (= (:source-count reference) (count covered)))
                     :invalid-import "History retrieval span does not match its original sources" {})))
    (when (contains? reference :source-reference)
      (validate-retrieval-shape! (:source-reference reference)))
    reference))

(defn- remap-retrieval [reference old-sid new-sid id-map]
  (validate-retrieval-shape! reference)
  (when (contains? reference :source-reference)
    (validate-retrieval-shape! (:source-reference reference)))
  (let [local? (= old-sid (:session-id reference))
        context-ids (if local? (into [] (keep id-map) (:context-entry-ids reference)) [])
        source-ids (if local? (into [] (keep id-map) (:source-entry-ids reference)) [])
        head? (contains? reference :context-head-entry-id)
        head-id (when local? (get id-map (:context-head-entry-id reference)))
        span? (contains? reference :source-count)
        first-id (get id-map (:source-first-entry-id reference))
        last-id (get id-map (:source-last-entry-id reference))
        span-complete? (and local? (or (not span?) (and first-id last-id)))
        complete? (and (= old-sid (:session-id reference))
                       (= (count context-ids) (count (:context-entry-ids reference)))
                       (= (count source-ids) (count (:source-entry-ids reference)))
                       (or (not head?) head-id)
                       span-complete?)
        provenance (or (:source-reference reference)
                       (dissoc reference :source-reference))
        remapped (-> reference
                     (dissoc :operation-id :call-id :context-head-entry-id
                             :source-first-entry-id :source-last-entry-id :source-count)
                     (assoc :session-id new-sid
                            :context-entry-ids context-ids
                            :source-entry-ids source-ids
                            :available? (boolean (and (:available? reference) complete?))
                            :source-reference provenance))]
    (cond-> remapped
      (and head? head-id)
      (assoc :context-head-entry-id head-id)
      (and span? span-complete?)
      (assoc :source-first-entry-id first-id
             :source-last-entry-id last-id
             :source-count (:source-count reference)))))

(defn- remap-entry-retrievals [entry old-sid new-sid id-map]
  (if (retrieval-entry? entry)
    (update entry :data map-retrieval-markers
            #(remap-retrieval % old-sid new-sid id-map))
    entry))

(defn- native-artifact-ids [results]
  (into #{} (keep #(when (= :artifact (:kind %)) (:artifact-id %))) results))

(defn- native-artifact-value [descriptor bytes native-ids strict?]
  (when (and bytes (= :edn (:kind descriptor))
             (contains? native-ids (:id descriptor)))
    (try
      {:value (codec/decode (String. ^bytes bytes StandardCharsets/UTF_8))}
      (catch Exception error
        (when strict?
          (value/fail! :invalid-import "Native EDN artifact cannot be checked for history retrievals"
                       {:artifact-id (:id descriptor) :cause (ex-message error)}))))))

(defn- remap-native-artifact [descriptor bytes native-ids transform]
  (if-let [native (native-artifact-value descriptor bytes native-ids false)]
    (let [mapped (map-retrieval-markers (:value native) transform)]
      (if (identical? mapped (:value native))
        {:descriptor descriptor :bytes bytes}
        (let [bytes (.getBytes ^String (binding [*print-length* nil *print-level* nil
                                               *print-dup* false]
                                        (pr-str mapped))
                               StandardCharsets/UTF_8)]
          {:descriptor (assoc descriptor :bytes (alength bytes) :sha256 (util/sha256 bytes))
           :bytes bytes})))
    {:descriptor descriptor :bytes bytes}))

(defn- visit-retrievals
  ([entries results artifact-records transform]
   (visit-retrievals entries results artifact-records transform false))
  ([entries results artifact-records transform strict?]
   (doseq [entry entries :when (retrieval-entry? entry)]
     (map-retrieval-markers (:data entry) transform))
   (doseq [descriptor results]
     (map-retrieval-markers (:details descriptor) transform)
     (when (= :inline (:kind descriptor))
       (map-retrieval-markers (:value descriptor) transform)))
   (let [native-ids (native-artifact-ids results)]
     (doseq [{:keys [descriptor bytes]} artifact-records]
       (when-let [native (native-artifact-value descriptor bytes native-ids strict?)]
         (map-retrieval-markers (:value native) transform))))))
(defn- persisted-artifact-bytes [store ^Connection connection descriptor]
  (if (:memory? store)
    (first (sql/query-sql connection "SELECT content FROM artifacts WHERE id=? AND session_id=?"
                      [(:id descriptor) (:session-id descriptor)]
                      #(.getBytes ^ResultSet % "content")))
    (let [path (files/artifact-storage-path store (:sha256 descriptor))]
      (when (and (not (Files/isSymbolicLink path))
                 (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
        (Files/readAllBytes path)))))
(defn- remap-labels [labels id-map]
  (into []
        (keep (fn [label]
                (when-let [entry-id (get id-map (:entry-id label))]
                  (assoc label :entry-id entry-id))))
        labels))

(defn- remap-entry-refs [{:keys [kind data] :as entry} id-map compaction-id-map]
  (case kind
    :compaction
    (let [kept (get compaction-id-map (:first-kept-entry-id data))]
      (if (and kept (not= kept (:id entry)))
        (assoc-in entry [:data :first-kept-entry-id] kept)
        (update entry :data dissoc :first-kept-entry-id)))

    :branch-summary
    (if-let [from-id (get id-map (:from-id data))]
      (assoc-in entry [:data :from-id] from-id)
      (update entry :data dissoc :from-id))

    :label
    (assoc-in entry [:data :entry-id] (get id-map (:entry-id data)))

    entry))

(defn- canonical-result-descriptor [{:keys [kind data]}]
  (case kind
    :message (:message/result data)
    :custom-context (:message/result data)
    :evaluation (get-in data [:result :result])
    :custom (get-in data [:result :result])
    nil))

(defn- referenced-result-ids [entries]
  (into #{}
        (keep (fn [entry]
                (let [descriptor (canonical-result-descriptor entry)]
                  (when (and (map? descriptor) (integer? (:id descriptor)))
                    (:id descriptor)))))
        entries))

(defn- remap-entry-result-refs [entry imported-results]
  (let [descriptor (canonical-result-descriptor entry)
        replacement (when (and (map? descriptor) (integer? (:id descriptor)))
                      (get imported-results (:id descriptor)))]
    (if-not replacement
      entry
      (case (:kind entry)
        :message (assoc-in entry [:data :message/result] replacement)
        :custom-context (assoc-in entry [:data :message/result] replacement)
        :evaluation (assoc-in entry [:data :result :result] replacement)
        :custom (assoc-in entry [:data :result :result] replacement)
        entry))))

(defn- artifact-descriptor? [value]
  (and (map? value)
       (codec/uuid? (:id value))
       (string? (:session-id value))
       (string? (:sha256 value))
       (integer? (:bytes value))
       (contains? #{:text :edn :binary} (:kind value))))

(defn- result-artifact-ids [descriptor]
  (cond-> #{}
    (and (= :artifact (:kind descriptor)) (codec/uuid? (:artifact-id descriptor)))
    (conj (:artifact-id descriptor))

    (artifact-descriptor? (get-in descriptor [:details :artifact]))
    (conj (get-in descriptor [:details :artifact :id]))))

(defn- referenced-artifact-ids [descriptors]
  (reduce into #{} (map result-artifact-ids descriptors)))

(defn- remap-result-details [details artifact-by-old]
  (if-let [replacement (let [artifact (:artifact details)]
                         (when (artifact-descriptor? artifact)
                           (get artifact-by-old (:id artifact))))]
    (assoc details :artifact replacement)
    details))

(defn- remap-entry-artifact-refs [entry artifact-by-old]
  (let [details (get-in entry [:data :result :details])]
    (if (and (contains? #{:evaluation :custom} (:kind entry)) (map? details))
      (assoc-in entry [:data :result :details]
                (remap-result-details details artifact-by-old))
      entry)))

(defn- copy-value-records! [store ^Connection connection old-sid new-sid entries id-map now]
  (let [wanted (referenced-result-ids entries)
        source-results (->> (sql/query-sql connection
                                       "SELECT descriptor FROM results WHERE session_id=? ORDER BY id"
                                       [old-sid] #(codec/decode (.getString ^ResultSet % "descriptor")))
                            (filterv #(contains? wanted (:id %))))
        found (set (map :id source-results))
        _ (value/check! (= wanted found) :result-not-found
                       "Copied history references missing durable results"
                       {:result-ids (vec (sort (set/difference wanted found)))})
        artifact-ids (referenced-artifact-ids source-results)
        source-artifacts
        (mapv (fn [artifact-id]
                (let [row (first (sql/query-sql connection "SELECT * FROM artifacts WHERE session_id=? AND id=?"
                                            [old-sid artifact-id]
                                            (fn [^ResultSet rs]
                                              (cond-> {:id (.getString rs "id")
                                                       :session-id (.getString rs "session_id")
                                                       :sha256 (.getString rs "sha256")
                                                       :bytes (.getLong rs "bytes")
                                                       :kind (keyword (.getString rs "kind"))
                                                       :available? (pos? (.getInt rs "available"))
                                                       :created-at (.getLong rs "created_at")}
                                                (.getString rs "name") (assoc :name (.getString rs "name"))))))]
                  (value/check! row :artifact-not-found
                               "Copied history references a missing artifact"
                               {:artifact-id artifact-id})
                  row))
              artifact-ids)
        source-shas (set (map :sha256 source-artifacts))
        artifact-id-map (into {} (map (fn [descriptor] [(:id descriptor) (util/id)]) source-artifacts))
        native-ids (native-artifact-ids source-results)
        transform #(remap-retrieval % old-sid new-sid id-map)
        imported-artifacts
        (mapv (fn [descriptor]
                (let [source-bytes (when (:available? descriptor)
                                     (persisted-artifact-bytes store connection descriptor))
                      available? (and source-bytes
                                      (= (:bytes descriptor) (alength ^bytes source-bytes))
                                      (= (:sha256 descriptor) (util/sha256 source-bytes)))
                      remapped (remap-native-artifact descriptor (when available? source-bytes)
                                                     native-ids transform)
                      copied (cond-> (assoc (:descriptor remapped)
                                            :id (get artifact-id-map (:id descriptor))
                                            :session-id new-sid
                                            :available? (boolean available?)
                                            :created-at now)
                               (:name descriptor) (assoc :name (:name descriptor)))]
                  {:descriptor copied :bytes (:bytes remapped)}))
              source-artifacts)
        artifact-by-old (into {} (map (fn [old copied] [(:id old) (:descriptor copied)])
                                      source-artifacts imported-artifacts))
        result-id-map (into {} (map-indexed (fn [index descriptor] [(:id descriptor) (inc index)])
                                            source-results))
        imported-results
        (into {}
              (map (fn [descriptor]
                     (let [artifact (get artifact-by-old (:artifact-id descriptor))
                           copied (cond-> {:id (get result-id-map (:id descriptor))
                                           :session-id new-sid
                                           :kind (:kind descriptor)
                                           :content (or (:content descriptor) "")
                                           :details (map-retrieval-markers
                                                     (remap-result-details
                                                      (or (:details descriptor) {}) artifact-by-old)
                                                     transform)
                                           :available? (case (:kind descriptor)
                                                         :live false
                                                         :artifact (and (:available? descriptor)
                                                                        (:available? artifact))
                                                         (boolean (:available? descriptor)))}
                                    (= :inline (:kind descriptor))
                                    (assoc :value (map-retrieval-markers (:value descriptor) transform))
                                    (= :artifact (:kind descriptor)) (assoc :artifact-id (:id artifact)))]
                       [(:id descriptor) copied])))
              source-results)]
    (doseq [{:keys [descriptor bytes]} imported-artifacts]
      (when (and bytes (not (:memory? store))
                 (not (contains? source-shas (:sha256 descriptor))))
        (files/write-content-addressed! store (:sha256 descriptor) bytes))
      (sql/execute-sql! connection
                    "INSERT INTO artifacts(id,session_id,sha256,bytes,kind,available,created_at,name,content) VALUES(?,?,?,?,?,?,?,?,?)"
                    [(:id descriptor) new-sid (:sha256 descriptor) (:bytes descriptor)
                     (name (:kind descriptor)) (if (:available? descriptor) 1 0)
                     now (:name descriptor) (when (:memory? store) bytes)]))
    (doseq [descriptor (sort-by :id (vals imported-results))]
      (sql/execute-sql! connection
                    "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                    [new-sid (:id descriptor) (name (:kind descriptor)) (codec/encode descriptor) now]))
    {:results imported-results :artifacts artifact-by-old}))

(defn- copy-path! [store ^Connection connection source-row source-path opts]
  (let [copy-path
        (loop [remaining source-path
               included #{}
               result []]
          (if-let [entry (first remaining)]
            (if (and (= :label (:kind entry))
                     (not (contains? included (get-in entry [:data :entry-id]))))
              (recur (next remaining) included result)
              (recur (next remaining) (conj included (:id entry)) (conj result entry)))
            result))
        new-sid (or (:id opts) (util/id))
        _ (codec/require-uuid! new-sid :session-id)
        id-map (into {} (map (fn [entry] [(:id entry) (util/id)]) copy-path))
        compaction-id-map
        (loop [remaining (rseq source-path)
               following nil
               result id-map]
          (if-let [entry (first remaining)]
            (if-let [copied-id (get id-map (:id entry))]
              (recur (next remaining) copied-id result)
              (recur (next remaining) following (assoc result (:id entry) following)))
            result))
        now (util/now)
        config (session-model/effective-config (:arrodes.store/base-config source-row) source-path)
        copied-source-head (get id-map (:id (last copy-path)))
        raw-boundary (records/tool-boundary-entries source-path)
        final-head (or (:id (last raw-boundary)) copied-source-head)
        snapshot (-> (session-model/new-snapshot
                      (records/prepare-session-options
                       {:id new-sid
                        :name (or (:name opts) (str (:name source-row) " copy"))
                        :cwd (or (:cwd opts) (:cwd source-row))
                        :config config
                        :status :idle
                        :created-at now
                        :metadata (dissoc (or (:metadata opts) (:metadata source-row))
                                          :agent/origin :context/view-node-ids)
                        :labels (remap-labels (:labels source-row) id-map)
                        :parent-id (:id source-row)
                        :fork-entry (:source-leaf opts)}))
                     (assoc :head final-head))]
    (value/check! (nil? (records/find-session connection new-sid)) :session-exists
                 "Session ID already exists" {:session-id new-sid})
    (records/insert-session! connection snapshot (:arrodes.store/base-config source-row))
    (let [value-records (copy-value-records! store connection (:id source-row)
                                             new-sid copy-path id-map now)
          imported-results (:results value-records)
          copied-source
          (mapv (fn [index entry]
                  (-> entry
                      (assoc :id (get id-map (:id entry))
                             :session-id new-sid
                             :parent-id (when (pos? index)
                                          (get id-map (:id (nth copy-path (dec index))))))
                      (remap-entry-refs id-map compaction-id-map)
                      (remap-entry-retrievals (:id source-row) new-sid id-map)
                      (remap-entry-result-refs imported-results)
                      (remap-entry-artifact-refs (:artifacts value-records))))
                (range) copy-path)
          start-seq (inc (long (reduce max 0 (map :seq copy-path))))
          boundary
          (loop [remaining raw-boundary
                 parent copied-source-head
                 seq start-seq
                 result []]
            (if-let [raw (first remaining)]
              (let [entry (assoc raw :session-id new-sid :parent-id parent
                                 :seq seq :created-at now)]
                (recur (next remaining) (:id entry) (inc seq) (conj result entry)))
              result))
          copied (into copied-source boundary)]
      (doseq [entry copied]
        (sql/execute-sql! connection
                      "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                      [(:id entry) new-sid (:parent-id entry) (:seq entry) (name (:kind entry))
                       (codec/encode (:data entry)) (:created-at entry)])))
    snapshot))

(defn fork!
  "Copies a selected root-to-entry path into a fresh session with remapped entry references."
  [store sid opts]
  (db/transact! store
    (fn [connection]
      (let [source (records/require-session connection sid)
            expected (:expected-revision opts)
            _ (when (some? expected)
                (value/check! (and (integer? expected)
                                  (= (long expected) (:revision source)))
                             :stale-revision "Session revision has changed"
                             {:session-id sid :expected expected
                              :actual (:revision source)}))
            requested (or (:entry-id opts) (:head source))
            _ (value/check! requested :invalid-fork "Cannot fork an empty session" {:session-id sid})
            target (case (or (:position opts) :at)
                     :at requested
                     :before (let [entry (first (sql/query-sql connection "SELECT * FROM entries WHERE session_id=? AND id=?" [sid requested] records/entry-row))]
                               (value/check! entry :entry-not-found "Fork entry does not exist" {:entry-id requested})
                               (:parent-id entry))
                     (value/fail! :invalid-fork "Fork position must be :before or :at" {:position (:position opts)}))
            path (if target (session-model/active-path (records/all-entries connection sid) target) [])]
        (copy-path! store connection source path (assoc opts :source-leaf requested))))))

(defn clone!
  "Copies the full active path into a fresh session."
  [store sid opts]
  (db/transact! store
    (fn [connection]
      (let [source (records/require-session connection sid)
            expected (:expected-revision opts)
            _ (when (some? expected)
                (value/check! (and (integer? expected)
                                  (= (long expected) (:revision source)))
                             :stale-revision "Session revision has changed"
                             {:session-id sid :expected expected
                              :actual (:revision source)}))
            path (session-model/active-path (records/all-entries connection sid) (:head source))]
        (copy-path! store connection source path (assoc opts :source-leaf (:head source)))))))

(defn- export-artifact-row [store connection ^ResultSet rs]
  (let [descriptor (cond-> {:id (.getString rs "id")
                            :session-id (.getString rs "session_id")
                            :sha256 (.getString rs "sha256")
                            :bytes (.getLong rs "bytes")
                            :kind (keyword (.getString rs "kind"))
                            :available? (pos? (.getInt rs "available"))
                            :created-at (.getLong rs "created_at")}
                     (.getString rs "name") (assoc :name (.getString rs "name")))
        bytes (when (:available? descriptor)
                (if (:memory? store)
                  (.getBytes rs "content")
                  (persisted-artifact-bytes store connection descriptor)))
        intact? (and bytes
                     (= (:bytes descriptor) (alength ^bytes bytes))
                     (= (:sha256 descriptor) (util/sha256 bytes)))
        descriptor (assoc descriptor :available? (boolean intact?))]
    (cond-> {:descriptor descriptor}
      intact? (assoc :encoding :base64
                     :content (.encodeToString (Base64/getEncoder) bytes)))))

(defn- export-artifacts [store connection sid]
  (let [records (sql/query-sql connection
                          "SELECT * FROM artifacts WHERE session_id=? ORDER BY created_at,id"
                          [sid] #(export-artifact-row store connection %))
        total (reduce + 0 (keep #(when (:content %) (get-in % [:descriptor :bytes])) records))]
    (value/check! (<= total max-transfer-artifact-bytes) :session-export-too-large
                 "Session artifacts exceed the bounded export transfer size"
                 {:bytes total :limit max-transfer-artifact-bytes})
    records))

(defn- export-results [connection sid artifact-records]
  (let [artifact-availability
        (into {} (map (fn [{:keys [descriptor]}]
                        [(:id descriptor) (:available? descriptor)])
                      artifact-records))]
    (mapv (fn [descriptor]
            (case (:kind descriptor)
              :live (assoc descriptor :available? false)
              :artifact
              (do
                (value/check! (contains? artifact-availability (:artifact-id descriptor))
                             :artifact-not-found
                             "Result references an artifact missing from the session"
                             {:result-id (:id descriptor)
                              :artifact-id (:artifact-id descriptor)})
                (assoc descriptor :available?
                       (boolean (and (:available? descriptor)
                                     (get artifact-availability (:artifact-id descriptor))))))
              descriptor))
          (sql/query-sql connection "SELECT descriptor FROM results WHERE session_id=? ORDER BY id"
                     [sid] #(codec/decode (.getString ^ResultSet % "descriptor"))))))

(defn export-session [store sid]
  (db/store-read store
    (fn [connection]
      (let [snapshot (records/require-session connection sid)
            artifacts (export-artifacts store connection sid)
            entries (records/all-entries connection sid)
            results (export-results connection sid artifacts)
            native-ids (native-artifact-ids results)
            typed? (volatile! false)
            _ (visit-retrievals
               entries results
               (mapv (fn [record]
                       {:descriptor (:descriptor record)
                        :bytes (when (and (= :edn (get-in record [:descriptor :kind]))
                                          (contains? native-ids (get-in record [:descriptor :id])))
                                 (when-let [content (:content record)]
                                   (.decode (Base64/getDecoder) ^String content)))})
                     artifacts)
               (fn [reference] (vreset! typed? true) reference))]
        {:format "arrodes-session"
         :version (if @typed? 2 1)
         :session (update (records/public-session snapshot) :metadata dissoc :context/view-node-ids)
         :base-config (:arrodes.store/base-config snapshot)
         :entries entries
         :results results
         :artifacts artifacts}))))
(defn- decode-transfer-bytes [record]
  (when (contains? record :content)
    (value/check! (contains? #{:base64 "base64"} (:encoding record)) :invalid-import
                 "Artifact transfer encoding must be base64" {})
    (try
      (.decode (Base64/getDecoder) ^String (:content record))
      (catch Throwable error
        (value/fail! :invalid-import "Artifact contains invalid base64"
                    {:cause (ex-message error)})))))

(defn- validate-import-artifacts! [records source-sid]
  (value/check! (vector? records) :invalid-import "Export artifacts must be a vector" {})
  (let [ids (mapv #(get-in % [:descriptor :id]) records)
        _ (value/check! (= (count ids) (count (set ids))) :invalid-import
                       "Export contains duplicate artifact IDs" {})
        validated
        (mapv (fn [record]
                (value/check! (map? record) :invalid-import
                             "Artifact transfer record must be a map" {})
                (let [descriptor (:descriptor record)
                      _ (value/check! (map? descriptor) :invalid-import
                                     "Artifact descriptor must be a map" {})
                      bytes (decode-transfer-bytes record)]
                  (codec/require-uuid! (:id descriptor) :artifact-id)
                  (value/check! (= source-sid (:session-id descriptor)) :invalid-import
                               "Artifact belongs to another exported session" {:artifact-id (:id descriptor)})
                  (value/check! (contains? #{:text :edn :binary} (:kind descriptor)) :invalid-import
                               "Artifact has an invalid kind" {:artifact-id (:id descriptor)})
                  (value/check! (boolean? (:available? descriptor)) :invalid-import
                               "Artifact availability must be boolean" {:artifact-id (:id descriptor)})
                  (value/check! (= (:available? descriptor) (boolean bytes)) :invalid-import
                               "Artifact content and availability disagree" {:artifact-id (:id descriptor)})
                  (value/check! (or (contains? record :content)
                                   (not (contains? record :encoding)))
                               :invalid-import "Unavailable artifact cannot declare an encoding"
                               {:artifact-id (:id descriptor)})
                  (value/check! (and (string? (:sha256 descriptor))
                                    (boolean (re-matches #"[0-9a-f]{64}" (:sha256 descriptor))))
                               :invalid-import "Artifact digest is invalid" {:artifact-id (:id descriptor)})
                  (value/check! (and (integer? (:bytes descriptor)) (not (neg? (:bytes descriptor))))
                               :invalid-import "Artifact byte count is invalid" {:artifact-id (:id descriptor)})
                  (value/check! (and (integer? (:created-at descriptor))
                                    (not (neg? (:created-at descriptor))))
                               :invalid-import "Artifact creation time is invalid"
                               {:artifact-id (:id descriptor)})
                  (when (contains? descriptor :name)
                    (value/check! (and (string? (:name descriptor))
                                      (not (str/blank? (:name descriptor))))
                                 :invalid-import "Artifact name is invalid"
                                 {:artifact-id (:id descriptor)}))
                  (when bytes
                    (value/check! (and (= (:bytes descriptor) (alength ^bytes bytes))
                                      (= (:sha256 descriptor) (util/sha256 bytes)))
                                 :invalid-import "Artifact content does not match its digest"
                                 {:artifact-id (:id descriptor)}))
                  {:descriptor descriptor :bytes bytes}))
              records)
        total (reduce + 0 (keep #(some-> % :bytes alength) validated))]
    (value/check! (<= total max-transfer-artifact-bytes) :session-import-too-large
                 "Session artifacts exceed the bounded import transfer size"
                 {:bytes total :limit max-transfer-artifact-bytes})
    validated))

(defn- validate-import-results! [descriptors source-sid artifact-availability]
  (value/check! (vector? descriptors) :invalid-import "Export results must be a vector" {})
  (let [ids (mapv :id descriptors)]
    (value/check! (= (count ids) (count (set ids))) :invalid-import
                 "Export contains duplicate result IDs" {})
    (doseq [descriptor descriptors]
      (value/check! (map? descriptor) :invalid-import "Result descriptor must be a map" {})
      (value/check! (and (integer? (:id descriptor)) (pos? (:id descriptor))) :invalid-import
                   "Result ID must be positive" {:result-id (:id descriptor)})
      (value/check! (= source-sid (:session-id descriptor)) :invalid-import
                   "Result belongs to another exported session" {:result-id (:id descriptor)})
      (value/check! (contains? #{:inline :artifact :live} (:kind descriptor)) :invalid-import
                   "Result has an invalid kind" {:result-id (:id descriptor)})
      (value/check! (boolean? (:available? descriptor)) :invalid-import
                   "Result availability must be boolean" {:result-id (:id descriptor)})
      (value/check! (string? (or (:content descriptor) "")) :invalid-import
                   "Result content must be text" {:result-id (:id descriptor)})
      (value/check! (map? (or (:details descriptor) {})) :invalid-import
                   "Result details must be a map" {:result-id (:id descriptor)})
      (case (:kind descriptor)
        :inline
        (value/check! (contains? descriptor :value) :invalid-import
                     "Inline result is missing its durable value" {:result-id (:id descriptor)})

        :artifact
        (do
          (value/check! (contains? artifact-availability (:artifact-id descriptor)) :invalid-import
                       "Result references a missing artifact"
                       {:result-id (:id descriptor) :artifact-id (:artifact-id descriptor)})
          (value/check! (or (not (:available? descriptor))
                           (get artifact-availability (:artifact-id descriptor)))
                       :invalid-import "Available result references an unavailable artifact"
                       {:result-id (:id descriptor) :artifact-id (:artifact-id descriptor)}))

        :live
        (value/check! (false? (:available? descriptor)) :invalid-import
                     "Portable live results must be explicitly unavailable"
                     {:result-id (:id descriptor)}))
      (codec/encode descriptor))
    descriptors))


(defn- validate-import! [packet]
  (value/check! (map? packet) :invalid-import "Session export must be a map" {})
  (value/check! (= "arrodes-session" (:format packet)) :invalid-import
               "Unsupported session export format" {:format (:format packet)})
  (value/check! (contains? #{1 2} (:version packet)) :invalid-import
               "Unsupported session export version" {:version (:version packet)})
  (value/check! (vector? (:entries packet)) :invalid-import
               "Export entries must be a vector" {})
  (value/check! (contains? packet :base-config) :invalid-import
               "Export is missing its base configuration" {})
  (let [snapshot (:session packet)
        base-config (session-model/normalize-config (:base-config packet))
        entries (:entries packet)
        ids (mapv :id entries)
        id-set (set ids)
        by-id (into {} (map (juxt :id identity)) entries)]
    (value/check! (map? (:base-config packet)) :invalid-import
                 "Export base configuration must be a map" {})
    (value/check! (= (:base-config packet) base-config) :invalid-import
                 "Export base configuration is not normalized" {})
    (value/check! (map? snapshot) :invalid-import "Export session must be a map" {})
    (value/check! (and (string? (:name snapshot)) (not (str/blank? (:name snapshot))))
                 :invalid-import "Export session name is invalid" {})
    (value/check! (and (string? (:cwd snapshot)) (not (str/blank? (:cwd snapshot))))
                 :invalid-import "Export session cwd is invalid" {})
    (value/check! (= (:config snapshot)
                    (session-model/normalize-config (:config snapshot)))
                 :invalid-import "Export session configuration is not normalized" {})
    (value/check! (contains? records/statuses (:status snapshot)) :invalid-import
                 "Export session status is invalid" {:status (:status snapshot)})
    (value/check! (and (integer? (:revision snapshot)) (not (neg? (:revision snapshot))))
                 :invalid-import "Export session revision is invalid" {})
    (value/check! (map? (:metadata snapshot)) :invalid-import
                 "Export session metadata must be a map" {})
    (value/check! (vector? (:labels snapshot)) :invalid-import
                 "Export session labels must be a vector" {})
    (doseq [label (:labels snapshot)]
      (value/check! (and (map? label) (contains? id-set (:entry-id label)))
                   :invalid-import "Session label references a missing entry"
                   {:entry-id (:entry-id label)}))
    (codec/require-uuid! (:id snapshot) :session-id)
    (value/check! (= (count ids) (count id-set)) :invalid-import
                 "Export contains duplicate entry IDs" {})
    (value/check! (= (count entries) (count (set (map :seq entries)))) :invalid-import
                 "Export contains duplicate entry sequence numbers" {})
    (doseq [entry entries]
      (value/check! (map? entry) :invalid-import "Export entry must be a map" {})
      (codec/require-uuid! (:id entry) :entry-id)
      (value/check! (= (:id snapshot) (:session-id entry)) :invalid-import
                   "Entry belongs to another session" {:entry-id (:id entry)})
      (value/check! (contains? records/entry-kinds (:kind entry)) :invalid-import
                   "Export contains an invalid entry kind"
                   {:entry-id (:id entry) :kind (:kind entry)})
      (value/check! (or (nil? (:parent-id entry)) (contains? id-set (:parent-id entry)))
                   :invalid-import "Entry parent is missing"
                   {:entry-id (:id entry) :parent-id (:parent-id entry)})
      (value/check! (and (integer? (:seq entry)) (pos? (:seq entry))) :invalid-import
                   "Entry sequence must be a positive integer"
                   {:entry-id (:id entry) :seq (:seq entry)})
      (value/check! (and (integer? (:created-at entry)) (not (neg? (:created-at entry))))
                   :invalid-import "Entry creation time is invalid" {:entry-id (:id entry)})
      (when-let [parent (:parent-id entry)]
        (let [parent-seq (:seq (get by-id parent))]
          (value/check! (and (integer? parent-seq) (< parent-seq (:seq entry))) :invalid-import
                       "Entry parent must precede its child"
                       {:entry-id (:id entry) :parent-id parent})))
      (value/check! (map? (:data entry)) :invalid-import
                   "Entry data must be a map" {:entry-id (:id entry)})
      (case (:kind entry)
        :config
        (value/check! (= (:data entry)
                        (session-model/normalize-config (:data entry)))
                     :invalid-import "Configuration entry is not normalized"
                     {:entry-id (:id entry)})

        :compaction
        (let [kept (get-in entry [:data :first-kept-entry-id])
              ancestors (set (map :id (session-model/active-path
                                       entries (:parent-id entry))))]
          (when kept
            (value/check! (contains? ancestors kept) :invalid-import
                         "Compaction retained entry must be on its preceding active path"
                         {:entry-id (:id entry) :first-kept-entry-id kept}))
          (value/check! (string? (get-in entry [:data :summary])) :invalid-import
                       "Compaction summary must be text" {:entry-id (:id entry)})
          (value/check! (map? (or (get-in entry [:data :usage]) {})) :invalid-import
                       "Compaction usage must be a map" {:entry-id (:id entry)}))

        :branch-summary
        (do
          (value/check! (string? (get-in entry [:data :summary])) :invalid-import
                       "Branch summary must be text" {:entry-id (:id entry)})
          (when-let [from-id (get-in entry [:data :from-id])]
            (value/check! (contains? id-set from-id) :invalid-import
                         "Branch summary references a missing entry"
                         {:entry-id (:id entry) :from-id from-id})))

        :label
        (value/check! (contains? id-set (get-in entry [:data :entry-id])) :invalid-import
                     "Label references a missing entry" {:entry-id (:id entry)})

        nil))
    (when-let [head (:head snapshot)]
      (value/check! (contains? id-set head) :invalid-import
                   "Session head is missing" {:head head}))
    (doseq [entry entries]
      (session-model/active-path entries (:id entry)))
    (let [artifacts (validate-import-artifacts! (:artifacts packet) (:id snapshot))
          artifact-availability
          (into {} (map (fn [{:keys [descriptor]}]
                          [(:id descriptor) (:available? descriptor)])
                        artifacts))
          results (validate-import-results! (:results packet) (:id snapshot)
                                            artifact-availability)
          referenced (referenced-result-ids entries)
          present (set (map :id results))
          artifact-referenced (referenced-artifact-ids results)
          artifact-present (set (keys artifact-availability))]
      (value/check! (set/subset? referenced present) :invalid-import
                   "History references results missing from the export"
                   {:result-ids (vec (sort (set/difference referenced present)))})
      (value/check! (set/subset? artifact-referenced artifact-present) :invalid-import
                   "History or results reference artifacts missing from the export"
                   {:artifact-ids
                    (vec (sort (set/difference artifact-referenced artifact-present)))})
      (visit-retrievals
       entries results artifacts
       (fn [reference]
         (value/check! (= 2 (:version packet)) :invalid-import
                      "History retrieval metadata requires session export version 2" {})
         (validate-retrieval! reference (:id snapshot) entries id-set))
       (= 2 (:version packet)))
      {:snapshot snapshot :base-config base-config :entries entries
       :artifacts artifacts :results results})))


(defn- copy-graph! [^Connection connection source-row source-entries opts]
  (let [source-entries (vec (sort-by :seq source-entries))
        new-sid (or (:id opts) (util/id))
        _ (codec/require-uuid! new-sid :session-id)
        id-map (:entry-id-map opts)
        now (util/now)
        base-config (session-model/normalize-config (:arrodes.store/base-config source-row))
        copied-source-head (get id-map (:head source-row))
        raw-boundary (records/tool-boundary-entries
                      (session-model/active-path source-entries (:head source-row)))
        final-head (or (:id (last raw-boundary)) copied-source-head)
        snapshot (-> (session-model/new-snapshot
                      {:id new-sid
                       :name (or (:name opts) (:name source-row))
                       :cwd (or (:cwd opts) (:cwd source-row))
                       :config (session-model/normalize-config (:config source-row))
                       :status :idle
                       :created-at now
                       :metadata (dissoc (or (:metadata opts) (:metadata source-row))
                                         :agent/origin :context/view-node-ids)
                       :labels (remap-labels (:labels source-row) id-map)})
                     (assoc :head final-head))
        copied-source (mapv (fn [entry]
                              (-> entry
                                  (assoc :id (get id-map (:id entry))
                                         :session-id new-sid
                                         :parent-id (get id-map (:parent-id entry)))
                                  (remap-entry-refs id-map id-map)
                                  (remap-entry-retrievals (:id source-row) new-sid id-map)
                                  (remap-entry-result-refs (:imported-results opts))
                                  (remap-entry-artifact-refs (:imported-artifacts opts))))
                            source-entries)
        start-seq (inc (long (reduce max 0 (map :seq source-entries))))
        boundary
        (loop [remaining raw-boundary
               parent copied-source-head
               seq start-seq
               result []]
          (if-let [raw (first remaining)]
            (let [entry (assoc raw :session-id new-sid :parent-id parent
                               :seq seq :created-at now)]
              (recur (next remaining) (:id entry) (inc seq) (conj result entry)))
            result))
        copied (mapv (fn [entry] [entry (codec/encode (:data entry))])
                     (into copied-source boundary))]
    (value/check! (nil? (records/find-session connection new-sid)) :session-exists
                 "Session ID already exists" {:session-id new-sid})
    (records/insert-session! connection snapshot base-config)
    (doseq [[entry encoded-data] copied]
      (sql/execute-sql! connection
                    "INSERT INTO entries(id,session_id,parent_id,seq,kind,data,created_at) VALUES(?,?,?,?,?,?,?)"
                    [(:id entry) new-sid (:parent-id entry) (:seq entry) (name (:kind entry))
                     encoded-data (:created-at entry)]))
    snapshot))

(defn import-session!
  "Validates a complete export and imports history, results, and artifacts under fresh identities."
  [store packet opts]
  (let [{source :snapshot source-base-config :base-config source-entries :entries
         source-artifacts :artifacts source-results :results} (validate-import! packet)
        new-sid (or (:id opts) (util/id))
        _ (codec/require-uuid! new-sid :session-id)
        now (util/now)
        entry-id-map (into {} (map (fn [entry] [(:id entry) (util/id)]) source-entries))
        native-ids (native-artifact-ids source-results)
        transform #(remap-retrieval % (:id source) new-sid entry-id-map)
        artifact-id-map (into {} (map (fn [{:keys [descriptor]}]
                                        [(:id descriptor) (util/id)])
                                      source-artifacts))
        imported-artifacts
        (mapv (fn [{:keys [descriptor bytes]}]
                (let [remapped (remap-native-artifact descriptor bytes native-ids transform)]
                  {:descriptor
                   (cond-> (assoc (:descriptor remapped)
                                  :id (get artifact-id-map (:id descriptor))
                                  :session-id new-sid
                                  :available? (boolean bytes)
                                  :created-at now)
                     (:name descriptor) (assoc :name (:name descriptor)))
                   :bytes (:bytes remapped)}))
              source-artifacts)
        artifact-by-old-id (into {}
                                 (map (fn [source-record imported-record]
                                        [(get-in source-record [:descriptor :id])
                                         (:descriptor imported-record)])
                                      source-artifacts imported-artifacts))
        result-id-map (into {} (map-indexed (fn [index descriptor]
                                              [(:id descriptor) (inc index)])
                                            (sort-by :id source-results)))
        imported-results
        (into {}
              (map (fn [descriptor]
                     (let [kind (:kind descriptor)
                           artifact (when (= :artifact kind)
                                      (get artifact-by-old-id (:artifact-id descriptor)))
                           result (cond-> {:id (get result-id-map (:id descriptor))
                                          :session-id new-sid
                                          :kind kind
                                          :content (or (:content descriptor) "")
                                          :details (map-retrieval-markers
                                                    (remap-result-details
                                                     (or (:details descriptor) {}) artifact-by-old-id)
                                                    transform)
                                          :available? (case kind
                                                        :live false
                                                        :artifact (and (:available? descriptor)
                                                                       (:available? artifact))
                                                        (boolean (:available? descriptor)))}
                                    (= :inline kind)
                                    (assoc :value (map-retrieval-markers (:value descriptor) transform))
                                    (= :artifact kind) (assoc :artifact-id (:id artifact)))]
                       [(:id descriptor) result])))
              source-results)
        encoded-results (mapv (fn [descriptor] [descriptor (codec/encode descriptor)])
                              (sort-by :id (vals imported-results)))]
    (db/transact! store
      (fn [connection]
        (let [source-row (assoc source :arrodes.store/base-config source-base-config)
              imported-opts (merge {:id new-sid
                                    :name (:name source)
                                    :cwd (:cwd source)
                                    :metadata (assoc (merge (or (:metadata source) {})
                                                            (or (:metadata opts) {}))
                                                     :imported-from (:id source))
                                    :labels (:labels source)
                                    :entry-id-map entry-id-map
                                    :imported-results imported-results
                                    :imported-artifacts artifact-by-old-id}
                                   (select-keys opts [:name :cwd]))
              snapshot (copy-graph! connection source-row source-entries imported-opts)]
          (doseq [{:keys [descriptor bytes]} imported-artifacts]
            (when (and bytes (not (:memory? store)))
              (files/write-content-addressed! store (:sha256 descriptor) bytes))
            (sql/execute-sql! connection
                          "INSERT INTO artifacts(id,session_id,sha256,bytes,kind,available,created_at,name,content) VALUES(?,?,?,?,?,?,?,?,?)"
                          [(:id descriptor) new-sid (:sha256 descriptor) (:bytes descriptor)
                           (name (:kind descriptor)) (if (:available? descriptor) 1 0)
                           (:created-at descriptor) (:name descriptor)
                           (when (:memory? store) bytes)]))
          (doseq [[descriptor encoded] encoded-results]
            (sql/execute-sql! connection
                          "INSERT INTO results(session_id,id,kind,descriptor,created_at) VALUES(?,?,?,?,?)"
                          [new-sid (:id descriptor) (name (:kind descriptor))
                           encoded now]))
          snapshot)))))
