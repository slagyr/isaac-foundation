(ns isaac.module.classpath-spec
  (:require
    [isaac.module.classpath :as classpath]
    [isaac.module.coords :as coords]
    [speclj.core :refer :all]))

(describe "isaac.module.classpath"

  (it "always excludes the seed foundation lib from classpath coords"
    (should= {:local/root "/tmp/m" :exclusions [coords/seed-foundation-lib]}
             (classpath/classpath-coord {:local/root "/tmp/m"}))
    (should= #{coords/seed-foundation-lib 'other/lib}
             (set (:exclusions (classpath/classpath-coord {:local/root "/tmp/m"
                                                           :exclusions ['other/lib]})))))

  (it "compose-module-deps-map adds seed + sibling exclusions per module"
    (let [server-coord {:git/url "https://github.com/slagyr/isaac-server.git" :git/sha "abc"}
          acp-coord    {:git/url "https://github.com/slagyr/isaac-acp.git" :git/sha "def"}
          deps         (classpath/compose-module-deps-map
                         [[:isaac.server server-coord]
                          [:isaac.comm.acp acp-coord]])
          acp-ex       (set (:exclusions (get deps 'isaac.comm.acp/isaac.comm.acp)))
          server-ex    (set (:exclusions (get deps 'isaac.server/isaac.server)))]
      (should-contain coords/seed-foundation-lib acp-ex)
      (should-contain 'io.github.slagyr/isaac-server acp-ex)
      (should-contain 'isaac.server/isaac.server acp-ex)
      (should-contain coords/seed-foundation-lib server-ex)
      (should-contain 'io.github.slagyr/isaac-acp server-ex)
      (should-contain 'isaac.comm.acp/isaac.comm.acp server-ex)))

  (it "dedupe-module-pairs keeps one preferred coord per module id"
    (let [a {:local/root "/a"}
          b {:local/root "/b"}
          pairs [[:mod.x b] [:mod.x a] [:mod.y a]]]
      (should= [[:mod.x a] [:mod.y a]]
               (classpath/dedupe-module-pairs pairs))))

  (it "without-preload-planned! binds *skip-preload-planned?* true while body runs"
    (let [seen (atom nil)]
      (classpath/without-preload-planned!
        (fn [] (reset! seen classpath/*skip-preload-planned?*)))
      (should @seen)
      (should-not classpath/*skip-preload-planned?*))))
