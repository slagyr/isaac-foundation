(ns isaac.startup.cache
  "CLI startup cache (isaac-clic): the expensive upfront work — classpath
   planning, module discovery, command registration — is deterministic given
   the config and local module source trees. We persist a small summary at
   `<root>/cache/cli.edn` and skip the recompute on the fast path (--version,
   --help) when nothing has changed.

   Freshness is write-ordering, not stored values: the cache is fresh when it
   exists and no watched file has been written AFTER the cache file itself
   (`fs/modified` stamps are monotonic per filesystem — real mtime on disk, a
   write revision in-memory). The `:basis` map is a witness recorded at write
   time, not the comparator."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]))

(def cache-version 2)

(defn cache-path [root]
  (str root "/cache/cli.edn"))

(defn- local-roots [config]
  (keep (fn [[_ coord]] (when (and (map? coord) (:local/root coord)) (:local/root coord)))
        (:modules config)))

(defn- abs-path [cwd path]
  (if (str/starts-with? path "/") path (str cwd "/" path)))

(defn- local-watch-paths [cwd root]
  (let [r (abs-path cwd root)]
    ;; Watch every place a local module's planning inputs can live plus its
    ;; deps.edn; over-watching only ever forces a safe recompute.
    [(str r "/isaac-manifest.edn")
     (str r "/resources/isaac-manifest.edn")
     (str r "/src/isaac-manifest.edn")
     (str r "/deps.edn")]))

(defn watched-files
  "Map of category -> watched file paths whose changes invalidate the cache:
   :config the root config that supplied :modules (path passed in by the caller,
   which owns config-path resolution); :local each local module's manifest/deps
   candidates."
  [config-file config cwd]
  {:config [config-file]
   :local  (vec (mapcat #(local-watch-paths cwd %) (local-roots config)))})

(defn- all-paths [watched]
  (mapcat val watched))

(defn- max-stamp [fs* paths]
  (let [stamps (keep #(fs/modified fs* %) paths)]
    (when (seq stamps) (apply max stamps))))

(defn compute-basis
  "Witness map recorded on write: category -> newest watched stamp (omitting
   categories with no existing files)."
  [fs* watched]
  (into {} (keep (fn [[k paths]]
                   (when-let [s (max-stamp fs* paths)] [k s]))
                 watched)))

(defn read-cache [fs* root]
  (let [p (cache-path root)]
    (when (fs/exists? fs* p)
      (try (edn/read-string (fs/slurp fs* p)) (catch Exception _ nil)))))

(defn fresh?
  "True when the cache exists, is the current version, and no watched file was
   written after the cache file itself."
  [fs* root watched]
  (let [p (cache-path root)]
    (boolean
      (when (fs/exists? fs* p)
        (when-let [cstamp (fs/modified fs* p)]
          (and (= cache-version (:version (read-cache fs* root)))
               (every? (fn [wp]
                         (let [s (fs/modified fs* wp)]
                           (or (nil? s) (<= s cstamp))))
                       (all-paths watched))))))))

(defn write-cache! [fs* root data]
  (let [p (cache-path root)]
    (fs/mkdirs fs* (fs/parent p))
    (fs/spit fs* p (pr-str data))))
