(ns isaac.startup.cache
  "CLI startup cache (isaac-clic): the expensive upfront work — classpath
   planning, module discovery, command registration — is deterministic given
   the config and local module source trees. We persist a small summary at
   `<root>/cache/cli.edn` and skip the recompute on the fast path (--version,
   --help) when nothing has changed.

   Freshness is write-ordering plus a content witness: the cache is fresh when
   it exists, no watched file has been written AFTER the cache file itself
   (`fs/modified` stamps are monotonic per filesystem — real mtime on disk, a
   write revision in-memory), and an equal-mtime rewrite of the root config
   still matches the recorded content hash. Equal mtime is otherwise stale
   (real-fs millisecond ties). The `:basis` map is a witness recorded at write
   time, not the comparator."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs])
  (:import (java.security MessageDigest)))

(def cache-version 3)

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

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn content-hash
  "SHA-256 hex of file contents, or nil when the path is missing."
  [fs* path]
  (when (and path (fs/exists? fs* path))
    (let [md      (MessageDigest/getInstance "SHA-256")
          content (or (fs/slurp fs* path) "")]
      (hex (.digest md (.getBytes ^String content "UTF-8"))))))

(defn compute-basis
  "Witness map recorded on write: category -> newest watched stamp (omitting
   categories with no existing files). Also records :config-hash of the root
   config so an equal-mtime rewrite is not treated as fresh."
  [fs* watched]
  (let [stamps (into {} (keep (fn [[k paths]]
                                (when-let [s (max-stamp fs* paths)] [k s]))
                              watched))
        cfg    (first (:config watched))]
    (cond-> stamps
      cfg (assoc :config-hash (content-hash fs* cfg)))))

(defn read-cache [fs* root]
  (let [p (cache-path root)]
    (when (fs/exists? fs* p)
      (try (edn/read-string (fs/slurp fs* p)) (catch Exception _ nil)))))

(defn- stamp-fresh? [cstamp wp-stamp]
  (or (nil? wp-stamp) (< wp-stamp cstamp)))

(defn- equal-mtime-config-unchanged? [fs* cached watched cstamp]
  (let [cfg (first (:config watched))
        s   (when cfg (fs/modified fs* cfg))]
    (boolean
      (and cfg
           s
           (= s cstamp)
           (let [recorded (get-in cached [:basis :config-hash])]
             (and recorded (= recorded (content-hash fs* cfg))))))))

(defn fresh?
  "True when the cache exists, is the current version, and no watched file was
   written after the cache file itself. Equal mtime is stale unless the root
   config content hash still matches the recorded witness (real-fs ms ties)."
  [fs* root watched]
  (let [p (cache-path root)]
    (boolean
      (when (fs/exists? fs* p)
        (when-let [cstamp (fs/modified fs* p)]
          (let [cached (read-cache fs* root)]
            (and (= cache-version (:version cached))
                 (every? (fn [wp]
                           (let [s (fs/modified fs* wp)]
                             (or (stamp-fresh? cstamp s)
                                 (and (= wp (first (:config watched)))
                                      (equal-mtime-config-unchanged? fs* cached watched cstamp)))))
                         (all-paths watched)))))))))

(defn write-cache! [fs* root data]
  (let [p (cache-path root)]
    (fs/mkdirs fs* (fs/parent p))
    (fs/spit fs* p (pr-str data))))
