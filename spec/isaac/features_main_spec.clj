(ns isaac.features-main-spec
  (:require
    [isaac.features-main :as sut]
    [speclj.core :refer :all]))

(describe "features main"

  (it "delegates to the canonical bb features task"
    (should= ["bb" "features"] (sut/command-args [])))

  (it "forwards feature arguments to bb features"
    (should= ["bb" "features" "features/module/activation.feature"]
             (sut/command-args ["features/module/activation.feature"]))))
