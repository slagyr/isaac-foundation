(ns isaac.module.versions-spec
  (:require
    [isaac.fs :as fs]
    [isaac.module.fixtures :refer [ctx mod-root write-local-module!]]
    [isaac.module.versions :as versions]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "module version divergences"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "reports explicit configured version as the chosen conflict winner"
    (write-local-module! :mod.shared.old {:id :mod.shared :version "0.1.0"})
    (write-local-module! :mod.shared {:id :mod.shared :version "0.2.0"})
    (with-redefs [isaac.module.versions/collect-module-version-requests
                  (fn [_ _]
                    [{:module-id :mod.shared
                      :version "0.1.0"
                      :required-by :mod.consumer
                      :coord {:local/root (mod-root :mod.shared.old)}}])]
      (should= {:conflicts []
                 :drift [{:id :mod.shared
                          :chosen "0.2.0"
                          :requested [{:version "0.1.0" :required-by [:mod.consumer]}]}]}
               (#'isaac.module.versions/module-version-divergences
                {:mod.consumer {:local/root (mod-root :mod.consumer)}
                 :mod.shared   {:local/root (mod-root :mod.shared)}}
                ctx))))

  (it "does not record chosen-version duplicates as drift"
    (write-local-module! :mod.shared.match {:id :mod.shared :version "0.2.0"})
    (write-local-module! :mod.shared {:id :mod.shared :version "0.2.0"})
    (with-redefs [isaac.module.versions/collect-module-version-requests
                  (fn [_ _]
                    [{:module-id :mod.shared
                      :version "0.2.0"
                      :required-by :mod.consumer
                      :coord {:local/root (mod-root :mod.shared.match)}}])]
      (should= {:conflicts [] :drift []}
               (#'isaac.module.versions/module-version-divergences
                {:mod.consumer {:local/root (mod-root :mod.consumer)}
                 :mod.shared   {:local/root (mod-root :mod.shared)}}
                ctx))))

  (it "classifies newer-than-chosen requests as conflicts and older ones as drift"
    (write-local-module! :mod.shared.base {:id :mod.shared :version "0.2.0"})
    (write-local-module! :mod.shared.old {:id :mod.shared :version "0.1.0"})
    (write-local-module! :mod.shared.new {:id :mod.shared :version "0.3.0"})
    (with-redefs [isaac.module.versions/collect-module-version-requests
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
               (#'isaac.module.versions/module-version-divergences
                {:mod.shared {:local/root (mod-root :mod.shared.base)}
                 :mod.consumer.old {:local/root (mod-root :mod.consumer.old)}
                 :mod.consumer.new {:local/root (mod-root :mod.consumer.new)}}
                ctx)))))
