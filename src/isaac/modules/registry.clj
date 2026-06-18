(ns isaac.modules.registry
  "Module catalog registry: fetch, cache, and normalize registry EDN."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]))

(def default-registry-url
  "https://raw.githubusercontent.com/slagyr/isaac/main/modules.edn")

(defonce ^:private cache* (atom {}))

(defn clear-cache!
  "Clear the in-memory registry cache (tests)."
  []
  (reset! cache* {}))

(defn- url? [source]
  (or (str/starts-with? source "http://")
      (str/starts-with? source "https://")))

(defn registry-source
  "Registry location from config :module-registry, else the default raw-github URL."
  [config]
  (or (:module-registry config) default-registry-url))

(defn- registry-path [root source]
  (if (str/starts-with? source "/")
    source
    (str root "/" source)))

(defn- read-registry-text [source root fs*]
  (if (url? source)
    (slurp source)
    (let [path (registry-path root source)]
      (fs/slurp fs* path))))

(defn- catalog-entry [id {:keys [desc] :as entry}]
  (cond-> {:id id}
    (some? desc) (assoc :desc desc)
    (and (nil? desc) (:description entry)) (assoc :desc (:description entry))))

(defn catalog-entries
  "Normalize registry data to a seq of {:id :desc} sorted by id."
  [registry]
  (->> registry
       (map (fn [[id entry]] (catalog-entry id entry)))
       (sort-by :id)
       vec))

(defn- parse-registry [text]
  (let [data (edn/read-string text)]
    (if (map? data)
      data
      (throw (ex-info "registry must be a map of module name to entry" {})))))

(defn fetch-registry
  "Load the registry for `config` at `root`. Returns
   {:registry <map>} or {:error <message>}."
  [config root]
  (let [source (registry-source config)
        fs*    (or (fs/instance) (fs/real-fs))]
    (when (and (not (url? source))
              (not (fs/exists? fs* (registry-path root source))))
      (swap! cache* dissoc [root source]))
    (try
      (if-let [cached (get @cache* [root source])]
        {:registry cached}
        (let [text     (read-registry-text source root fs*)
              registry (parse-registry text)]
          (swap! cache* assoc [root source] registry)
          {:registry registry}))
      (catch Exception _
        {:error "Could not reach the module registry"}))))

(defn lookup-entry
  "Resolve a user-facing module `name` in `registry`. Returns
   {:id :coord} or {:error <message>}."
  [registry name]
  (let [id (keyword name)]
    (if-let [{:keys [coord]} (get registry id)]
      (if (map? coord)
        {:id id :coord coord}
        {:error (str "Registry entry for " name " has no coordinate")})
      {:error (str "Unknown module: " name)})))