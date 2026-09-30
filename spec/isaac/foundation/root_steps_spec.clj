(ns isaac.foundation.root-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.root-steps :as sut]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "foundation root steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (nexus/-with-nexus {}
      (it))
    (g/reset!))

  (describe "initialize-root!"

    ;; isaac-600d: a snapshot read no longer registers the config slot, so the
    ;; scenario entry point owns it — as nexus/init! does in production. Without
    ;; it, a config installed inside a nested nexus (with-feature-fs) registers
    ;; the slot only in the nested scope and vanishes when that scope exits.

    (it "registers an empty config slot"
      (sut/initialize-root! "/target/root-steps" true)
      (should (nexus/registered? [:config]))
      (should-be-nil (loader/snapshot "spec")))

    (it "keeps a config installed inside a nested nexus visible after it exits"
      (sut/initialize-root! "/target/root-steps" true)
      (nexus/-with-nested-nexus {:fs (nexus/get :fs)}
        (loader/set-snapshot! {:crew {"main" {}}} "spec"))
      (should= {:crew {"main" {}}} (loader/snapshot "spec")))

    ;; isaac.foundation.config.schema-compose caches the composed root schema in its own
    ;; process-global atom, independent of discovery's foundation/builtin
    ;; index caches (which lifecycle/clear-activations! already drops). Left
    ;; unreset, a schema composed from one scenario's module-index leaks into
    ;; a later scenario for the rest of the shared JVM process.
    (it "drops the composed root-schema cache"
      (reset! @#'schema-compose/last-composed* {:schema :leaked})
      (reset! @#'schema-compose/last-descriptors* {:descriptor :leaked})
      (sut/initialize-root! "/target/root-steps" true)
      (should-be-nil @@#'schema-compose/last-composed*)
      (should-be-nil @@#'schema-compose/last-descriptors*))))
