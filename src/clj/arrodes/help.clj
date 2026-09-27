(ns arrodes.help
  "Bounded discovery of the functions callable in a session REPL."
  (:require [clojure.string :as str]
            [arrodes.value :as value]))

(def ^:private page-size 8)
(def ^:private max-page-size 20)

;; Only public session-facing entry points belong here. The Vars themselves supply
;; the live docstrings and arities; contracts supplement native return semantics.
(def ^:private native-contracts
  {"help" {:arities ["[]" "[{:workflow \"background\"|\"delegation\"|\"failure\"|\"results\"}]" "[{:group group :query query :offset offset :limit limit}]" "[name]" "[name {:detailed? true}]"]
           :returns "Bounded group discovery or an on-demand workflow recipe; named lookups return one Clojure function contract."
           :examples ["(help {:workflow \"background\"})" "(help 'agents/start!)"]}
   "workspace" {:arities ["[]" "[{:query query :offset offset :limit limit}]"]
                :returns "{:namespace ... :generation ... :bindings [...] :total-bindings n :next-offset n/nil}."
                :examples ["(workspace {:query \"notes\" :limit 8})"]}
   "result" {:arities ["[id]"] :returns "The retained native value (live when available, otherwise reconstructed)."
             :examples ["(result 42)"]}
   "result-info" {:arities ["[id]"] :returns "Retained descriptor and inert :next source strings; does not load values or acknowledge job delivery."
                  :examples ["(result-info 42)"]}
   "results" {:arities ["[]" "[{:limit limit :before-id id}]"]
              :returns "Newest-first retained reference page with :next-before-id."
              :examples ["(results {:limit 8})"]}
   "artifact" {:arities ["[id]"] :returns "Text or bytes from an owned retained artifact; large content requires artifact-page."
               :examples ["(artifact \"id\")"]}
   "artifact-page" {:arities ["[id]" "[id {:offset offset :limit limit}]" "[id {:after cursor :limit limit}]"]
                    :returns "Bounded content page with reusable resource/session-scoped :cursor (nil at EOF); :next-offset supports random access."
                    :examples ["(def page (artifact-page \"id\" {:limit 4096}))" "(artifact-page \"id\" {:after (:cursor page) :limit 4096})"]}
   "invoke-tool" {:arities ["[name arguments]"] :returns "Native registered function result, through validation and hooks."
                  :examples ["(invoke-tool \"grep\" {:path \"src\" :pattern \"needle\"})"]}
   "register-tool!" {:arities ["[var descriptor]"] :returns "Registration acknowledgement for a Var; ordinary functions need no registration."
                     :examples ["(register-tool! #'my-function {:name \"my-function\"})"]}
   "jobs/start!" {:returns "Session-owned job handle immediately; the zero-argument function runs in managed background work."
                  :examples ["(def check-job (jobs/start! {:name \"Check\"} #(do (prn \"checked\") {:ok true})))"]}
   "jobs/inspect" {:returns "Compact status with inert :next source strings for output, result inspection, successful value-and-ack, or waiting/cancellation. {:detailed? true} includes diagnostics."
                   :examples ["(jobs/inspect j)" "(jobs/inspect j {:detailed? true})"]}
   "jobs/list" {:returns "Newest-first compact job statuses; :limit/:before page, :detailed? opts into full records."
                :examples ["(jobs/list {:limit 8})"]}
   "jobs/output" {:returns "Bounded output page; :cursor is job-scoped and reusable; use :after for sequential reading."
                  :examples ["(def page (jobs/output check-job {:limit 4096}))" "(jobs/output check-job {:after (:cursor page) :limit 4096})"]}
   "jobs/wait" {:returns "Compact status after up to :timeout-ms; :detailed? opts into full record."
                :examples ["(jobs/wait j {:timeout-ms 1000})"]}
   "jobs/result" {:returns "Successful job's native value without blocking, acknowledging its outcome delivery; otherwise throws. Generic result only retrieves the retained value."
                  :examples ["(jobs/result j)"]}
   "jobs/cancel!" {:returns "Compact status after requesting cancellation of job and owned children."
                   :examples ["(jobs/cancel! j)"]}
   "agents/start!" {:returns "Session/operation handle for an independent persistent REPL; :task required, :name/:context/:config/:submission-id optional."
                    :examples ["(def a (agents/start! {:name \"Scout\" :task \"Inspect parser\"}))"]}
   "agents/submission" {:arities ["[id]"]
                        :returns "Original accepted submission/receipt for a caller-owned ID, or nil; does not resend."
                        :examples ["(agents/submission submission-id)"]}
   "agents/send!" {:returns "Durable acceptance receipt, not proof of delivery; target handle/name/:parent/:all, optional :submission-id/:wake?."
                   :examples ["(def receipt (agents/send! a \"Check boundary\"))"]}
   "agents/delivery" {:returns "Paged receipt delivery states with exact incorporating operations and inert :next guidance; rows link to their operation result. :limit/:offset page. Inspection does not consume delivery."
                      :examples ["(agents/delivery receipt {:limit 8})"]}
   "agents/inspect" {:returns "Compact agent status and inert :next links pinned to the observed operation; {:detailed? true} returns full diagnostics without consuming deliveries."
                     :examples ["(agents/inspect a)" "(agents/inspect a {:detailed? true})"]}
   "agents/list" {:returns "Paged compact team roster including root; {:limit 8 :offset 0}, :detailed? for full records."
                  :examples ["(agents/list {:limit 8})"]}
   "agents/messages" {:returns "Paged sent/received messages and delivery states, without consuming; :detailed? for full content."
                      :examples ["(agents/messages {:limit 8})"]}
   "agents/wait" {:returns "{:reason ... :ready [...]}; :handles OR :receipts, :until :delivered/:completed (default completed), :timeout-ms. Peer input/steering can wake it."
                  :examples ["(agents/wait {:receipts [receipt] :until :delivered :timeout-ms 30000})"]}
   "agents/result" {:returns "Compact outcome and inert :next guidance for a particular operation, never the latest arbitrary reply; :detailed? for full native outcome."
                    :examples ["(agents/result a)" "(agents/result a {:detailed? true})"]}
   "agents/value" {:returns "Another team member's portable retained native value; never a live JVM object."
                   :examples ["(agents/value a 42)"]}
   "agents/cancel!" {:returns "Cancel this operation or current operation; independent jobs/children continue."
                     :examples ["(agents/cancel! a)"]}
   "agents/stop!" {:returns "Stop a subtree and its jobs; may return :stopping while cleanup completes."
                   :examples ["(agents/stop! a {:timeout-ms 10000})"]}
   "agents/resume!" {:arities ["[target]"]
                     :returns "Resume pending eligible input for an explicitly stopped/paused agent."
                     :examples ["(agents/resume! a)"]}})

(def ^:private workflows
  {"background"
   {:purpose "Start a function job; inspect, wait, read output, then retrieve success or failure."
    :requires #{"jobs/start!" "jobs/inspect" "jobs/wait" "jobs/output" "jobs/result" "jobs/cancel!"}
    :steps ["Evaluate separately; each REPL call returns only its last form. Bind work under a descriptive name: (def check-job \"Managed project check.\" (jobs/start! {:name \"Check\"} #(do (prn \"checked\") {:ok true})))"
            "Inspect without blocking: (jobs/inspect check-job). Its :next entries are inert source strings with the actual handle; copy only the action you intend. (jobs/wait check-job {:timeout-ms 1000}) returns a status; timeout does not cancel work."
            "Read bounded output: (def output-page (jobs/output check-job {:limit 4096})); then (def output-page (jobs/output check-job {:after (:cursor output-page) :limit 4096})). :more? false means caught up for now, not finished; :eof? means the job is terminal and output is drained. Keep the cursor for later, rather than polling. The same API reads retained output after settlement."
            "After a completed successful status, (jobs/result check-job) returns and acknowledges its native value. Failure/cancellation: inspect the status and output rather than calling jobs/result; (jobs/cancel! check-job) requests cancellation, not rollback. Return a final map, e.g. {:status (jobs/inspect check-job) :output output-page}, rather than expecting intermediate forms to print."]}
   "delegation"
   {:purpose "Start an independent child session; reconcile submissions, delivery, wait and results."
    :requires #{"agents/start!" "agents/submission" "agents/send!" "agents/delivery" "agents/wait" "agents/result" "agents/inspect" "agents/cancel!" "agents/stop!"}
    :steps ["Evaluate separately; before each spawn/send, bind a caller-owned UUID: (def launch-id (str (java.util.UUID/randomUUID))); then (def child (agents/start! {:name \"Parser\" :task \"Inspect parser boundaries\" :submission-id launch-id})). Child REPL bindings are separate from yours."
            "If a spawn response is unknown, (agents/submission launch-id) inspects acceptance; (agents/inspect child) inspects a known child. Do not launch again under a new ID or assume an unanswered call had no effect."
            "Before messaging, (def send-id (str (java.util.UUID/randomUUID))); then (def receipt (agents/send! child \"Check the fallback branch\" {:submission-id send-id})). If uncertain, (agents/submission send-id) recovers the original receipt; acceptance is not delivery. Peer sends are for blockers/interim findings; child final answers automatically reach the parent, so do not send a duplicate final report."
            "Inspect delivery: (agents/delivery receipt). Managed wait: (def wake (agents/wait {:receipts [receipt] :until :completed :timeout-ms 30000})). If :reason is :message or :steering, return to the model boundary for input, not a polling loop; :timeout does not cancel the child. Pending/superseded deliveries provide no fabricated completed result."
            "Select one operation from :ready, e.g. (def ready (first (:ready wake))); when ready exists, (agents/result ready). Delivery rows also offer :next :result for their exact operation. The launch handle still selects the original operation, not a follow-up. Return a final map such as {:wake wake :delivery (agents/delivery receipt)}."
            "To cancel a particular execution, (agents/cancel! child) targets that handle's operation; independent jobs/children continue. (agents/stop! child) stops the session subtree and its jobs; :stopping means cleanup is incomplete. Neither action rolls back effects."]}
   "failure"
   {:purpose "Inspect partial evaluation failure without assuming rollback or safe replay."
    :requires #{"result-info" "results" "workspace"}
    :steps ["A failing REPL evaluation can retain a result ID. Inspect that ID without loading its value: (result-info id); *e is only the latest live exception."
            "Failure details include phase, failing form index and completed top-level forms. Completed forms are not an effect count: the failing form can perform effects even if zero forms completed. Earlier bindings, writes and external effects remain; there is no rollback or complete effect log."
            "Inspect (results {:limit 8}) for retained calls and (result-info id) for available receipts/output references; these are observations, not a complete effect log. Inspect relevant files or remote state before another mutation. Known agent submission IDs can be reconciled with agents/submission; do not generate a new ID to retry an uncertain send/spawn."
            "Return a small final map of observed evidence, e.g. {:failure (result-info id) :bindings (workspace)}. Do not rerun an entire failed batch. *e and workspace describe only this live evaluator; retained failure details survive reload."]}
   "results"
   {:purpose "Inspect retained references, live/unavailable values and bounded artifacts."
    :requires #{"results" "result-info" "result" "artifact-page" "workspace"}
    :steps ["The REPL returns only the last form's value; bind named values using (def sample \"Working sample.\" {:answer 42}) and return a final map, e.g. {:sample sample}. (workspace {:query \"sample\"}) inspects binding labels/docs and bounded sizes without realizing lazy sequences or dereferencing atoms."
            "Find retained references with (def retained-page (results {:limit 8})); page with (results {:before-id (:next-before-id retained-page) :limit 8}) while :next-before-id exists. (result-info id) gives a descriptor without loading the value; (result id) retrieves the session-local native value when available. Live-only values can become unavailable after restart/reload."
            "For artifact-backed content, (def page (artifact-page artifact-id {:limit 4096})); then (artifact-page artifact-id {:after (:cursor page) :limit 4096}) while :cursor exists. That cursor is reusable and scoped to this session/artifact; :offset remains for random access. Never load huge content merely to list or inspect it."]}})

(defn- available-workflows [entries]
  (let [installed (set (map :name (remove :symbol entries)))]
    (->> workflows
         (keep (fn [[name {:keys [purpose requires]}]]
                 (when (every? installed requires)
                   {:workflow name :purpose purpose})))
         (sort-by :workflow)
         vec)))

(defn- clipped [x limit]
  (let [s (str x)]
    (if (and (<= (count s) limit) (<= (count (pr-str s)) limit))
      s
      (loop [n (min (count s) (- limit 14))]
        (let [preview (str (subs s 0 n) " [omitted]")]
          (if (or (zero? n) (<= (count (pr-str preview)) limit))
            preview
            (recur (dec n))))))))

(defn- native-entry [session-ns name]
  (let [sym (symbol name)
        v (ns-resolve (the-ns session-ns) sym)]
    (when (and v (if (namespace sym)
                   (= (str (ns-name (:ns (meta v)))) (str "arrodes." (namespace sym)))
                   (:arrodes/helper (meta v))))
      (let [{:keys [doc arglists]} (meta v)]
        {:name name :description (or doc "") :arities (or (:arities (native-contracts name))
                                                        (mapv pr-str arglists))
         :returns (:returns (native-contracts name))
         :examples (:examples (native-contracts name))}))))

(defn- entries [namespace selected-tools]
  (let [native (keep #(native-entry namespace %) (keys native-contracts))
        registered (map (fn [entry] (assoc entry :registered-name (:name entry) :name (:symbol entry))) (selected-tools))]
    (->> (concat native registered)
         (sort-by :name)
         vec)))

(defn- group-of [name]
  (if-let [prefix (namespace (symbol name))] prefix
      (if (contains? #{"help" "workspace" "result" "result-info" "results"
                       "artifact" "artifact-page" "invoke-tool" "register-tool!"} name)
        "repl" "coding")))

(defn- params-summary [parameters]
  (let [props (or (:properties parameters) (get parameters "properties") {})
        required (set (map name (or (:required parameters) (get parameters "required") [])))]
    {:arguments (mapv (fn [[k schema]]
                        {:name (clipped (name k) 70)
                         :required? (contains? required (name k))
                         :type (clipped (or (:type schema) (get schema "type") "any") 40)
                         :description (clipped (or (:description schema) (get schema "description") "") 100)})
                      (take 8 props))
     :omitted-arguments (max 0 (- (count props) 8))}))

(defn- named-contract [entry detailed?]
  (if detailed?
    (if (:symbol entry)
      (select-keys entry [:name :registered-name :symbol :description :parameters :returns :examples
                          :execution :permission :owner])
      entry)
    (let [registered? (contains? entry :symbol)]
      (cond-> {:name (clipped (:name entry) 120)
               :description (clipped (:description entry) 500)
               :arities (if registered? ["[arguments]"] (mapv #(clipped % 100) (:arities entry)))
               :returns (clipped (if registered? (or (get-in entry [:returns :description]) "Not documented")
                                     (:returns entry)) 450)
               :examples (mapv #(clipped (if (map? %) (:source %) %) 200)
                               (take 2 (:examples entry)))}
        registered? (assoc :parameters (params-summary (:parameters entry)))
        (> (count (:examples entry)) 2) (assoc :omitted-examples (- (count (:examples entry)) 2))))))

(defn- page-options! [opts]
  (value/check! (and (map? opts) (every? #{:group :query :offset :limit} (keys opts))
                     (or (nil? (:group opts)) (string? (:group opts)))
                     (or (nil? (:query opts)) (string? (:query opts)))
                     (integer? (get opts :offset 0)) (<= 0 (get opts :offset 0))
                     (integer? (get opts :limit page-size))
                     (<= 1 (get opts :limit page-size) max-page-size))
                :invalid-arguments "help expects {:group string :query string :offset nonnegative :limit 1..20}" {})
  opts)

(defn help
  "Discover only functions installed in this session, under its current tool selection."
  [namespace selected-tools & args]
  (value/check! (<= (count args) 2) :invalid-arguments "help accepts at most two arguments" {})
  (let [[selection opts] args
        named? (or (string? selection) (symbol? selection))
        _ (when named?
            (value/check! (and (or (nil? opts) (= {:detailed? true} opts))
                               (not (str/blank? (str selection))))
                          :invalid-arguments "Named help accepts only {:detailed? true}" {}))
        _ (when (and (not named?) (some? opts))
            (value/fail! :invalid-arguments "Use a function name before {:detailed? true}" {}))
        entries (entries namespace selected-tools)
        available (available-workflows entries)]
    (if named?
      (if-let [entry (some #(when (= (str selection) (:name %)) %) entries)]
        (named-contract entry (boolean (:detailed? opts)))
        (value/fail! :unknown-tool "No installed function with this name" {:name (str selection)}))
      (if (and (map? selection) (contains? selection :workflow))
        (do
          (value/check! (and (= #{:workflow} (set (keys selection)))
                             (string? (:workflow selection))
                             (not (str/blank? (:workflow selection))))
                        :invalid-arguments "Workflow help expects only {:workflow nonblank-string}" {})
          (let [name (:workflow selection)]
            (value/check! (contains? workflows name) :unknown-workflow
                          "Unknown workflow; use (help) to list available workflows" {:workflow name})
            (value/check! (some #(= name (:workflow %)) available) :unavailable-workflow
                          "Workflow requires functions not installed in this session" {:workflow name})
            {:workflow name :purpose (get-in workflows [name :purpose])
             :steps (get-in workflows [name :steps])}))
        (let [options (page-options! (or selection {}))
              group (:group options)
              query (:query options)]
          (if (nil? group)
            (let [groups (sort-by key (group-by (comp group-of :name) entries))]
              {:groups (mapv (fn [[name members]] {:group (clipped name 80) :count (count members)})
                             (take 8 groups))
               :omitted-groups (max 0 (- (count groups) 8))
               :total (count entries)
               :workflows available
               :usage "(help {:workflow \"background\"}) gives a recipe; (help {:group \"agents\" :limit 8}) pages names; (help 'agents/start!) inspects one contract."})
            (let [matching (->> entries
                                (filter #(and (= group (group-of (:name %)))
                                              (or (nil? query) (str/includes? (str/lower-case (:name %))
                                                                              (str/lower-case query)))))
                                vec)
                  offset (:offset options 0) limit (:limit options page-size)]
              {:group (clipped group 80)
               :entries (mapv (fn [entry] {:name (clipped (:name entry) 80)
                                           :description (clipped (:description entry) 160)})
                              (take limit (drop offset matching)))
               :total (count matching)
               :next-offset (when (< (+ offset limit) (count matching)) (+ offset limit))})))))))
