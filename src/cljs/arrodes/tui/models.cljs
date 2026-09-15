(ns arrodes.tui.models
  "Provider/model selection and inline effort controls."
  (:require
            [arrodes.catalog :as catalog]
            [arrodes.tui-widgets :as w]
            [clojure.string :as str]
            [arrodes.tui.context :as c]))

(declare open-providers! open-models! browser-provider! provider-actions! effort-label model-settings set-effort! change-effort! model-horizontal! focus-model-settings! apply-model! render-model-settings!)

(defn open-providers! [view]
  (c/action! :open-overlay! view {:kind :providers :title "Providers" :query ""
                       :hint "Connect your accounts. Connecting a provider keeps your conversation's model unchanged."})
  (c/fire! view :providers {}))


(defn open-models! [view]
  (let [provider (or (get-in (c/state view) [:view :session :config :provider])
                     (some-> (first (catalog/providers (:providers (c/state view)))) catalog/provider-id))]
    (c/action! :open-overlay! view {:kind :models :title "Models" :query "" :provider provider :pane :models
                         :hint "Select a model, adjust its effort, then apply."})
    (c/fire! view :providers {})
    (when provider
      (c/fire! view :browse-provider
             {:provider provider :available? (:available? (some #(when (= (catalog/provider-id %) (keyword provider)) %) (:providers (c/state view))))}))))


(defn browser-provider! [view entry]
  (c/ui! view update :overlay assoc :provider (catalog/provider-id entry) :query "" :index 0 :pane :models)
  (.focus (:modal-input view))
  (c/fire! view :browse-provider {:provider (catalog/provider-id entry) :available? (:available? entry)}))


(defn provider-actions! [view entry]
  (let [previous (get-in (c/state view) [:ui :overlay])
        id (catalog/provider-id entry)
        auth-type (keyword (get-in entry [:auth :type] :api-key))
        return! #(do (c/ui! view assoc :overlay previous) (c/action! :render-overlay! view))
        connect! (fn []
                   (return!)
                   (c/fire! view :provider-login {:provider id :type auth-type}))
        browse! (fn []
                  (c/action! :open-overlay! view {:kind :models :title "Models" :provider id :query "" :pane :models
                                       :return-overlay previous
                                       :hint "Select a model and adjust its effort here. F5 refreshes this provider."})
                  (c/fire! view :browse-provider {:provider id :available? (:available? entry)}))]
    (c/action! :open-overlay!
     view {:kind :choices :title (catalog/provider-name entry) :return-overlay previous
           :hint (str (name id) " · " (catalog/status-label entry) " · " (catalog/auth-label entry))
           :items (vec (concat
                        (when (:available? entry)
                          [{:label "Browse models" :description "Choose for this conversation or save a default" :choose browse!}])
                        (cond
                          (= :ambient auth-type)
                          [{:label "Check environment credentials" :description "Configure credentials in your shell, then restart Arrodes to load them."
                            :choose #(do (return!) (c/fire! view :providers {}))}]
                          (= :none auth-type)
                          [{:label "Browse available models" :description "This provider does not require sign-in" :choose browse!}]
                          :else
                          [{:label (if (:available? entry) "Sign in again" "Connect provider")
                            :description (catalog/auth-label entry) :choose connect!}])
                        (when (and (:available? entry) (not (contains? #{:ambient :none} auth-type)))
                          [{:label "Remove saved credentials" :description "Environment credentials, if present, remain available."
                            :choose #(c/action! :open-overlay!
                                      view {:kind :confirm :title "Remove saved credentials?" :return-overlay previous
                                            :hint (catalog/provider-name entry)
                                            :items [{:label "Keep connection" :choose return!}
                                                    {:label "Remove credentials" :choose (fn [] (return!) (c/fire! view :provider-logout {:provider id}))}]})}])))})))


(defn effort-label [level]
  (case (keyword level)
    :none "None" :minimal "Minimal" :low "Low" :medium "Medium"
    :high "High" :xhigh "Extra high" :max "Max" :ultra "Ultra"
    (str/capitalize (name level))))


(defn model-settings [view overlay]
  (let [m (:model (get (c/action! :overlay-items view overlay) (or (:index overlay) 0)))
        identity [(:provider m) (:id m)]
        current (if (= identity (:effort-model overlay)) (:thinking overlay)
                    (get-in (c/state view) [:view :session :config :thinking]))]
    (assoc overlay :model m :thinking (when m (catalog/thinking m current)))))


(defn set-effort! [view level]
  (let [overlay (model-settings view (get-in (c/state view) [:ui :overlay]))
        m (:model overlay)]
    (when m
      (c/ui! view update :overlay assoc :thinking level :effort-model [(:provider m) (:id m)] :pane :effort)
      (.focus (:modal-list view)))))


(defn change-effort! [view direction]
  (let [overlay (model-settings view (get-in (c/state view) [:ui :overlay]))
        levels (mapv keyword (or (seq (get-in overlay [:model :thinking-levels])) [:none]))
        index (or (first (keep-indexed #(when (= %2 (:thinking overlay)) %1) levels)) 0)]
    (set-effort! view (get levels (max 0 (min (dec (count levels)) (+ index direction)))))))


(defn model-horizontal! [view direction]
  (let [overlay (get-in (c/state view) [:ui :overlay])
        pane (:pane overlay)
        input (:modal-input view)
        move! (fn [target]
                (c/ui! view assoc-in [:overlay :pane] target)
                (if (= target :models) (.focus input) (.focus (:modal-list view)))
                true)]
    (case pane
      :providers (if (pos? direction) (move! :models) true)
      :models
      (let [buffer (.-editBuffer input) cursor (.getCursorPosition buffer)
            edge? (if (neg? direction) (zero? (.-offset cursor))
                      (and (= (.-row cursor) (dec (.getLineCount buffer)))
                           (= (.-col cursor) (.-col (.getEOL buffer)))))]
        (if (and edge? (not (.hasSelection input)))
          (move! (if (neg? direction) :providers :effort)) false))
      :effort
      (let [selection (model-settings view overlay)
            levels (mapv keyword (or (seq (get-in selection [:model :thinking-levels])) [:none]))]
        (if (and (neg? direction) (or (nil? (:model selection)) (= (:thinking selection) (first levels))))
          (move! :models)
          (do (change-effort! view direction) true)))
      (:session :default) (if (neg? direction) (move! :models) true)
      false)))


(defn focus-model-settings! [view m]
  (let [overlay (get-in (c/state view) [:ui :overlay])
        index (first (keep-indexed #(when (= [(:provider m) (:id m)]
                                            [(get-in %2 [:model :provider]) (get-in %2 [:model :id])]) %1)
                                  (c/action! :overlay-items view overlay)))]
    (when index (c/ui! view update :overlay assoc :index index :pane :effort))
    (.focus (:modal-list view))))


(defn apply-model! [view scope]
  (let [overlay (model-settings view (get-in (c/state view) [:ui :overlay]))
        m (:model overlay) token (:token overlay)]
    (when (and m (nil? (:catalog-operation (c/state view))))
      (-> (c/invoke! view :select-model {:provider (:provider m) :model (:id m)
                                    :thinking (:thinking overlay) :scope scope})
        (.then (fn [_]
                 (when (= token (get-in (c/state view) [:ui :overlay :token]))
                   (c/ui! view assoc :overlay nil)
                   (c/action! :focus! view :composer))))
        (.catch (fn [failure]
                  (when (= token (get-in (c/state view) [:ui :overlay :token]))
                    (c/ui! view assoc-in [:overlay :error] (c/error-text failure)))))))))


(defn render-model-settings! [view overlay wide?]
  (let [{:keys [model thinking pane] :as selection} (model-settings view overlay)
        renderer (:renderer view) panel (:model-settings view)
        compact? (and (not wide?) (< (.-terminalHeight renderer) 24))
        signature [model thinking pane wide? compact? (:catalog-operation (c/state view))]
        levels (mapv keyword (or (seq (:thinking-levels model)) [:none]))]
    (when (not= signature (:settings-signature @(:local view)))
      (swap! (:local view) assoc :settings-signature signature)
      (w/clear! panel)
      (if-not model
        (.add panel (w/text renderer "Select a model to configure it." {:height 1 :fg :text/secondary}))
        (let [options (w/box renderer {:width "100%" :flexDirection "row" :flexWrap "wrap" :gap 1})
              action (fn [id label scope]
                       (w/button renderer (str (when (= pane scope) "› ") label)
                                 #(apply-model! view scope)
                                 {:id id :height 1 :width "100%" :truncate true :wrapMode "none"
                                  :fg (if (= pane scope) :ui/accent :text/primary)
                                  :bg (if (= pane scope) :surface/selected :surface/panel)}))]
          (.add panel (w/text renderer (:id model) {:id "model-selection-summary" :height 1 :width "100%"
                                                   :truncate true :wrapMode "none" :fg :ui/accent}))
          (when-not compact?
            (.add panel (w/text renderer (str (when-let [window (:context-window model)] (str window " context · "))
                                             (catalog/provider-name model))
                               {:height 1 :truncate true :wrapMode "none" :fg :text/dim :marginBottom 1})))
          (.add panel (w/text renderer (str "Effort: " (effort-label thinking))
                             {:id "effort-value" :height 1 :fg (if (= pane :effort) :ui/accent :text/primary)}))
          (if compact?
            (w/add! options
                    (w/button renderer "‹" #(change-effort! view -1) {:id "effort-prev" :width 3})
                    (w/text renderer (effort-label thinking) {:height 1 :width 14 :fg :ui/accent})
                    (w/button renderer "›" #(change-effort! view 1) {:id "effort-next" :width 3}))
            (doseq [level levels]
              (let [selected? (= thinking level)
                    label (str (when selected? "[") (effort-label level) (when selected? " ✓]"))]
                (.add options (w/button renderer label #(set-effort! view level)
                                        {:id (str "effort-" (name level)) :height 1 :width (+ 2 (count label)) :paddingX 1
                                         :fg (if selected? :ui/accent :text/secondary)
                                         :bg (if selected? :surface/selected :surface/panel)})))))
          (.add panel options)
          (when-not compact?
            (.add panel (w/text renderer "← → adjusts effort · Tab moves to apply"
                               {:width "100%" :fg :text/dim :marginBottom 1 :marginTop 1})))
          (when (get-in (c/state view) [:view :session])
            (.add panel (action "apply-session-model" "Apply to this session" :session)))
          (.add panel (action "apply-default-model"
                              (if (get-in (c/state view) [:view :session]) "Apply here + make default" "Apply as default") :default)))))))

