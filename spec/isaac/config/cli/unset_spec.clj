(ns isaac.config.cli.unset-spec
  (:require
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.config.cli.mutate-common :as mutate-common]
    [isaac.marigold :as marigold]
    [isaac.config.mutate :as mutate]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(def ^:private test-home "/test/config-unset")
(def ^:private test-root (str test-home "/.isaac"))

(describe "CLI Config unset"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "prints help and returns 0 with unset --help"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["unset" "--help"])))]
      (should-contain "Usage: isaac config" output)))

  (it "returns 1 when unset is missing a path"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["unset"])))
      (should-contain "missing path" (str err))))

  (it "treats trailing tokens after the path as arguments, not help options"
    (let [captured (atom nil)]
      (with-redefs [mutate/unset-config (fn [_home path & _opts]
                                          (reset! captured path)
                                          {:status :ok :warnings [] :file "isaac.edn"})]
        (should= 0 (sut/run {:root test-root} ["unset" (str "berths." marigold/first-mate ".ledger") "--help"])))
      (should= (str "berths." marigold/first-mate ".ledger") @captured)))

  (it "passes a set member separately from the field path"
    (let [captured (atom nil)]
      (with-redefs [mutate-common/unset-config! (fn [& args]
                                                   (reset! captured args)
                                                   0)]
        (should= 0 (sut/run {:root test-root} ["unset" "relay.r1.flags" "wip"])))
      (should= [{:root test-root} "relay.r1.flags" {} "wip"] @captured))))