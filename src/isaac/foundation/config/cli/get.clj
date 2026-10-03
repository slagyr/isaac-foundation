(ns isaac.foundation.config.cli.get
  "isaac config get — read the resolved config (or a subtree) by config path."
  (:require
    [isaac.foundation.config.paths :as paths]
    [clojure.string :as str]
    [isaac.foundation.config.cli.common :as common]
    [isaac.foundation.config.cli.inspect :as inspect]
    [isaac.foundation.util.edn :as edn-pretty]))

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

(defn- data-at [config path-str]
  ;; isaac-cgxa: a `.`-separated segment containing `/` is one namespaced
  ;; keyword (`gchat/allow-from`), not two nested keys. c3kit's tokenizer
  ;; drops the slash; descend isaac-parsed segments instead.
  (reduce (fn [value segment]
            (case (first segment)
              :key   (get value (second segment))
              :str   (get value (second segment))
              :index (when (sequential? value) (nth value (second segment) nil))))
          config
          (paths/parse-path-segments path-str)))

(defn- select [config path-str]
  (if (or (nil? path-str) (str/blank? path-str))
    config
    (let [value (data-at (common/queryable-config config) path-str)]
      (when (common/value-present? value) value))))

(defn- load-result [opts raw? reveal?]
  (cond
    raw?    (common/load-raw-result opts true)
    :else   (common/printable-config opts reveal?)))

(defn- raw-at [raw path-str]
  (when (some? raw)
    (select raw path-str)))

(defn- raw-field [raw key]
  (when (map? raw)
    (let [alternate (if (keyword? key) (name key) (keyword key))]
      (if (contains? raw key) (get raw key) (get raw alternate)))))

(defn- defaulted-leaf? [value raw]
  (if (map? value)
    (some (fn [[k v]] (defaulted-leaf? v (raw-field raw k))) value)
    (nil? raw)))

(defn- annotated-map [value raw indent]
  (let [pad     (apply str (repeat indent " "))
        entries (sort-by (comp pr-str key) value)
        lines   (for [[k v] entries
                      :let [source (raw-field raw k)]]
                  (str pad "  " (pr-str k) " "
                       (if (and (map? v) (defaulted-leaf? v source))
                         (annotated-map v source (+ indent 2))
                         (edn-pretty/pretty v))
                       (when (and (not (map? v)) (nil? source)) " ; default")))]
    (str "{\n" (str/join "\n" lines) "\n" pad "}")))

(defn- get-value! [opts path-str options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [{:keys [raw reveal edn json]} options
          {:keys [config errors missing-config? raw-root]
           raw-config :raw} (load-result opts raw reveal)
          file-values (when (some? raw-root) (merge-with merge raw-root raw-config))]
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

                raw
                (do (common/print-edn! presented) 0)

                (and (map? presented) (some? file-values)
                     (defaulted-leaf? presented (raw-at file-values path-str)))
                (do (println (annotated-map presented (raw-at file-values path-str) 0)) 0)

                (and (some? file-values) (nil? (raw-at file-values path-str)))
                (do (println (str (edn-pretty/pretty presented) " (default)")) 0)

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
