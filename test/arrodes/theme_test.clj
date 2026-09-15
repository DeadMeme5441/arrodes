(ns arrodes.theme-test
  (:require [arrodes.theme :as theme] [clojure.test :refer [deftest is testing]]))

(deftest builtins-and-partial-overrides
  (is (= #{"default" "dracula"} (set (map :id theme/builtins))))
  (is (= "#282a36" (get-in (theme/builtin "dracula") [:colors :surface/base])))
  (is (= "#ff79c6" (get-in (theme/builtin "dracula") [:colors :syntax/keyword])))
  (let [pack (theme/resolve-pack {:schema-version 1 :id "custom" :name "Custom" :version "1"
                                 :colors {:ui/accent "#123456"}
                                 :styles {:markdown/heading {:bold false}}})]
    (is (= "#123456" (get-in pack [:colors :ui/accent])))
    (is (= (get-in theme/default-pack [:colors :surface/base]) (get-in pack [:colors :surface/base])))
    (is (false? (get-in pack [:styles :markdown/heading :bold])))
    (is (= theme/color-roles (set (keys (:colors pack)))))))

(deftest rejects-behavior-layout-and-malformed-values
  (let [base {:schema-version 1 :id "custom" :name "Custom" :version "1"}]
    (doseq [invalid [nil [] (assoc base :schema-version 2) (assoc base :id "../theme")
                     (assoc base :name "bad\u001b[2J") (assoc base :script "run.clj")
                     (assoc base :layout {:width 20})
                     (assoc base :colors {:ui/accent "purple"})
                     (assoc base :colors {:ui/accent :other-role})
                     (assoc base :colors {:unknown/role "#ffffff"})
                     (assoc base :styles {:user/heading {:fontSize 20}})
                     (assoc base :styles {:user/heading {:bold "true"}})]]
      (is (thrown? clojure.lang.ExceptionInfo (theme/resolve-pack invalid))))))
