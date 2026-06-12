(ns isaac.module.lifecycle-spec
  (:require
    [isaac.module :as module]
    [isaac.module.loader :as sut]
    [speclj.core :refer :all]))

(declare *calls)

(defrecord LifecycleModule [id calls]
  module/Module
  (on-startup [_]
    (swap! calls conj [:startup id]))
  (on-shutdown [_]
    (swap! calls conj [:shutdown id])))

(defrecord NoopModule [])

(defn bridge-module []
  (LifecycleModule. :marigold.bridge *calls))

(defn longwave-module []
  (LifecycleModule. :marigold.longwave *calls))

(defn contribution-only-module []
  (module/module))

(defn startup-failure-module []
  (reify module/Module
    (on-startup [_]
      (swap! *calls conj [:startup :marigold.longwave])
      (throw (ex-info "boom" {:phase :startup})))
    (on-shutdown [_]
      (swap! *calls conj [:shutdown :marigold.longwave]))))

(defn exploding-factory []
  (throw (ex-info "factory exploded" {:phase :factory})))

(def ^:dynamic *calls nil)

(defn- module-entry [factory & {:keys [deps]}]
  {:manifest (cond-> {:factory factory}
               deps (assoc :deps deps))})

(describe "module lifecycle"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (binding [*calls (atom [])]
      (sut/clear-activations!)
      (example)
      (sut/clear-activations!)))

  (it "supports contribution-only no-op modules"
    (let [noop (contribution-only-module)]
      (module/run-startup! noop)
      (module/run-shutdown! noop)
      (should (module/module? noop))
      (should= [] @*calls)))

  (it "starts modules in topological order from :deps"
    (sut/start-modules!
      {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})
       :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
    (should= [[:startup :marigold.bridge]
              [:startup :marigold.longwave]]
             @*calls))

  (it "shuts down modules in reverse topological order"
    (sut/start-modules!
      {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})
       :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
    (sut/shutdown-modules!)
    (should= [[:startup :marigold.bridge]
              [:startup :marigold.longwave]
              [:shutdown :marigold.longwave]
              [:shutdown :marigold.bridge]]
             @*calls))

  (it "aborts load when a factory symbol cannot be resolved"
    (let [error (try
                  (sut/start-modules! {:marigold.bridge (module-entry 'missing.module/create-module)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :resolve-factory (:reason (ex-data error)))
      (should= :marigold.bridge (:module-id (ex-data error)))))

  (it "aborts load when a factory throws"
    (let [error (try
                  (sut/start-modules! {:marigold.bridge (module-entry 'isaac.module.lifecycle-spec/exploding-factory)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :factory-threw (:reason (ex-data error)))
      (should= :marigold.bridge (:module-id (ex-data error)))))

  (it "aborts load when a factory returns a non-module value"
    (let [error (try
                  (sut/start-modules! {:marigold.bridge (module-entry 'isaac.module.lifecycle-spec/->NoopModule)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :not-a-module (:reason (ex-data error)))
      (should= :marigold.bridge (:module-id (ex-data error)))))

  (it "rolls back already-started modules when a later startup fails"
    (let [error (try
                  (sut/start-modules!
                    {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/startup-failure-module :deps {:marigold.bridge {}})
                     :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :startup-failed (:reason (ex-data error)))
      (should= :marigold.longwave (:module-id (ex-data error)))
      (should= [[:startup :marigold.bridge]
                [:startup :marigold.longwave]
                [:shutdown :marigold.bridge]]
               @*calls)))

  (it "aborts load when module deps contain a cycle"
    (let [error (try
                  (sut/start-modules!
                    {:marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module :deps {:marigold.longwave {}})
                     :marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :dependency-cycle (:reason (ex-data error))))))
