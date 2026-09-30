(ns isaac.foundation.config.cli.validate
  "isaac config validate — validate the config composition."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.config.cli.common :as common]
    [isaac.foundation.config.cli.inspect :as inspect]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.schema-compose :as schema-compose]))

(def option-spec
  (into [[nil  "--as CONFIG-PATH" "Overlay stdin EDN at the given config path before validating"]]
        inspect/structured-option-spec))

(defn help []
  (common/render-help
    {:command       "isaac config validate"
     :params        "[options] [-]"
     :description   "Validate the config composition"
     :option-spec   option-spec
     :post-sections [["Arguments" "  -  Read EDN to validate from stdin (isolated when no --as)"]]}))

(defn- parse-data-path [root-schema path-str]
  (let [segments (str/split path-str #"\.")
        head     (keyword (first segments))
        entity?  (schema-compose/entity-collection-key? root-schema head)
        tail     (cond->> (rest segments)
                   entity? (map-indexed (fn [idx seg] (if (zero? idx) seg (keyword seg))))
                   (not entity?) (map keyword))]
    (into [head] tail)))

(defn- validation-record [{:keys [errors warnings]}]
  {:ok (empty? errors) :warnings warnings :errors errors})

(defn- report-validation! [{:keys [errors warnings]} options]
  (let [{:keys [edn json]} options]
    (if (or edn json)
      (do (inspect/print-structured! edn json (validation-record {:errors errors :warnings warnings}))
          (if (seq errors) 1 0))
      (do (common/print-errors! errors "error")
          (common/print-warnings! warnings)
          (if (seq errors)
            1
            (do
              (println "OK - config is valid")
              0))))))

(defn- validate-stdin! [opts options]
  (report-validation!
    (loader/load-config-result {:root          (common/resolve-root opts)
                                :overlay-content    (slurp (host/in))
                                :overlay-path       "isaac.edn"
                                :skip-entity-files? true})
    options))

(defn- validate-overlay-data! [opts data-path-str options]
  (let [stdin-value (try
                      {:value (edn/read-string (slurp (host/in)))}
                      (catch Exception e
                        {:error (.getMessage e)}))]
    (if (:error stdin-value)
      (do
        (binding [*out* *err*]
          (println (str "invalid EDN from stdin: " (:error stdin-value))))
        1)
      (let [root-schema (:root (common/schema-context opts))]
        (report-validation!
          (loader/load-config-result {:root         (common/resolve-root opts)
                                      :data-path-overlay {:path  (parse-data-path root-schema data-path-str)
                                                          :value (:value stdin-value)}})
          options)))))

(defn- validate-config! [opts options]
  (report-validation! (common/load-result opts) options))

(defn- file-path-style? [path-str]
  (and path-str
       (not (str/starts-with? path-str "/"))
       (str/includes? path-str "/")))

(defn run [opts arguments options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [as-value (:as options)
          stdin?   (= "-" (first arguments))]
      (cond
      (file-path-style? as-value)
      (common/print-cli-error! (str "validate --as expected a config path like foo.bar, got file path: " as-value))

      as-value
      (if stdin?
        (validate-overlay-data! opts (common/normalize-path as-value) options)
        (common/print-cli-error! "validate --as requires '-' stdin source"))

      stdin?
      (validate-stdin! opts options)

        :else
        (validate-config! opts options)))))

(def subcommand
  {:option-spec option-spec
   :runner      run
   :help-text   help})
