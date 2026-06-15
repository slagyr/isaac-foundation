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
      (should-contain "Crew member configurations" output)
      (should-contain "Default crew and model selections" output)))

  (it "resolves .value paths through a collection map's value-spec"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "providers.value.api-key"])))]
      (should-contain "string" output)
      (should-contain "API key" output)
      (should-contain "providers.value.api-key" output)))

  (it "resolves .key paths to the collection map's key-spec"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "providers.key"])))]
      (should-contain "string" output)
      (should-contain "providers.key" output)))

  (it "returns 1 for an unknown schema path"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["schema" "crew.nope"])))
      (should-contain "Path not found in config schema: crew.nope" (str err))))

  (it "renders manifest-supplied comm fields with provenance prefix in the description"
    (write-config! {:modules {:marigold.comm.parlor {:local/root (str fixture-modules-root "/marigold.comm.parlor")}}})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "comms.value.loft"]))) ]
      (should-contain ":loft" output)
      (should-contain "[parlor]" output)
      (should-contain "string" output)
      (should-contain "comms.value.loft" output)
      (should-not-contain "[parlor] loft" output)))

  (it "renders the statically-declared tool config fields"
    (write-config! {})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "tools.web_search.api-key"]))) ]
      (should-contain "string" output)
      (should-contain "tools.web_search.api-key" output)))

  (it "lists manifest-backed comm variants in the aggregate comm schema view"
    (write-config! {:modules {:marigold.comm.parlor {:local/root (str fixture-modules-root "/marigold.comm.parlor")}}})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "comms.value"]))) ]
      (should-contain ":crew" output)
      (should-contain ":type" output)
      (should-contain ":loft" output)
      (should-contain "[parlor]" output)
      (should-not-contain "[parlor] loft" output)
      (should-not-contain (str "type: " marigold/longwave) output)
      (should-not-contain "type: parlor" output)
      (should-not-contain "no manifest fields" output)))

  (it "renders only the base comm schema when no modules are declared"
    (write-config! {})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "comms.value"]))) ]
      (should-contain "crew" output)
      (should-contain "type" output)
      (should-not-contain "[parlor]" output)))

  (it "returns 1 for a manifest-supplied comm field when its module is not declared"
    (write-config! {})
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["schema" "comms.value.loft"])))
      (should-contain "Path not found in config schema: comms.value.loft" (str err))))

  (it "renders manifest-supplied provider fields with provenance prefix"
    (write-config! {:modules {:marigold.providers.fizz {:local/root (str fixture-modules-root "/marigold.providers.fizz")}}})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "providers.value.fizz-level"]))) ]
      (should-contain "[fizz]" output)
      (should-contain "int" output)
      (should-contain "providers.value.fizz-level" output)))

  (it "renders the statically-declared tool config fields"
    (write-config! {})
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["schema" "tools.web_search.provider"]))) ]
      (should-contain "keyword" output)
      (should-contain "tools.web_search.provider" output))))