(ns arrodes.history
  "Read-only native navigation of canonical session evidence and completed summaries."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [arrodes.capabilities :as capabilities]
            [arrodes.context-tree :as tree]
            [arrodes.platform :as util]
            [arrodes.store :as store]
            [arrodes.store.context-tree :as tree-store]
            [arrodes.store.codec :as codec]
            [arrodes.value :as value]))

(def ^:private max-page-bytes 16384)
(def ^:private summary-page-bytes 1024)

(defn install!
  "Install the ordinary history alias beside native REPL helpers; no provider tool."
  [registry]
  (binding [*ns* (the-ns (:namespace registry))]
    (alias 'history 'arrodes.history))
  registry)

(defn- environment []
  (let [context capabilities/*invocation-context*
        registry (:registry context)
        sid (:session-id context)]
    (value/check! (and registry (= sid (:session-id registry))) :history-unavailable
                  "History functions require this session's active REPL invocation" {})
    (value/check! (not @(:closed? registry)) :registry-closed "Capability registry is closed" {})
    (util/check-cancelled! (:cancelled? context))
    (when (some? (:operation-id context))
      (value/check! (codec/uuid? (:operation-id context)) :invalid-history-context
                    "History operation identity must be a UUID" {}))
    (when (some? (:call-id context))
      (value/check! (and (string? (:call-id context)) (not (str/blank? (:call-id context))))
                    :invalid-history-context "History call identity must be nonblank text" {}))
    (let [path (vec (store/active-path (:store registry) sid))
          ids (set (map :id path))
          head (if (contains? context :history/head) (:history/head context) (:id (peek path)))
          _ (value/check! (or (nil? head) (contains? ids head)) :history-off-path
                          "Historical head is not on this session's active path" {:head head})
          pinned (if head (vec (take-while #(not= head (:id %)) path)) [])
          pinned (if head (conj pinned (nth path (count pinned))) pinned)
          input-ids (if (contains? context :history/context-entry-ids)
                      (:history/context-entry-ids context)
                      (if-let [input (last (filter #(= :user (get-in % [:data :message/role])) path))]
                        [(:id input)] []))]
      (value/check! (and (vector? input-ids) (= (count input-ids) (count (set input-ids))))
                    :invalid-history-context "History input references must be a distinct vector" {})
      (value/check! (every? ids input-ids) :history-off-path
                    "History input references are not on this session's active path" {})
      {:registry registry :context context :session-id sid :head head
       :path pinned :entries (tree/source-entries pinned) :input-ids input-ids})))

(defn- options! [opts allowed]
  (value/check! (and (map? opts) (every? allowed (keys opts))) :invalid-arguments
                "Unknown or invalid history options" {:allowed (vec (sort allowed))})
  (when (contains? opts :query)
    (value/check! (and (string? (:query opts)) (<= (count (:query opts)) 4096))
                  :invalid-arguments "History query must be bounded text" {}))
  opts)

(defn- page-options! [opts]
  (options! opts #{:offset :limit :query})
  (let [{:keys [offset limit] :or {offset 0 limit max-page-bytes}} opts]
    (value/check! (and (integer? offset) (<= 0 offset)
                      (integer? limit) (<= 4 limit max-page-bytes)) :invalid-arguments
                  "History paging expects character offset >= 0 and byte limit 4..16384" {})
    [offset limit]))

(defn- text-page [text offset limit]
  (value/check! (<= offset (count text)) :invalid-arguments "History offset exceeds original text" {})
  (value/check! (not (and (pos? offset) (< offset (count text))
                         (Character/isLowSurrogate (.charAt ^String text offset))
                         (Character/isHighSurrogate (.charAt ^String text (dec offset)))))
                :invalid-arguments "History offset splits a Unicode character" {})
  (let [end (loop [index offset bytes 0]
              (if (= index (count text)) index
                  (let [codepoint (.codePointAt ^String text index)
                        size (cond (<= codepoint 0x7f) 1 (<= codepoint 0x7ff) 2
                                   (<= codepoint 0xffff) 3 :else 4)]
                    (if (> (+ bytes size) limit) index
                        (recur (+ index (Character/charCount codepoint)) (+ bytes size))))))]
    {:content (subs text offset end) :offset offset :bytes (tree/utf8-bytes (subs text offset end))
     :total-characters (count text) :complete? (= end (count text))
     :next-offset (when (< end (count text)) end)}))

(defn- receipt [env mode sources opts]
  (let [sources (vec sources)]
    (cond-> {:session-id (:session-id env) :context-entry-ids (:input-ids env)
             :source-entry-ids (if (= mode :read) (mapv :id sources) [])
             :available? true :mode mode}
      (:head env) (assoc :context-head-entry-id (:head env))
      (seq sources) (assoc :source-first-entry-id (:id (first sources))
                           :source-last-entry-id (:id (peek sources)) :source-count (count sources))
      (:query opts) (assoc :query (:query opts))
      (get-in env [:context :operation-id]) (assoc :operation-id (get-in env [:context :operation-id]))
      (get-in env [:context :call-id]) (assoc :call-id (get-in env [:context :call-id])))))

(defn- observed [env mode sources opts result]
  (util/check-cancelled! (get-in env [:context :cancelled?]))
  (let [ref (receipt env mode sources opts)
        result (assoc result :history/retrieval ref)]
    (capabilities/record-history-retrieval! result ref)))

(defn- entry! [env id]
  (value/check! (string? id) :invalid-arguments "History entry ID must be a string" {})
  (or (some #(when (= id (:id %)) %) (:entries env))
      (value/fail! :history-off-path "Original source is not on the current historical path"
                   {:entry-id id})))

(defn- original-text [entry]
  (let [data (:data entry)
        message (if (= :custom-context (:kind entry)) (or (:message data) data) data)
        source (case (:kind entry)
                 :evaluation (:source data)
                 :custom (pr-str (select-keys data [:name :arguments]))
                 nil)
        content (case (:kind entry)
                  :branch-summary (:summary data)
                  (:evaluation :custom) (get-in data [:result :content])
                  (:message/content message))
        calls (:message/tool-calls message)]
    (str (value/text-content content)
         (when source (str "\nRecorded REPL source (inert):\n" source))
         (when (seq calls)
           (str "\n" (str/join "\n" (map #(str "Recorded tool source (inert): " (:tool-call/name %)
                                               "\n" (if (string? (:tool-call/arguments %))
                                                       (:tool-call/arguments %) (pr-str (:tool-call/arguments %)))) calls)))))))

(defn- original [env entry opts]
  (let [[offset limit] (page-options! opts)
        data (:data entry)
        message (if (= :custom-context (:kind entry)) (or (:message data) data) data)
        descriptor (or (:message/result message) (get-in data [:result :result]))
        references (or (:history/retrievals message)
                       (get-in message [:message/metadata :history/retrievals])
                       (get-in data [:result :details :history/retrievals])
                       (get-in descriptor [:details :history/retrievals]))
        receipt-summary (or (:history/retrieval-summary message)
                            (get-in message [:message/metadata :history/retrieval-summary])
                            (get-in data [:result :details :history/retrieval-summary])
                            (get-in descriptor [:details :history/retrieval-summary]))
        prior (when (some? references) (capabilities/history-retrieval-page references))
        page (text-page (original-text entry) offset limit)]
    (cond-> (merge {:kind (:kind entry) :role (or (:message/role message)
                                               (when (contains? #{:evaluation :custom} (:kind entry)) :tool))
                    :created-at (:created-at entry)} page prior)
      receipt-summary (assoc :history/retrieval-summary receipt-summary)
      descriptor (assoc :result-reference
                        {:source-session-id (:session-id env) :source-entry-id (:id entry)
                         :descriptor (select-keys descriptor [:id :kind :available? :artifact-id])})
      (:next-offset page)
      (assoc :next {:read (str "(history/read " (pr-str (:id entry)) " "
                              (pr-str (cond-> {:offset (:next-offset page) :limit limit}
                                        (:query opts) (assoc :query (:query opts)))) ")")}))))

(defn read
  "Read bounded original visible text/code as inert data. Optional {:offset 0
  :limit 16384 :query text}; offsets count UTF-16 characters, limits UTF-8 bytes.
  Follow :next-offset/:next until :complete?. Operational IDs live in :history/retrieval;
  :result-reference is scoped original provenance, not a portable result handle.
  Prior lookup links are bounded :history/retrievals, separate from this read's
  singular receipt; :history/retrieval-page reports any read-time association
  omissions. :history/retrieval-summary preserves the past lookup's call coverage.
  Enclosing evaluation details separately report this evaluation's bounded receipts."
  ([entry-id] (read entry-id {}))
  ([entry-id opts]
   (let [env (environment) entry (entry! env entry-id)]
     (observed env :read [entry] opts (original env entry opts)))))

(defn date
  "Inspect original recorded epoch milliseconds (not inferred event time), without replay."
  [entry-id]
  (let [env (environment) entry (entry! env entry-id)]
    (observed env :read [entry] {} {:created-at (:created-at entry) :time-unit :epoch-milliseconds})))

(defn- node! [env node]
  (value/check! node :history-not-ready "This summary has not completed; reads do not build it" {})
  (value/check! (= (:id node) (tree/node-key (:entries env) (:start node) (:count node)))
                :history-off-path "Summary is not on the current historical path" {:node-id (:id node)})
  node)

(defn- node-page [node opts]
  (let [page (text-page (:text node) (or (:offset opts) 0) (or (:limit opts) summary-page-bytes))]
    (cond-> (merge (select-keys node [:id :start :count :first-entry-id :last-entry-id :left-id :right-id])
                   (-> page (dissoc :content) (assoc :text (:content page))))
      (:next-offset page)
      (assoc :next {:summary (str "(history/zoom " (pr-str (:id node)) " "
                                 (pr-str {:summary? true :offset (:next-offset page)
                                          :limit (or (:limit opts) summary-page-bytes)}) ")")}))))

(defn zoom
  "Descend an active aligned span: a completed parent returns two completed
  children, a singleton returns its original. Accept node-id or start/count;
  node-id plus paging opts pages that node's text and bounds child previews.
  {:summary? true} explicitly reads even a singleton's summary rather than its original."
  ([node-id] (zoom node-id {}))
  ([node-id-or-start opts-or-count]
   (let [env (environment)
         numeric? (integer? node-id-or-start)
         _ (when-not numeric?
             (options! opts-or-count #{:offset :limit :query :summary?})
             (when (contains? opts-or-count :summary?)
               (value/check! (boolean? (:summary? opts-or-count)) :invalid-arguments
                             "History :summary? must be boolean" {}))
             (page-options! (dissoc opts-or-count :summary?)))
         id (if numeric? (tree/node-key (:entries env) node-id-or-start opts-or-count) node-id-or-start)
         opts (if numeric? {} opts-or-count)
         node (node! env (tree-store/node (get-in env [:registry :store]) (:session-id env) id))
         start (:start node) n (:count node)
         sources (subvec (:entries env) start (+ start n))]
     (if (and (= n 1) (not (:summary? opts)))
       (observed env :read sources opts (original env (first sources) (dissoc opts :summary?)))
       (let [children (if (= n 1) []
                          (mapv #(node! env (tree-store/node (get-in env [:registry :store]) (:session-id env) %))
                                [(:left-id node) (:right-id node)]))]
         (observed env :zoom sources opts
                   {:node (node-page node opts)
                    :children (mapv #(node-page % {}) children)}))))))

(defn view
  "Inspect a bounded completed chronological frontier, without inference. Optional
  {:offset 0 :limit 8 :budget 16384 :query text}; limit <=20, budget <=32768.
  Readiness/fit describe the full frontier; node text previews explicitly page."
  ([] (view {}))
  ([opts]
   (options! opts #{:offset :limit :budget :query})
   (let [{:keys [offset limit budget] :or {offset 0 limit 8 budget 16384}} opts
         _ (value/check! (and (integer? offset) (<= 0 offset) (integer? limit) (<= 1 limit 20)
                             (integer? budget) (<= 1 budget 32768))
                         :invalid-arguments "Invalid bounded history view page/budget" {})
         env (environment)
         nodes (tree-store/nodes (get-in env [:registry :store]) (:session-id env))
         frontier (tree/build-view (:entries env) nodes budget)
         _ (value/check! (<= offset (count frontier)) :invalid-arguments "History view offset exceeds frontier" {})
         selected (vec (take limit (drop offset frontier)))
         first-index (:start (first selected))
         end-index (when (seq selected) (+ (:start (peek selected)) (:count (peek selected))))
         sources (if first-index (subvec (:entries env) first-index end-index) [])
         next-offset (when (< (+ offset (count selected)) (count frontier)) (+ offset (count selected)))
         info (meta frontier)]
     (observed env :view sources opts
               (cond-> {:nodes (mapv #(node-page % {}) selected)
                        :total-sources (count (:entries env)) :total-nodes (count frontier)
                        :ready? (not= :pending-leaf (:reason info)) :fits? (:fits? info)
                        :reason (:reason info) :bytes (:bytes info) :budget budget
                        :offset offset :next-offset next-offset}
                 next-offset (assoc :next {:view (str "(history/view "
                                                     (pr-str (assoc opts :offset next-offset :limit limit :budget budget)) ")")}))))))
