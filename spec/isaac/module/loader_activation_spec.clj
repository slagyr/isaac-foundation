(ns isaac.module.loader-activation-spec
  (:require
    [c3kit.apron.env :as c3env]
    [isaac.cli.registry :as cli-registry]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.loader :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- reset-cli-registry! []
  (cli-registry/clear-module-commands!))

(describe "module loader activation"

  (describe "clear-activations!"

    (around [example]
      (let [handlers* @#'isaac.module.loader/handlers*
            handlers  @handlers*]
        (reset! handlers* {})
        (try
          (example)
          (finally
            (reset! handlers* handlers)))))

    (it "invokes registered clear-registration handlers"
      (let [calls (atom [])]
        (sut/register-handler! :clear-registrations #(swap! calls conj :api))
        (sut/register-handler! :clear-registrations #(swap! calls conj :commands))
        (sut/clear-activations!)
        (should= [:api :commands] @calls)))

    (it "does nothing when no clear-registration handlers are registered"
      (should-not-throw
        (sut/clear-activations!))))

  (describe "activate!"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (reset! @#'isaac.module.loader/loaded-module-coords* #{})
        (reset-cli-registry!)
        (sut/clear-activations!)
        (reset! c3env/-overrides {})
        (example)
        (reset! @#'isaac.module.loader/loaded-module-coords* #{})
        (reset! c3env/-overrides {})
        (sut/clear-activations!)
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
          (sut/activate! :marigold.comm.stub module-index)
          (sut/activate! :marigold.comm.stub module-index)
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
                        (sut/activate! :marigold.comm.stub module-index)
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
        (with-redefs [isaac.module.loader/invoke-add-deps! (fn [deps-map]
                                                             (swap! calls conj deps-map))]
          (sut/activate! :marigold.comm.stub module-index)
          (should= {'marigold.comm.stub/marigold.comm.stub
                    {:local/root stub-dir
                     :exclusions '[io.github.slagyr/isaac-foundation]}}
                   (first @calls)))))

    ;; Phase 5 of the berth epic (isaac-8v1n): route registration moved
    ;; out of activate! entirely. The :isaac.server/route berth flows
    ;; through process-manifest-berths! (covered in
    ;; loader-berths-spec), and the per-entry factory
    ;; (isaac.server.routes/register-route-entry!) is a thin shim around
    ;; register-route!. The activate!-side tests that lived here are
    ;; gone with the dispatch they tested.

    ;; activate! used to register manifest :cli entries via
    ;; register-cli-extension!. Phase 4 of the berth epic moved :cli
    ;; into the berth pass (process-manifest-berths!), so the
    ;; activate!-side handling is gone. Coverage for the new path
    ;; lives in loader-berths-spec.

    (it "does not add the same local/root deps twice across activation resets"
      (let [stub-dir     (str (System/getProperty "user.dir") "/modules/marigold.comm.stub-cache-test")
            module-index {:marigold.comm.stub {:coord {:local/root stub-dir}
                                                :path  stub-dir
                                                :manifest {:marigold.bridge/comm
                                                           {:stub {:factory 'marigold.comm.stub/make}}}}}
            calls        (atom [])]
        (with-redefs [isaac.module.loader/invoke-add-deps! (fn [deps-map]
                                                             (swap! calls conj deps-map))]
          (sut/activate! :marigold.comm.stub module-index)
          (sut/clear-activations!)
          (sut/activate! :marigold.comm.stub module-index)
          (should= 1 (count @calls))))))

  (describe "activate-modules!"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (reset! @#'isaac.module.loader/loaded-module-coords* #{})
        (reset-cli-registry!)
        (sut/clear-activations!)
        (example)
        (reset! @#'isaac.module.loader/loaded-module-coords* #{})
        (sut/clear-activations!)
        (reset-cli-registry!)))

    (it "activates every module in dependency order"
      (log/capture-logs
        (sut/activate-modules! {:marigold.longwave {:manifest {:deps {:marigold.bridge {}}}}
                                :marigold.bridge   {:manifest {}}})
        (let [events (filter #(= :module/activated (:event %)) @log/captured-logs)]
          (should= 2 (count events))
          (should= ["marigold.bridge" "marigold.longwave"]
                   (mapv :module events)))))))
