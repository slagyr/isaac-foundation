(ns isaac.config.cli.schema-spec
  (:require
    [isaac.fs :as fs]
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.config.marigold :as config-marigold]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(def ^:private test-home "/test/config-schema")
(def ^:private test-root (str test-home "/.isaac"))
(def ^:private fixture-modules-root (config-marigold/fixture-modules-root))

(defn- write-config! [config]
  (let [fs* (nexus/get :fs)]
    (config-marigold/install-config-modules! config)
    (fs/mkdirs fs* (str test-root "/config"))
    (fs/spit   fs* (str test-root "/config/isaac.edn") (pr-str config))))

(describe "CLI Config schema"

  (config-marigold/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "prints the root schema when no path is given"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema"])))]
      (should-contain "Berth configurations" output)
      (should-contain "Default berth and gauge on the watch" output)))

  (it "resolves .value paths through a collection map's value-spec"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "foundries.value.api-key"])))]
      (should-contain "string" output)
      (should-contain "API key" output)
      (should-contain "foundries.value.api-key" output)))

  (it "resolves .key paths to the collection map's key-spec"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "foundries.key"])))]
      (should-contain "string" output)
      (should-contain "foundries.key" output)))

  (it "returns 1 for an unknown schema path"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["schema" "berths.nope"])))
      (should-contain "Path not found in config schema: berths.nope" (str err))))

  (it "renders manifest-supplied signal fields with provenance prefix in the description"
    (write-config! {:modules {:marigold.comm.parlor {:local/root (str fixture-modules-root "/marigold.comm.parlor")}}})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "signals.value.loft"]))) ]
      (should-contain ":loft" output)
      (should-contain "[parlor]" output)
      (should-contain "string" output)
      (should-contain "signals.value.loft" output)
      (should-not-contain "[parlor] loft" output)))

  (it "renders the statically-declared kit config fields"
    (write-config! {})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "kit.distant-read.api-key"]))) ]
      (should-contain "string" output)
      (should-contain "kit.distant-read.api-key" output)))

  (it "lists manifest-backed signal variants in the aggregate signal schema view"
    (write-config! {:modules {:marigold.comm.parlor {:local/root (str fixture-modules-root "/marigold.comm.parlor")}}})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "signals.value"]))) ]
      (should-contain ":berth" output)
      (should-contain ":kind" output)
      (should-contain ":loft" output)
      (should-contain "[parlor]" output)
      (should-not-contain "[parlor] loft" output)
      (should-not-contain (str "kind: " marigold/longwave) output)
      (should-not-contain "kind: parlor" output)
      (should-not-contain "no manifest fields" output)))

  (it "renders only the base signal schema when no modules are declared"
    (write-config! {})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "signals.value"]))) ]
      (should-contain "berth" output)
      (should-contain "kind" output)
      (should-not-contain "[parlor]" output)))

  (it "returns 1 for a manifest-supplied signal field when its module is not declared"
    (write-config! {})
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["schema" "signals.value.loft"])))
      (should-contain "Path not found in config schema: signals.value.loft" (str err))))

  (it "renders manifest-supplied foundry fields with provenance prefix"
    (write-config! {:modules {:marigold.providers.fizz {:local/root (str fixture-modules-root "/marigold.providers.fizz")}}})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "foundries.value.fizz-level"]))) ]
      (should-contain "[fizz]" output)
      (should-contain "int" output)
      (should-contain "foundries.value.fizz-level" output)))

  (it "renders the statically-declared kit vendor field"
    (write-config! {})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "kit.distant-read.vendor"]))) ]
      (should-contain "keyword" output)
      (should-contain "kit.distant-read.vendor" output))))