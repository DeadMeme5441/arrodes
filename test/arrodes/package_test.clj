(ns arrodes.package-test
  (:require [arrodes.packages :as packages]
            [arrodes.session-test :as fixtures]
            [arrodes.util :as u]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- make-package! [path content]
  (u/ensure-dir! path)
  (u/write-edn! (str path "/arrodes.edn") {:name "fixture" :extensions ["extension.clj"]})
  (spit (str path "/extension.clj") content)
  path)

(deftest local-packages-update-and-remove-without-deleting-source
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        source (make-package! (str directory "/fixture") "(fn [_] {:version 1})")]
    (try
      (let [installed (packages/install! home directory source {:scope :global})
            installed-file (str (:path installed) "/extension.clj")]
        (is (= "(fn [_] {:version 1})" (slurp installed-file)))
        (spit (str source "/extension.clj") "(fn [_] {:version 2})")
        (packages/update! home directory (:name installed) {:scope :global})
        (is (= "(fn [_] {:version 2})" (slurp installed-file)))
        (packages/remove! home directory (:name installed) {:scope :global})
        (is (= "(fn [_] {:version 2})" (slurp (str source "/extension.clj"))))
        (is (not (.exists (io/file (:path installed)))))
        (is (empty? (packages/list-packages home directory))))
      (finally (fixtures/remove-directory! directory)))))

(deftest unindexed-destination-is-not-replaced
  (let [directory (fixtures/temp-directory)
        home (str directory "/home")
        source (make-package! (str directory "/fixture") "(fn [_] nil)")]
    (try
      (let [installed (packages/install! home directory source {:scope :global})
            destination (:path installed)
            sentinel (str destination "/user-data.txt")]
        (packages/remove! home directory (:name installed) {:scope :global})
        (u/ensure-dir! destination)
        (spit sentinel "Do not replace this directory")
        (let [error (try (packages/install! home directory source {:scope :global}) nil
                         (catch clojure.lang.ExceptionInfo error error))]
          (is (some? error))
          (is (str/ends-with? (or (:error/code (ex-data error)) "") "unowned-destination")))
        (is (= "Do not replace this directory" (slurp sentinel)))
        (is (empty? (packages/list-packages home directory))))
      (finally (fixtures/remove-directory! directory)))))

(deftest project-package-cannot-copy-its-own-staging-root
  (let [directory (fixtures/temp-directory)
        home (str directory "/separate-home")
        project (make-package! (str directory "/project") "(fn [_] {:version 1})")]
    (try
      (let [error (try (packages/install! home project project {:scope :project :name "self"}) nil
                       (catch clojure.lang.ExceptionInfo error error))]
        (is (str/ends-with? (or (:error/code (ex-data error)) "") "source-overlap")))
      (is (= "(fn [_] {:version 1})" (slurp (str project "/extension.clj"))))
      (is (empty? (packages/list-packages home project)))
      (finally (fixtures/remove-directory! directory)))))
