;; mutation-tested: 2026-05-06
(ns isaac.config.env
  "Env-var overrides and <root>/.env snapshot for ${VAR} substitution."
  (:require
    [c3kit.apron.env :as c3env]
    [isaac.fs :as fs]))

(def env-overrides* (atom {}))
;; Snapshot of the <root>/.env file, locked at load time (see
;; lock-dotenv!). Avoids re-reading the file on every ${VAR} lookup and removes
;; the need to thread/bind root through the substitution pipeline.
(defonce ^:private dotenv* (atom {}))

(defn- runtime-fs
  ([] (or (fs/instance) (throw (ex-info "config.env requires :fs in system" {}))))
  ([opts] (or (fs/instance opts) (throw (ex-info "config.env requires :fs in system" {})))))

(defn- read-dotenv [root]
  (let [path (when root (str root "/.env"))
        fs*  (runtime-fs)]
    (if (and path (fs/exists? fs* path))
      (let [props (doto (java.util.Properties.)
                    (.load (java.io.StringReader. (or (fs/slurp fs* path) ""))))]
        (into {} (map (fn [k] [k (.getProperty props k)])) (.stringPropertyNames props)))
      {})))

(defn lock-dotenv!
  "Snapshots <root>/.env into dotenv*. Called once per load so ${VAR}
   substitution reads a locked map rather than re-reading the file.

   With a `snapshot` map, installs it directly instead of reading the
   filesystem — for a staged/in-memory load (isaac.config.mutate/validate-plan)
   that wants to reuse the process's already-locked <root>/.env rather than
   read a staging fs that never received a copy of it (isaac-p4oj)."
  ([root] (reset! dotenv* (read-dotenv root)))
  ([_root snapshot] (reset! dotenv* snapshot)))

(defn dotenv-snapshot
  "The <root>/.env map as of the last lock-dotenv! call — the process's
   currently-locked dotenv snapshot. Callers that build a staged/in-memory
   load can pass this through as `:dotenv` to loader/load-config-result so
   the staged load resolves ${VAR} refs the same way the live load did,
   instead of re-locking against a staging fs that lacks the file."
  []
  @dotenv*)

(defn clear-env-overrides! []
  (reset! env-overrides* {})
  (reset! dotenv* {}))

(defn set-env-override! [name value]
  (swap! env-overrides* assoc name value))

(defn env [name]
  (or (get @env-overrides* name)                            ;; TODO - MDM: c3env allows overrides.  Why reimplement?
      (c3env/env name)
      (get @dotenv* name)))
