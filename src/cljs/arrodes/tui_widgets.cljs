(ns arrodes.tui-widgets
  "Small OpenTUI construction helpers. No session or execution state lives here."
  (:require [arrodes.tui-model :as model]))

;; OpenTUI is an ESM module with top-level await; the Bun launcher loads it
;; before entering the compiler's CommonJS output.
(def core (or (aget js/globalThis "ARRODES_OPENTUI")
              (throw (js/Error. "Launch Arrodes through scripts/tui.ts to initialize OpenTUI"))))
(def colors {:background "#111419" :surface "#1a1f26" :raised "#232a33"
             :text "#e8e5de" :muted "#929aa7" :faint "#646e7c"
             :accent "#d6b47b" :success "#89b7a0" :error "#e08d8d"
             :border "#333c48" :selection "#3a485c"})

(defn create [renderer class-name options]
  (js/Reflect.construct (aget core class-name) #js [renderer (clj->js options)]))

(defn box [renderer options]
  (create renderer "BoxRenderable" (merge {:flexDirection "column" :flexShrink 0} options)))

(defn text [renderer content options]
  (let [content (model/safe-text (str (or content "")))
        node (create renderer "TextRenderable"
                     (merge {:content content :fg (:text colors) :selectable true
                             :selectionBg (:selection colors) :wrapMode "word" :flexShrink 0}
                            options))]
    (aset node "arrodesContent" content)
    node))

(defn content! [node content]
  (let [content (model/safe-text (str (or content "")))]
    (when (not= content (aget node "arrodesContent"))
      (aset node "arrodesContent" content)
      (set! (.-content node) content))))

(defn consume! [event]
  (.preventDefault event)
  (.stopPropagation event))

(defn button [renderer label action options]
  (text renderer label
        (merge {:fg (:accent colors) :height 1 :selectable false :focusable true
                :onMouseDown (fn [event] (consume! event) (action))
                :onKeyDown (fn [event]
                             (when (contains? #{"return" "enter" "space"} (.-name event))
                               (consume! event) (action)))}
               options)))

(defn add! [parent & children]
  (doseq [child children] (.add parent child))
  parent)

(defn clear! [parent]
  (doseq [child (array-seq (.getChildren parent))]
    (.remove parent child)
    (.destroyRecursively child)))

(defn syntax-style []
  (.fromStyles (.-SyntaxStyle core)
               (clj->js {"default" {:fg (:text colors)}
                         "markup.heading" {:fg (:accent colors) :bold true}
                         "markup.strong" {:fg (:text colors) :bold true}
                         "markup.italic" {:fg (:muted colors) :italic true}
                         "markup.raw" {:fg (:success colors)}
                         "markup.link" {:fg (:accent colors) :underline true}
                         "markup.list" {:fg (:muted colors)}
                         "punctuation.special" {:fg (:faint colors)}})))

(defn markdown [renderer style content options]
  (create renderer "MarkdownRenderable"
          (merge {:content (model/safe-text content) :syntaxStyle style
                  :fg (:text colors) :conceal true :concealCode false
                  :tableOptions {:style "columns" :wrapMode "word" :selectable true}
                  ;; Plain code stays local: no parser downloads or syntax worker
                  ;; is needed to read an answer, including unfamiliar languages.
                  :renderNode (fn [token _]
                                (when (= "code" (.-type token))
                                  (text renderer (.-text token)
                                        {:bg (:surface colors) :paddingX 1 :marginY 1})))
                  :flexShrink 0}
                 options)))

(defn scrollbox [renderer options]
  (create renderer "ScrollBoxRenderable"
          (merge {:flexGrow 1 :flexShrink 1 :minHeight 1 :minWidth 1
                  :scrollX false :scrollY true :viewportCulling true
                  :contentOptions {:flexDirection "column"}
                  :verticalScrollbarOptions {:visible false}}
                 options)))
