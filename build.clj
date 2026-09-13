(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.DeadMeme5441/arrodes)
(def version "0.1.0")
(def class-dir "target/classes")
(def source-dirs ["modules/common/src" "modules/session/src" "modules/runtime/src" "modules/cli/src"])
(defn clean [_] (b/delete {:path "target"}))
(defn uber [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs (conj source-dirs "resources") :target-dir class-dir})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/ARRODES-LICENSE")})
    (b/write-pom {:class-dir class-dir :lib lib :version version :basis basis
                  :src-dirs source-dirs
                  :pom-data [[:description "Durable Clojure agent sessions with a persistent evaluator"]
                             [:url "https://github.com/DeadMeme5441/arrodes-mono"]
                             [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]]})
    (b/compile-clj {:basis basis :src-dirs source-dirs :class-dir class-dir
                    :ns-compile '[arrodes.cli]})
    (b/uber {:class-dir class-dir :uber-file "target/arrodes.jar" :basis basis :main 'arrodes.cli})
    {:artifact "target/arrodes.jar" :version version}))
