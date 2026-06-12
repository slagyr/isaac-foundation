(ns isaac.core-spec
  (:require
    [isaac.core]
    [speclj.core :refer :all]))

(describe "Isaac"

  (it "bootstraps"
    (should= 1 1)))
