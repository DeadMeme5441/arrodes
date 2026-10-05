(ns arrodes.runtime.preparation
  "Input hooks, evaluator-generation context, intent delivery, and initial naming."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [arrodes.capabilities :as capabilities]
            [arrodes.context-tree :as context-tree]
            [arrodes.provider :as provider]
            [arrodes.run :as run]
            [arrodes.runtime.control :as control]
            [arrodes.runtime.handles :as handles]
            [arrodes.store :as store]
            [arrodes.store.command :as command]
            [arrodes.store.db :as db]
            [arrodes.store.sql :as sql]
            [arrodes.titles :as titles]
            [arrodes.resources :as resources]
            [arrodes.platform :as util]
            [arrodes.value :as value]))

(s/def ::registry map?)
(s/def ::manager map?)
(s/def ::config map?)
(s/def ::hook-context map?)
(s/def ::initial-intents vector?)
(s/def ::prepared-run
  (s/keys :req [::registry ::manager ::config ::hook-context ::initial-intents]))

(defn validate!
  "Checks only the prepared envelope, never native evaluator values."
  [prepared]
  (when-not (s/valid? ::prepared-run prepared)
    (value/fail! :invalid-run-context "Invalid prepared run context"
                 (s/explain-data ::prepared-run prepared)))
  prepared)

(defn provider-config [runtime sid config]
  (let [manager (:provider (handles/handle! runtime sid))]
    (if (or (context-tree/enabled? config)
            (provider/model manager (:provider config) (:model config)))
      config
      (if (true? (get-in config [:settings :fallback-model?]))
        (if-let [fallback (first (filter #(= (:provider config) (:provider %))
                                         (provider/catalog manager)))]
          (assoc config :model (:id fallback))
          config)
        config))))

(defn- initial-title [runtime sid entries config]
  (when-not (= false (get-in config [:settings :auto-title?]))
    (when-let [message (some #(when (= :user (get-in % [:data :message/role])) (:data %)) entries)]
      (let [snapshot (store/session (:store runtime) sid)
            source (get-in snapshot [:metadata :title/source])]
        (when (and (= :default source)
                   (not-any? #(= :user (get-in % [:data :message/role])) (store/entries (:store runtime) sid)))
          (let [content (:message/content message)
                text (value/text-content content)
                generation (util/id)]
            {:name (or (run/suggested-session-name content) "Attachment discussion")
             :generation generation
             :text (subs text 0 (min 8000 (count text)))
             :metadata (assoc (:metadata snapshot) :title/source :auto :title/generation generation)}))))))

(defn- start-title! [runtime sid config {:keys [generation text]}]
  (when-not (str/blank? text)
    (titles/start! (:titles runtime) (:provider (handles/handle! runtime sid)) sid config text
      (fn [name details]
        (locking (control/session-lock runtime sid)
          (when (= :open @(:lifecycle runtime))
            (let [snapshot (store/session (:store runtime) sid)]
              (when (and (= :auto (get-in snapshot [:metadata :title/source]))
                         (= generation (get-in snapshot [:metadata :title/generation])))
                (control/commit! runtime sid
                         {::command/session {:name name :metadata (assoc (:metadata snapshot) :title/model details)}
                          ::command/events [{:type :session/named :data {:name name :source :auto}}]})))))))))

(defn prepare-run! [runtime sid slot prompt overrides]
  (let [registry (:registry (handles/handle! runtime sid))
        manager (:resources (handles/handle! runtime sid))
        initial (locking (control/session-lock runtime sid)
                  {:session (store/session (:store runtime) sid)
                   :setting-revisions (get (some-> (:context-setting-revisions runtime) deref) sid {})})
        config (provider-config runtime sid
                                (run/effective-config
                                 (:session initial) overrides))
        context {:runtime runtime :session-id sid :operation-id (:operation-id slot)
                 :session (:session initial)}
        setting-revisions (:setting-revisions initial)
        input (when (some? prompt)
                (let [expanded (if (string? prompt)
                                 (resources/expand-input manager prompt) prompt)]
                  (capabilities/apply-hooks registry :input context expanded)))
        prepared (capabilities/apply-hooks registry :before-run context
                                           {:prompt input :config config})]
    (value/check! (map? prepared) :invalid-hook-result
                  "before-run hooks must return {:prompt ... :config ...}" {})
    (let [config (provider-config runtime sid
                                  (run/effective-config
                                   (store/session (:store runtime) sid)
                                   (:config prepared)))
          title (atom nil)
          prior-path (atom [])
          committed-entries (atom [])
          cursor (atom 0)
          context-baseline (atom {:settings (select-keys (get-in initial [:session :config :settings])
                                                        context-tree/setting-keys)
                                  :revisions setting-revisions})
          initial-intents
          (locking (control/session-lock runtime sid)
            (util/check-cancelled! (:cancelled slot))
            (let [snapshot (store/session (:store runtime) sid)
                  path (store/active-path (:store runtime) sid)
                  _ (reset! prior-path path)
                  _ (reset! cursor (db/store-read (:store runtime)
                                      #(long (or (sql/scalar % "SELECT MAX(seq) FROM entries WHERE session_id=?" [sid]) 0))))
                  running? (contains? #{:run :continue} (:kind slot))
                  new-generation? (and running?
                                       (not= (:generation registry)
                                             (get-in snapshot [:metadata :repl/generation])))
                  reset-entry (when (and new-generation?
                                         (seq (store/context-messages (:store runtime) sid)))
                                {:kind :custom-context
                                 :data {:message/role :user
                                        :message/repl-generation (:generation registry)
                                        :message/content
                                        "Execution environment notice: the REPL evaluator has been replaced. Earlier definitions and live JVM objects are no longer available; durable results and external effects remain. Inspect (workspace) and retained results before continuing; do not replay effects to reconstruct bindings."}})
                  items (if running?
                          (run/select-intents (store/pending (:store runtime) sid)
                                              :start-boundary)
                          [])
                  delivered-settings (into #{} (mapcat #(keys (get-in % [:options :config :settings]))) items)
                  revisions (get (some-> (:context-setting-revisions runtime) deref) sid {})
                  _ (swap! context-baseline
                           (fn [baseline]
                             (reduce (fn [baseline key]
                                       (if (contains? context-tree/setting-keys key)
                                         (-> baseline
                                             (update :settings
                                                     #(if (contains? (get-in snapshot [:config :settings]) key)
                                                        (assoc % key (get-in snapshot [:config :settings key]))
                                                        (dissoc % key)))
                                             (assoc-in [:revisions key] (get revisions key 0)))
                                         baseline))
                                     baseline delivered-settings)))
                  entries (cond-> (run/intent-entries items)
                            (some? prompt)
                            (conj {:kind :message
                                   :data (run/user-message (:prompt prepared))}))
                  naming (initial-title runtime sid entries config)
                  _ (reset! title naming)
                  entries (cond->> entries reset-entry (into [reset-entry]))
                  session-changes (cond-> (select-keys naming [:name :metadata])
                                    new-generation?
                                    (assoc :metadata
                                           (assoc (or (:metadata naming) (:metadata snapshot))
                                                  :repl/generation (:generation registry))))
                  events (cond-> []
                           (seq items)
                           (conj {:operation-id (:operation-id slot)
                                  :type :queue/delivered
                                  :data {:ids (mapv :id items) :phase :start-boundary}})
                           (some? prompt)
                           (conj {:operation-id (:operation-id slot)
                                  :type :message/user :data {}})
                           naming (conj {:type :session/named :data {:name (:name naming) :source :auto}}))]
              (when (or (seq entries) (seq items) (seq session-changes))
                (reset! committed-entries
                        (:entries
                         (control/commit! runtime sid
                                          {::command/entries entries
                                           ::command/queue-deliver (mapv :id items)
                                           ::command/events events
                                           ::command/session session-changes}))))
              items))]
      (when @title (start-title! runtime sid config @title))
      {::registry registry ::manager manager ::config config
       ::hook-context context ::initial-intents initial-intents
       ::session-context-settings (:settings @context-baseline)
       ::session-context-setting-revisions (:revisions @context-baseline)
       ::prior-path @prior-path ::committed-entries @committed-entries
       ::cursor (or (:seq (peek @committed-entries)) @cursor)})))
