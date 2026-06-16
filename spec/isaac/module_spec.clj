(ns isaac.module-spec
  (:require
    [isaac.module.protocol :as sut]
    [speclj.core :refer :all]))

(defrecord LoadOnlyModule [calls]
  sut/Module
  (on-load [_]
    (swap! calls conj :loaded))
  (on-unload [_] nil))

(defrecord PartialDefrecordModule [calls]
  sut/Module
  (on-load [_]
    (swap! calls conj :loaded)))

(defrecord PartialExtendedModule [calls])

(extend-protocol sut/Module
  PartialExtendedModule
  (on-unload [{:keys [calls]}]
    (swap! calls conj :unloaded)))

(describe "isaac.module.protocol"

  (it "supports modules defined with defrecord"
    (let [calls  (atom [])
          module (LoadOnlyModule. calls)]
      (sut/run-load! module)
      (sut/run-unload! module)
      (should (sut/module? module))
      (should= [:loaded] @calls)))

  (it "treats missing defrecord lifecycle hooks as no-ops"
    (let [calls  (atom [])
          module (PartialDefrecordModule. calls)]
      (sut/run-load! module)
      (sut/run-unload! module)
      (should (sut/module? module))
      (should= [:loaded] @calls)))

  (it "treats missing extend-protocol lifecycle hooks as no-ops"
    (let [calls  (atom [])
          module (PartialExtendedModule. calls)]
      (sut/run-load! module)
      (sut/run-unload! module)
      (should (sut/module? module))
      (should= [:unloaded] @calls)))

  (it "builds no-op modules with default lifecycle hooks"
    (let [module (sut/module)]
      (sut/run-load! module)
      (sut/run-unload! module)
      (should (sut/module? module)))))