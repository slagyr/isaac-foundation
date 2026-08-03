(ns isaac.module.lifecycle-spec
  (:require
    [c3kit.apron.env :as c3env]
    [isaac.cli.registry :as cli-registry]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.classpath :as classpath]
    [isaac.module.lifecycle :as lifecycle]
    [isaac.module.protocol :as module]
    [isaac.nexus :as nexus]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(declare *calls)

(defrecord LifecycleModule [id calls]
  module/Module
  (on-load [_]
    (swap! calls conj [:load id]))
  (on-unload [_]
    (swap! calls conj [:unload id])))

(defrecord NoopModule [])

(defn bridge-module []
  (LifecycleModule. :marigold.bridge *calls))

(defn longwave-module []
  (LifecycleModule. :marigold.longwave *calls))

(defn contribution-only-module []
  (module/module))

(defn load-failure-module []
  (reify module/Module
    (on-load [_]
      (swap! *calls conj [:load :marigold.longwave])
      (throw (ex-info "boom" {:phase :load})))
    (on-unload [_]
      (swap! *calls conj [:unload :marigold.longwave]))))

(defn exploding-factory []
  (throw (ex-info "factory exploded" {:phase :factory})))

(def ^:dynamic *calls nil)

(defn- module-entry [factory & {:keys [deps]}]
  {:coord    {:local/root "."}
   :manifest (cond-> {:factory factory}
               deps (assoc :deps deps))})

(defn- reset-cli-registry! []
  (cli-registry/clear-module-commands!))

(describe "module lifecycle"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (binding [*calls (atom [])]
      (lifecycle/clear-activations!)
      (example)
      (lifecycle/clear-activations!)))

  (it "supports contribution-only no-op modules"
    (let [noop (contribution-only-module)]
      (module/run-load! noop)
      (module/run-unload! noop)
      (should (module/module? noop))
      (should= [] @*calls)))

  (it "loads modules in topological order from :deps"
    (lifecycle/load-modules!
      {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})
       :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
    (should= [[:load :marigold.bridge]
              [:load :marigold.longwave]]
             @*calls))

  (it "logs module/loaded once per module in dependency order"
    (lifecycle/load-modules!
      {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})
       :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
    (let [events (filter #(= :module/loaded (:event %)) @log/captured-logs)]
      (should= 2 (count events))
      (should= ["marigold.bridge" "marigold.longwave"]
               (mapv :module events))))

  (it "unloads modules in reverse topological order"
    (lifecycle/load-modules!
      {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})
       :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
    (lifecycle/shutdown-modules!)
    (should= [[:load :marigold.bridge]
              [:load :marigold.longwave]
              [:unload :marigold.longwave]
              [:unload :marigold.bridge]]
             @*calls))

  (it "aborts load when a factory symbol cannot be resolved"
    (let [error (try
                  (lifecycle/start-modules! {:marigold.bridge (module-entry 'missing.module/create-module)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :resolve-factory (:reason (ex-data error)))
      (should= :marigold.bridge (:module-id (ex-data error)))))

  (it "aborts load when a factory throws"
    (let [error (try
                  (lifecycle/start-modules! {:marigold.bridge (module-entry 'isaac.module.lifecycle-spec/exploding-factory)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :factory-threw (:reason (ex-data error)))
      (should= :marigold.bridge (:module-id (ex-data error)))))

  (it "aborts load when a factory returns a non-module value"
    (let [error (try
                  (lifecycle/start-modules! {:marigold.bridge (module-entry 'isaac.module.lifecycle-spec/->NoopModule)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :not-a-module (:reason (ex-data error)))
      (should= :marigold.bridge (:module-id (ex-data error)))))

  (it "rolls back already-loaded modules when a later load fails"
    (let [error (try
                  (lifecycle/start-modules!
                    {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/load-failure-module :deps {:marigold.bridge {}})
                     :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :load-failed (:reason (ex-data error)))
      (should= :marigold.longwave (:module-id (ex-data error)))
      (should= [[:load :marigold.bridge]
                [:load :marigold.longwave]
                [:unload :marigold.bridge]]
               @*calls)))

  (it "aborts load when module deps contain a cycle"
    (let [error (try
                  (lifecycle/load-modules!
                    {:marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module :deps {:marigold.longwave {}})
                     :marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})})
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (should= :module/lifecycle-failed (:type (ex-data error)))
      (should= :dependency-cycle (:reason (ex-data error)))))

  (it "reconcile-modules! unloads removed modules and loads new ones"
    (lifecycle/load-modules!
      {:marigold.longwave (module-entry 'isaac.module.lifecycle-spec/longwave-module :deps {:marigold.bridge {}})
       :marigold.bridge   (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
    (lifecycle/reconcile-modules!
      {:marigold.bridge (module-entry 'isaac.module.lifecycle-spec/bridge-module)})
    (should= [[:load :marigold.bridge]
              [:load :marigold.longwave]
              [:unload :marigold.longwave]]
             @*calls))

  (it "load-modules! is idempotent for an unchanged index"
    (let [index {:marigold.bridge (module-entry 'isaac.module.lifecycle-spec/bridge-module)}]
      (lifecycle/load-modules! index)
      (lifecycle/load-modules! index)
      (should= [[:load :marigold.bridge]] @*calls)))

  (describe "clear-activations!"

    (around [example]
      (let [handlers* @#'isaac.module.lifecycle/handlers*
            handlers  @handlers*]
        (reset! handlers* {})
        (try
          (example)
          (finally
            (reset! handlers* handlers)))))

    (it "invokes registered clear-registration handlers"
      (let [calls (atom [])]
        (lifecycle/register-handler! :clear-registrations #(swap! calls conj :api))
        (lifecycle/register-handler! :clear-registrations #(swap! calls conj :commands))
        (lifecycle/clear-activations!)
        (should= [:api :commands] @calls)))

    (it "does nothing when no clear-registration handlers are registered"
      (should-not-throw
        (lifecycle/clear-activations!))))

  (describe "activate!"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (reset! @#'isaac.module.classpath/loaded-module-coords* #{})
        (reset-cli-registry!)
        (lifecycle/clear-activations!)
        (reset! c3env/-overrides {})
        (example)
        (reset! @#'isaac.module.classpath/loaded-module-coords* #{})
        (reset! c3env/-overrides {})
        (lifecycle/clear-activations!)
        (reset-cli-registry!)))

    (it "logs activation once"
      ;; Phase 8 (isaac-qqgv): comm factory registration moved into
      ;; berth per-entry factories; activate! only logs the activation
      ;; now. Coverage for the registration itself lives under
      ;; process-manifest-berths! and the comm registry spec.
      (let [stub-dir     "/tmp/marigold.comm.stub"
            module-index {:marigold.comm.stub {:dir stub-dir
                                                :manifest {:marigold.bridge/comm
                                                           {:stub {:factory 'marigold.comm.stub/make}}}}}]
        (log/capture-logs
          (lifecycle/activate! :marigold.comm.stub module-index)
          (lifecycle/activate! :marigold.comm.stub module-index)
          (let [events (filter #(= :module/activated (:event %)) @log/captured-logs)]
            (should= 1 (count events))
            (should= "marigold.comm.stub" (:module (first events)))))))

    (it "wraps bootstrap namespace load failures in structured error data and logs them"
      ;; Phase 8 of brth (isaac-qqgv): activate! no longer eagerly
      ;; resolves berth factory symbols. The remaining activate!-side
      ;; failure path is :bootstrap symbol resolution.
      (let [stub-dir     "/tmp/marigold.comm.stub"
            module-index {:marigold.comm.stub {:dir stub-dir
                                                :manifest {:bootstrap 'marigold.comm.stub/bootstrap-load}}}]
        (log/capture-logs
          (let [error (try
                        (lifecycle/activate! :marigold.comm.stub module-index)
                        (catch clojure.lang.ExceptionInfo e
                          e))
                event (first (filter #(= :module/activation-failed (:event %)) @log/captured-logs))]
            (should= :module/activation-failed (:type (ex-data error)))
            (should= :marigold.comm.stub (:module-id (ex-data error)))
            (should-not-be-nil event)
            (should= "marigold.comm.stub" (:module event))))))

    (it "adds local/root deps on first activation"
      (let [stub-dir     "/tmp/marigold.comm.stub"
            module-index {:marigold.comm.stub {:coord {:local/root stub-dir}
                                               :path  stub-dir
                                               :manifest {:marigold.bridge/comm
                                                          {:stub {:factory 'marigold.comm.stub/make}}}}}
            calls        (atom [])]
        (with-redefs [isaac.module.classpath/invoke-add-deps! (fn [deps-map]
                                                             (swap! calls conj deps-map))]
          (lifecycle/activate! :marigold.comm.stub module-index)
          (should= {'marigold.comm.stub/marigold.comm.stub
                    {:local/root stub-dir
                     :exclusions '[io.github.slagyr/isaac-foundation]}}
                   (first @calls)))))

    ;; Phase 5 of the berth epic (isaac-8v1n): route registration moved
    ;; out of activate! entirely. The :isaac.server/route berth flows
    ;; through process-manifest-berths! (covered in berths-spec), and
    ;; the per-entry factory (isaac.server.routes/register-route-entry!)
    ;; is a thin shim around register-route!. The activate!-side tests
    ;; that lived here are gone with the dispatch they tested.

    ;; activate! used to register manifest :cli entries via
    ;; register-cli-extension!. Phase 4 of the berth epic moved :cli
    ;; into the berth pass (process-manifest-berths!), so the
    ;; activate!-side handling is gone. Coverage for the new path
    ;; lives in berths-spec.

    (it "does not add the same local/root deps twice across activation resets"
      (let [stub-dir     (str (System/getProperty "user.dir") "/modules/marigold.comm.stub-cache-test")
            module-index {:marigold.comm.stub {:coord {:local/root stub-dir}
                                                :path  stub-dir
                                                :manifest {:marigold.bridge/comm
                                                           {:stub {:factory 'marigold.comm.stub/make}}}}}
            calls        (atom [])]
        (with-redefs [isaac.module.classpath/invoke-add-deps! (fn [deps-map]
                                                             (swap! calls conj deps-map))]
          (lifecycle/activate! :marigold.comm.stub module-index)
          (lifecycle/clear-activations!)
          (lifecycle/activate! :marigold.comm.stub module-index)
          (should= 1 (count @calls))))))

  (describe "activate-modules!"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (reset! @#'isaac.module.classpath/loaded-module-coords* #{})
        (reset-cli-registry!)
        (lifecycle/clear-activations!)
        (example)
        (reset! @#'isaac.module.classpath/loaded-module-coords* #{})
        (lifecycle/clear-activations!)
        (reset-cli-registry!)))

    (it "activates every module in dependency order"
      (log/capture-logs
        (lifecycle/activate-modules! {:marigold.longwave {:manifest {:deps {:marigold.bridge {}}}}
                                :marigold.bridge   {:manifest {}}})
        (let [events (filter #(= :module/activated (:event %)) @log/captured-logs)]
          (should= 2 (count events))
          (should= ["marigold.bridge" "marigold.longwave"]
                   (mapv :module events)))))))
