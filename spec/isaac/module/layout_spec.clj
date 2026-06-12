(ns isaac.module.layout-spec
  (:require
    [clojure.java.io :as io]
    [speclj.core :refer :all]))

(describe "module source layout"

  (it "keeps greeter source under its module directory"
    (let [module-source (io/file "modules/isaac.cli.greeter/src/isaac/cli/greeter.clj")
          plugin-source (io/file "plugins/isaac/cli/greeter.clj")]
      (should (.exists module-source))
      (should-not (.exists plugin-source))))

  (it "stores module manifests under resources and ships deps.edn"
    (doseq [module-dir ["modules/isaac.cli.greeter"]]
      (should (.exists (io/file module-dir "deps.edn")))
      (should (.exists (io/file module-dir "resources/isaac-manifest.edn"))))))
