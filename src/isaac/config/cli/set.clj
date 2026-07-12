(ns isaac.config.cli.set
  "isaac config set — set a value at a config path."
  (:require
    [isaac.config.cli.common :as common]
    [isaac.config.cli.inspect :as inspect]
    [isaac.config.cli.mutate-common :as mutate-common]))

(defn help []
  (common/render-help
    {:command     "isaac config set"
     :params      "<config-path> <value|-> [options]"
     :description (str "Set a config value at a config path. Writes to the entity file when the\n"
                       "key already lives in one; otherwise writes to the root isaac.edn file\n"
                       "(or a new entity file when [prefer-entity-files] is true).")
     :arguments   (str "  <config-path>     Config path (e.g. crew.marvin.model)\n"
                       "  <value>           Scalar value; keywords, numbers, and strings are inferred\n"
                       "  -                 Read the value as EDN from stdin")
     :option-spec inspect/structured-option-spec
     :examples    (str "  isaac config set crew.marvin.model llama\n"
                       "  isaac config set crew.marvin.model llama --json\n"
                       "  echo '{:soul \"paranoid\"}' | isaac config set crew.marvin -")}))

(defn run [opts arguments options]
  (if-let [{:keys [path-str]} (mutate-common/target-root+path! opts (first arguments))]
    (mutate-common/set-config! opts path-str (second arguments) options)
    1))

(def subcommand
  {:option-spec inspect/structured-option-spec
   :parse-fn    common/parse-in-order-with-structured-flags
   :runner      run
   :help-text   help})
