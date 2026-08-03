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

(defn substitute-env [s]
  (str/replace s #"\$\{([^}]+)\}" (fn [[match var-name]] (or (env/env var-name) match))))

(defn substitute-env-recursive [value]
  (cond
    (string? value) (substitute-env value)
    (map? value) (into {} (map (fn [[k v]] [k (substitute-env-recursive v)]) value))
    (sequential? value) (mapv substitute-env-recursive value)
    :else value))

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
