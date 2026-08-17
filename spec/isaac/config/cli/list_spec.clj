(ns isaac.config.cli.list-spec
  (:require
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.config.marigold :as config-marigold]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private test-home "/test/config-list")
(def ^:private test-root (str test-home "/.isaac"))

(defn- write-config! [path data]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str data))))

(describe "CLI Config list"

  (config-marigold/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env example))

  (it "lists root keys with sources when no path is given"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults  {:crew :main :model :llama}
                    :crew      {:main {}}
                    :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
                    :providers {:anthropic {}}})
    (should= 0 (sut/run {:root test-root} ["list"]))
    (let [out (str *out*)]
      (should-contain "defaults" out)
      (should-contain "config/isaac.edn" out)
      (should-not-contain "llama3.3" out)))

  (it "still lists nested keys with their source files"
    (write-config! (str test-root "/config/isaac.edn")
                   {:defaults  {:crew :main :model :llama}
                    :crew      {:main {} :backup {}}
                    :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
                    :providers {:anthropic {}}})
    (should= 0 (sut/run {:root test-root} ["list" "crew"]))
    (let [out (str *out*)]
      (should-contain "main" out)
      (should-contain "config/isaac.edn" out)
      (should-not-contain "llama3.3" out)))

  (it "help documents optional config path"
    (should= 0 (sut/run {:root test-root} ["list" "--help"]))
    (should-contain "list [config-path]" (str *out*))))
