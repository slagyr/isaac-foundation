(ns isaac.config.cli.unset
  "isaac config unset — remove a value at a config path."
  (:require
    [isaac.config.cli.common :as common]
    [isaac.config.cli.inspect :as inspect]
    [isaac.config.cli.mutate-common :as mutate-common]))

(defn help []
  (common/render-help
    {:command     "isaac config unset"
     :params      "<config-path> [options]"
     :description (str "Remove a value at a config path. Deletes the key from whichever file\n"
                       "defines it; deletes the entity file entirely if unset empties it.")
     :option-spec inspect/structured-option-spec
     :examples    "  isaac config unset crew.marvin.soul"}))

(defn run [opts arguments options]
  (if-let [{:keys [path-str]} (mutate-common/target-root+path! opts (first arguments))]
    (mutate-common/unset-config! opts path-str options)
    1))

(def subcommand
  {:option-spec inspect/structured-option-spec
   :parse-args  [:in-order true]
   :runner      run
   :help-text   help})
