;; mutation-tested: 2026-05-06
(ns isaac.config.parse
  "EDN/YAML/frontmatter parsing and ${VAR} substitution primitives."
  (:require
    [clj-yaml.core :as yaml]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.env :as env]
    [isaac.fs :as fs]))

(defn runtime-fs
  ([] (or (fs/instance) (throw (ex-info "config.parse requires :fs in system" {}))))
  ([opts] (or (fs/instance opts) (throw (ex-info "config.parse requires :fs in system" {})))))

(defn exists?* [path]
  (fs/exists? (runtime-fs) path))

(defn slurp* [path]
  (fs/slurp (runtime-fs) path))

(defn children* [path]
  (fs/children (runtime-fs) path))

(defn dir?* [path]
  (fs/dir? (runtime-fs) path))

(defn source-path [relative]
  (str "config/" relative))

(defn missing-config-message [root]
  (str "no config found; run `isaac init` or create " root "/config/isaac.edn"))

(defn warning [key value]
  {:key key :value value})

(defn has-ext? [path ext]
  (str/ends-with? path ext))

(defn split-frontmatter [content]
  (when-let [[_ frontmatter body] (re-matches #"(?s)\A---\r?\n(.*?)\r?\n---\r?\n?(.*)\z" content)]
    {:frontmatter frontmatter
     :body        (str/replace body #"^\r?\n" "")}))

;; region ----- ${VAR} substitution -----

(def ^:private reference-pattern #"\$\{([^}]+)\}")

(def ^:dynamic *unresolved-refs*
  "When bound to an atom, substitution appends `{:path [...] :ref \"VAR\"}` for
   every field it dropped because a `${...}` reference could not be resolved.
   Unbound (nil) substitution still drops the field; it just keeps no record."
  nil)

(def ^:dynamic *reference-path*
  "Config path prefix for recorded references. An entity file substitutes values
   whose paths are relative to the entity, so `crew/main.edn` binds `[:crew
   \"main\"]` and a dropped `:gauge` records `[:crew \"main\" :gauge]`."
  [])

(defn unresolved-references
  "Every `${...}` reference in `s` that has no value, in order, once each."
  [s]
  (->> (re-seq reference-pattern s)
       (keep (fn [[_ var-name]] (when (nil? (env/env var-name)) var-name)))
       (distinct)
       (vec)))

(defn substitute-env
  "Replace every `${VAR}` in `s` with its value, or return nil when any reference
   cannot be resolved.

   An unresolvable reference is an unset field, never the literal text: the
   literal looks like a real value, so it goes out as the API key and the
   provider answers 401 — the error then blames auth instead of the missing
   variable (isaac-rxun). A partly-resolvable string is unresolvable for the
   same reason."
  [s]
  (when (empty? (unresolved-references s))
    (str/replace s reference-pattern (fn [[_ var-name]] (env/env var-name)))))

(defn- record-unresolved!
  "Record the dropped field. Always returns nil — it is the `or` fallback in
   substitute-env-recursive, so a truthy return would resurrect the literal."
  [path refs]
  (when *unresolved-refs*
    (swap! *unresolved-refs* into (map (fn [ref] {:path (vec path) :ref ref}) refs)))
  nil)

(defn substitute-env-recursive
  "Substitute `${VAR}` references through a config value. A field whose reference
   cannot be resolved is dropped — absent, exactly as if it had never been set —
   and recorded in `*unresolved-refs*` under its path. An explicit nil is kept:
   an unresolvable reference is the only thing this drops."
  ([value] (substitute-env-recursive *reference-path* value))
  ([path value]
   (cond
     (string? value)
     (or (substitute-env value)
         (record-unresolved! path (unresolved-references value)))

     (map? value)
     (reduce-kv (fn [acc k v]
                  (let [substituted (substitute-env-recursive (conj (vec path) k) v)]
                    (if (and (some? v) (nil? substituted))
                      acc
                      (assoc acc k substituted))))
                {}
                value)

     (sequential? value)
     (into []
           (keep-indexed (fn [idx v]
                           (let [substituted (substitute-env-recursive (conj (vec path) idx) v)]
                             (when-not (and (some? v) (nil? substituted))
                               substituted))))
           value)

     :else value)))

;; endregion ^^^^^ ${VAR} substitution ^^^^^

(defn read-edn-string [content substitute-env?]
  (-> content
      edn/read-string
      ((fn [value]
         (if substitute-env?
           (substitute-env-recursive value)
           value)))))

(defn read-yaml-string [content substitute-env?]
  (-> (yaml/parse-string content :keywords true)
      ((fn [value]
         (if substitute-env?
           (substitute-env-recursive value)
           value)))))

(defn read-edn-file [path substitute-env? raw-parse-errors?]
  (try
    {:data (read-edn-string (slurp* path) substitute-env?)}
    (catch Exception e
      {:error (if raw-parse-errors?
                (.getMessage e)
                "EDN syntax error")})))

(defn entry-content [{:keys [content overlay? path]}]
  (if overlay?
    content
    (slurp* path)))

(defn read-frontmatter-file [{:keys [relative] :as entry} substitute-env? raw-parse-errors?]
  (try
    (if-let [{:keys [body frontmatter]} (split-frontmatter (entry-content entry))]
      {:body body
       :data (read-yaml-string frontmatter substitute-env?)}
      {:error (str relative " is missing YAML frontmatter")})
    (catch Exception e
      {:error (if raw-parse-errors?
                (.getMessage e)
                "YAML syntax error")})))

(defn assoc-error [result key value]
  (update result :errors conj {:key key :value value}))
