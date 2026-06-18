(ns isaac.foundation.fs-steps
  "Foundation-grade filesystem fixture/assertion steps: write a file, write
   per-entity isaac config (EDN, dotted-path rows, #delete), assert a file
   exists / doesn't / has content. Depends only on foundation namespaces
   (fs, root, nexus, util.shell, edn). The config-change notification that
   server hot-reload needs is a registered post-write hook (see
   register-post-write-hook!), so this namespace stays server-free and moves
   to the foundation repo at cut time."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]
    [isaac.shell :as shell]))

(helper! isaac.foundation.fs-steps)

;; Post-write hooks run after each isaac-file write with the written path.
;; The server layer registers one that notifies the config-change source
;; for hot-reload, so this namespace needn't depend on config.runtime.
(defonce ^:private post-write-hooks* (atom []))

(defn register-post-write-hook!
  "Register (fn [path]) to run after an isaac-file write. Used by the server
   layer to notify the config-change source for hot reload."
  [f]
  (swap! post-write-hooks* conj f))

(defn- notify-write! [path]
  (g/dissoc! :feature-config)
  (doseq [hook @post-write-hooks*] (hook path)))

;; region ----- Path expansion / generic file steps -----

(defn- uid-placeholder []
  (str/trim (:out (shell/sh! "id" "-u"))))

(defn- expand-path [path]
  (cond
    (= "~" path)                  (root/user-home)
    (str/starts-with? path "~/")  (str (root/user-home) (subs path 1))
    (str/starts-with? path "<uid>") (str/replace path "<uid>" (uid-placeholder))
    (str/starts-with? path "/")   path
    :else                         (str (or (g/get :root) (System/getProperty "user.dir")) "/" path)))

(defn- check-file-exists [path]
  (let [expanded (expand-path path)
        fs*      (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
    (fs/exists? fs* expanded)))

(defn file-with-content [path content]
  (let [expanded (expand-path path)]
    (if-let [mem-fs (g/get :mem-fs)]
      (do
        (fs/mkdirs mem-fs (fs/parent expanded))
        (fs/spit   mem-fs expanded (str/trim content)))
      (do
        (io/make-parents expanded)
        (spit expanded (str/trim content))))))

(defn file-exists [path]
  (g/should (check-file-exists path)))

(defn file-does-not-exist [path]
  (g/should-not (check-file-exists path)))

;; endregion ^^^^^ Path expansion / generic file steps ^^^^^

;; region ----- isaac-file write closure -----

(defn- delete-sentinel? [value]
  (= "#delete" (str/trim (str value))))

(defn- skip-row? [value]
  (str/blank? (str value)))

(defn- dissoc-in [m path]
  (cond
    (empty? path)      m
    (= 1 (count path)) (dissoc m (first path))
    :else              (let [parent-path (vec (butlast path))
                             leaf        (last path)
                             parent      (get-in m parent-path)]
                         (if (map? parent)
                           (assoc-in m parent-path (dissoc parent leaf))
                           m))))

(defn- isaac-root-path []
  (g/get :root))

(defn- runtime-root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- config-path? [path]
  (str/starts-with? path "config/"))

(defn- isaac-file-path [path]
  (cond
    (str/starts-with? path "/") path
    (= path "isaac.edn")         (str (isaac-root-path) "/config/isaac.edn")
    (config-path? path)          (str (isaac-root-path) "/" path)
    :else                        (str (runtime-root-dir) "/" path)))

(defn- server-fs []
  (or (g/get :mem-fs)
      (fs/real-fs)))

(defn- with-server-fs [f]
  (let [fs* (server-fs)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (feature-fs)}
    (f)))

;; "a file X exists with content Y" writes a root-relative (or absolute) file
;; and registers the fs so a following run sees it. resolve-path resolves
;; against :root, tolerating a leading root-name segment.
(defn- ensure-feature-fs! []
  (let [fs* (feature-fs)]
    (nexus/register! [:fs] fs*)
    fs*))

(defn- with-ensured-fs [f]
  (let [fs* (ensure-feature-fs!)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- resolve-path [p]
  (if (str/starts-with? p "/")
    p
    (let [root      (g/get :root)
          root-name (.getName (io/file root))]
      (if (str/starts-with? p (str root-name "/"))
        (str root "/" (subs p (inc (count root-name))))
        (str root "/" p)))))

(defn- unescape-content [s]
  (-> s (str/replace "\\\"" "\"") (str/replace "\\n" "\n")))

(defn file-at-with-content [name content]
  (let [path   (resolve-path name)
        actual (unescape-content content)]
    (with-ensured-fs
      #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
         (fs/mkdirs fs* (fs/parent path))
         (fs/spit   fs* path actual)))))

(defn file-at-with-docstring-content [name doc-string]
  (let [path   (resolve-path name)
        actual (str/trim doc-string)]
    (with-ensured-fs
      #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
         (fs/mkdirs fs* (fs/parent path))
         (fs/spit   fs* path actual)))))

(defn file-appended-with [name content]
  (let [path   (resolve-path name)
        actual (unescape-content content)]
    (with-ensured-fs
      #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
         (fs/spit fs* path (str actual "\n") :append true)))))

(defn file-with-log-entries [name n]
  (let [path  (resolve-path name)
        n     (parse-long n)
        lines (->> (range 1 (inc n))
                   (map #(format "{:ts \"2026-05-12T00:%02d:%02dZ\" :level :info :event :e%02d}"
                                 (quot % 60) (mod % 60) %))
                   (str/join "\n"))]
    (with-ensured-fs
      #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
         (fs/mkdirs fs* (fs/parent path))
         (fs/spit   fs* path lines)))))

(defn- parse-state-value [value]
  (cond
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")
        (str/starts-with? value "#"))
    (edn/read-string value)
    (re-matches #"[a-z][a-z-]*" value) (keyword value)
    :else value))

(defn- parse-isaac-value [file-path path value]
  (cond
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (= path "tools.allow")
    (->> (str/split value #",")
         (map str/trim)
         (remove str/blank?)
         (mapv keyword))

    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")
        (str/starts-with? value "#"))
    (edn/read-string value)

    (and (= path "session") (re-find #"hail/" file-path))
    (if (re-matches #"[a-z][a-z-]*" value) (keyword value) value)

    (or (contains? #{"defaults.crew" "defaults.model"} path)
        (and (= path "model") (re-find #"/config/crew/" file-path))
        (and (= path "crew") (or (re-find #"/config/cron/" file-path)
                                 (re-find #"hail/" file-path)))
        (and (= path "provider") (re-find #"/config/models/" file-path))
        (and (= path "api") (re-find #"/config/providers/" file-path))
        (str/ends-with? path ".last-status"))
    (keyword value)

    :else value))

(defn- get-path [data path]
  (reduce (fn [current segment]
            (cond
              (nil? current) nil
              (map? current) (or (get current (keyword segment))
                                 (get current segment))
              :else nil))
          data
          (str/split path #"\.")))

(defn- isaac-file-data [path]
  (let [path (isaac-file-path path)
        fs*  (server-fs)]
    (when (fs/exists? fs* path)
      (edn/read-string (fs/slurp fs* path)))))

(defn- maybe-prune-root-entity! [path]
  (when-let [[_ kind id] (re-matches #"config/(crew|models|providers)/([^/]+)\.edn" path)]
    (let [root-path (isaac-file-path "config/isaac.edn")
          fs*       (server-fs)]
      (when (fs/exists? fs* root-path)
        (let [data (edn/read-string (fs/slurp fs* root-path))
              data (update data (keyword kind) dissoc id)]
          (fs/spit fs* root-path (pr-str data)))))))

;; endregion ^^^^^ isaac-file write closure ^^^^^

;; region ----- isaac-file step bodies -----

(defn isaac-file-exists-with-content [path content]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            fs*       (server-fs)]
        (fs/mkdirs fs* (fs/parent file-path))
        (fs/spit   fs* file-path content)
        (notify-write! file-path)))))

(defn isaac-edn-file-exists [path table]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            data      (reduce (fn [acc row]
                                (let [row-map (zipmap (:headers table) row)
                                      p       (get row-map "path")
                                      value   (get row-map "value")
                                      keys    (mapv keyword (str/split p #"\."))]
                                  (cond
                                    (skip-row? value)
                                    acc

                                    (delete-sentinel? value)
                                    (dissoc-in acc keys)

                                    :else
                                    (assoc-in acc keys (parse-isaac-value file-path p value)))))
                              (or (isaac-file-data path) {})
                              (:rows table))]
        (maybe-prune-root-entity! path)
        (let [fs* (server-fs)]
          (fs/mkdirs fs* (fs/parent file-path))
          (fs/spit   fs* file-path (pr-str data)))
        (notify-write! file-path)))))

(defn isaac-file-edn-contains [path table]
  (let [data (with-server-fs #(isaac-file-data path))]
    (doseq [row (:rows table)]
      (let [row-map (zipmap (:headers table) row)
            value   (get row-map "value")]
        (when-not (skip-row? value)
          (let [actual   (get-path data (get row-map "path"))
                expected (parse-isaac-value path (get row-map "path") value)]
            (g/should= expected actual)))))))

(defn edn-isaac-file-does-not-exist [path]
  (g/should-not (with-server-fs #(fs/exists? (server-fs) (isaac-file-path path)))))

(defn file-exists-with [path content]
  (with-feature-fs
    (fn []
      (let [abs-path (if (str/starts-with? path "/")
                       path
                       (str (runtime-root-dir) "/" path))
            fs*      (feature-fs)]
        (fs/mkdirs fs* (fs/parent abs-path))
        (fs/spit   fs* abs-path content)
        (notify-write! abs-path)))))

;; endregion ^^^^^ isaac-file step bodies ^^^^^

;; region ----- Routing -----

(defgiven "the file {path:string} contains:" isaac.foundation.fs-steps/file-with-content
  "Writes the heredoc content to the given path (tilde/<uid>-expanded, or
   root-relative) in mem-fs or the real fs.")

(defthen "the file {path:string} exists" isaac.foundation.fs-steps/file-exists
  "Asserts the file at the given path (tilde-expanded) exists in mem-fs or real fs.")

(defthen "the file {path:string} does not exist" isaac.foundation.fs-steps/file-does-not-exist
  "Asserts the file at the given path (tilde-expanded) does not exist.")

(defgiven "the isaac file {path:string} exists with:" isaac.foundation.fs-steps/isaac-file-exists-with-content
  "Writes the heredoc content verbatim to the isaac file at <path>
   (root/config-relative, or absolute). Fires registered post-write hooks
   (config-change notify for hot reload).")

(defthen #"the isaac file \"([^\"]+)\" EDN contains:" isaac.foundation.fs-steps/isaac-file-edn-contains
  "Assert-only structural EDN inspector for isaac files. Table rows are
   path | value, using dotted-path semantics. Pair with 'the isaac EDN file
   X exists with:' for the write side.")

(defgiven "the isaac EDN file {path:string} exists with:" isaac.foundation.fs-steps/isaac-edn-file-exists
  "Writes structured EDN to the isaac file at <path>. Table rows are
    {path, value}; dot-separated path column creates nested keyword maps
    (e.g. 'server.port' → {:server {:port ...}}). Fires post-write hooks
    (config-change notify). A value of '#delete' removes that dotted path
    from the current file before write.")

(defthen "the isaac file \"{path}\" does not exist" isaac.foundation.fs-steps/edn-isaac-file-does-not-exist)

(defgiven #"the file \"([^\"]+)\" exists with:$" isaac.foundation.fs-steps/file-exists-with)

(defgiven "a file {name:string} exists with content {content:string}" isaac.foundation.fs-steps/file-at-with-content)

(defgiven "a file {name:string} exists with content:" isaac.foundation.fs-steps/file-at-with-docstring-content)

(defwhen "the file {name:string} is appended with {content:string}" isaac.foundation.fs-steps/file-appended-with)

(defgiven #"a file \"([^\"]+)\" exists with (\d+) log entries" isaac.foundation.fs-steps/file-with-log-entries)

;; endregion ^^^^^ Routing ^^^^^
