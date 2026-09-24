(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.DeadMeme5441/arrodes)
(def version "0.1.6")
(def core-source-dirs ["src/clj" "src/cljc"])
(def host-source-dirs (conj core-source-dirs "hosts/rpc"))

(def pom-data
  [[:description "Durable Clojure agent sessions with a persistent evaluator"]
   [:url "https://github.com/DeadMeme5441/arrodes"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn- package!
  [{:keys [aliases artifact class-dir main ns-compile source-dirs]}]
  (b/delete {:path class-dir})
  (b/delete {:path artifact})
  (let [basis (b/create-basis (cond-> {:project "deps.edn"}
                                (seq aliases) (assoc :aliases aliases)))]
    (b/copy-dir {:src-dirs (conj source-dirs "resources")
                 :target-dir class-dir})
    (b/copy-file {:src "LICENSE"
                  :target (str class-dir "/META-INF/ARRODES-LICENSE")})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs source-dirs
                  :pom-data pom-data})
    (b/compile-clj {:basis basis
                    :src-dirs source-dirs
                    :class-dir class-dir
                    :ns-compile ns-compile})
    (b/uber (cond-> {:class-dir class-dir
                     :uber-file artifact
                     :basis basis}
              main (assoc :main main)))
    {:artifact artifact :version version}))

(defn uber
  "Build the headless SDK artifact."
  [_]
  (package! {:artifact "target/arrodes.jar"
             :class-dir "target/classes-core"
             :source-dirs core-source-dirs
             :ns-compile '[arrodes.runtime]}))

(defn rpc
  "Build the standalone JSONL RPC host."
  [_]
  (package! {:aliases [:host]
             :artifact "target/arrodes-rpc.jar"
             :class-dir "target/classes-rpc"
             :source-dirs host-source-dirs
             :ns-compile '[arrodes.runtime arrodes.commands arrodes.rpc-main]
             :main 'arrodes.rpc-main}))

