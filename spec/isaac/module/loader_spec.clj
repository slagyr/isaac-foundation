(ns isaac.module.loader-spec
  (:require
    [isaac.fs :as fs]
    [isaac.module.fixtures :refer [ctx mod-coord mod-root
                                   valid-comm-manifest write-local-module!]]
    [isaac.module.classpath :as classpath]
    [isaac.module.discovery :as discovery]
    [isaac.module.loader :as loader]
    [isaac.module.versions :as versions]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "compose-config-modules!"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (reset! @#'isaac.module.classpath/loaded-module-coords* #{})
      (example)
      (reset! @#'isaac.module.classpath/loaded-module-coords* #{})))

  (it "resolves all configured modules in one add-deps pass"
    (write-local-module! :mod.a {:id :mod.a :version "1"})
    (write-local-module! :mod.b {:id :mod.b :version "1"})
    (let [calls (atom [])
          mod-a (mod-coord :mod.a)
          mod-b (mod-coord :mod.b)]
      (with-redefs [isaac.module.classpath/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (loader/compose-config-modules! {:modules {:mod.a mod-a :mod.b mod-b}})
        (should= 1 (count @calls))
        (should= #{'mod.a/mod.a 'mod.b/mod.b}
                  (set (keys (first @calls)))))))

  (it "excludes seed foundation from every module coordinate"
    (write-local-module! :isaac.comm.pigeon valid-comm-manifest)
    (let [calls (atom [])]
      (with-redefs [isaac.module.classpath/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (loader/compose-config-modules! {:modules {:isaac.comm.pigeon (mod-coord :isaac.comm.pigeon)}})
        (should= '[io.github.slagyr/isaac-foundation]
                  (:exclusions (get (first @calls) 'isaac.comm.pigeon/isaac.comm.pigeon))))))

  (it "excludes split-repo lib aliases for sibling modules in one batch"
    (let [calls (atom [])
          server-coord {:git/url "https://github.com/slagyr/isaac-server.git"
                        :git/sha "ba30caa2c2dc4564a352ae82742d39739fad9744"}
          acp-coord {:git/url "https://github.com/slagyr/isaac-acp.git"
                     :git/sha "d10856296e9b35378c3dfd009e67a50fad2f25af"}]
      (with-redefs [isaac.module.classpath/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (loader/compose-config-modules! {:modules {:isaac.server server-coord
                                                :isaac.comm.acp acp-coord}})
        (should= 1 (count @calls))
        (let [acp-exclusions (:exclusions (get (first @calls) 'isaac.comm.acp/isaac.comm.acp))]
          (should-contain 'io.github.slagyr/isaac-server acp-exclusions)
          (should-contain 'isaac.server/isaac.server acp-exclusions)))))

  (it "compose-module-deps-map returns the same deps map add-modules-deps! would pass to invoke-add-deps!"
    (write-local-module! :isaac.comm.pigeon valid-comm-manifest)
    (let [coord (mod-coord :isaac.comm.pigeon)
          pairs [[:isaac.comm.pigeon coord]]
          calls (atom [])]
      (with-redefs [isaac.module.classpath/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (loader/compose-config-modules! {:modules {:isaac.comm.pigeon coord}})
        (should= (first @calls) (classpath/compose-module-deps-map pairs)))))

  (it "warms git coords even when *resolve-classpath?* is false"
    (let [calls (atom [])
          coord {:git/url "https://github.com/slagyr/isaac-agent.git"
                 :git/sha "b44a660aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
      (with-redefs [isaac.module.classpath/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (binding [classpath/*resolve-classpath?* false]
          (loader/warm-module-checkouts! {:modules {:isaac.agent coord}} "/workspace"))
        (should= 1 (count @calls))
        (should= coord (dissoc (get (first @calls) 'isaac.agent/isaac.agent) :exclusions))))))
