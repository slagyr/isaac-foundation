(ns isaac.module.loader
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.protocol :as module]
    [isaac.module.manifest :as manifest]
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.registered-in :as registered-in]))

(def ^:dynamic *resolve-classpath?* true)

(defonce ^:private activated-modules* (atom #{}))
(defonce ^:private loaded-module-coords* (atom #{}))
(defonce ^:private started-modules* (atom []))

;; ----- Registry handler injection -----
;; module.loader needs to call into registries (isaac.api, tool.registry,
;; slash.registry, server.routes) and a config-snapshot reader (config.loader)
;; during activation, but those nses transitively require module.loader. To
;; break the cycle, each one self-registers a handler at load time and
;; module.loader dispatches through this table instead of compile-time
;; requires.
(defonce ^:private handlers* (atom {}))

(def ^:private multi-handler-kinds #{:clear-registrations})

(defn register-handler!
  "Registers a handler fn that module.loader will invoke during activation.
   Called by registry namespaces at their load time.

   Known kinds:
     :clear-registrations (fn [] => any)                  — clears module-contributed registrations
     :user-config         (fn [root-key entry-id] => map) — reads user config for an extension

   Every other extension kind has migrated to a :isaac.server/* berth
   processed by `process-manifest-berths!` (phases 4–8 of brth):
   :isaac/cli (phase 4), :route (phase 5), :tools (phase 6),
   :slash-commands / :llm/api / :hook / :provider (phase 7), :comm
   (phase 8)."
  [kind handler-fn]
  (swap! handlers*
         (fn [handlers]
           (if (contains? multi-handler-kinds kind)
             (update handlers kind (fnil conj []) handler-fn)
             (assoc handlers kind handler-fn)))))

(defn- handler-for [kind]
  (or (get @handlers* kind)
      (throw (ex-info (str "no module-loader handler registered for kind " kind
                           " (registry namespace must self-register at load time)")
                      {:kind kind :registered-kinds (vec (sort (keys @handlers*)))}))))

(defn- handlers-for [kind]
  (get @handlers* kind []))

(def ^:private foundation-module-id :isaac.foundation)

;; Transitive module deps.edn files pin foundation for standalone dev; the
;; packaged seed already provides it — never let a module pull a second copy.
(def ^:private seed-foundation-lib 'io.github.slagyr/isaac-foundation)

(def ^:private platform-module-ids
  #{:isaac.foundation :isaac.server})

(defn- runtime-fs []
  (or (fs/instance) (throw (ex-info "module.loader requires :fs in system" {}))))

(declare activate!)
(declare builtin-index)
(declare foundation-index)
(declare invalidate-builtin-index!)
(declare resolve-symbol!)

(defn- ->module-id [raw]
  (cond
    (keyword? raw) raw
    (symbol? raw)  (keyword (str raw))
    (string? raw)  (keyword raw)
    :else          nil))

(defn- id-str [id]
  (cond
    (keyword? id) (subs (str id) 1)
    (symbol? id)  (str id)
    (string? id)  id
    :else         (str id)))

(defn- ->lib-sym [id]
  (let [s (id-str id)]
    (if (str/includes? s "/")
      (symbol s)
      (symbol s s))))

(defn- mod-error-key [id]
  (str "modules[\"" (id-str id) "\"]"))

(defn- manifest-error-key [id field]
  (str "module-index[\"" (id-str id) "\"]." (name field)))

(defn- manifest-errors [id result]
  (mapv (fn [[field msg]]
          {:key   (manifest-error-key id field)
           :value msg})
        (cs/message-map result)))

(defn- read-manifest-edn [path]
  (try
    (edn/read-string (if-let [fs* (and (string? path) (fs/instance))]
                       (if (fs/exists? fs* path)
                         (fs/slurp fs* path)
                         (slurp path))
                       (slurp path)))
    (catch Exception _ nil)))

(defn- abs-path [cwd path]
  (if (or (str/starts-with? path "/")
          (re-matches #"[A-Za-z]:.*" path))
    path
    (str cwd "/" path)))

(defn- absolute-path? [path]
  (or (str/starts-with? path "/")
      (re-matches #"[A-Za-z]:.*" path)))

(defn- coord-directory [coord context]
  (when-let [root (:local/root coord)]
    (if (absolute-path? root)
      root
      (abs-path (:cwd context) root))))

(defn- resolve-nested-dep-coord [parent-dir dep-coord]
  (if-let [root (:local/root dep-coord)]
    (if (absolute-path? root)
      dep-coord
      (assoc dep-coord :local/root (.getCanonicalPath (java.io.File. (java.io.File. parent-dir) root))))
    dep-coord))

(defn- local-root-path [context coord]
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

(defn- ensure-dynamic-classloader!
  "`clojure.repl.deps/add-libs` requires a `DynamicClassLoader` on the
   current thread. Bare `clj -M` doesn't install one, so we wrap whatever
   loader is there. Bb manages its own classpath via `babashka.deps`."
  []
  (let [thread (Thread/currentThread)
        cl    (.getContextClassLoader thread)]
    (when-not (instance? clojure.lang.DynamicClassLoader cl)
      (.setContextClassLoader thread (clojure.lang.DynamicClassLoader. cl)))))

(defn- classpath-coord [coord]
  (update coord :exclusions
          (fn [xs]
            (vec (set/union (set xs) #{seed-foundation-lib})))))

(defn- invoke-add-deps! [deps-map]
  (when (seq deps-map)
    (let [bb-add-deps  (try (requiring-resolve 'babashka.deps/add-deps)
                            (catch Exception _ nil))
          clj-add-libs (try (requiring-resolve 'clojure.repl.deps/add-libs)
                            (catch Exception _ nil))]
      (cond
        bb-add-deps
        (bb-add-deps {:deps deps-map})

        clj-add-libs
        (try
          (binding [clojure.core/*repl* true]
            (ensure-dynamic-classloader!)
            (clj-add-libs deps-map))
          (catch Exception e
            (log/warn :module/add-libs-failed :deps deps-map :error (.getMessage e))))

        :else
        (log/warn :module/no-add-deps-mechanism :deps deps-map)))))

(defn- add-module-deps! [id coord]
  (invoke-add-deps! {(->lib-sym id) (classpath-coord coord)}))

(defn- add-modules-deps! [id-coord-pairs]
  (let [deps-map (into {}
                       (map (fn [[id coord]]
                              [(->lib-sym id) (classpath-coord coord)])
                            id-coord-pairs))]
    (invoke-add-deps! deps-map)))

(defn- mark-modules-loaded! [id-coord-pairs]
  (swap! loaded-module-coords* into (set id-coord-pairs))
  (invalidate-builtin-index!))

(defn- unload-module-pairs [pairs]
  (remove (fn [[id coord]] (contains? @loaded-module-coords* [id coord])) pairs))

(defn- ensure-module-deps! [id coord]
  (when-not (contains? @loaded-module-coords* [id coord])
    (add-module-deps! id coord)
    (mark-modules-loaded! [[id coord]])))

(defn- absolutize-local-root [coord cwd]
  (if-let [root (:local/root coord)]
    (assoc coord :local/root (abs-path cwd root))
    coord))

(defn- local-manifest-path [root fs*]
  (some #(when (fs/exists? fs* %) %)
         [(str root "/resources/isaac-manifest.edn")
          (str root "/src/isaac-manifest.edn")]))

(defn- local-root-error [context id coord]
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

(defn- preload-explicit-module-deps! [raw-modules cwd]
  (when (and *resolve-classpath?* (seq raw-modules))
    (let [ctx   {:cwd cwd}
          pairs (vec (keep (fn [[raw-id coord]]
                             (when-let [id (->module-id raw-id)]
                               (when (map? coord)
                                 (let [abs-coord (absolutize-local-root coord cwd)]
                                   (when (and (needs-classpath-preload? abs-coord)
                                              (not (local-root-error ctx id abs-coord)))
                                     [id abs-coord])))))
                           raw-modules))
          unloaded (vec (unload-module-pairs pairs))]
      (when (seq unloaded)
        (add-modules-deps! unloaded)
        (mark-modules-loaded! unloaded)))))

(defn- resource-urls [resource-name]
  (let [loader (or (.getContextClassLoader (Thread/currentThread))
                   (clojure.lang.RT/baseLoader))]
    (enumeration-seq (.getResources loader resource-name))))

(defn- manifest-resource [id]
  (some (fn [url]
          (when (= id (:id (read-manifest-edn url)))
            url))
        (resource-urls "isaac-manifest.edn")))

(defn- read-module-deps-edn [coord context]
  (when-let [dir (coord-directory coord context)]
    (let [fs*       (runtime-fs)
          deps-file (str dir "/deps.edn")]
      (when (fs/exists? fs* deps-file)
        (try
          (:deps (edn/read-string (fs/slurp fs* deps-file)))
          (catch Exception _ nil))))))

(defn- module-id-from-dep-coord [coord context]
  (when (map? coord)
    (let [fs* (runtime-fs)]
      (when-let [path (coord-directory coord context)]
        (when-let [manifest-path (local-manifest-path path fs*)]
          (try
            (:id (manifest/read-manifest manifest-path fs*))
            (catch Exception _ nil)))))))

(defn- transitive-module-requirements
  "Module ids reachable from `coord` via deps.edn edges that ship isaac-manifest.edn."
  [coord context]
  (loop [pending [coord]
         seen    #{}
         found   #{}]
    (if (empty? pending)
      found
      (let [c (first pending)]
        (if (contains? seen c)
          (recur (rest pending) seen found)
          (let [seen*      (conj seen c)
                module-id  (module-id-from-dep-coord c context)
                platform?  (and module-id (contains? platform-module-ids module-id))
                found*     (if (and module-id (not platform?))
                             (conj found module-id)
                             found)
                parent-dir (coord-directory c context)
                child-deps (if platform?
                             []
                             (->> (vals (or (read-module-deps-edn c context) {}))
                                  (map #(resolve-nested-dep-coord parent-dir %))))]
            (recur (into (vec (rest pending)) child-deps) seen* found*)))))))

(defn- loadable-coord [context coord]
  (if-let [root (local-root-path context coord)]
    (assoc coord :local/root root)
    coord))

(defn- explicit-module-map [raw-modules context]
  (into {}
        (keep (fn [[raw-id coord]]
                (when-let [id (->module-id raw-id)]
                  (when (map? coord)
                    [id (loadable-coord context coord)])))
              raw-modules)))

(defn- resolved-module-ids [explicit-modules context]
  (let [explicit-ids (set (keys explicit-modules))
        implied      (reduce (fn [acc coord]
                                 (into acc (transitive-module-requirements coord context)))
                               #{}
                               (vals explicit-modules))]
    (set/union explicit-ids implied)))

(defn- find-dep-coord-for-module [target-id coord context]
  (let [parent-dir (coord-directory coord context)
        deps       (or (read-module-deps-edn coord context) {})]
    (or (some (fn [[_ dep-coord]]
                (let [resolved (resolve-nested-dep-coord parent-dir dep-coord)]
                  (when (= target-id (module-id-from-dep-coord resolved context))
                    resolved)))
              deps)
        (some (fn [[_ dep-coord]]
                (find-dep-coord-for-module target-id
                                            (resolve-nested-dep-coord parent-dir dep-coord)
                                            context))
              deps))))

(defn- classpath-module-index []
  (->> (resource-urls "isaac-manifest.edn")
       (keep (fn [url]
               (when-let [manifest (read-manifest-edn url)]
                 (when-let [id (:id manifest)]
                   [id {:coord {} :manifest manifest :path nil}]))))
       (into {})))

(defn- required-by-map [explicit-modules context]
  (let [explicit-ids (set (keys explicit-modules))]
    (reduce
      (fn [req [explicit-id coord]]
        (let [required (transitive-module-requirements coord context)]
          (reduce (fn [m implied-id]
                    (if (contains? explicit-ids implied-id)
                      m
                      (update m implied-id (fnil conj #{}) explicit-id)))
                  req
                  required)))
      {}
      explicit-modules)))

(defn resolve-manifest-resource [id coord]
  (let [fs* (runtime-fs)]
    (or (when-let [root (:local/root coord)]
          (when-not (fs/exists? fs* (str root "/deps.edn"))
            (local-manifest-path root fs*)))
        (when *resolve-classpath?*
          (when (and (seq coord) (coord-shape-valid? coord))
            (ensure-module-deps! id coord))
          (manifest-resource id)))))

(defn- discover-resolved [id coord path]
  (try
    (let [fs*      (runtime-fs)
          resource (resolve-manifest-resource id coord)]
      (if (nil? resource)
        {:errors [{:key (mod-error-key id) :value "manifest: could not read"}]}
        {:entry {id {:coord    coord
                     :manifest (manifest/read-manifest resource fs*)
                     :path     path}}}))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (cond
          ;; Custom manifest validators (see isaac.module.manifest) emit
          ;; pre-formatted error rows under this key so they can carry the
          ;; exact module-index["id"]... key shape c3kit's nested
          ;; message-map can't reproduce cleanly.
          (:isaac/manifest-errors data)
          {:errors (:isaac/manifest-errors data)}

          (cs/error? data)
          {:errors (manifest-errors id data)}

          :else
          {:errors [{:key (mod-error-key id) :value (.getMessage e)}]})))
    (catch Exception e
      {:errors [{:key (mod-error-key id) :value (.getMessage e)}]})))

(defn- discover-one [context id coord]
  (cond
    ;; Route the foundation module through `foundation-index` so the override seam
    ;; (`*foundation-index-override*`) is the single source of truth — instead
    ;; of having `discover!` re-resolve isaac-manifest.edn from disk.
    (= foundation-module-id id)
    (if-let [entry (get (foundation-index) foundation-module-id)]
      {:entry {foundation-module-id entry}}
      {:errors [{:key (mod-error-key id) :value "manifest: could not read"}]})

    (not (coord-shape-valid? coord))
    {:errors [{:key (mod-error-key id) :value "invalid coordinate"}]}

    :else
    (if-let [error (local-root-error context id coord)]
      {:errors [error]}
      (discover-resolved id (loadable-coord context coord) (:local/root coord)))))

(defn- discover-implied-entry [target-id explicit-modules context]
  (some (fn [[_ coord]]
          (when-let [dep-coord (find-dep-coord-for-module target-id coord context)]
            (let [{:keys [entry]} (discover-resolved target-id dep-coord (:local/root dep-coord))]
              (get entry target-id))))
        explicit-modules))

(defn- merge-resolved-classpath-modules [index explicit-modules context]
  (if-not *resolve-classpath?*
    index
    (let [allowed-ids (resolved-module-ids explicit-modules context)
          implied-ids (set/difference allowed-ids (set (keys index)))
          implied     (into {}
                            (keep (fn [id]
                                    (when-let [entry (discover-implied-entry id explicit-modules context)]
                                      [id entry]))
                                  implied-ids))]
      (merge index implied))))

(defn- cycle-errors [index]
  (let [id->requires (into {} (map (fn [[id e]] [id (keys (get-in e [:manifest :deps] {}))]) index))
        white        (atom (set (keys id->requires)))
        gray         (atom #{})
        found        (atom [])]
    (letfn [(dfs [node]
              (swap! white disj node)
              (swap! gray conj node)
              (doseq [req (get id->requires node [])]
                (when (contains? id->requires req)
                  (cond
                    (contains? @gray req)
                    (swap! found conj {:key   (str "modules[\"" (id-str req) "\"]")
                                       :value (str "dependency cycle detected involving " (id-str req))})

                    (contains? @white req)
                    (dfs req))))
              (swap! gray disj node))]
      (doseq [node (keys id->requires)]
        (when (contains? @white node)
          (dfs node)))
      @found)))

(defn supporting-module-id [module-index berth-id capability]
  (let [cap-key (cond
                  (keyword? capability) capability
                  (string? capability)  (keyword capability)
                  :else                 (keyword (str capability)))]
    (some (fn [[module-id entry]]
            (when (get-in entry [:manifest berth-id cap-key])
              module-id))
          module-index)))

(defonce ^:private foundation-index-cache (atom nil))
(defonce ^:private builtin-index-cache (atom nil))

(defn- invalidate-builtin-index! []
  (reset! builtin-index-cache nil))

;; When bound, replaces the resource-loaded core manifest index.
;; Tests use this to swap in a themed manifest; see spec/isaac/marigold.clj.
(def ^:dynamic *foundation-index-override* nil)

(defn clear-activations! []
  (reset! foundation-index-cache nil)
  (reset! builtin-index-cache nil)
  (reset! activated-modules* #{})
  (reset! started-modules* [])
  (doseq [handler (handlers-for :clear-registrations)]
    (handler)))

(defn clear-caches! []
  (reset! foundation-index-cache nil)
  (reset! builtin-index-cache nil))

(defn- index-entry [resource]
  (let [manifest (manifest/read-manifest resource (fs/instance))]
    [(:id manifest) {:coord {} :manifest manifest :path nil}]))

(defn foundation-index []
  (or *foundation-index-override*
      @foundation-index-cache
      (let [result (if-let [resource (manifest-resource foundation-module-id)]
                     (let [manifest (manifest/read-manifest resource (fs/instance))]
                        {foundation-module-id {:coord {} :manifest manifest :path nil}})
                      {})]
        (reset! foundation-index-cache result)
        result)))

(defn- builtin-manifest-resource? [resource]
  (true? (:builtin? (read-manifest-edn resource))))

(defn- classpath-builtin-index []
  (->> (resource-urls "isaac-manifest.edn")
       (filter builtin-manifest-resource?)
       (map index-entry)
       (into {})))

(defn builtin-index []
  (or *foundation-index-override*
      @builtin-index-cache
      (let [result (merge (foundation-index) (classpath-builtin-index))]
        (reset! builtin-index-cache result)
        result)))

(def server-module-id :isaac.server)

(defn activate-foundation! []
  (activate! foundation-module-id (foundation-index)))

(defn deactivate-foundation! []
  (swap! activated-modules* disj foundation-module-id))

(defn activate-server! []
  (activate! server-module-id (builtin-index)))

(defn- berth-entry-factory-sym [module-index berth-id]
  (some (fn [[_ entry]]
          (get-in entry [:manifest :berths berth-id :schema :value-spec :factory]))
        module-index))

(defn register-builtin-berth-entry!
  "Look up `entry-id` in `berth-id` across builtin manifests and install
   it via the berth's per-entry factory. Called by isaac.tool.builtin to
   lazily register a single built-in tool. Returns nil when the entry is
   not declared in any builtin manifest."
  [berth-id entry-id]
  (let [entry-kw    (keyword entry-id)
        builtin     (builtin-index)
        factory-sym (berth-entry-factory-sym builtin berth-id)
        entry       (some (fn [[_ mod-entry]]
                            (get-in mod-entry [:manifest berth-id entry-kw]))
                          builtin)]
    (when (and entry factory-sym)
      (binding [registered-in/*module-index* builtin]
        ((resolve-symbol! factory-sym) [entry-kw entry])))))

(defn- resolve-symbol! [sym]
  (requiring-resolve sym))

(defn user-config
  "Reads the user-supplied config slot at `[root-key entry-id]` from
   the live config snapshot. Returns {} when nothing is configured.
   Public so berth factories (e.g. tool.registry/register-tool-entry!
   for the :isaac.server/tools berth) can read their per-entry
   user config without re-implementing the lookup."
  [root-key entry-id]
  (or ((handler-for :user-config) root-key entry-id) {}))

(defn- register-extensions! [_manifest]
  ;; Phases 4–8 of the berth epic moved every extension kind into
  ;; :isaac.server/* berths processed by process-manifest-berths!.
  ;; activate! still runs this for backwards compat with old call
  ;; sites; it's now a no-op.
  nil)

(defn- call-bootstrap! [bootstrap]
  (when bootstrap
    ((resolve-symbol! bootstrap))))

(defn activate! [module-id module-index]
  (let [id          (or (->module-id module-id) module-id)
        module-meta (get module-index id)
        manifest    (:manifest module-meta)
        bootstrap   (:bootstrap manifest)
        coord       (:coord module-meta)]
    (cond
      (contains? @activated-modules* id)
      :already-active

      (nil? manifest)
      (let [error (ex-info (str "module activation failed: " (id-str id))
                           {:type      :module/activation-failed
                            :module-id id
                            :bootstrap bootstrap
                            :reason    :missing-manifest})]
        (log/error :module/activation-failed :module (id-str id) :reason :missing-manifest)
        (throw error))

      :else
      (try
        (when (:path module-meta)
          (ensure-module-deps! id coord))
        (register-extensions! manifest)
        (call-bootstrap! bootstrap)
        (swap! activated-modules* conj id)
        (log/info :module/activated :bootstrap (some-> bootstrap str) :module (id-str id))
        :activated
        (catch Exception e
          (let [error (ex-info (str "module activation failed: " (id-str id))
                               {:type      :module/activation-failed
                                :module-id id
                                :bootstrap bootstrap}
                               e)]
            (log/error :module/activation-failed
                       :bootstrap (some-> bootstrap str)
                       :error  (.getMessage e)
                       :module (id-str id))
            (throw error)))))))

;; Top-level manifest keys that are NOT berth contributions: the
;; existing reserved + extension-kind set carried by manifest.clj. Any
;; OTHER namespaced top-level key in a consumer manifest is treated
;; as a contribution and validated against the matching berth's
;; :manifest :schema (see validate-contributions!).
(def ^:private reserved-top-level-keys
  (into @#'manifest/known-meta-keys @#'manifest/known-extend-kinds))

(defn- contribution-key? [k]
  (and (qualified-keyword? k)
       (not (contains? reserved-top-level-keys k))))

(defn- collect-contributions [manifest-map]
  (keep (fn [[k v]]
          (when (contribution-key? k) [k v]))
        manifest-map))

(defn- find-berth-decl [module-index berth-key]
  (some (fn [[_provider-id entry]]
          (get-in entry [:manifest :berths berth-key]))
        module-index))

(defn- ns-keyword->str [kw]
  (str (namespace kw) "/" (name kw)))

(defn- unknown-berth-error [consumer-id berth-key]
  {:key   (str "module-index[\"" (id-str consumer-id) "\"][" berth-key "]")
   :value "berth not declared by any installed module"})

(defn- flatten-error-paths
  "Walk a c3kit message-map (nested keywords → message strings) producing
   flat [path-vec message-string] pairs."
  ([m] (flatten-error-paths m []))
  ([m prefix]
   (cond
     (map? m) (mapcat (fn [[k v]] (flatten-error-paths v (conj prefix k))) m)
     :else    [[prefix (str m)]])))

(defn- format-contribution-suffix
  "First path segment is the contribution-map's outer key (rendered as
   [<kw>]); subsequent segments are dot-prefixed field names. Matches
   the bean's expected shape: berth[:key].field..."
  [path]
  (let [[head & tail] path]
    (str (when head (str "[" head "]"))
         (apply str (map #(str "." (name %)) tail)))))

(defn- berth-lexicon
  "Active lexicon with `:present?` re-messaged for berth contributions —
   apron's default 'is required' becomes 'must be present', which is
   the wording ISAAC surfaces consistently for missing berth fields."
  []
  (-> (@#'lexicon/active-lexicon)
      (assoc-in [:validations :present?]
                {:validate cs/present? :message "must be present"})))

(defn- contribution-validation-errors [consumer-id berth-key value berth-schema]
  (let [prefix (str "module-index[\"" (id-str consumer-id) "\"]."
                    (ns-keyword->str berth-key))
        result (try (binding [cs/*lexicon* (berth-lexicon)]
                      (cs/conform berth-schema value))
                    (catch Throwable _ nil))]
    (when (and result (cs/error? result))
      (->> (cs/message-map result)
           flatten-error-paths
           (mapv (fn [[path msg]]
                   {:key   (str prefix (format-contribution-suffix path))
                    :value msg}))))))

(defn- validate-contributions! [module-index]
  ;; Bind *module-index* so berth schemas using the :registered-in?
  ;; primitive can resolve sibling contributions across the loaded set
  ;; (the validator is data-only; the foundation supplies the view).
  (binding [registered-in/*module-index* module-index]
    (vec
      (mapcat
        (fn [[consumer-id entry]]
          (mapcat
            (fn [[berth-key value]]
              (if-let [berth-decl (find-berth-decl module-index berth-key)]
                (contribution-validation-errors consumer-id berth-key value
                                                (:schema berth-decl))
                [(unknown-berth-error consumer-id berth-key)]))
            (collect-contributions (:manifest entry))))
        module-index))))

(defn- lifecycle-error
  [message data cause]
  (ex-info message (assoc data :type :module/lifecycle-failed) cause))

(defn- lifecycle-deps [module-index module-id]
  (->> (keys (get-in module-index [module-id :manifest :deps] {}))
       (filter #(contains? module-index %))
       (sort-by id-str)))

(defn- cycle-path [stack module-id]
  (conj (vec (drop-while #(not= % module-id) stack)) module-id))

(defn topological-order
  "Module ids in dependency order — deps before dependents, alphabetical
   tie-break. The order activation processes berths in; the config gather
   orders contributions by it too so both agree on last-wins ownership."
  [module-index]
  (let [visiting (atom #{})
        visited  (atom #{})
        order    (atom [])]
    (letfn [(visit [module-id stack]
              (cond
                (contains? @visited module-id)
                nil

                (contains? @visiting module-id)
                (let [cycle   (cycle-path stack module-id)
                      message (str "module dependency cycle detected: "
                                   (str/join " -> " (map id-str cycle)))]
                  (throw (lifecycle-error message
                                          {:reason    :dependency-cycle
                                           :module-id module-id
                                           :cycle     cycle}
                                          nil)))

                :else
                (do
                  (swap! visiting conj module-id)
                  (doseq [dep (lifecycle-deps module-index module-id)]
                    (visit dep (conj stack module-id)))
                  (swap! visiting disj module-id)
                  (swap! visited conj module-id)
                  (swap! order conj module-id))))]
      (doseq [module-id (sort-by id-str (keys module-index))]
        (visit module-id []))
      @order)))

(defn- resolve-module-factory! [module-id factory-sym]
  (try
    (resolve-symbol! factory-sym)
    (catch Exception e
      (throw (lifecycle-error (str "module factory resolution failed for " (id-str module-id)
                                   ": " factory-sym)
                              {:reason    :resolve-factory
                               :module-id module-id
                               :factory   factory-sym}
                              e)))))

(defn- instantiate-module! [module-id {:keys [manifest coord path]}]
  (let [factory-sym (:factory manifest)
        _           (when path
                      (ensure-module-deps! module-id coord))
        factory     (resolve-module-factory! module-id factory-sym)
        instance    (try
                      (factory)
                      (catch Exception e
                        (throw (lifecycle-error (str "module factory threw for " (id-str module-id))
                                                {:reason    :factory-threw
                                                 :module-id module-id
                                                 :factory   factory-sym}
                                                e))))]
    (when-not (module/module? instance)
      (throw (lifecycle-error (str "module factory returned non-Module for " (id-str module-id))
                              {:reason    :not-a-module
                               :module-id module-id
                               :factory   factory-sym
                               :value-type (some-> instance class str)}
                              nil)))
    instance))

(defn- eager-load? [module-id {:keys [path coord manifest]}]
  (and (:factory manifest)
       (or (contains? platform-module-ids module-id)
           (some? path)
           (:local/root coord))))

(defn- eager-load-module-index [module-index]
  (into {} (filter (fn [[id entry]] (eager-load? id entry)) module-index)))

(defn- loaded-module-ids []
  (set (map :id @started-modules*)))

(defn- rollback-loaded-modules! [started]
  (doseq [{:keys [id instance]} (reverse started)]
    (try
      (module/run-unload! instance)
      (catch Exception e
        (log/error :module/unload-failed
                   :error  (.getMessage e)
                   :module (id-str id))))))

(defn- unload-module-ids! [ids]
  (when (seq ids)
    (let [to-unload (vec (reverse (filter #(contains? ids (:id %)) @started-modules*)))]
      (rollback-loaded-modules! to-unload)
      (swap! started-modules* (fn [started]
                                (vec (remove #(contains? ids (:id %)) started)))))))

(defn load-modules!
  "Instantiate each eager-load Module in `module-index` (topological order)
   and run on-load. Classpath builtin contributions without a user
   :modules declaration stay lazy (activate! on first use). Idempotent —
   already-loaded module ids are skipped."
  [module-index]
  (let [index     (eager-load-module-index module-index)
        already   (loaded-module-ids)
        order     (topological-order index)
        pending   (vec (remove already order))]
    (if (empty? pending)
      :loaded
      (let [instances (mapv (fn [module-id]
                              {:id       module-id
                               :instance (instantiate-module! module-id (get index module-id))})
                            pending)
            started   (atom [])]
        (try
          (doseq [{:keys [id instance] :as loaded-module} instances]
            (try
              (module/run-load! instance)
              (swap! started conj loaded-module)
              (catch Exception e
                (throw (lifecycle-error (str "module load failed for " (id-str id))
                                        {:reason    :load-failed
                                         :module-id id}
                                        e)))))
          (swap! started-modules* into @started)
          :loaded
          (catch Exception e
            (rollback-loaded-modules! @started)
            (throw e)))))))

(defn reconcile-modules!
  "Unload eager-load modules removed from `module-index`, then load any
   new ones. Idempotent when the eager-load set is unchanged."
  [module-index]
  (let [index   (eager-load-module-index module-index)
        loaded  (loaded-module-ids)
        target  (set (keys index))
        removed (set/difference loaded target)]
    (unload-module-ids! removed)
    (load-modules! module-index)))

(defn shutdown-modules! []
  (rollback-loaded-modules! @started-modules*)
  (reset! started-modules* [])
  :stopped)

(defn start-modules!
  "Deprecated alias for `load-modules!`. Resets loaded modules first so
   callers that expect a fresh boot still get one — prefer
   `reconcile-modules!` for config-load paths."
  [module-index]
  (shutdown-modules!)
  (load-modules! module-index))

;; ----- Manifest-only berth processing (isaac-8yxs) -----

(defn- collect-berth-declarations
  "Walks `module-index` and returns a seq of [berth-id berth-decl] pairs
   across all modules. Berth declarations live at
   `[<provider-id> :manifest :berths <berth-id>]`."
  [module-index]
  (mapcat (fn [[_ entry]]
            (seq (get-in entry [:manifest :berths] {})))
          module-index))

(defn- manifest-only-berth?
  "A berth declares `:manifest` (the contribution shape) without a
   `:config` shape — i.e., contributions come from manifests only, not
   user config slots."
  [berth-decl]
  (and (contains? berth-decl :schema)
       (not (contains? berth-decl :config))))

(defn- entry-factory-symbol
  "Walks a berth's :manifest :schema looking for an entry-level
   :factory. For :type :seq berths it lives on :spec; for :type :map on
   :value-spec; for scalar/map berths it can live at the top of the
   schema. Returns the unresolved symbol or nil."
  [berth-schema]
  (some :factory [berth-schema (:spec berth-schema) (:value-spec berth-schema)]))

(defn- berth-contribution-entries
  "Returns the entries `(factory entry)` should be called with, given
   the schema shape and a contribution value. Seq → each element;
   map → each `[id entry]` MapEntry so factories can read both the
   contribution id and value (matters for the :tools case where the
   id is the tool's name); scalar → the value itself."
  [berth-schema contribution]
  (case (:type berth-schema)
    :seq contribution
    :map (seq contribution)
    [contribution]))

(defn- contributions-to-berth
  "All [consumer-id contribution-value] pairs in `module-index` for
   `berth-id`."
  [module-index berth-id]
  (keep (fn [[consumer-id entry]]
          (when-let [v (get-in entry [:manifest berth-id])]
            [consumer-id v]))
        module-index))

(defn- process-manifest-berth!
  "For one manifest-only berth: resolve its entry-factory and invoke it
   once per contribution entry across all consumers. Returns a vec of
   error rows (empty on success)."
  [module-index berth-id berth-decl]
  (let [berth-schema (:schema berth-decl)
        factory-sym  (entry-factory-symbol berth-schema)]
    (if-not factory-sym
      ;; Foundation default for berths without an entry-level factory
      ;; (the simple merge-to-[<berth-id>] form) is intentionally
      ;; deferred — see bean's "Out of scope".
      []
      (if-let [factory (try (resolve-symbol! factory-sym) (catch Throwable _ nil))]
        ;; Process consumers in topological (load) order so the gather
        ;; (schema-compose) and the activation agree on last-wins
        ;; ownership. For keyed (:map) berths, a later module's entry
        ;; overriding an earlier one's by id is audible — :<kind>/override
        ;; at :warn — matching the gather's override event (isaac-un18).
        (let [order  (try (zipmap (topological-order module-index) (range)) (catch Throwable _ nil))
              ranked (sort-by (fn [[cid _]] (if order (get order cid) (id-str cid)))
                              (contributions-to-berth module-index berth-id))
              keyed? (= :map (:type berth-schema))
              evt    (keyword (name berth-id) "override")
              seen   (atom #{})]
          (vec
            (mapcat
              (fn [[consumer-id contribution]]
                (keep
                  (fn [entry]
                    (when keyed?
                      (let [id (key entry)]
                        (when (contains? @seen id)
                          (log/warn evt :berth (str berth-id) :entry (id-str id)
                                    :module (when consumer-id (id-str consumer-id))))
                        (swap! seen conj id)))
                    (try
                      (factory entry)
                      nil
                      (catch Throwable t
                        ;; Don't let one consumer's broken factory abort
                        ;; the whole berth pass. Log the activation
                        ;; failure (mirrors activate!'s legacy error
                        ;; channel) and collect a structured error row.
                        (log/error :module/activation-failed
                                   :module (when consumer-id (id-str consumer-id))
                                   :berth  (str berth-id)
                                   :error  (.getMessage t))
                        {:key   (str "module-index[\"" (id-str consumer-id) "\"].berths[" berth-id "]")
                         :value (.getMessage t)})))
                  (berth-contribution-entries berth-schema contribution)))
              ranked)))
        [{:key   (str "module-index.berths[" berth-id "].factory")
          :value (str "could not resolve factory symbol: " factory-sym)}]))))

(defn process-manifest-berths!
  "For each berth in `module-index` whose schema declares an entry-level
   `:factory`, invokes `(factory entry)` once per contribution entry.
   Factories typically register the entry in the nexus (routes are the
   canonical case — the foundation hands each route map to its
   registration factory; the entry lands at the conventional path so
   the platform can find it later).

   Run AFTER load-config-result has returned and its nested-nexus
   wrap has exited — otherwise the wrap's `install! previous` rolls
   back any new top-level keys the factories register. Returns a vec
   of error rows (empty on success)."
  [module-index]
  (binding [registered-in/*module-index* module-index]
    (vec (mapcat (fn [[berth-id berth-decl]]
                   (when (manifest-only-berth? berth-decl)
                     (process-manifest-berth! module-index berth-id berth-decl)))
                 (collect-berth-declarations module-index)))))

(defn- pending-deps
  "Pairs of [consumer-id dep-id coord] for deps not yet in `index`."
  [index]
  (mapcat (fn [[consumer-id entry]]
            (let [deps (get-in entry [:manifest :deps])]
              (when (map? deps)
                (keep (fn [[dep-id coord]]
                        (when (and (not (contains? index dep-id))
                                   (map? coord))
                          [consumer-id dep-id coord]))
                      deps))))
          index))

(defn- dep-resolution-error [consumer-id dep-id]
  {:key   (str "module-index[\"" (id-str consumer-id) "\"].deps[" dep-id "]")
   :value "failed to resolve coordinate"})

(defn- resolve-deps!
  "Iteratively walks each loaded manifest's `:deps` and resolves any
   modules not already in `index` (delegating to discover-one, which
   routes through `tools.deps`/bb internals for non-local-root coords).
   Closes over the transitive set; reports each failed resolution as
   `module-index[\"<consumer>\"].deps[<dep-id>]` so the user can see
   which consumer dragged the offending dep in. Index membership
   doubles as a cycle guard — A → B → A stops when B sees A already
   resolved."
  [context initial-index]
  (loop [index  initial-index
         errors []]
    (let [pending (pending-deps index)]
      (if (empty? pending)
        {:index index :errors errors}
        (let [{:keys [new-entries new-errors]}
              (reduce
                (fn [{:keys [new-entries new-errors]} [consumer-id dep-id coord]]
                  (cond
                    (contains? index dep-id)        {:new-entries new-entries
                                                     :new-errors  new-errors}
                    (contains? new-entries dep-id)  {:new-entries new-entries
                                                     :new-errors  new-errors}
                    :else
                    (let [{:keys [entry] mod-errors :errors} (discover-one context dep-id coord)]
                      (if (seq mod-errors)
                        {:new-entries new-entries
                         :new-errors  (conj new-errors (dep-resolution-error consumer-id dep-id))}
                        {:new-entries (merge new-entries entry)
                         :new-errors  new-errors}))))
                {:new-entries {} :new-errors []}
                pending)]
          (if (empty? new-entries)
            ;; No forward progress — stop. Any remaining unresolved deps
            ;; landed in new-errors this pass.
            {:index index :errors (into errors new-errors)}
            (recur (merge index new-entries) (into errors new-errors))))))))

(defn duplicate-berth-declaration-errors
  "A berth-id may be declared by only one module. Two modules declaring
   the same berth would silently first-win (find-berth-decl takes the
   first), validating contributions against whichever won the walk — so
   flag it as a located error at discovery."
  [module-index]
  (->> module-index
       (mapcat (fn [[module-id entry]]
                 (for [berth-id (keys (get-in entry [:manifest :berths] {}))]
                   [berth-id module-id])))
       (group-by first)
       (sort-by key)
       (keep (fn [[berth-id pairs]]
               (let [modules (distinct (map second pairs))]
                 (when (> (count modules) 1)
                   {:key   (str "berths[" berth-id "]")
                    :value (str "berth declared by multiple modules: "
                                (str/join ", " (map id-str (sort-by id-str modules))))}))))
       vec))

(defn discover!
  "Resolves module coordinates from config :modules and returns
   {:index {...} :errors [...]}."
  [config context]
  (let [declared    (get config :modules {})
        raw-modules (when (map? declared) declared)]
    (preload-explicit-module-deps! raw-modules (or (:cwd context) (System/getProperty "user.dir")))
    (if (and (some? declared) (not (map? declared)))
      {:index  (builtin-index)
       :errors [{:key "modules"
                 :value "must be a map of id to coordinate (legacy vector shape)"}]}
      (let [{init-index :index init-errors :errors}
            (reduce-kv (fn [{:keys [index errors]} raw-id coord]
                         (let [id (->module-id raw-id)]
                           (if (or (nil? id) (not (map? coord)))
                             {:index  index
                              :errors (conj errors {:key   (mod-error-key (or id raw-id))
                                                    :value "invalid coordinate"})}
                             (let [{entry :entry mod-errors :errors} (discover-one context id coord)]
                               {:index  (merge index entry)
                                :errors (into errors (or mod-errors []))}))))
                       {:index (builtin-index) :errors []}
                       raw-modules)
            explicit-modules (explicit-module-map raw-modules context)
            merged-index   (merge-resolved-classpath-modules init-index explicit-modules context)
            {:keys [index errors]} (resolve-deps! context merged-index)]
        ;; Note: manifest-only berth processing (per-entry :factory
        ;; invocation, isaac-8yxs) must run OUTSIDE the load's
        ;; nested-nexus wrap or the wrap's restore discards any
        ;; nexus registrations the factories make. Callers invoke
        ;; process-manifest-berths! after load returns.
        {:index  index
         :errors (into (into init-errors errors)
                       (concat (cycle-errors index)
                               (duplicate-berth-declaration-errors index)
                               (validate-contributions! index)))}))))

(defn- inspect-module-status [context id coord]
  (cond
    (not (map? coord)) :invalid
    (not (coord-shape-valid? coord)) :invalid
    (some? (local-root-error context id coord)) :invalid
    :else :ok))

(defn list-configured-modules
  "Returns {:modules [{:id :coord :status :required-by}]} for explicit config
   entries plus transitive module deps (deps.edn-native). Resolves classpath
   for implied modules; :required-by is [] for explicit, requirer ids for implied."
  [config context]
  (binding [*resolve-classpath?* true]
    (let [declared (get config :modules {})]
      (if (or (nil? declared) (not (map? declared)))
        {:modules []}
        (let [explicit-modules (explicit-module-map declared context)
              explicit-ids     (set (keys explicit-modules))
              requirers        (required-by-map explicit-modules context)
              allowed-ids      (resolved-module-ids explicit-modules context)
              implied-ids      (sort (set/difference allowed-ids explicit-ids))
              explicit-rows
              (vec
                (for [[raw-id coord] (sort-by (fn [[k _]] (id-str (or (->module-id k) k))) declared)
                      :let [id (or (->module-id raw-id) raw-id)]]
                  (cond-> {:id           id
                           :status       (inspect-module-status context id coord)
                           :required-by  []}
                    (map? coord) (assoc :coord coord))))
              implied-rows
              (vec
                (for [id implied-ids]
                  {:id          id
                   :status      :ok
                   :required-by (vec (sort (get requirers id #{})))}))]
          {:modules (vec (concat explicit-rows implied-rows))})))))

(defn compose-config-modules!
  "Adds every valid :modules coordinate to the runtime classpath in one
   tools.deps resolution pass. :local/root paths are resolved relative to
   `cwd` (default user.dir) so packaged launchers can live outside the
   checkout. Foundation is excluded from module transitive deps — the seed
   on the classpath is authoritative."
  ([config] (compose-config-modules! config (System/getProperty "user.dir")))
  ([config cwd]
   (when-let [modules (and (map? (:modules config)) (seq (:modules config)))]
     (let [pairs (vec (keep (fn [[raw-id coord]]
                              (when-let [id (->module-id raw-id)]
                                (when (map? coord)
                                  [id (absolutize-local-root coord cwd)])))
                            modules))
           unloaded (vec (unload-module-pairs pairs))]
       (when (seq unloaded)
         (add-modules-deps! unloaded)
         (mark-modules-loaded! unloaded))))))
