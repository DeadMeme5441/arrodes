(ns arrodes.tui-widgets
  "Small OpenTUI construction helpers. No session or execution state lives here."
  (:require [arrodes.tui-model :as model]
            [clojure.string :as str]
            [arrodes.tui.palette :as palette]))

;; OpenTUI is an ESM module with top-level await; the Bun launcher loads it
;; before entering the compiler's CommonJS output.
(def core (or (aget js/globalThis "ARRODES_OPENTUI")
              (throw (js/Error. "Launch Arrodes through scripts/tui.ts to initialize OpenTUI"))))
(def layout {:gutter 2 :section-gap 1 :sidebar-width 28})

(def color palette/color)
(def paint! palette/paint!)
(def renderer palette/renderer)
(def style! palette/style!)

(defn create [renderer class-name options]
  (let [node (js/Reflect.construct (aget core class-name)
                                  #js [renderer (clj->js (palette/resolve-options renderer (dissoc options :style-role)))])]
    (palette/bind! node renderer options)))

(defn box [renderer options]
  (create renderer "BoxRenderable" (merge {:flexDirection "column" :flexShrink 0} options)))

(defn text [renderer content options]
  (let [content (model/safe-text (str (or content "")))
        node (create renderer "TextRenderable"
                     (merge {:content content :fg :text/primary :selectable true
                             :selectionBg :selection/background :wrapMode "word" :flexShrink 0}
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
        (merge {:fg :ui/accent :height 1 :selectable false :focusable true
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

(defn syntax-style [renderer]
  (let [styles (fn []
                 (let [pack (palette/current renderer)]
                   {"default" {:fg (color renderer :text/primary)}
                    "markup.heading" (merge {:fg (color renderer :markdown/heading)} (get-in pack [:styles :markdown/heading]))
                    "markup.strong" (merge {:fg (color renderer :text/primary)} (get-in pack [:styles :markdown/strong]))
                    "markup.italic" (merge {:fg (color renderer :text/secondary)} (get-in pack [:styles :markdown/emphasis]))
                    "markup.raw" {:fg (color renderer :syntax/string)}
                    "markup.link" (merge {:fg (color renderer :markdown/link)} (get-in pack [:styles :markdown/link]))
                    "markup.list" {:fg (color renderer :text/secondary)}
                    "punctuation.special" {:fg (color renderer :text/dim)}}))
        expanded-styles (fn []
                          (let [base (styles)]
                            (merge base
                                   (into {} (for [level (range 1 7)] [(str "markup.heading." level) (get base "markup.heading")]))
                                   (into {} (for [name ["markup.link.label" "markup.link.url" "string.special.url"]]
                                              [name (get base "markup.link")])))))
        painter (fn [style] (doseq [[name value] (expanded-styles)] (.registerStyle style name (clj->js value))))]
    (palette/register-syntax! renderer (.fromStyles (.-SyntaxStyle core) (clj->js (expanded-styles))) painter)))

(declare code-content!)

(defn markdown [renderer style content options]
  (let [node (create renderer "MarkdownRenderable"
          (merge {:content (model/safe-text content) :syntaxStyle (palette/current-syntax renderer style)
                  :fg :text/primary :conceal true :concealCode false
                  :tableOptions {:style "grid" :widthMode "content" :cellPaddingX 1 :cellPaddingY 0
                                 :borderColor :text/dim :wrapMode "word" :selectable true}
                  ;; Plain code stays local: no parser downloads or syntax worker
                  ;; is needed to read an answer, including unfamiliar languages.
                  :renderNode (fn [token _]
                                (when (= "code" (.-type token))
                                  (let [language (or (not-empty (.-lang token)) "Code")
                                        block (box renderer {:width "100%" :paddingX 1 :marginY 1
                                                             :backgroundColor :surface/base :border ["top" "bottom"]
                                                             :borderColor :border/default})
                                        code (text renderer (.-text token) {:width "100%" :fg :text/primary})]
                                    (add! block
                                          (text renderer language {:height 1 :fg :ui/accent :selectable false})
                                          code)
                                    (when (contains? #{"clojure" "clj" "cljs" "cljc" "edn"} language)
                                      (aset code "arrodesContent" nil)
                                      (code-content! code (.-text token)))
                                    block)))
                  :flexShrink 0}
                 options))]
    (aset node "arrodesRepaint"
          (fn []
            ;; Markdown caches styled spans. Invalidate styling even when a pack
            ;; changes only an accent and keeps its primary text color unchanged.
            (set! (.-syntaxStyle node) (palette/current-syntax renderer style))
            (set! (.-tableOptions node)
                  (clj->js (palette/resolve-options renderer
                                                   (merge {:style "grid" :widthMode "content" :cellPaddingX 1 :cellPaddingY 0
                                                           :borderColor :text/dim :wrapMode "word" :selectable true}
                                                          (:tableOptions options)))))
            (.refreshStyles node)))
    node))

(defn code-content!
  "Local Clojure token coloring. No parser downloads or code execution."
  [node source]
  (let [source (model/safe-text (str (or source "")))]
    (when (not= source (aget node "arrodesContent"))
      (aset node "arrodesContent" source)
      (aset node "arrodesRepaint" (fn [] (aset node "arrodesContent" nil) (code-content! node source)))
      (let [tokens (re-seq #";[^\n]*|\"(?:\\.|[^\"\\])*\"|:[^\s\[\]{}()]+|[\[\]{}()]|[^\s\[\]{}()\";]+|\s+|." source)
            chunks (mapv (fn [token]
                           ((.fg core (color (renderer node) (cond
                                        (str/starts-with? token ";") :syntax/comment
                                        (str/starts-with? token "\"") :syntax/string
                                        (str/starts-with? token ":") :syntax/keyword
                                        (re-matches #"[\[\]{}()]" token) :syntax/comment
                                        (contains? #{"def" "defn" "let" "fn" "if" "do" "require" "future" "try" "catch"} token) :syntax/function
                                        (re-matches #"-?[0-9]+(?:\.[0-9]+)?" token) :syntax/number
                                        :else :text/primary))) token)) tokens)]
        (set! (.-content node) (js/Reflect.construct (.-StyledText core) #js [(clj->js chunks)]))))))

(defn scrollbox [renderer options]
  (create renderer "ScrollBoxRenderable"
          (merge {:flexGrow 1 :flexShrink 1 :minHeight 1 :minWidth 1
                  :scrollX false :scrollY true :viewportCulling true
                  :contentOptions {:flexDirection "column"}
                  :verticalScrollbarOptions {:visible false}}
                 options)))
