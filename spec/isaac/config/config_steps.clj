(ns isaac.config.config-steps
  (:require
    [c3kit.apron.env :as c3env]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.config.berths :as berths]
    [isaac.config.loader :as loader]
    [isaac.config.marigold :as config-marigold]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]))

(helper! isaac.config.config-steps)

;; region ----- Helpers -----

(defn- root []
  (or (g/get :root) "/isaac-state"))

(defn- config-root []
  (str (root) "/config"))

(defn- mem-fs []
  (or (g/get :mem-fs)
      (let [mem (fs/mem-fs)]
        (g/assoc! :mem-fs mem)
        mem)))

(defn- with-config-fs [f]
  (let [fs* (mem-fs)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- path-exists? [path]
  #_{:clj-kondo/ignore [:invalid-arity]}
  (or (fs/exists? (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)) path)
      (.exists (java.io.File. path))))

(defn- module-manifest-path [id]
  (some (fn [root]
          (some #(when (path-exists? %) %)
                [(str root "/resources/isaac-manifest.edn")
                 (str root "/src/isaac-manifest.edn")]))
        [(str (root) "/.isaac/modules/" (name id))
         (str (System/getProperty "user.dir") "/modules/" (name id))]))

(defn- ->resource [path]
  (some-> path java.io.File. .toURI .toURL))

(defn- manifest-reference [path]
  (let [fs* (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
    (cond
      (and path (fs/exists? fs* path)) path
      (and path (.exists (java.io.File. path))) (->resource path)
      :else nil)))

(defn- load-config-result []
  (let [real-manifest-resource @#'isaac.module.loader/manifest-resource
         real-resolve           module-loader/resolve-manifest-resource
         base-cwd               (System/getProperty "user.dir")
         override               (g/get :effective-cwd)
         effective-cwd          (when override
                                   (if (str/starts-with? override "/")
                                     override
                                     (str base-cwd "/" override)))
         fs*                    (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))
         absolutize-root        (fn [path]
                                  (if (str/starts-with? path "/")
                                    path
                                    (str (or effective-cwd base-cwd) "/" path)))
         coord-manifest-path    (fn [coord]
                                  (when-let [root (:local/root coord)]
                                    (let [root (absolutize-root root)]
                                      (some-> (some #(when (path-exists? %) %)
                                                    [(str root "/resources/isaac-manifest.edn")
                                                     (str root "/src/isaac-manifest.edn")])
                                              manifest-reference))))]
    (try
      (when effective-cwd
        (System/setProperty "user.dir" effective-cwd))
	      (with-redefs [module-loader/invoke-add-deps! (fn [_])
	                    module-loader/manifest-resource (fn [id]
	                                                      (or (some-> (module-manifest-path id) manifest-reference)
	                                                          (real-manifest-resource id)))
                    ;; Test envs don't have a real classpath, so always try
                    ;; the coord's :local/root manifest first; only fall back
                    ;; to the upstream resolver (id-based search) if it fails.
                    module-loader/resolve-manifest-resource (fn [id coord]
                                                              (or (coord-manifest-path coord)
                                                                  (real-resolve id coord)))]
        (loader/load-config-result {:root (root) :fs fs*}))
      (finally
        (System/setProperty "user.dir" base-cwd)))))

(defn- load-result* []
  (let [result       (load-config-result)
        module-index (get-in result [:config :module-index])]
    ;; Per-entry berth :factory invocation runs OUTSIDE the loader's
    ;; nested-nexus wrap so its nexus registrations persist into the
    ;; ambient nexus (the wrap's install!/restore would otherwise
    ;; discard them). When no errors slipped through, fire the pass.
    (when (and module-index (empty? (:errors result)))
      (module-loader/process-manifest-berths! module-index))
    result))

(defn load-result []
  (or (g/get :loaded-config-result)
      ;; When a scenario declares fixture modules that extend a themed test
      ;; berth (e.g. the chartroom signals/foundries tables), the override
      ;; supplies those berth declarations for the whole load.
      (if-let [override (g/get :foundation-index-override)]
        (binding [module-loader/*foundation-index-override* override]
          (load-result*))
        (load-result*))))

(defn reload-result
  "Drops the cached load and loads fresh — for steps that rewrite
   isaac.edn mid-scenario and need the loader to see it."
  []
  (g/assoc! :loaded-config-result nil)
  (load-result))

(defn- parse-expected [value]
  (cond
    (re-matches #"-?\d+" value) (parse-long value)
    :else                        value))

(defn- actual->string [value]
  (cond
    (keyword? value) (name value)
    :else            (str value)))

(defn- unescape-pointer-segment
  "JSON-Pointer (RFC 6901) escapes: ~1 → /, ~0 → ~. Order matters — ~0 last
   so ~01 (literal '~1') doesn't get re-expanded into a slash."
  [seg]
  (-> seg
      (str/replace "~1" "/")
      (str/replace "~0" "~")))

(defn- get-path [data path]
  (let [segments (if (str/starts-with? path "/")
                   (mapv unescape-pointer-segment
                         (remove str/blank? (str/split (subs path 1) #"/")))
                   (str/split path #"\."))]
    (reduce (fn [current segment]
              (cond
                (nil? current) nil
                (map? current) (or (get current (keyword segment))
                                   (get current segment))
                (vector? current) (nth current (parse-long segment) nil)
                :else nil))
            data
            segments)))

(defn- matching-messages [table]
  (mapv (fn [row]
          (zipmap (:headers table) row))
         (:rows table)))

(defn- row-matches? [entry expected]
  (and (= (:key entry) (get expected "key"))
       (re-find (re-pattern (get expected "value")) (:value entry))))

(defn- config-file-path [path]
  (str (config-root) "/" path))

(defn- isaac-env-path []
  (str (root) "/.env"))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Given step bodies -----

(defn effective-cwd-is [path]
  (g/assoc! :effective-cwd path))

(defn config-file-containing [path content]
  (with-config-fs
    (fn []
      (let [full-path (str (config-root) "/" path)
            fs*       (nexus/get :fs)]
        #_{:clj-kondo/ignore [:invalid-arity]}
        (fs/mkdirs fs* (or (fs/parent full-path) (config-root)))
        (fs/spit   fs* full-path (str/trim content))))))

(defn environment-variable-is [name value]
  (loader/set-env-override! name value)
  (c3env/override! name value))

(defn chartroom-modules-available []
  ;; Bind the chartroom test index (foundation + :marigold.chartroom) as the
  ;; foundation-index override for this scenario's loads, so the themed test
  ;; berths (:signals, :foundries) are declared and fixture-module
  ;; contributions (parlor, fizz) compose into them.
  (g/assoc! :foundation-index-override (config-marigold/chartroom-test-index)))

(defn isaac-env-file-contains [content]
  (with-config-fs
    (fn []
      (let [path (isaac-env-path)
            fs*  (nexus/get :fs)]
        #_{:clj-kondo/ignore [:invalid-arity]}
        (fs/mkdirs fs* (fs/parent path))
        (fs/spit fs* path (str/trim content))))))

;; endregion ^^^^^ Given step bodies ^^^^^

;; region ----- When step bodies -----

(defn config-is-loaded []
  (let [result (load-result)]
    (g/assoc! :loaded-config-result result)))

;; --- config-berth node lifecycle (reconcile! engine) ----------------------
;; load-result fires process-manifest-berths! (manifest-only berths only).
;; Config berths (a :config-claimed path like [:relays]) are installed by
;; the reconcile engine; do it lazily on first nexus assertion so loading
;; stays cheap and these steps stay self-contained.

(defn- loaded-module-index []
  (merge (module-loader/foundation-index)
         (get-in (g/get :loaded-config-result) [:config :module-index])))

(defn- ensure-config-berths-installed! []
  (when-not (g/get :config-berths-installed?)
    (when-let [loaded (g/get :loaded-config-result)]
      (let [module-index (loaded-module-index)]
        (module-loader/reconcile-modules! module-index)
        (berths/install! {:config       (:config loaded)
                          :module-index module-index}))
      (g/assoc! :config-berths-installed? true))))

(defn config-is-reloaded []
  ;; Install the pre-reload nodes (boot), then reconcile against the freshly
  ;; loaded config so Reconfigurable nodes receive on-config-change! and
  ;; removed slots deregister — the real reload path, not a fresh install.
  (ensure-config-berths-installed!)
  (let [prev   (:config (g/get :loaded-config-result))
        result (reload-result)]
    (g/assoc! :loaded-config-result result)
    (berths/reconcile! {:config       (:config result)
                        :old-config   prev
                        :module-index (loaded-module-index)})))

;; endregion ^^^^^ When step bodies ^^^^^

;; region ----- Then step bodies -----

(defn- edn-shaped?
  "Cheap heuristic: leading char hints the value is meant as EDN
   (map / vector / keyword / quoted string) rather than a plain string."
  [s]
  (and (string? s)
       (let [c (when (seq s) (first s))]
         (contains? #{\{ \[ \: \"} c))))

(defn loaded-config-has [table]
  (let [config (or (loader/snapshot "feature: loaded-config-has prefers the committed snapshot (hot-reload-aware)")
                   (:config (load-result)))]
    (doseq [row (:rows table)]
      (let [m        (zipmap (:headers table) row)
            actual   (get-path config (get m "key"))
            expected (parse-expected (get m "value"))]
        (cond
          ;; EDN-shaped expected values (maps, vectors, keywords) are
          ;; compared structurally so commas/whitespace in the expected
          ;; literal don't matter.
          (edn-shaped? expected)
          (g/should= (edn/read-string expected) actual)

          (string? expected)
          (g/should= expected (actual->string actual))

          :else
          (g/should= expected actual))))))

(defn config-has-validation-errors [table]
  (let [errors   (:errors (load-result))
        expected (matching-messages table)]
    (doseq [row expected]
      (g/should (some #(row-matches? % row) errors)))))

(defn config-has-validation-warnings [table]
  (let [warnings (:warnings (load-result))
        expected (matching-messages table)]
    (doseq [row expected]
      (g/should (some #(row-matches? % row) warnings)))))

(defn config-file-matches [path table]
  (with-config-fs
    (fn []
      (let [content (or (fs/slurp (nexus/get :fs) (config-file-path path)) "")]
        (doseq [row (:rows table)]
          (g/should (re-find (re-pattern (str/trim (first row))) content)))))))

(defn config-file-does-not-contain [path expected]
  (with-config-fs
    (fn []
      (let [content (or (fs/slurp (nexus/get :fs) (config-file-path path)) "")]
        (g/should-not (str/includes? content expected))))))

(defn config-file-does-not-exist [path]
  (with-config-fs
    (fn []
      #_{:clj-kondo/ignore [:invalid-arity]}
      (g/should-not (fs/exists? (nexus/get :fs) (config-file-path path))))))

(defn config-has-no-validation-errors []
  (g/should= [] (:errors (load-result))))

(defn nexus-has-route [route-key-edn handler-edn]
  (let [route-key (edn/read-string route-key-edn)
        expected  (edn/read-string handler-edn)
        actual    (nexus/get-in [:marigold.bridge/signal-route route-key])]
    (g/should= expected actual)))

(defn config-has-no-validation-warnings []
  (g/should= [] (:warnings (load-result))))

(defn- parse-state-value [value]
  (cond
    (re-matches #"-?\d+" value)        (parse-long value)
    (= "true" (str/lower-case value))  true
    (= "false" (str/lower-case value)) false
    (str/starts-with? value "[")       (edn/read-string value)
    (str/starts-with? value "{")       (edn/read-string value)
    (str/starts-with? value ":")       (edn/read-string value)
    (str/starts-with? value "\"")      (edn/read-string value)
    :else                              value))

(defn- get-by-dotted-path [m path]
  (get-in m (mapv keyword (str/split path #"\."))))

(defn- node-at-path [path-str]
  (nexus/get-in (edn/read-string path-str)))

(defn- read-state [instance]
  ;; Reconfigurable fixture nodes (RelayStation) expose their tracked state
  ;; via a :state* atom; plain map nodes are their own state.
  (cond
    (some-> instance :state*) @(:state* instance)
    (map? instance)           instance
    :else                     {}))

(defn nexus-node-state-has [path-str table]
  (ensure-config-berths-installed!)
  (let [state (read-state (node-at-path path-str))]
    (g/should-not-be-nil (node-at-path path-str))
    (doseq [row (:rows table)]
      (let [row-map (zipmap (:headers table) row)]
        (g/should= (parse-state-value (get row-map "value"))
                   (get-by-dotted-path state (get row-map "path")))))))

(defn nexus-no-node-at [path-str]
  (ensure-config-berths-installed!)
  (g/should-be-nil (node-at-path path-str)))

;; endregion ^^^^^ Then step bodies ^^^^^

;; region ----- Routing -----

(defgiven "the effective working directory is {path:string}" isaac.config.config-steps/effective-cwd-is
  "Sets the working directory used during config loading. Relative paths are
   resolved against the JVM's actual working directory. Use this to test
   :local/root \".\" module discovery (where the module root equals the cwd).")

(defgiven "config file {path:string} containing:" isaac.config.config-steps/config-file-containing
  "Writes the heredoc content to <root>/.isaac/config/<path>. Uses
   the in-memory fs. Path is config-root-relative, e.g. 'isaac.edn' or
   'crew/atticus.edn'.")

(defgiven "environment variable {name:string} is {value:string}" isaac.config.config-steps/environment-variable-is
  "Sets BOTH the loader env-override (used by ${VAR} substitution) AND
    c3env's override (used by any c3env/env call). Covers both entry
    points so tests don't rely on which one the code happens to use.")

(defgiven #"the env var \"([^\"]+)\" is set to \"([^\"]+)\"" isaac.config.config-steps/environment-variable-is)

(defgiven "the chartroom fixture modules are available" isaac.config.config-steps/chartroom-modules-available
  "Binds the foundation+chartroom test index as the foundation-index
   override for this scenario, declaring the themed test berths (:signals,
   :foundries) that the marigold.comm.parlor / marigold.providers.fizz
   fixture modules contribute schema fragments into.")

(defgiven "the isaac .env file contains:" isaac.config.config-steps/isaac-env-file-contains
  "Writes the heredoc content to <root>/.isaac/.env. This is the
   file the loader reads for ${VAR} substitution.")

(defwhen "the config is loaded" isaac.config.config-steps/config-is-loaded
  "Triggers a fresh load-config-result against the root and caches
   the result so subsequent Then steps (loaded-config-has, validation
   errors) use the same load.")

(defwhen "the config is reloaded" isaac.config.config-steps/config-is-reloaded
  "Reloads isaac.edn and reconciles config-berth nodes against the new
   config: Reconfigurable nodes get on-config-change!, plain nodes are
   recreated, removed slots deregister. Use after rewriting isaac.edn
   mid-scenario.")

(defthen #"the nexus node at (.+) has state:" isaac.config.config-steps/nexus-node-state-has
  "Reads the config-berth node registered at the EDN path (e.g.
   [:relays :relay1]) and asserts its state. For Reconfigurable nodes
   the state is the tracked @:state* map; for plain nodes the node map
   itself. Rows use dotted paths (e.g. slice.helm/freq).")

(defthen #"the nexus has no node at (.+)" isaac.config.config-steps/nexus-no-node-at
  "Asserts no config-berth node is registered at the EDN path.")

(defthen "the loaded config has:" isaac.config.config-steps/loaded-config-has
  "Prefers the committed config snapshot (hot-reload-aware) via
   loader/snapshot; falls back to a fresh load-config against the
   root when no snapshot is committed. Rows use dot-path keys, e.g.
   'crew.atticus.soul'.")

(defthen "the config has validation errors matching:" isaac.config.config-steps/config-has-validation-errors)

(defthen "the config has validation warnings matching:" isaac.config.config-steps/config-has-validation-warnings)

(defthen "the config file {path:string} matches:" isaac.config.config-steps/config-file-matches
  "Reads the on-disk config file content (root-relative path under
   config-root). Each row is a regex pattern; all must match somewhere
   in the file. Order and structure are not enforced.")

(defthen "the config file {path:string} does not contain {expected:string}" isaac.config.config-steps/config-file-does-not-contain)

(defthen "the config file {path:string} does not exist" isaac.config.config-steps/config-file-does-not-exist)

(defthen "the config has no validation errors" isaac.config.config-steps/config-has-no-validation-errors)

(defthen "the config has no validation warnings" isaac.config.config-steps/config-has-no-validation-warnings)

(defthen #"the nexus has a route (.+) with handler (.+)" isaac.config.config-steps/nexus-has-route
  "Reads the nexus at [:marigold.bridge/signal-route <route-key>] and
   asserts the registered value equals the expected handler symbol.
   <route-key> and <handler> are read as EDN; supply a vector literal
   (e.g. [:get \"/path\"]) for the key and a qualified symbol for the
   handler.")

;; endregion ^^^^^ Routing ^^^^^
