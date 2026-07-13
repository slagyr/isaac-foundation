(ns isaac.startup.classpath-cache
  (:require
    [clojure.string :as str]
    [isaac.foundation.version :as version]
    [isaac.module.loader :as module-loader]
    [isaac.startup.cache :as cache]))

(def ^:dynamic *timing-samples*
  "When bound to an atom, records {:phase keyword :ms long} samples (isaac-tki3)."
  nil)

(defn- record-phase! [phase started-ms]
  (when *timing-samples*
    (swap! *timing-samples* conj {:phase phase
                                 :ms    (- (System/nanoTime) started-ms)})))

(defn- module-id-name [k]
  (if (keyword? k) (name k) (str k)))

(defn- normalize-module-coord [coord]
  (cond
    (and (map? coord) (:git/sha coord)) {:git/sha (str (:git/sha coord))}
    (and (map? coord) (:local/root coord)) {:local/root (str (:local/root coord))}
    (map? coord) (select-keys coord [:git/url :git/tag :git/sha :local/root])
    :else coord))

(defn module-coords-basis
  "SHA pins and local roots from config :modules — cache invalidates when these change."
  [config]
  (when-let [mods (:modules config)]
    (into {}
          (map (fn [[id coord]]
                 [(module-id-name id) (normalize-module-coord coord)])
               mods))))

(defn identity-basis [config]
  (cond-> {:foundation (or (version/manifest-version) "unknown")}
    (seq (:modules config))
    (assoc :module-coords (module-coords-basis config))))

(defn identity-fresh? [cached config]
  (let [basis (:basis cached)]
    (if (nil? (:foundation basis))
      ;; Legacy v2 caches seeded in features with only timestamp basis — still
      ;; honor write-ordering freshness until rewritten with full identity.
      true
      (= (identity-basis config)
         (select-keys basis [:foundation :module-coords])))))

(defn read-classpath-pairs [fs* root config]
  (when-let [c (cache/read-cache fs* root)]
    (when (and (= cache/cache-version (:version c)) (identity-fresh? c config))
      (:classpath-pairs (:data c)))))

(defn read-resolved-classpath
  "Resolved -cp string from a fresh identity-matching cache (isaac-ogiu).
   Returns the string when the key is present (including empty = no modules).
   Returns nil when the key is absent (legacy tki3 cache → fall back to pairs)."
  [fs* root config]
  (when-let [c (cache/read-cache fs* root)]
    (when (and (= cache/cache-version (:version c)) (identity-fresh? c config))
      (when (contains? (:data c) :classpath)
        (let [cp (get-in c [:data :classpath])]
          (if (string? cp) cp ""))))))

(defn try-apply-cached-pairs! [fs* root config _cwd]
  (let [t0 (System/nanoTime)]
    (try
      (if-let [pairs (read-classpath-pairs fs* root config)]
        (do (module-loader/apply-module-classpath-pairs! pairs)
            (record-phase! :apply-cached t0)
            true)
        false)
      (catch Exception _ false))))

(defn try-apply-cached-classpath!
  "Prefer the resolved classpath string (skip add-deps). Fall back to pairs.
   Returns {:ok? bool :via :classpath|:pairs|nil}."
  [fs* root config]
  (let [t0 (System/nanoTime)
        cp (read-resolved-classpath fs* root config)]
    (if (some? cp)
      (try
        (module-loader/apply-resolved-classpath! cp)
        (when-let [pairs (read-classpath-pairs fs* root config)]
          (module-loader/mark-module-pairs-loaded! pairs))
        (record-phase! :apply-cached-classpath t0)
        {:ok? true :via :classpath}
        (catch Exception _
          {:ok? false :via :classpath}))
      (if (try-apply-cached-pairs! fs* root config nil)
        {:ok? true :via :pairs}
        {:ok? false :via nil}))))

(defn write-classpath-cache!
  ([fs* root watched config pairs commands]
   (write-classpath-cache! fs* root watched config pairs commands nil))
  ([fs* root watched config pairs commands classpath]
   (cache/write-cache! fs* root
                       {:version cache/cache-version
                        :basis   (merge (cache/compute-basis fs* watched)
                                        (identity-basis config))
                        :data    (cond-> {:classpath-pairs pairs
                                          :commands        commands}
                                   ;; Persist empty string too — means "no modules,
                                   ;; resolved, skip add-deps on warm" (isaac-ogiu).
                                   (string? classpath)
                                   (assoc :classpath classpath))})))

(defn plan-and-compose! [config cwd]
  (let [t0 (System/nanoTime)]
    (module-loader/compose-config-modules! config cwd)
    (record-phase! :plan-compose t0)
    (let [pairs (or (module-loader/plan-module-classpath-pairs (:modules config) cwd) [])
          ;; Module-added segments only (not the process bb.edn classpath).
          cp    (module-loader/take-added-classpath-delta!)]
      {:pairs pairs :classpath cp})))

(defn compose-with-cache! [fs* root config cwd watched]
  (if (and (cache/fresh? fs* root watched)
           (let [c (cache/read-cache fs* root)] (and c (identity-fresh? c config)))
           (:ok? (try-apply-cached-classpath! fs* root config)))
    (do (record-phase! :cache-hit (System/nanoTime))
        {:pairs     (read-classpath-pairs fs* root config)
         :classpath (read-resolved-classpath fs* root config)
         :from-cache? true})
    ;; Do NOT reset the delta accumulator: an earlier path (e.g. registry
    ;; require / discover!) may already have resolved modules via add-deps.
    ;; plan-and-compose! takes whatever has been accumulated this process.
    (let [t0     (System/nanoTime)
          result (plan-and-compose! config cwd)]
      (record-phase! :cold-plan t0)
      (assoc result :from-cache? false))))