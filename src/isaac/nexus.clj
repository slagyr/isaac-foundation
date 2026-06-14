(ns isaac.nexus
  (:refer-clojure :exclude [get get-in reset!]))

;; ctx is per-turn; nexus is the process-wide runtime registry.
;; Runtime code reads the installed root runtime; tests can temporarily install an
;; isolated runtime around an example.

(def schema
  "c3kit schema documenting foundation-reserved nexus slots.
   Platform hosts install additional keys at runtime (:sessions, :tool-registry,
   :comm-registry, ...); those are intentionally omitted here. Modules may also
   register namespaced state (e.g. :my-module/state) via register!."
  {:name        :nexus
   :type        :map
   :description "Isaac global runtime context (foundation slots)"
   :schema      {:fs           {:type :ignore :description "Filesystem implementation (isaac.fs/Fs)"}
                 :config       {:type :ignore :description "Runtime configuration atom or value"}
                 :module-index {:type :ignore :description "Module activation index"}
                 :scheduler    {:type :ignore :description "Shared task scheduler instance"}}})


(defonce ^:private root-runtime (atom {}))

(def ^:private default-slots
  {:config        (atom nil)
   :tool-registry (atom {})})

(defn necho
  "Returns the currently installed nexus snapshot."
  []
  @root-runtime)

(defn install!
  "Installs runtime as the current root nexus, replacing the previous map."
  [runtime]
  (clojure.core/reset! root-runtime runtime))

(defn get
  "Returns the value registered under k, or nil."
  [k]
  (clojure.core/get (necho) k))

(defn get-in
  "Returns the value at path in the current nexus, or nil."
  [path]
  (clojure.core/get-in (necho) path))

(defn register!
  "Registers value v at path in the current nexus."
  [path v]
  (swap! root-runtime clojure.core/assoc-in path v))

(defn deregister!
  "Removes whatever is registered at path in the current nexus."
  [path]
  (if (= 1 (count path))
    (swap! root-runtime dissoc (first path))
    (swap! root-runtime update-in (vec (butlast path)) dissoc (last path))))

(defn registered?
  "Returns true if path has been registered in the current nexus."
  [path]
  (let [parent (clojure.core/get-in (necho) (butlast path))]
    (contains? parent (last path))))

(defn init!
  "Registers the default runtime atoms for the current nexus.
    Optional overrides replace the defaults for matching keys."
  ([] (init! {}))
  ([overrides]
   (install! (merge (necho) default-slots overrides))))

(defn reset!
  "Clears every key from the current nexus. Test fixtures call this between
    scenarios so registered values (e.g. :sessions) don't leak across
    examples sharing the process root runtime."
  []
  (install! {}))

(defn with-installed* [runtime f]
  (let [previous (necho)]
    (try
      (install! runtime)
      (f)
      (finally
        (install! previous)))))

(defn bound-runtime-fn
  "Captures the current nexus and returns a function that reinstalls it
   when invoked later, including on a different thread."
  [f]
  (let [runtime (necho)]
    (fn [& args]
      (with-installed* runtime #(apply f args)))))

(defmacro -with-nexus
  "Temporarily installs m as the root nexus for the duration of body.
   Provides test isolation: mutations inside do not affect the outer nexus. Test-only."
  [m & body]
  `(with-installed* ~m (fn [] ~@body)))

(defmacro -with-nested-nexus
  "Temporarily installs a nexus that merges m over the current root nexus.
   Unlike -with-nexus, existing slots (:config, :tool-registry, etc.) are
   preserved; only keys in m are overridden. Mutations to top-level keys in the
   nested scope do not bleed back to the outer nexus. Inner atoms stored as
   values (like the :config atom) are shared, so both layers see the same runtime
   state through them."
  [m & body]
  `(with-installed* (merge (necho) ~m) (fn [] ~@body)))
