(ns isaac.config.cli.keys-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.config.marigold :as config-marigold]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private test-home "/test/config-keys")
(def ^:private test-root (str test-home "/.isaac"))

(defn- write-config! [path data]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str data))))

(describe "CLI Config keys"

  (config-marigold/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "lists root keys when no path is given"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults  {:crew :main :model :llama}
                    :crew      {:main {}}
                    :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
                    :providers {:anthropic {}}})
    (should= 0 (sut/run {:root test-root} ["keys"]))
    (let [out (str *out*)]
      (should-contain "defaults" out)
      (should-contain "crew" out)
      (should-contain "models" out)
      (should-contain "providers" out)
      (should-not-contain "llama3.3" out)))

  (it "still lists bare key names at a nested path"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults  {:crew :main :model :llama}
                    :crew      {:main {} :backup {}}
                    :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
                    :providers {:anthropic {}}})
    (should= 0 (sut/run {:root test-root} ["keys" "crew"]))
    (let [out (str *out*)]
      (should-contain "main" out)
      (should-contain "backup" out)
      (should-not-contain "llama3.3" out)))

  (it "help documents optional config path"
    (should= 0 (sut/run {:root test-root} ["keys" "--help"]))
    (should-contain "keys [config-path]" (str *out*))))
