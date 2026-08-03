(ns isaac.module.coords-spec
  (:require
    [isaac.module.coords :as coords]
    [speclj.core :refer :all]))

(describe "isaac.module.coords"

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
    (should= 'io.github.slagyr/isaac-server (coords/->lib-sym :io.github.slagyr/isaac-server)))

  (it "maps split-repo isaac.* ids to io.github.slagyr/isaac-* libs"
    (should= 'io.github.slagyr/isaac-server (coords/split-repo-lib-sym :isaac.server))
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
