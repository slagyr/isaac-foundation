(ns isaac.config.cli.sources
  "isaac config sources — list contributing config files."
  (:require [clojure.string :as str]
            [isaac.config.cli.common :as common]
            [isaac.config.cli.inspect :as inspect]
            [isaac.config.root :as root]))

(defn help []
  (common/render-help
    {:command     "isaac config sources"
     :description (str "List every config file that contributes to the resolved config,\n"
                       "in the order they are applied.")
     :pre-sections [["Root resolution" (str/join "\n" root/root-lookup-precedence)]]
     :option-spec inspect/structured-option-spec}))

(defn run [opts _arguments options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [{:keys [sources]} (common/load-result opts)
          {:keys [edn json]} options]
      (if (or edn json)
        (do (inspect/print-structured! edn json (inspect/sources-structured-value sources))
            0)
        (do (common/print-lines! (concat root/root-lookup-precedence
                                        (when (seq sources) [""])
                                        sources))
            0)))))

(def subcommand
  {:option-spec inspect/structured-option-spec
   :runner      run
   :help-text   help})
