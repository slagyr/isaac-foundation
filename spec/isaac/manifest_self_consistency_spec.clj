(ns isaac.manifest-self-consistency-spec
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [speclj.core :refer :all]))

(defn- read-manifest [path]
  (-> path io/file slurp edn/read-string))

(defn- ensure-local-deps! [path]
  ;; Under bb, dynamically classpath the module so requiring-resolve can
  ;; find its symbols. Under JVM, the test alias in deps.edn already
  ;; pre-declares the modules (clojure.repl.deps/add-libs is REPL-only
  ;; and can't add deps from a spec-runner thread), so this is a no-op.
  (when-let [add-deps (try (requiring-resolve 'babashka.deps/add-deps)
                           (catch Throwable _ nil))]
    (when (str/starts-with? path "modules/")
      (when-let [module-root (second (re-find #"^(modules/[^/]+)" path))]
        (add-deps {:deps {(symbol module-root) {:local/root module-root}}})))))

(defn- manifest-paths []
  (->> (concat ["resources/isaac-manifest.edn" "src/isaac-manifest.edn"]
               (->> (file-seq (io/file "modules"))
                    (filter #(.isFile %))
                    (map #(.getPath %))
                    (filter #(str/ends-with? % "resources/isaac-manifest.edn"))
                    sort))
       (filter #(.exists (io/file %)))))

(defn- factory-symbols [manifest]
  (->> (:extends manifest)
       vals
       (mapcat vals)
       (keep :isaac/factory)))

(describe "manifest self-consistency"
  (it "resolves every declared :isaac/factory and :bootstrap symbol"
    (doseq [path (manifest-paths)
            :let [manifest   (read-manifest path)
                  bootstrap (:bootstrap manifest)]
            symbol (concat (when bootstrap [bootstrap]) (factory-symbols manifest))]
      (ensure-local-deps! path)
      (should-not-be-nil (requiring-resolve symbol)))))
