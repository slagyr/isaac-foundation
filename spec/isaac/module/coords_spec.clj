(ns isaac.module.coords-spec
  (:require
    [clojure.tools.gitlibs :as gitlibs]
    [isaac.cli.host :as host]
    [isaac.module.coords :as coords]
    [speclj.core :refer :all]))

(describe "isaac.module.coords"

  (it "looks for cached gitlibs under the initialized tools.deps cache directory"
    (with-redefs [gitlibs/cache-dir (constantly "/tmp/fixture-gitlibs")]
      (should= "/tmp/fixture-gitlibs/libs" (coords/gitlibs-root))))

  (it "uses the initialized tools.deps gitlibs cache even if the property changes later"
    (with-redefs [gitlibs/cache-dir (constantly "/tmp/initialized-gitlibs")]
      (should= "/tmp/initialized-gitlibs/libs" (coords/gitlibs-root))))

  (it "uses the configured property ahead of the GITLIBS environment override"
    (let [host (host/embedded-host {:env {"GITLIBS" "/tmp/host-gitlibs"}})]
      (with-redefs [requiring-resolve (constantly nil)]
        (binding [host/*host* host]
          (should= (str (System/getProperty "clojure.gitlibs.dir") "/libs")
                   (coords/gitlibs-root))))))

  (it "uses the active CLI host's GITLIBS override when no property is configured"
    (let [host (host/embedded-host {:env {"GITLIBS" "/tmp/host-gitlibs"}})
          prior (System/getProperty "clojure.gitlibs.dir")]
      (try
        (System/clearProperty "clojure.gitlibs.dir")
        (with-redefs [requiring-resolve (constantly nil)]
          (binding [host/*host* host]
            (should= "/tmp/host-gitlibs/libs" (coords/gitlibs-root))))
        (finally
          (when prior (System/setProperty "clojure.gitlibs.dir" prior))))))

  (it "coerces raw ids to keywords"
    (should= :mod.a (coords/->module-id :mod.a))
    (should= :mod.a (coords/->module-id 'mod.a))
    (should= :mod.a (coords/->module-id "mod.a"))
    (should-be-nil (coords/->module-id 42)))

  (it "formats ids without the leading colon"
    (should= "mod.a" (coords/id-str :mod.a))
    (should= "mod.a" (coords/id-str 'mod.a))
    (should= "mod.a" (coords/id-str "mod.a")))

  (it "builds tools.deps lib symbols from module ids"
    (should= 'mod.a/mod.a (coords/->lib-sym :mod.a))
    (should= 'io.github.slagyr/isaac-http (coords/->lib-sym :io.github.slagyr/isaac-http)))

  (it "maps split-repo isaac.* ids to io.github.slagyr/isaac-* libs"
    (should= 'io.github.slagyr/isaac-http (coords/split-repo-lib-sym :isaac.http))
    (should= 'io.github.slagyr/isaac-acp (coords/split-repo-lib-sym :isaac.comm.acp))
    (should-be-nil (coords/split-repo-lib-sym :mod.a)))

  (it "accepts local/root, mvn, and git coordinate shapes"
    (should (coords/valid-module-coord? {:local/root "/tmp/mod"}))
    (should (coords/valid-module-coord? {:mvn/version "1.0.0"}))
    (should (coords/valid-module-coord? {:git/url "https://example.com/x.git" :git/sha "abc"}))
    (should-not (coords/valid-module-coord? {}))
    (should-not (coords/valid-module-coord? "not-a-map")))

  (it "builds modules[...] and module-index[...] error keys"
    (should= "modules[\"mod.a\"]" (coords/mod-error-key :mod.a))
    (should= "module-index[\"mod.a\"].version" (coords/manifest-error-key :mod.a :version))))
