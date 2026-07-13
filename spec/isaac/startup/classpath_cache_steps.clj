(ns isaac.startup.classpath-cache-steps
  (:require
    [clojure.edn :as edn]
    [gherclj.core :as g :refer [defgiven defthen defwhen]]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.startup.classpath-cache :as classpath-cache]
    [isaac.startup.cache :as startup-cache]))

(def ^:private plan-spy-key :classpath-plan-spy)
(def ^:private add-deps-spy-key :classpath-add-deps-spy)
(def ^:private timing-key :classpath-timing-samples)

(defn- isaac-root-path []
  (or (g/get :runtime-root-dir)
      (g/get :root)
      (System/getProperty "user.home")))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- plan-spy-atom []
  (g/get plan-spy-key))

(defn- add-deps-spy-atom []
  (g/get add-deps-spy-key))

(defn- wrap-plan-with-spy [f]
  (let [spy (plan-spy-atom)]
    (fn [& args]
      (when spy (swap! spy inc))
      (apply f args))))

(defn- wrap-add-deps-with-spy [f]
  (let [spy (add-deps-spy-atom)]
    (fn [& args]
      (when spy (swap! spy inc))
      (apply f args))))

(defn- install-run-hooks! []
  (cli-steps/register-isaac-run-wrapper!
    (fn [thunk]
      (let [spy     (plan-spy-atom)
            depspy  (add-deps-spy-atom)
            times   (or (g/get timing-key) (atom []))]
        (when spy (reset! spy 0))
        (when depspy (reset! depspy 0))
        (binding [classpath-cache/*timing-samples* times]
          (with-redefs [module-loader/plan-module-classpath-pairs
                        (wrap-plan-with-spy module-loader/plan-module-classpath-pairs)
                        module-loader/invoke-add-deps!
                        (wrap-add-deps-with-spy module-loader/invoke-add-deps!)]
            (thunk)))))))

(install-run-hooks!)

(defn classpath-plan-spy-armed []
  (g/assoc! plan-spy-key (atom 0))
  (g/assoc! timing-key (atom [])))

(defn add-deps-spy-armed []
  (g/assoc! add-deps-spy-key (atom 0)))

(defn classpath-cache-seeded-from-prior-run []
  (cli-steps/isaac-run "logs --list")
  (when-let [spy (plan-spy-atom)] (reset! spy 0)))

(defn classpath-cache-file-removed []
  (let [root (isaac-root-path)
        path (startup-cache/cache-path root)
        fs*  (feature-fs)]
    (when (fs/exists? fs* path)
      (fs/delete fs* path))))

(defn classpath-cache-file-corrupted []
  (let [root (isaac-root-path)
        path (startup-cache/cache-path root)
        fs*  (feature-fs)]
    (when (fs/exists? fs* path)
      (let [data (edn/read-string (fs/slurp fs* path))]
        (fs/spit fs* path
                  (pr-str (-> data
                              (assoc-in [:data :classpath-pairs] [{:bad "corrupt"}])
                              (assoc-in [:data :classpath] "/definitely/missing/artifact.jar"))))))))

(defn cached-classpath-references-missing-artifact []
  (let [root (isaac-root-path)
        path (startup-cache/cache-path root)
        fs*  (feature-fs)]
    (when-not (fs/exists? fs* path)
      (throw (ex-info (str "missing cache " path) {})))
    (let [data (edn/read-string (fs/slurp fs* path))]
      (fs/spit fs* path
                (pr-str (assoc-in data [:data :classpath]
                                    "/definitely/missing/isaac-ogiu-artifact.jar"))))))

(defn classpath-plan-spy-invoked-exactly [n]
  (let [spy (plan-spy-atom)
        c   (or (when spy @spy) 0)
        exp (parse-long n)]
    (when-not (= exp c)
      (throw (ex-info (str "expected plan-module-classpath-pairs " exp " times, got " c)
                      {:expected exp :actual c})))))

(defn classpath-plan-spy-invoked-at-least [n]
  (let [spy (plan-spy-atom)
        c   (or (when spy @spy) 0)
        min (parse-long n)]
    (when-not (<= min c)
      (throw (ex-info (str "expected plan-module-classpath-pairs at least " min " times, got " c)
                      {:minimum min :actual c})))))

(defn add-deps-spy-invoked-exactly [n]
  (let [spy (add-deps-spy-atom)
        c   (or (when spy @spy) 0)
        exp (parse-long n)]
    (when-not (= exp c)
      (throw (ex-info (str "expected invoke-add-deps! " exp " times, got " c)
                      {:expected exp :actual c})))))

(defn classpath-cache-stores-resolved-classpath []
  (let [root (isaac-root-path)
        path (startup-cache/cache-path root)
        fs*  (feature-fs)]
    (when-not (fs/exists? fs* path)
      (throw (ex-info (str "missing cache " path) {})))
    (let [data (edn/read-string (fs/slurp fs* path))
          cp   (get-in data [:data :classpath])]
      ;; Key must be present; empty string is valid when config has no modules.
      (when-not (contains? (:data data) :classpath)
        (throw (ex-info "cache missing :data :classpath key after cold run"
                        {:data (:data data)})))
      (when-not (string? cp)
        (throw (ex-info "cache :classpath is not a string"
                        {:classpath cp}))))))

(defn classpath-cache-refreshed-after-replan []
  (let [root (isaac-root-path)
        path (startup-cache/cache-path root)
        fs*  (feature-fs)]
    (when-not (fs/exists? fs* path)
      (throw (ex-info (str "missing cache " path) {})))
    (let [data  (edn/read-string (fs/slurp fs* path))
          pairs (get-in data [:data :classpath-pairs])
          cp    (get-in data [:data :classpath])]
      (when (some #(= "corrupt" (:bad %)) pairs)
        (throw (ex-info "classpath cache still has corrupt pairs after replan" {:pairs pairs})))
      (when (= "/definitely/missing/isaac-ogiu-artifact.jar" cp)
        (throw (ex-info "classpath cache still has missing-artifact classpath after replan"
                        {:classpath cp}))))))

(defn classpath-cache-basis-includes-module-coords []
  (let [root (isaac-root-path)
        path (startup-cache/cache-path root)
        fs*  (feature-fs)]
    (when-not (fs/exists? fs* path)
      (throw (ex-info (str "missing cache " path) {})))
    (let [data   (edn/read-string (fs/slurp fs* path))
          coords (get-in data [:basis :module-coords])]
      (when-not (contains? (:basis data) :module-coords)
        (throw (ex-info "basis missing :module-coords (foundation-only legacy basis)"
                        {:basis (:basis data)}))))))

(defn classpath-timing-warm-faster-than-cold []
  (let [samples @(or (g/get timing-key) (atom []))
        cold-phases #{:plan-compose-cold :plan-compose :cold-plan}
        warm-phases #{:cache-hit :plan-compose-warm}
        cold?     (some #(contains? cold-phases (:phase %)) samples)
        warm?     (some #(contains? warm-phases (:phase %)) samples)]
    (when-not cold?
      (throw (ex-info (str "missing cold plan-compose timing sample: " (pr-str samples))
                      {:samples samples})))
    (when-not warm?
      (throw (ex-info (str "missing warm/cache-hit timing sample: " (pr-str samples))
                      {:samples samples})))))

(defgiven "the classpath plan spy is armed" isaac.startup.classpath-cache-steps/classpath-plan-spy-armed)

(defgiven "the add-deps spy is armed" isaac.startup.classpath-cache-steps/add-deps-spy-armed)

(defgiven "a warm classpath cache exists from a prior non-fast-path run"
  isaac.startup.classpath-cache-steps/classpath-cache-seeded-from-prior-run)

(defwhen "the classpath cache file is removed"
  isaac.startup.classpath-cache-steps/classpath-cache-file-removed)

(defgiven "the classpath cache file is corrupted so apply fails"
  isaac.startup.classpath-cache-steps/classpath-cache-file-corrupted)

(defgiven "the cached classpath references a missing artifact"
  isaac.startup.classpath-cache-steps/cached-classpath-references-missing-artifact)

(defthen #"the classpath plan spy was invoked exactly (\d+) times?"
  isaac.startup.classpath-cache-steps/classpath-plan-spy-invoked-exactly)

(defthen #"the classpath plan spy was invoked at least (\d+) times?"
  isaac.startup.classpath-cache-steps/classpath-plan-spy-invoked-at-least)

(defthen #"the add-deps spy was invoked exactly (\d+) times?"
  isaac.startup.classpath-cache-steps/add-deps-spy-invoked-exactly)

(defthen "the classpath cache stores a resolved classpath string"
  isaac.startup.classpath-cache-steps/classpath-cache-stores-resolved-classpath)

(defthen "the classpath cache was refreshed after replan"
  isaac.startup.classpath-cache-steps/classpath-cache-refreshed-after-replan)

(defthen "the classpath cache basis records module coordinates from isaac.edn"
  isaac.startup.classpath-cache-steps/classpath-cache-basis-includes-module-coords)

(defthen "classpath timing evidence shows warm plan-compose faster than cold"
  isaac.startup.classpath-cache-steps/classpath-timing-warm-faster-than-cold)