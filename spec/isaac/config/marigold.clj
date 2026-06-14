(ns isaac.config.marigold
  "Test fixtures for config CLI, mutate, and schema rendering specs.
   Loads the agent manifest schema contributions from the sibling
   isaac-agent repo when present."
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

(def baseline-agent-manifest
  (let [manifest (or (sibling-agent-manifest)
                     (throw (ex-info "config specs require ../isaac-agent/resources/isaac-manifest.edn"
                                     {:cwd (System/getProperty "user.dir")})))]
    ;; Foundation specs run without isaac-agent on the classpath; schema
    ;; contributions suffice for CLI/mutate tests.
    (dissoc manifest :isaac.config/check)))

(def ^:private baseline-config-test-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :isaac.agent      {:coord {} :manifest baseline-agent-manifest :path nil}})

(defn agent-modules-root
  "Path to isaac-agent/modules for optional third-party module fixtures."
  []
  (let [sibling (io/file (System/getProperty "user.dir") "../isaac-agent/modules")]
    (if (.exists sibling)
      (.getPath sibling)
      (str (System/getProperty "user.dir") "/modules"))))

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