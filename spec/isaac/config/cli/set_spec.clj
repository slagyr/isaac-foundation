(ns isaac.config.cli.set-spec
  (:require
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.config.marigold :as config-marigold]
    [isaac.marigold :as marigold]
    [isaac.config.mutate :as mutate]

    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(def ^:private test-home "/test/config-set")
(def ^:private test-root (str test-home "/.isaac"))

(describe "CLI Config set"

  (config-marigold/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "prints help and returns 0 with set --help"
    (let [output (with-out-str (should= 0 (sut/run {:root test-root} ["set" "--help"])))]
      (should-contain "Usage: isaac config" output)))

  (it "returns 1 when set is missing a path"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["set"])))
      (should-contain "missing path" (str err))))

  (it "returns 1 when set is missing a value"
    (let [err (StringWriter.)]
      (binding [*err* err]
        (should= 1 (sut/run {:root test-root} ["set" "watch.berth"])))
      (should-contain "missing value" (str err))))

  (it "treats a hyphen-prefixed token as the set value after the path"
    (let [captured (atom nil)]
      (with-redefs [mutate/set-config (fn [_home path value & _]
                                        (reset! captured [path value])
                                        {:status :ok :warnings [] :file "isaac.edn"})]
        (should= 0 (sut/run {:root test-root} ["set" (str "berths." marigold/first-mate ".ledger") "--raw"])))
      (should= [(str "berths." marigold/first-mate ".ledger") "--raw"] @captured)))

  (it "set --json emits structured mutation result (isaac-0jse)"
    (config-marigold/write-baseline!)
    (config-marigold/write-berth! marigold/first-mate {:gauge marigold/helm-mark-iii} :ledger "Old ledger.")
    (let [path (str "berths." marigold/first-mate ".ledger")]
      (should= 0 (sut/run {:root marigold/root} ["set" path "New ledger." "--json"]))
      (should-contain "\"ok\"" (str *out*))
      (should-contain path (str *out*)))))

