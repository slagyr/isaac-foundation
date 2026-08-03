(ns isaac.module.classpath
  "Classpath mutation for modules — dynamic classloader, add-deps, loaded-coord
   tracking, and preload application. Owns the dynamic vars bound by
   isaac.startup.classpath-cache."
  (:require
    [clojure.set :as set]
    [isaac.logger :as log]
    [isaac.module.coords :as coords]))

(def ^:dynamic *resolve-classpath?* true)

;; When bound (isaac-tki3), `preload-planned-module-deps!` skips
;; `plan-module-classpath-pairs` and uses these pairs instead.
(def ^:dynamic *planned-classpath-pairs* nil)
(def ^:dynamic *skip-preload-planned?* false)

(defonce ^:private loaded-module-coords* (atom #{}))

;; Discovery registers invalidate-builtin-index! here at load time to avoid
;; a require cycle (classpath must not require discovery).
(defonce ^:private on-modules-loaded* (atom nil))

(defn set-on-modules-loaded!
  "Register a zero-arg callback invoked after modules are marked loaded
   (used by discovery to drop the builtin-index cache)."
  [f]
  (reset! on-modules-loaded* f))

(defn clear-loaded-coords!
  "Drop tracked loaded coordinates (test/helper seam)."
  []
  (reset! loaded-module-coords* #{}))

(defn ensure-dynamic-classloader!
  "`clojure.repl.deps/add-libs` requires a `DynamicClassLoader` on the
   current thread. Bare `clj -M` doesn't install one, so we wrap whatever
   loader is there. Bb manages its own classpath via `babashka.deps`."
  []
  (let [thread (Thread/currentThread)
        cl    (.getContextClassLoader thread)]
    (when-not (instance? clojure.lang.DynamicClassLoader cl)
      (.setContextClassLoader thread (clojure.lang.DynamicClassLoader. cl)))))

(defn classpath-coord
  ([coord] (classpath-coord coord #{}))
  ([coord extra-exclusions]
   (update coord :exclusions
           (fn [xs]
             (vec (set/union (set xs) #{coords/seed-foundation-lib} extra-exclusions))))))

(defn invoke-add-deps! [deps-map]
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

(defn add-module-deps! [id coord]
  (invoke-add-deps! {(coords/->lib-sym id) (classpath-coord coord)}))

(defn compose-module-deps-map
  "Pure: the tools.deps `{lib-sym coord}` map for `id-coord-pairs`, each coord
   carrying its sibling exclusions plus the 92p3 seed-foundation exclusion.
   Shared by the bb dynamic-classpath path (`add-modules-deps!`) and the JVM
   launch emitter (`config->launch-deps`) so both produce the identical
   dependency set."
  [id-coord-pairs]
  (let [id->libs (into {} (map (fn [[id _]] [id (coords/module-lib-syms id)]) id-coord-pairs))]
    (into {}
          (map (fn [[id coord]]
                 (let [sibling-exclusions
                       (into #{}
                             (mapcat (fn [[other-id libs]]
                                       (when (not= other-id id) libs))
                                     id->libs))]
                   [(coords/->lib-sym id) (classpath-coord coord sibling-exclusions)]))
               id-coord-pairs))))

(defn add-modules-deps! [id-coord-pairs]
  (invoke-add-deps! (compose-module-deps-map id-coord-pairs)))

(defn trackable-coord [coord]
  (classpath-coord coord))

(defn loaded-module-pair? [[id coord]]
  (contains? @loaded-module-coords* [id (trackable-coord coord)]))

(defn mark-modules-loaded! [id-coord-pairs]
  (swap! loaded-module-coords* into
         (set (map (fn [[id coord]] [id (trackable-coord coord)]) id-coord-pairs)))
  (when-let [hook @on-modules-loaded*]
    (hook)))

(defn unload-module-pairs [pairs]
  (remove loaded-module-pair? pairs))

(defn coord-sort-key [coord]
  (or (:local/root coord)
      (str (:git/sha coord) (:git/tag coord) (:mvn/version coord))
      (pr-str coord)))

(defn prefer-module-coord [a b]
  (if (neg? (compare (coord-sort-key a) (coord-sort-key b))) a b))

(defn dedupe-module-pairs [pairs]
  (let [by-id (reduce (fn [m [id coord]]
                        (update m id #(if % (prefer-module-coord % coord) coord)))
                      {}
                      pairs)]
    (vec (map (fn [[id coord]] [id coord]) by-id))))

(defn preload-module-pairs! [pairs]
  (let [unloaded (vec (unload-module-pairs (dedupe-module-pairs pairs)))]
    (when (seq unloaded)
      (add-modules-deps! unloaded)
      (mark-modules-loaded! unloaded))))

(defn ensure-module-deps! [id coord]
  (when-not (loaded-module-pair? [id coord])
    (add-module-deps! id coord)
    (mark-modules-loaded! [[id coord]])))


(defn apply-module-classpath-pairs!
  "Apply a cached classpath plan without re-walking modules (isaac-tki3)."
  [pairs]
  (when (seq pairs)
    (preload-module-pairs! pairs)))

(defn without-preload-planned!
  "Run `f` while suppressing discover!'s automatic planned preload (caller composed)."
  [f]
  (binding [*skip-preload-planned?* true]
    (f)))
