(ns arrodes.theme-macros
  "Embed built-in declarative packs in the compiled TUI, including standalone releases."
  (:require [clojure.edn :as edn] [clojure.java.io :as io]))
(defmacro builtin-packs []
  (let [read-pack (fn [id]
                    (let [name (str "arrodes/themes/" id "/theme.edn")]
                      (edn/read-string (slurp (or (io/resource name) (io/file "resources" name))))))]
    (list 'quote (mapv read-pack ["default" "dracula"]))))
