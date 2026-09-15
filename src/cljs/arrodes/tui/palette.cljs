(ns arrodes.tui.palette
  "Per-renderer paint bindings. Recolor existing nodes without remounting editors or changing layout."
  (:require [arrodes.theme :as theme] [clojure.walk :as walk]))

(def core (aget js/globalThis "ARRODES_OPENTUI"))
(def contexts (js/WeakMap.))
(def paint-properties #{:fg :bg :backgroundColor :borderColor :textColor :focusedTextColor
                        :focusedBackgroundColor :cursorColor :selectionBg :selectionFg
                        :selectionBgColor :selectionFgColor})
(defn attach! [renderer manager]
  (.set contexts renderer {:manager manager :syntax (atom [])})
  (.setBackgroundColor renderer (get-in @(:current manager) [:colors :surface/base])))
(defn current [renderer]
  (if-let [manager (:manager (.get contexts renderer))] @(:current manager) (theme/builtin "default")))
(defn renderer [node] (aget node "arrodesRenderer"))
(defn color [renderer role]
  (if (keyword? role)
    (or (get-in (current renderer) [:colors role]) (throw (js/Error. (str "Unknown color role " role)))) role))
(defn resolve-options [renderer options]
  (walk/postwalk (fn [value]
                   (if (map? value)
                     (reduce-kv (fn [m k v] (assoc m k (if (and (paint-properties k) (keyword? v)) (color renderer v) v))) {} value)
                     value)) options))
(defn style! [node role]
  (aset node "arrodesStyleRole" role)
  (let [style (get-in (current (renderer node)) [:styles role])
        attributes (aget core "TextAttributes")]
    (set! (.-attributes node)
          (reduce bit-or 0 (for [[key flag] [[:bold "BOLD"] [:italic "ITALIC"] [:underline "UNDERLINE"]]
                                :when (get style key)] (aget attributes flag))))))
(defn paint! [node property role]
  (let [bindings (or (aget node "arrodesPaint") {})]
    (aset node "arrodesPaint" (assoc bindings property role))
    (js/Reflect.set node (name property) (color (renderer node) role))))
(defn bind! [node renderer options]
  (aset node "arrodesRenderer" renderer)
  (aset node "arrodesPaint" (into {} (filter (fn [[k v]] (and (paint-properties k) (keyword? v))) options)))
  (when-let [role (:style-role options)] (style! node role))
  node)
(defn register-syntax! [renderer style painter]
  (when-let [entries (:syntax (.get contexts renderer))]
    (let [alternate (.create (.-SyntaxStyle core))]
      (painter alternate)
      (swap! entries conj {:styles [style alternate] :index (atom 0) :painter painter})))
  style)
(defn current-syntax [renderer fallback]
  (if-let [entry (some-> (.get contexts renderer) :syntax deref first)]
    (get (:styles entry) @(:index entry)) fallback))
(defn- repaint! [node]
  (when-not (.-isDestroyed node)
    (let [border (.-border node)
          bindings (aget node "arrodesPaint")]
      (doseq [[property role] bindings] (js/Reflect.set node (name property) (color (renderer node) role)))
      ;; OpenTUI's border color setter may enable borders: preserve geometry.
      (when (and (contains? bindings :borderColor) (some? border)) (set! (.-border node) border)))
    (when-let [role (aget node "arrodesStyleRole")] (style! node role))
    (when-let [paint (aget node "arrodesRepaint")] (paint))
    (doseq [child (array-seq (.getChildren node))] (repaint! child))
    (.requestRender node)))
(defn apply! [renderer pack]
  (let [{:keys [manager syntax]} (.get contexts renderer)]
    (when (not= pack @(:current manager))
      (reset! (:current manager) pack)
      ;; Native code blocks invalidate their cached highlights on style identity.
      ;; Alternate two owned styles instead of accumulating one per preview.
      (doseq [{:keys [styles index painter]} @syntax]
        (let [next (- 1 @index) style (get styles next)]
          (painter style) (.clearCache style) (reset! index next)))
      (.setBackgroundColor renderer (get-in pack [:colors :surface/base]))
      (repaint! (.-root renderer))
      (.requestRender renderer))))
(defn detach! [renderer]
  (when-let [entries (:syntax (.get contexts renderer))]
    (doseq [entry @entries style (:styles entry)] (.destroy style)))
  (.delete contexts renderer))
