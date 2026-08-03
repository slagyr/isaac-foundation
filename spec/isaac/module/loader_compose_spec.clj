(ns isaac.module.loader-compose-spec
  (:require
    [isaac.fs :as fs]
    [isaac.module.loader-fixtures :refer [ctx mod-coord mod-root
                                          valid-comm-manifest write-local-module!]]
    [isaac.module.loader :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "compose-config-modules!"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (reset! @#'isaac.module.loader/loaded-module-coords* #{})
      (example)
      (reset! @#'isaac.module.loader/loaded-module-coords* #{})))

  (it "resolves all configured modules in one add-deps pass"
    (write-local-module! :mod.a {:id :mod.a :version "1"})
    (write-local-module! :mod.b {:id :mod.b :version "1"})
    (let [calls (atom [])
          mod-a (mod-coord :mod.a)
          mod-b (mod-coord :mod.b)]
      (with-redefs [isaac.module.loader/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (sut/compose-config-modules! {:modules {:mod.a mod-a :mod.b mod-b}})
        (should= 1 (count @calls))
        (should= #{'mod.a/mod.a 'mod.b/mod.b}
                  (set (keys (first @calls)))))))

  (it "reports explicit configured version as the chosen conflict winner"
    (write-local-module! :mod.shared.old {:id :mod.shared :version "0.1.0"})
    (write-local-module! :mod.shared {:id :mod.shared :version "0.2.0"})
    (with-redefs [isaac.module.loader/collect-module-version-requests
                  (fn [_ _]
                    [{:module-id :mod.shared
                      :version "0.1.0"
                      :required-by :mod.consumer
                      :coord {:local/root (mod-root :mod.shared.old)}}])]
      (should= {:conflicts []
                 :drift [{:id :mod.shared
                          :chosen "0.2.0"
                          :requested [{:version "0.1.0" :required-by [:mod.consumer]}]}]}
               (#'isaac.module.loader/module-version-divergences
                {:mod.consumer {:local/root (mod-root :mod.consumer)}
                 :mod.shared   {:local/root (mod-root :mod.shared)}}
                ctx))))

  (it "does not record chosen-version duplicates as drift"
    (write-local-module! :mod.shared.match {:id :mod.shared :version "0.2.0"})
    (write-local-module! :mod.shared {:id :mod.shared :version "0.2.0"})
    (with-redefs [isaac.module.loader/collect-module-version-requests
                  (fn [_ _]
                    [{:module-id :mod.shared
                      :version "0.2.0"
                      :required-by :mod.consumer
                      :coord {:local/root (mod-root :mod.shared.match)}}])]
      (should= {:conflicts [] :drift []}
               (#'isaac.module.loader/module-version-divergences
                {:mod.consumer {:local/root (mod-root :mod.consumer)}
                 :mod.shared   {:local/root (mod-root :mod.shared)}}
                ctx))))

  (it "classifies newer-than-chosen requests as conflicts and older ones as drift"
    (write-local-module! :mod.shared.base {:id :mod.shared :version "0.2.0"})
    (write-local-module! :mod.shared.old {:id :mod.shared :version "0.1.0"})
    (write-local-module! :mod.shared.new {:id :mod.shared :version "0.3.0"})
    (with-redefs [isaac.module.loader/collect-module-version-requests
                  (fn [_ _]
                    [{:module-id :mod.shared
                      :version "0.1.0"
                      :required-by :mod.consumer.old
                      :coord {:local/root (mod-root :mod.shared.old)}}
                     {:module-id :mod.shared
                      :version "0.3.0"
                      :required-by :mod.consumer.new
                      :coord {:local/root (mod-root :mod.shared.new)}}])]
      (should= {:conflicts [{:id :mod.shared
                             :chosen "0.2.0"
                             :requested [{:version "0.3.0" :required-by [:mod.consumer.new]}]}]
                 :drift [{:id :mod.shared
                          :chosen "0.2.0"
                          :requested [{:version "0.1.0" :required-by [:mod.consumer.old]}]}]}
               (#'isaac.module.loader/module-version-divergences
                {:mod.shared {:local/root (mod-root :mod.shared.base)}
                 :mod.consumer.old {:local/root (mod-root :mod.consumer.old)}
                 :mod.consumer.new {:local/root (mod-root :mod.consumer.new)}}
                ctx))))

  (it "excludes seed foundation from every module coordinate"
    (write-local-module! :isaac.comm.pigeon valid-comm-manifest)
    (let [calls (atom [])]
      (with-redefs [isaac.module.loader/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (sut/compose-config-modules! {:modules {:isaac.comm.pigeon (mod-coord :isaac.comm.pigeon)}})
        (should= '[io.github.slagyr/isaac-foundation]
                  (:exclusions (get (first @calls) 'isaac.comm.pigeon/isaac.comm.pigeon))))))

  (it "excludes split-repo lib aliases for sibling modules in one batch"
    (let [calls (atom [])
          server-coord {:git/url "https://github.com/slagyr/isaac-server.git"
                        :git/sha "ba30caa2c2dc4564a352ae82742d39739fad9744"}
          acp-coord {:git/url "https://github.com/slagyr/isaac-acp.git"
                     :git/sha "d10856296e9b35378c3dfd009e67a50fad2f25af"}]
      (with-redefs [isaac.module.loader/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (sut/compose-config-modules! {:modules {:isaac.server server-coord
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
      (with-redefs [isaac.module.loader/invoke-add-deps! (fn [deps-map] (swap! calls conj deps-map))]
        (sut/compose-config-modules! {:modules {:isaac.comm.pigeon coord}})
        (should= (first @calls) (sut/compose-module-deps-map pairs))))))
