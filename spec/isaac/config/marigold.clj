(ns isaac.config.marigold
  "Test fixtures for config CLI, mutate, and schema rendering specs.
   Uses a bundled agent manifest for CI; prefers the sibling isaac-agent
   checkout when present so local monorepo layouts stay fresh."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [c3kit.apron.env :as c3env]
    [isaac.config.loader :as loader]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [speclj.core :as speclj]))

(defn- sibling-agent-manifest []
  (let [f (io/file (System/getProperty "user.dir") "../isaac-agent/resources/isaac-manifest.edn")]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn- bundled-agent-manifest []
  (or (when-let [url (io/resource "isaac/config/fixtures/agent-manifest.edn")]
        (edn/read-string (slurp url)))
      (let [f (io/file (System/getProperty "user.dir")
                       "spec/isaac/config/fixtures/agent-manifest.edn")]
        (when (.exists f)
          (edn/read-string (slurp f))))))

(def baseline-agent-manifest
  (let [manifest (or (sibling-agent-manifest)
                     (bundled-agent-manifest)
                     (throw (ex-info "config specs require an agent manifest fixture"
                                     {:cwd (System/getProperty "user.dir")})))]
    ;; Foundation specs run without isaac-agent on the classpath; schema
    ;; contributions suffice for CLI/mutate tests.
    (dissoc manifest :isaac.config/check)))

(def ^:private baseline-config-test-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :isaac.agent      {:coord {} :manifest baseline-agent-manifest :path nil}})

(defn agent-modules-root
  "Path to config-spec module fixtures (telly, kombucha, ...)."
  []
  (let [cwd     (System/getProperty "user.dir")
        sibling (io/file cwd "../isaac-agent/modules")
        bundled (io/file cwd "spec/isaac/config/fixtures/modules")]
    (cond
      (.exists sibling) (.getPath sibling)
      (.exists bundled) (.getPath bundled)
      :else             (str cwd "/modules"))))

(defn test-root-schema
  "Composed config schema used by config-cli specs."
  []
  (schema-compose/effective-root-schema baseline-config-test-index))

(defn with-manifest
  "Bind foundation + agent schema manifests for config schema/CLI specs."
  []
  (speclj/around [example]
    (binding [module-loader/*foundation-index-override* baseline-config-test-index]
      (schema-compose/clear-cache!)
      (try
        (example)
        (finally
          (schema-compose/clear-cache!))))))

(defn aboard
  "Mem-fs scene with agent schema contributions for mutate specs."
  []
  (speclj/around [example]
    (let [mem (fs/mem-fs)]
      (nexus/-with-nested-nexus {:fs mem}
        (binding [module-loader/*foundation-index-override* baseline-config-test-index]
          (reset! c3env/-overrides {})
          (loader/clear-env-overrides!)
          (schema-compose/clear-cache!)
          (example))))))