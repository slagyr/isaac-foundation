(ns isaac.config.cli.keys
  "isaac config keys — bare key names at a config path."
  (:require
    [isaac.config.cli.common :as common]
    [isaac.config.cli.inspect :as inspect]))

(defn help []
  (common/render-help
    {:command     "isaac config keys"
     :params      "<config-path> [options]"
     :description (str "Print the bare key names one level below a config path.\n"
                       "Leaf paths and non-map values print nothing. Values are never shown.")
     :option-spec inspect/structured-option-spec
     :examples    (str "  isaac config keys providers\n"
                       "  isaac config keys providers --json")}))

(defn run [opts arguments options]
  (let [path-str (common/normalize-path (first arguments))]
    (if-let [error (inspect/require-path! path-str)]
      error
      (inspect/inspect! opts path-str options {:mode :keys}))))

(def subcommand
  {:option-spec inspect/structured-option-spec
   :runner      run
   :help-text   help})