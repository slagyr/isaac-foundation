(ns isaac.config.cli.reformat
  "isaac config reformat — normalize active config EDN files with Isaac's
   house formatter."
  (:require
    [clojure.string :as str]
    [isaac.config.cli.common :as common]
    [isaac.config.parse :as parse]
    [isaac.config.paths :as paths]
    [isaac.fs :as fs]
    [isaac.util.edn :as edn-pretty]))

(defn help []
  (common/render-help
    {:command "isaac config reformat"
     :description "Pretty-print every active .edn file under config/. Backups are skipped."}))

(defn- backup-path? [relative]
  (some #(str/includes? % ".bak-") (str/split relative #"/")))

(defn- edn-files [fs* base relative]
  (mapcat (fn [name]
            (let [child-relative (if (seq relative) (str relative "/" name) name)
                  child          (str base "/" child-relative)]
              (cond
                (fs/dir? fs* child)  (edn-files fs* base child-relative)
                (and (fs/file? fs* child)
                     (str/ends-with? name ".edn")
                     (not (backup-path? child-relative))) [child]
                :else [])))
          (or (fs/children fs* (str base (when (seq relative) (str "/" relative)))) [])))

(defn run [opts _arguments _options]
  (let [fs*   (fs/instance opts)
        base  (paths/config-root (:root opts))
        files (edn-files fs* base "")]
    (doseq [path files]
      (let [formatted (str (edn-pretty/pretty (parse/read-edn-string (fs/slurp fs* path) false)) "\n")]
        (when (not= formatted (fs/slurp fs* path))
          (fs/spit fs* path formatted)
          (println path))))
    0))

(def subcommand {:option-spec [] :runner run :help-text help})
