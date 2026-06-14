(ns isaac.module.layout-spec
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [speclj.core :refer :all]))

(describe "module source layout"

  (it "keeps greeter source under its module directory"
    (let [module-source (io/file "modules/marigold.cli.greeter/src/marigold/cli/greeter.clj")
          plugin-source (io/file "plugins/isaac/cli/greeter.clj")]
      (should (.exists module-source))
      (should-not (.exists plugin-source))))

  (it "names every checked-in fixture module marigold.*"
    (doseq [^java.io.File dir (.listFiles (io/file "modules"))
            :when            (.isDirectory dir)
            :let              [name (.getName dir)]]
      (should (str/starts-with? name "marigold."))))

  (it "stores module manifests and ships deps.edn"
    (doseq [module-dir ["modules/marigold.cli.greeter"
                        "modules/marigold.bridge"
                        "modules/marigold.longwave"]]
      (should (.exists (io/file module-dir "deps.edn")))
      (should (.exists (io/file module-dir "resources/isaac-manifest.edn"))))
    (should (.exists (io/file "modules/marigold.comm.noop/src/isaac-manifest.edn")))))