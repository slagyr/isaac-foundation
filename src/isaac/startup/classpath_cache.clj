(ns isaac.startup.classpath-cache
  (:require
    [isaac.foundation.version :as version]
    [isaac.module.loader :as module-loader]
    [isaac.startup.cache :as cache]))

(defn identity-basis [config]
  {:foundation (or (version/manifest-version) "unknown")})

(defn identity-fresh? [cached config]
  (= (identity-basis config) (select-keys (:basis cached) [:foundation])))

(defn read-classpath-pairs [fs* root config]
  (when-let [c (cache/read-cache fs* root)]
    (when (and (= cache/cache-version (:version c)) (identity-fresh? c config))
      (:classpath-pairs (:data c)))))

(defn try-apply-cached-pairs! [fs* root config _cwd]
  (try
    (if-let [pairs (read-classpath-pairs fs* root config)]
      (do (module-loader/apply-module-classpath-pairs! pairs) true)
      false)
    (catch Exception _ false)))

(defn write-classpath-cache! [fs* root watched config pairs commands]
  (cache/write-cache! fs* root
    {:version cache/cache-version
     :basis (merge (cache/compute-basis fs* watched) (identity-basis config))
     :data {:classpath-pairs pairs :commands commands}}))

(defn plan-and-compose! [config cwd]
  (module-loader/compose-config-modules! config cwd)
  (or (module-loader/plan-module-classpath-pairs (:modules config) cwd) []))

(defn compose-with-cache! [fs* root config cwd watched]
  (if (and (cache/fresh? fs* root watched)
           (let [c (cache/read-cache fs* root)] (and c (identity-fresh? c config)))
           (try-apply-cached-pairs! fs* root config cwd))
    {:pairs (read-classpath-pairs fs* root config) :from-cache? true}
    (let [pairs (plan-and-compose! config cwd)]
      {:pairs pairs :from-cache? false})))
