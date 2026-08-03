(ns isaac.module.loader-discovery-spec
  (:require
    [clojure.java.io :as io]
    [isaac.fs :as fs]
    [isaac.module.loader-fixtures :refer [ctx mod-coord mod-deps! mod-dir!
                                          mod-manifest! mod-root
                                          valid-comm-manifest write-local-module!]]
    [isaac.module.manifest]
    [isaac.module.loader :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- local-manifest-path [id]
  (let [root           (mod-root id)
        resources-path (str root "/resources/isaac-manifest.edn")
        src-path       (str root "/src/isaac-manifest.edn")]
    (cond
      (fs/exists? (nexus/get :fs) resources-path) resources-path
      (fs/exists? (nexus/get :fs) src-path) src-path
      :else nil)))

(defn- discover-local! [ids]
  (with-redefs [isaac.module.loader/invoke-add-deps! (fn [_])
                isaac.module.loader/manifest-resource local-manifest-path]
    (sut/discover! {:modules (into {} (map (fn [id] [id (mod-coord id)]) ids))} ctx)))

(defn- fixture-url [path]
  (io/as-url (io/file path)))

(defn- builtin-fixture-resources [real-resource-urls resource-name]
  (concat (real-resource-urls resource-name)
          [(fixture-url "spec/isaac/module/fixtures/builtin/resources/isaac-manifest.edn")
           (fixture-url "spec/isaac/module/fixtures/unflagged/resources/isaac-manifest.edn")]))

(describe "module loader discovery"

  (describe "foundation-index"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example] (nexus/-with-nested-nexus {:fs (fs/mem-fs)} (example)))

    (before
      (sut/clear-caches!))

    (after
      (sut/clear-caches!))

    (it "reads the foundation manifest only once"
      (let [resource-calls (atom 0)
            read-calls     (atom 0)]
        (with-redefs-fn {#'isaac.module.loader/manifest-resource (fn [_]
                                                                   (swap! resource-calls inc)
                                                                   :core-resource)
                         #'isaac.module.manifest/read-manifest    (fn [_ _]
                                                                    (swap! read-calls inc)
                                                                    {:id :isaac.foundation :version "1.0.0"})}
          #(do
             (should= {:isaac.foundation {:coord {}
                                          :manifest {:id :isaac.foundation :version "1.0.0"}
                                          :path nil}}
                      (sut/foundation-index))
             (should= (sut/foundation-index) (sut/foundation-index))))
        (should= 1 @resource-calls)
        (should= 1 @read-calls))))

  (describe "discover!"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (reset! @#'isaac.module.loader/loaded-module-coords* #{})
        (example)
        (reset! @#'isaac.module.loader/loaded-module-coords* #{})))

    (it "includes builtin manifests even when :modules is absent"
      (let [{:keys [index errors]} (sut/discover! {} ctx)]
        (should= [] errors)
        (should= :isaac.foundation (get-in index [:isaac.foundation :manifest :id]))))

    (it "builds an index entry for a valid local module"
      (write-local-module! :isaac.comm.pigeon valid-comm-manifest)
      (let [{:keys [index errors]} (discover-local! [:isaac.comm.pigeon])]
        (should= [] errors)
        (should= :isaac.comm.pigeon (get-in index [:isaac.comm.pigeon :manifest :id]))
        (should= (mod-root :isaac.comm.pigeon) (get-in index [:isaac.comm.pigeon :path]))))

    (it "discovers local/root manifests via classpath loading"
      (let [cwd         (System/getProperty "user.dir")
            module-root "modules/marigold.cli.greeter"
            result      (sut/discover! {:modules {:marigold.cli.greeter {:local/root module-root}}}
                                      (assoc ctx :cwd cwd))]
        (should= [] (:errors result))
        (should= :marigold.cli.greeter (get-in result [:index :marigold.cli.greeter :manifest :id]))
        (should= module-root (get-in result [:index :marigold.cli.greeter :path]))))

    (it "invalidates builtin-index cache when a module dep is dynamically loaded"
      (let [invalidated? (atom false)
            cwd          (System/getProperty "user.dir")]
        (with-redefs [sut/invalidate-builtin-index! (fn [] (reset! invalidated? true))]
          (sut/discover! {:modules {:marigold.cli.greeter {:local/root "modules/marigold.cli.greeter"}}}
                         (assoc ctx :cwd cwd))
          (should @invalidated?))))

    (it "adds an error when a local/root path is not found"
      (let [{:keys [index errors]} (sut/discover! {:modules {:isaac.comm.ghost {:local/root "/state/.isaac/modules/isaac.comm.ghost"}}} ctx)]
        (should= nil (get index :isaac.comm.ghost))
        (should= "modules[\"isaac.comm.ghost\"]" (:key (first errors)))
        (should= "local/root path does not resolve" (:value (first errors)))))

    (it "adds an error when a local/root path has no matching manifest on its classpath"
      (mod-dir! (mod-root :isaac.comm.ghost))
      (mod-deps! (str (mod-root :isaac.comm.ghost) "/deps.edn"))
      (let [{:keys [index errors]} (discover-local! [:isaac.comm.ghost])]
        (should= nil (get index :isaac.comm.ghost))
        (should= "modules[\"isaac.comm.ghost\"]" (:key (first errors)))
        (should= "manifest: could not read" (:value (first errors)))))

    (it "reads a local/root manifest directly when no deps.edn is present"
      (let [root (mod-root :isaac.comm.broken)]
        (mod-dir! root)
        (mod-manifest! (str root "/resources/isaac-manifest.edn") (pr-str {:id :isaac.comm.broken :version "0.1.0"}))
        (let [calls (atom [])]
          (with-redefs [isaac.module.loader/invoke-add-deps! (fn [_])]
            (let [{:keys [index errors]} (sut/discover! {:modules {:isaac.comm.broken {:local/root root}}} ctx)]
              (should= [] errors)
              (should= :isaac.comm.broken (get-in index [:isaac.comm.broken :manifest :id]))
              (should= [] @calls))))))

    (it "uses the installed runtime fs for local manifest discovery"
      (let [mem  (fs/mem-fs)
            root (mod-root :isaac.comm.runtime)]
        (fs/mkdirs mem root)
        (fs/mkdirs mem (str root "/resources"))
        (fs/spit mem (str root "/resources/isaac-manifest.edn") (pr-str {:id :isaac.comm.runtime :version "0.1.0"}))
        (nexus/-with-nexus {:fs mem}
          (let [{:keys [index errors]} (sut/discover! {:modules {:isaac.comm.runtime {:local/root root}}} ctx)]
            (should= [] errors)
            (should= :isaac.comm.runtime (get-in index [:isaac.comm.runtime :manifest :id]))))))

    (it "resolves overlapping explicit and manifest :deps modules in one add-deps pass"
      (write-local-module! :mod.server {:id :mod.server :version "0.1.0"})
      (write-local-module! :mod.client {:id :mod.client :version "0.1.0"
                                        :deps {:mod.server (mod-coord :mod.server)}})
      (let [calls            (atom [])
            classpath-ready? (atom #{})]
        (with-redefs [isaac.module.loader/manifest-resource
                      (fn [id]
                        (when (contains? @classpath-ready? id)
                          (str (mod-root id) "/resources/isaac-manifest.edn")))
                      isaac.module.loader/invoke-add-deps!
                      (fn [deps-map]
                        (swap! calls conj deps-map)
                        (doseq [[lib-sym _] deps-map]
                          (when-let [ns (namespace lib-sym)]
                            (swap! classpath-ready? conj (keyword ns)))))]
          (let [{:keys [errors index]}
                (sut/discover! {:modules {:mod.server (mod-coord :mod.server)
                                          :mod.client (mod-coord :mod.client)}}
                               ctx)]
            (should= [] errors)
            (should= :mod.server (get-in index [:mod.server :manifest :id]))
            (should= :mod.client (get-in index [:mod.client :manifest :id]))
            (should= 1 (count @calls))
            (should= 1 (count (filter #(contains? % 'mod.server/mod.server) @calls)))))))

    (it "adds a manifest-transitive dep once when only the parent is explicit"
      (write-local-module! :mod.server {:id :mod.server :version "0.1.0"})
      (write-local-module! :mod.client {:id :mod.client :version "0.1.0"
                                        :deps {:mod.server (mod-coord :mod.server)}})
      (let [calls            (atom [])
            classpath-ready? (atom #{})]
        (with-redefs [isaac.module.loader/manifest-resource
                      (fn [id]
                        (when (contains? @classpath-ready? id)
                          (str (mod-root id) "/resources/isaac-manifest.edn")))
                      isaac.module.loader/invoke-add-deps!
                      (fn [deps-map]
                        (swap! calls conj deps-map)
                        (doseq [[lib-sym _] deps-map]
                          (when-let [ns (namespace lib-sym)]
                            (swap! classpath-ready? conj (keyword ns)))))]
          (let [{:keys [errors index]}
                (sut/discover! {:modules {:mod.client (mod-coord :mod.client)}} ctx)]
            (should= [] errors)
            (should= :mod.server (get-in index [:mod.server :manifest :id]))
            (should= 1 (count (filter #(contains? % 'mod.server/mod.server) @calls)))))))

    (it "adds module deps only once per coordinate across repeated discovery"
      (write-local-module! :isaac.comm.pigeon valid-comm-manifest)
      (let [calls            (atom [])
            classpath-ready? (atom false)]
        (with-redefs [isaac.module.loader/manifest-resource (fn [id]
                                                              (when (and @classpath-ready?
                                                                         (= id :isaac.comm.pigeon))
                                                                (str (mod-root :isaac.comm.pigeon) "/resources/isaac-manifest.edn")))
                      isaac.module.loader/invoke-add-deps!   (fn [deps-map]
                                                              (swap! calls conj deps-map)
                                                              (reset! classpath-ready? true))]
          (let [first-result  (sut/discover! {:modules {:isaac.comm.pigeon (mod-coord :isaac.comm.pigeon)}} ctx)
                second-result (sut/discover! {:modules {:isaac.comm.pigeon (mod-coord :isaac.comm.pigeon)}} ctx)
                pigeon-lib  'isaac.comm.pigeon/isaac.comm.pigeon
                pigeon-coord (mod-coord :isaac.comm.pigeon)]
            (should= [] (:errors first-result))
            (should= [] (:errors second-result))
            (should= 1 (count @calls))
            (let [deps-map (first @calls)]
              (should= {pigeon-lib (assoc pigeon-coord
                                          :exclusions '[io.github.slagyr/isaac-foundation])}
                        deps-map))))))

    (it "discovers builtin classpath manifests and ignores unflagged manifests"
      (let [real-resource-urls @#'isaac.module.loader/resource-urls]
        (with-redefs [isaac.module.loader/resource-urls
                      #(builtin-fixture-resources real-resource-urls %)]
          (try
            (sut/clear-caches!)
            (let [{:keys [index errors]} (sut/discover! {} ctx)]
              (should= [] errors)
              (should (contains? index :isaac.fixture.builtin))
              (should-not (contains? index :isaac.fixture.unflagged)))
            (finally
              (sut/clear-caches!)
              (sut/foundation-index))))))

    (it "adds errors when a manifest fails schema validation"
      (write-local-module! :isaac.comm.pigeon {:id :isaac.comm.pigeon})
      (let [{:keys [index errors]} (discover-local! [:isaac.comm.pigeon])]
        (should= nil (get index :isaac.comm.pigeon))
        (should (some #(= "module-index[\"isaac.comm.pigeon\"].version" (:key %)) errors))))

    (it "builds an index entry for two independent modules"
      (write-local-module! :mod.a {:id :mod.a :version "1"})
      (write-local-module! :mod.b {:id :mod.b :version "1"})
      (let [{:keys [index errors]} (discover-local! [:mod.a :mod.b])]
        (should= [] errors)
        ;; Both independent modules plus foundation get index entries. We
        ;; assert presence (not an exact set): sibling :builtin? manifests can
        ;; legitimately appear on the test classpath once an earlier spec
        ;; discover!s a fixture module whose deps.edn back-references its repo.
        (should-contain :mod.a index)
        (should-contain :mod.b index)
        (should-contain :isaac.foundation index)))))
