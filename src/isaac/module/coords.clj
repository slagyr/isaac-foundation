(ns isaac.module.coords
  "Module identity and coordinate resolution — pure helpers shared by the
   module subsystem (discovery, classpath, lifecycle, versions)."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]))

(def foundation-module-id :isaac.foundation)

;; Transitive module deps.edn files pin foundation for standalone dev; the
;; packaged seed already provides it — never let a module pull a second copy.
(def seed-foundation-lib 'io.github.slagyr/isaac-foundation)

(def platform-module-ids
  #{:isaac.foundation})

(defn runtime-fs []
  (or (fs/instance) (throw (ex-info "module.coords requires :fs in system" {}))))

(defn ->module-id [raw]
  (cond
    (keyword? raw) raw
    (symbol? raw)  (keyword (str raw))
    (string? raw)  (keyword raw)
    :else          nil))

(defn id-str [id]
  (cond
    (keyword? id) (subs (str id) 1)
    (symbol? id)  (str id)
    (string? id)  id
    :else         (str id)))

(defn ->lib-sym [id]
  (let [s (id-str id)]
    (if (str/includes? s "/")
      (symbol s)
      (symbol s s))))

(defn split-repo-lib-sym
  "Lib symbol split platform repos declare in deps.edn (e.g.
   io.github.slagyr/isaac-server for :isaac.server). Configured modules use
   ->lib-sym instead; sibling exclusions must cover both or transitive deps
   re-append the same manifest under a second lib."
  [id]
  (let [s (id-str id)]
    (when (str/starts-with? s "isaac.")
      (symbol "io.github.slagyr"
              (if (str/starts-with? s "isaac.comm.")
                (str "isaac-" (nth (str/split s #"\.") 2))
                (str "isaac-" (str/replace (subs s (count "isaac.")) "." "-")))))))

(defn module-lib-syms [id]
  (cond-> #{(->lib-sym id)}
    (split-repo-lib-sym id) (conj (split-repo-lib-sym id))))

(defn mod-error-key [id]
  (str "modules[\"" (id-str id) "\"]"))

(defn manifest-error-key [id field]
  (str "module-index[\"" (id-str id) "\"]." (name field)))

(defn manifest-errors [id result]
  (mapv (fn [[field msg]]
          {:key   (manifest-error-key id field)
           :value msg})
        (cs/message-map result)))

(defn read-manifest-edn [path]
  (try
    (edn/read-string (if-let [fs* (and (string? path) (fs/instance))]
                       (if (fs/exists? fs* path)
                         (fs/slurp fs* path)
                         (slurp path))
                       (slurp path)))
    (catch Exception _ nil)))

(defn abs-path [cwd path]
  (if (or (str/starts-with? path "/")
          (re-matches #"[A-Za-z]:.*" path))
    path
    (str cwd "/" path)))

(defn- absolute-path? [path]
  (or (str/starts-with? path "/")
      (re-matches #"[A-Za-z]:.*" path)))

(def gitlibs-root
  (str (System/getProperty "user.home") "/.gitlibs/libs"))

(defn apply-deps-root [dir coord]
  (if-let [root (:deps/root coord)]
    (.getCanonicalPath (java.io.File. dir root))
    dir))

(defn find-gitlib-directory [sha]
  (when (and (string? sha) (seq sha))
    (let [libs (java.io.File. gitlibs-root)]
      (when (.isDirectory libs)
        (some (fn [^java.io.File ns-dir]
                (when (.isDirectory ns-dir)
                  (some (fn [^java.io.File name-dir]
                          (when (.isDirectory name-dir)
                            (let [sha-dir (java.io.File. name-dir sha)]
                              (when (.isDirectory sha-dir)
                                (.getPath sha-dir)))))
                        (.listFiles ns-dir))))
              (.listFiles libs))))))

(defn coord-directory [coord context]
  (when (map? coord)
    (or (when-let [root (:local/root coord)]
          (if (absolute-path? root)
            root
            (abs-path (:cwd context) root)))
        (when-let [sha (:git/sha coord)]
          (when-let [dir (find-gitlib-directory sha)]
            (apply-deps-root dir coord))))))

(defn resolve-nested-dep-coord [parent-dir dep-coord]
  (if-let [root (:local/root dep-coord)]
    (if (absolute-path? root)
      dep-coord
      (assoc dep-coord :local/root (.getCanonicalPath (java.io.File. (java.io.File. parent-dir) root))))
    dep-coord))

(defn local-root-path [context coord]
  (when-let [root (:local/root coord)]
    (abs-path (:cwd context) root)))

(defn valid-module-coord? [coord]
  (and (map? coord)
       (or (contains? coord :local/root)
           (contains? coord :mvn/version)
           (contains? coord :git/url)
           (contains? coord :git/tag)
           (contains? coord :git/sha))))

(defn- coord-shape-valid? [coord]
  (valid-module-coord? coord))

(defn- real-dir? [path]
  (.isDirectory (java.io.File. path)))

(defn absolutize-local-root [coord cwd]
  (if-let [root (:local/root coord)]
    (assoc coord :local/root (abs-path cwd root))
    coord))

(defn local-manifest-path [root fs*]
  (some #(when (fs/exists? fs* %) %)
         [(str root "/resources/isaac-manifest.edn")
          (str root "/src/isaac-manifest.edn")]))

(defn local-root-error [context id coord]
  (when-let [declared-path (:local/root coord)]
    (let [root (local-root-path context coord)
          fs*  (runtime-fs)]
      (cond
        (not (string? declared-path))
        {:key (mod-error-key id) :value "local/root must be a string"}

        (not (or (real-dir? root) (fs/dir? fs* root)))
        {:key (mod-error-key id) :value "local/root path does not resolve"}))))

(defn- needs-classpath-preload? [coord]
  "True when discovery must add this coordinate to the runtime classpath.
   Mem-fs fixtures with only isaac-manifest.edn skip preload — the same
   shortcut resolve-manifest-resource uses before ensure-module-deps!."
  (when (map? coord)
    (if (:local/root coord)
      (let [fs* (runtime-fs)
            root (:local/root coord)]
        (or (fs/exists? fs* (str root "/deps.edn"))
            (not (local-manifest-path root fs*))))
      (or (contains? coord :mvn/version)
          (contains? coord :git/url)
          (contains? coord :git/tag)
          (contains? coord :git/sha)))))

