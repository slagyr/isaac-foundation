(ns isaac.config.root
  "Bootstrap root resolution: where Isaac keeps config and state on disk.
   Sibling to config.paths / config.nav — requires only fs and logger, not
   config.loader.

   After the isaac-root collapse, --root <dir> points at the data directory
   directly; the only place the .isaac literal survives is as the
   default-root value when nothing else is provided.

   Lookup chain (first hit wins):
     1. --root <dir>            CLI flag (new, preferred)
     2. fallback                test-injection slot
     3. ISAAC_ROOT              environment variable
     4. ~/.config/isaac.edn     {:root \"/some/dir\"}
     5. ~/.isaac.edn            {:root \"/some/dir\"}
     6. ~/.isaac                default."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli.host :as host]
    [isaac.fs :as fs]
    [isaac.logger :as log]))

(def ^:dynamic *root* nil)
(def ^:dynamic *user-home* nil)
(def ^:dynamic *pointer-config* nil)

(defonce ^:private process-root* (atom nil))

(defn user-home []
  (or *user-home* (System/getProperty "user.home")))

(defn default-root
  "The default Isaac root. No args: ~/.isaac. A string home: <home>/.isaac.
   A CLI opts map: :root wins, else :home (or user-home) + /.isaac."
  ([] (str (user-home) "/.isaac"))
  ([home-or-opts]
   (if (map? home-or-opts)
     (or (:root home-or-opts)
         (default-root (or (:home home-or-opts) (user-home))))
     (str home-or-opts "/.isaac"))))

(defn current-root
  "Returns the currently-active Isaac root. Thread-local binding wins,
   then the process-wide value set by init-root!, then the default."
  []
  (or *root* @process-root* (default-root)))

(defn init-root!
  "Sets the process-wide root. Called at server boot so all threads can
   reach the root without explicit threading."
  [dir]
  (reset! process-root* dir))

(defn- absolute-path [path]
  (if (and (string? path) (str/starts-with? path "/"))
    path
    (str (host/cwd) "/" path)))

(defn- expand-tilde [path]
  (cond
    (not (string? path))         path
    (= "~" path)                 (user-home)
    (str/starts-with? path "~/") (str (user-home) (subs path 1))
    :else                        path))

(defn pointer-config
  "Raw home pointer config. XDG file wins; malformed/missing files fall through."
  [fs*]
  (or *pointer-config*
      (letfn [(read-pointer [path]
            (try
              (let [real-fs? (= "isaac.fs.RealFs" (.getName (class fs*)))
                    content (or (when (fs/exists? fs* path) (fs/slurp fs* path))
                                (when (and real-fs? (.exists (java.io.File. path))) (slurp path)))]
                (when content (edn/read-string content)))
              (catch Exception _
                (log/warn :root/pointer-file-invalid :path path)
                nil)))]
        (or (read-pointer (str (user-home) "/.config/isaac.edn"))
            (read-pointer (str (user-home) "/.isaac.edn"))))))

(defn- pointer-root [fs*]
  (some-> (pointer-config fs*) :root expand-tilde))

(defn- env-root []
  (let [v (host/env "ISAAC_ROOT")]
    (when-not (str/blank? v) v)))

(defn resolve-root
  "Walks the lookup chain. `explicit-root` is the --root value (or nil);
   `fallback-root` is a test-injection slot (or nil). Returns an absolute
   path."
  [explicit-root fallback-root fs*]
  (-> (or explicit-root
          fallback-root
          (env-root)
          (pointer-root fs*)
          (default-root))
      absolute-path))

(def root-lookup-precedence
  "Human-readable lines for the root resolution chain (first hit wins)."
  ["1. --root <dir>            CLI flag"
   "2. ISAAC_ROOT              environment variable"
   "3. ~/.config/isaac.edn     {:root \"/path\"}"
   "4. ~/.isaac.edn            {:root \"/path\"}"
   "5. ~/.isaac                default"])