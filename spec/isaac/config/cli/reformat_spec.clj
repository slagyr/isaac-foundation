(ns isaac.config.cli.reformat-spec
  (:require
    [isaac.config.cli.command :as sut]
    [isaac.config.cli.spec-support :as support]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private root "/test/config-reformat/.isaac")

(defn- write! [relative content]
  (let [path (str root "/config/" relative)
        fs*  (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path content)))

(describe "CLI Config reformat"
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example] (support/with-cli-env example))

  (it "pretty-prints every active EDN config file and leaves backups alone"
    (write! "isaac.edn" "{:defaults {:crew :main :model :gpt}}")
    (write! "crew/marvin.edn" "{:model :gpt :conversation :episodes}")
    (write! "crew.bak-20260903/marvin.edn" "{:model :gpt}")
    (should= 0 (sut/run {:root root} ["reformat"]))
    (should= "{\n  :defaults {:crew :main :model :gpt}\n}\n"
             (fs/slurp (nexus/get :fs) (str root "/config/isaac.edn")))
    (should= "{:conversation :episodes :model :gpt}\n"
             (fs/slurp (nexus/get :fs) (str root "/config/crew/marvin.edn")))
    (should= "{:model :gpt}"
             (fs/slurp (nexus/get :fs) (str root "/config/crew.bak-20260903/marvin.edn")))))
