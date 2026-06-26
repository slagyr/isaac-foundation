(ns isaac.foundation.config-steps
  "Foundation-grade config harness step: persist dotted keys to
   config/isaac.edn and route log.output. bind-server-port is ignored
   here — the server layer handles it via `server config:`."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(helper! isaac.foundation.config-steps)

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- invalidate-feature-config! []
  (g/dissoc! :feature-config))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (feature-fs)}
    (f)))

(defn- delete-sentinel? [value]
  (= "#delete" (str/trim (str value))))

(defn- dissoc-in [m path]
  (cond
    (empty? path)      m
    (= 1 (count path)) (dissoc m (first path))
    :else              (let [parent-path (vec (butlast path))
                             leaf        (last path)
                             parent      (get-in m parent-path)]
                         (if (map? parent)
                           (assoc-in m parent-path (dissoc parent leaf))
                           m)))))

(defn- config-path [path]
  (mapv keyword (str/split path #"\."))

(defn parse-config-value
  "Coerce a harness table cell to an EDN-friendly config value."
  [value]
  (cond
    (re-matches #"-?\d+" value)        (parse-long value)
    (= "true" (str/lower-case value))  true
    (= "false" (str/lower-case value)) false
    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")
        (str/starts-with? value "#"))
    (try (edn/read-string value)
         (catch Exception _ value))
    :else value))

(defn config-rows
  "Normalize gherclj table rows. Headerless single-pair tables store the
   pair in :headers; explicit | key | value | headers are skipped."
  [table]
  (let [header      (:headers table)
        header-pair (when (and (= 2 (count header))
                               (not (and (= "key" (first header))
                                         (= "value" (second header)))))
                      [header])]
    (map (fn [row] [(first row) (second row)])
         (concat header-pair (:rows table)))))

(defn persist-config-entry!
  "Persist one dotted config key to <root>/config/isaac.edn."
  [k v]
  (when-let [root (root-dir)]
    (with-feature-fs
      (fn []
        (let [path    (str root "/config/isaac.edn")
              fs*     (feature-fs)
              current (if (fs/exists? fs* path) (edn/read-string (fs/slurp fs* path)) {})
              updated (if (delete-sentinel? v)
                        (dissoc-in current (config-path k))
                        (assoc-in current (config-path k) (parse-config-value v)))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit   fs* path (pr-str updated))
          (invalidate-feature-config!))))))

(defn apply-log-output!
  [value]
  (log/set-output! (keyword value))
  (log/clear-entries!))

(defn config-applied
  "Applies a key/value harness table: routes log.output, ignores
   bind-server-port, persists every other dotted key to config/isaac.edn."
  [table]
  (doseq [[k v] (config-rows table)]
    (when-not (or (str/blank? (str k)) (= "key" k))
      (cond
        (= "log.output" k)     (apply-log-output! v)
        (= "bind-server-port" k) nil
        :else                  (persist-config-entry! k v)))))

(defgiven "config:" isaac.foundation.config-steps/config-applied
  "Applies harness settings from a key/value table. Persists dotted keys
   to config/isaac.edn; routes log.output to the in-memory logger.")