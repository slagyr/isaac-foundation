(ns isaac.config.cli.inspect
  "Shared structured-output helpers for isaac config read/write subcommands."
  (:require
    [c3kit.apron.schema.path :as path]
    [clojure.string :as str]
    [isaac.cli.common :as cli-common]
    [isaac.cli.table :as table]
    [isaac.config.cli.common :as common]
    [isaac.config.paths :as paths]
    [isaac.config.root :as root-res]
    [isaac.config.validation :as validation]))

(def structured-option-spec
  (into common/help-option-spec
        [[nil "--edn" "Print structured EDN output"]
         [nil "--json" "Print structured JSON output"]]))

(defn- config-root [opts]
  (paths/config-root (common/resolve-root opts)))

(defn- value-at-path [config path-str]
  (path/data-at (common/queryable-config config) path-str))

(defn- child-key-names [config path-str]
  (when-let [value (value-at-path config path-str)]
    (when (map? value)
      (sort (map name (keys value))))))

(defn- dotted-child-key [path-str child-key]
  (if (or (nil? path-str) (str/blank? path-str))
    child-key
    (str path-str "." child-key)))

(defn- list-rows [opts path-str child-keys]
  (let [root (config-root opts)]
    (mapv (fn [child-key]
            {:key    child-key
             :source (validation/config-source-file root (dotted-child-key path-str child-key))})
          child-keys)))

(defn structured-format-conflict? [options]
  (when (and (:edn options) (:json options))
    (common/print-cli-error! "choose one of --edn or --json")))

(defn print-structured! [edn? json? value]
  (cond
    json? (cli-common/print-json! value)
    edn?  (cli-common/print-edn! value)
    :else (common/print-cli-error! "structured output requires --edn or --json")))

(defn structured-requested? [{:keys [edn json]}]
  (or edn json))

(defn mutation-result-record [path-str result]
  (cond-> {:path path-str
           :ok   (= :ok (:status result))}
    (:file result) (assoc :file (:file result))
    (seq (:errors result)) (assoc :errors (:errors result))
    (seq (:warnings result)) (assoc :warnings (:warnings result))))

(defn sources-structured-value [sources]
  {:precedence (vec (root-res/root-lookup-precedence))
   :sources    (vec (or sources []))})

(defn inspect! [opts path-str options {:keys [mode]}]
  (if-let [format-error (structured-format-conflict? options)]
    format-error
    (let [{:keys [config errors missing-config?]} (common/load-result opts)]
      (cond
        missing-config?
        (do (common/print-errors! errors "error") 1)

        :else
        (let [child-keys (child-key-names config path-str)
              {:keys [edn json]} options]
          (cond
            (nil? child-keys)
            0

            (or edn json)
            (do (print-structured! edn json
                                   (case mode
                                     :keys child-keys
                                     :list (list-rows opts path-str child-keys)))
                0)

            (= mode :keys)
            (do (common/print-lines! child-keys) 0)

            :else
            (do (println (table/render
                           {:columns [{:header "KEY" :key :key}
                                      {:header "SOURCE" :key :source}]
                            :rows    (list-rows opts path-str child-keys)
                            :zebra?  true}))
                0)))))))

(defn require-path! [path-str]
  (when (or (nil? path-str) (str/blank? path-str))
    (common/print-cli-error! "config path is required")))