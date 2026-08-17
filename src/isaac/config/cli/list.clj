(ns isaac.config.cli.list
  "isaac config list — keys and config source files at a config path."
  (:require
    [isaac.config.cli.common :as common]
    [isaac.config.cli.inspect :as inspect]))

(defn help []
  (common/render-help
    {:command     "isaac config list"
     :params      "[config-path] [options]"
     :description (str "Print the keys one level below a config path with each entry's\n"
                       "config source file. With no path, lists the top-level keys of the\n"
                       "resolved config. Leaf paths and non-map values print nothing.\n"
                       "Values are never shown.")
     :option-spec inspect/structured-option-spec
     :examples    (str "  isaac config list\n"
                       "  isaac config list providers\n"
                       "  isaac config list providers --json")}))

(defn run [opts arguments options]
  (inspect/inspect! opts (common/normalize-path (first arguments)) options {:mode :list}))

(def subcommand
  {:option-spec inspect/structured-option-spec
   :runner      run
   :help-text   help})