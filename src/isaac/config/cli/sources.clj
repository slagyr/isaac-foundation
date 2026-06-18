(ns isaac.config.cli.sources
  "isaac config sources — list contributing config files."
  (:require [clojure.string :as str]
            [isaac.config.cli.common :as common]
            [isaac.config.root :as root]))

(defn help []
  (common/render-help
    {:command     "isaac config sources"
     :description (str "List every config file that contributes to the resolved config,\n"
                       "in the order they are applied.")
     :pre-sections [["Root resolution" (str/join "\n" root/root-lookup-precedence)]]
     :option-spec common/help-option-spec}))

(defn run [opts _arguments _options]
  (let [{:keys [sources]} (common/load-result opts)]
    (common/print-lines! (concat root/root-lookup-precedence
                                 (when (seq sources) [""])
                                 sources))
    0))

(def subcommand
  {:option-spec common/help-option-spec
   :runner      run
   :help-text   help})
