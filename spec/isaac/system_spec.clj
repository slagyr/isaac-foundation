(ns isaac.system-spec
  (:refer-clojure :exclude [get])
  (:require
    [isaac.nexus :as nexus]
    [isaac.system :as sut]
    [speclj.core :refer :all]))

(def original-runtime (atom nil))

(describe "isaac.system compatibility"
  (before
    (reset! original-runtime (nexus/necho))
    (nexus/reset!))

  (after
    (nexus/install! @original-runtime))

  (it "reads values from the current nexus"
    (nexus/install! {:scheduler :sch})
    (should= :sch (sut/get :scheduler)))

  (it "installs a temporary runtime for with-system"
    (nexus/install! {:outer :value})
    (sut/with-system {:inner :value}
      (should= nil (sut/get :outer))
      (should= :value (sut/get :inner)))
    (should= :value (sut/get :outer))
    (should= nil (sut/get :inner)))

  (it "merges temporary values for with-nested-system"
    (nexus/install! {:outer :value})
    (sut/with-nested-system {:inner :value}
      (should= :value (sut/get :outer))
      (should= :value (sut/get :inner)))
    (should= :value (sut/get :outer))
    (should= nil (sut/get :inner))))
