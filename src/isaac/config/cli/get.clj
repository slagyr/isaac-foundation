(ns isaac.config.cli.get
  "isaac config get — read the resolved config (or a subtree) by config path."
  (:require
    [c3kit.apron.schema.path :as path]
    [clojure.string :as str]
    [isaac.config.cli.common :as common]
    [isaac.config.cli.inspect :as inspect]))

(def option-spec
  (into [[nil  "--raw"    "Print pre-substitution config (raw ${VAR} tokens intact)"]
         [nil  "--reveal" "Reveal ${VAR} secrets after confirmation (type REVEAL on stdin)"]]
        inspect/structured-option-spec))

(defn help []
  (common/render-help
    {:command     "isaac config get"
     :params      "[config-path] [options]"
     :description (str "Read from the resolved config. With no path, prints the whole config.\n"
                       "With a config path, prints the subtree at that path.")
     :option-spec option-spec
     :examples    (str "  isaac config get\n"
                       "  isaac config get --raw\n"
                       "  isaac config get crew.marvin.soul\n"
                       "  isaac config get models --json\n"
                       "  isaac config get providers.anthropic.api-key --reveal")}))

(defn- select [config path-str]
  (if (or (nil? path-str) (str/blank? path-str))
    config
    (let [value (path/data-at (common/queryable-config config) path-str)]
      (when (common/value-present? value) value))))

(defn- load-result [opts raw? reveal?]
  (cond
    raw?    (common/load-raw-result opts)
    :else   (common/printable-config opts reveal?)))

(defn- get-value! [opts path-str options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [{:keys [raw reveal edn json]} options
          {:keys [config errors missing-config?]} (load-result opts raw reveal)]
      (cond
        missing-config?
        (do (common/print-errors! errors "error") 1)

        (and reveal (not (common/reveal-confirmed?)))
        (do (common/print-reveal-refused!) 1)

        :else
        (let [value (select config path-str)]
          (if (common/value-present? value)
            (let [presented (common/present-identifiers value)]
              (cond
                (or edn json)
                (do (inspect/print-structured! edn json presented) 0)

                :else
                (do (common/print-edn! presented) 0)))
            (do
              (binding [*out* *err*]
                (println (str "not found: " path-str)))
              1)))))))

(defn run [opts arguments options]
  (get-value! opts (common/normalize-path (first arguments)) options))

(def subcommand
  {:option-spec option-spec
   :runner      run
   :help-text   help})
