(ns isaac.config.cli.get
  "isaac config get — read the resolved config (or a subtree) by config path."
  (:require
    [isaac.config.paths :as paths]
    [clojure.string :as str]
    [isaac.config.cli.common :as common]
    [isaac.config.cli.inspect :as inspect]
    [isaac.util.edn :as edn-pretty]))

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

(defn- defaulted-value?
  "True when `path-str` resolves to a value in the runtime (conformed-over-raw)
   config but is ABSENT from `raw-root` — the pre-conform root-level data the
   SAME load already computed — at that same path, i.e. the value on display
   came from a schema `:default`, not the file (isaac-dnib). Text output
   annotates this; --edn/--json never do. Reads `raw-root` off the result
   already in hand rather than issuing a second `load-config-result` call,
   which would break \"the CLI resolves the config once per command\"
   (isaac-v1la, cli/config_resolution.feature). `raw-root` is absent for a
   threaded/in-memory config (tests); treat that as \"can't tell, don't
   annotate\" rather than guess."
  [raw-root path-str]
  (and (some? raw-root)
       (nil? (select (common/queryable-config raw-root) path-str))))

(defn- get-value! [opts path-str options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [{:keys [raw reveal edn json]} options
          {:keys [config errors missing-config? raw-root]} (load-result opts raw reveal)]
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

                (defaulted-value? raw-root path-str)
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
