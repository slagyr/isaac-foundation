(ns isaac.startup.config-cache-steps
  "Feature steps for CLI config-resolution (isaac-v1la). Spies wrap
   load-config-result / -validate-root-config for the duration of `isaac is run
   with` via the shared run-wrapper registry."
  (:require
    [gherclj.core :as g :refer [defgiven defthen]]
    [isaac.config.loader :as loader]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.startup.classpath-cache-steps :as classpath-steps]))

(def ^:private resolution-spy-key :config-resolution-spy)
(def ^:private validation-spy-key :config-validation-spy)

(defn- wrap-with-spy [spy-atom f]
  (fn [& args]
    (when spy-atom (swap! spy-atom inc))
    (apply f args)))

(defn- install-run-hooks! []
  (cli-steps/register-isaac-run-wrapper!
    (fn [thunk]
      (let [res-spy (g/get resolution-spy-key)
            val-spy (g/get validation-spy-key)]
        (when res-spy (reset! res-spy 0))
        (when val-spy (reset! val-spy 0))
        (cond-> thunk
          res-spy (as-> t
                    (fn []
                      (with-redefs [loader/load-config-result
                                    (wrap-with-spy res-spy loader/load-config-result)]
                        (t))))
          val-spy (as-> t
                    (fn []
                      (with-redefs [loader/-validate-root-config
                                    (wrap-with-spy val-spy loader/-validate-root-config)]
                        (t))))
          true    (#(%)))))))

(install-run-hooks!)

(defn config-resolution-spy-armed []
  (g/assoc! resolution-spy-key (atom 0)))

(defn config-validation-spy-armed []
  (g/assoc! validation-spy-key (atom 0)))

(defn- ensure-root-config!
  "A prior CLI run that wrote a warm cache implies a loadable root config.
   Empty-root scenarios otherwise seed cache/cli.edn with :config {} and
   `config get` then reports missing-config."
  []
  (let [root (or (g/get :runtime-root-dir) (g/get :root))
        fs*  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))
        path (str root "/config/isaac.edn")]
    (when-not (fs/exists? fs* path)
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path "{}"))))

(defn warm-startup-cache-from-prior-run []
  (ensure-root-config!)
  (classpath-steps/classpath-cache-seeded-from-prior-run))

(defn- spy-count [key]
  (or (some-> (g/get key) deref) 0))

(defn config-resolution-spy-invoked-exactly [n]
  (let [c   (spy-count resolution-spy-key)
        exp (parse-long n)]
    (when-not (= exp c)
      (throw (ex-info (str "expected load-config-result " exp " times, got " c)
                      {:expected exp :actual c})))))

(defn config-validation-spy-invoked-exactly [n]
  (let [c   (spy-count validation-spy-key)
        exp (parse-long n)]
    (when-not (= exp c)
      (throw (ex-info (str "expected -validate-root-config " exp " times, got " c)
                      {:expected exp :actual c})))))

(defn config-validation-spy-invoked-at-least [n]
  (let [c   (spy-count validation-spy-key)
        min (parse-long n)]
    (when-not (<= min c)
      (throw (ex-info (str "expected -validate-root-config at least " min " times, got " c)
                      {:minimum min :actual c})))))

(defgiven "the config resolution spy is armed" isaac.startup.config-cache-steps/config-resolution-spy-armed)

(defgiven "the config validation spy is armed" isaac.startup.config-cache-steps/config-validation-spy-armed)

(defgiven "a warm startup cache exists from a prior run"
  isaac.startup.config-cache-steps/warm-startup-cache-from-prior-run)

(defthen #"the config resolution spy was invoked exactly (\d+) times?"
  isaac.startup.config-cache-steps/config-resolution-spy-invoked-exactly)

(defthen #"the config validation spy was invoked exactly (\d+) times?"
  isaac.startup.config-cache-steps/config-validation-spy-invoked-exactly)

(defthen #"the config validation spy was invoked at least (\d+) times?"
  isaac.startup.config-cache-steps/config-validation-spy-invoked-at-least)

(defthen "the startup cache was refreshed after replan"
  isaac.startup.classpath-cache-steps/classpath-cache-refreshed-after-replan)
