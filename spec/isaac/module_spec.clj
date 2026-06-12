(ns isaac.module-spec
  (:require
    [isaac.module :as sut]
    [speclj.core :refer :all]))

(defrecord StartupOnlyModule [calls]
  sut/Module
  (on-startup [_]
    (swap! calls conj :started))
  (on-shutdown [_] nil))

(defrecord PartialDefrecordModule [calls]
  sut/Module
  (on-startup [_]
    (swap! calls conj :started)))

(defrecord PartialExtendedModule [calls])

(extend-protocol sut/Module
  PartialExtendedModule
  (on-shutdown [{:keys [calls]}]
    (swap! calls conj :stopped)))

(describe "isaac.module"

  (it "supports modules defined with defrecord"
    (let [calls  (atom [])
          module (StartupOnlyModule. calls)]
      (sut/run-startup! module)
      (sut/run-shutdown! module)
      (should (sut/module? module))
      (should= [:started] @calls)))

  (it "treats missing defrecord lifecycle hooks as no-ops"
    (let [calls  (atom [])
          module (PartialDefrecordModule. calls)]
      (sut/run-startup! module)
      (sut/run-shutdown! module)
      (should (sut/module? module))
      (should= [:started] @calls)))

  (it "treats missing extend-protocol lifecycle hooks as no-ops"
    (let [calls  (atom [])
          module (PartialExtendedModule. calls)]
      (sut/run-startup! module)
      (sut/run-shutdown! module)
      (should (sut/module? module))
      (should= [:stopped] @calls)))

  (it "builds no-op modules with default lifecycle hooks"
    (let [module (sut/module)]
      (sut/run-startup! module)
      (sut/run-shutdown! module)
      (should (sut/module? module)))))
