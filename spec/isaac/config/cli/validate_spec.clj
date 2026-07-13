(ns isaac.config.cli.validate-spec
  (:require
     [isaac.config.cli.command :as sut]
     [isaac.config.cli.spec-support :as support]
     [isaac.config.marigold :as config-marigold]
     [isaac.fs :as fs]
     [isaac.marigold :as marigold]
     [isaac.nexus :as nexus]
     [speclj.core :refer :all])
  (:import (java.io BufferedReader StringReader)))

(def ^:private test-home "/test/config-validate")
(def ^:private test-root (str test-home "/.isaac"))
(def ^:private test-berth (keyword marigold/captain))

(defn- write-config! [path data]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path (pr-str data))))

(describe "CLI Config validate"

  (config-marigold/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "fails clearly when no config exists"
    (should= 1 (sut/run {:root test-root} ["validate"]))
    (should-contain "no config found" (str *err*)))

  (it "prints OK and returns 0 when validation passes"
    (write-config! (str test-root "/config/isaac.edn")
                   {:watch     {:berth test-berth :gauge :llama}
                    :berths    {test-berth {:ledger "You are Atticus."}}
                    :gauges    {:llama {:reading "llama3.3:1b" :foundry :anthropic}}
                    :foundries {:anthropic {}}})
    (should= 0 (sut/run {:root test-root} ["validate"]))
    (should-contain "OK" (str *out*)))

  (it "returns 1 and prints errors when validation fails"
    (write-config! (str test-root "/config/isaac.edn")
                   {:watch {:berth :ghost :gauge :llama}})
    (should= 1 (sut/run {:root test-root} ["validate"]))
    (should-contain "watch.berth" (str *err*)))

  (it "overlays stdin content at a data path when validating"
    (write-config! (str test-root "/config/isaac.edn")
                   {:watch     {:berth test-berth :gauge :llama}
                    :berths    {}
                    :gauges    {:llama {:reading "llama3.3:1b" :foundry :anthropic}}
                    :foundries {:anthropic {}}})
    (binding [*in* (BufferedReader. (StringReader. "{:ledger \"You are Atticus.\"}"))]
      (let [result (sut/run {:root test-root} ["validate" "--as" (str "berths." marigold/captain) "-"])]
        (should= 0 result))
      (should-contain "OK" (str *out*))))

  (it "validate --json still emits structured warnings on success"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults     {:crew :main :model :llama}
                    :crew         {:main {}}
                    :models       {:llama {:model "llama3.3:1b" :provider :anthropic}}
                    :providers    {:anthropic {}}
                    :experimental {:feature-flag true}})
    (should= 0 (sut/run {:root test-root} ["validate" "--json"]))
    (should-contain "\"warnings\"" (str *out*))
    (should-contain "\"ok\"" (str *out*))))