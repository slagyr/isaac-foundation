(ns isaac.foundation.config.cli.schema
  "isaac config schema — print the schema for a schema path."
  (:require
    [c3kit.apron.schema :as schema]
    [c3kit.apron.schema.path :as schema-path]
    [clojure.string :as str]
    [isaac.foundation.config.cli.common :as common]
    [isaac.foundation.config.cli.inspect :as inspect]
    [isaac.foundation.config.paths :as paths]
    [isaac.foundation.config.schema-base :as schema-base]
    [isaac.foundation.config.schema.examples :as schema-examples]
    [isaac.foundation.config.schema.term :as schema-term]
    [isaac.foundation.schema.registered-in :as registered-in]
))

(def option-spec
  (into [[nil  "--tree" "Expand every named sub-schema as its own section"]]
        inspect/structured-option-spec))

(defn- examples-body [root]
  (str/join "\n" (map #(str "  " %) (schema-examples/command-lines root))))

(defn help []
  (common/render-help
    {:command     "isaac config schema"
     :params      "[schema-path] [options]"
     :description (str "Print the config schema for a schema path. Schema paths use literal\n"
                       "'key' and 'value' segments to address the key/value types of a map —\n"
                       "for example 'modules.value' is the schema of a single module entry,\n"
                       "'modules.key' is the type of that entry's map key.")
     :option-spec option-spec
     :examples    (examples-body schema-base/base-root)}))

(defn- guidance [root]
  (str "\nTry:\n" (examples-body root)))

(defn- schema-context [opts]
  (common/schema-context opts))

(defn- segment->schema-token
  "One isaac-parsed path segment as an apron schema-path token — always the
   bracket form, so a namespaced keyword (`:local/root`) round-trips whole
   instead of being split on `/` (isaac-cgxa/isaac-dnib: apron's own path
   tokenizer excludes `/` from its identifier class)."
  [[kind v]]
  (case kind
    :key   (str "[:" (subs (str v) 1) "]")
    :index (str "[" v "]")
    :str   (str "[" (pr-str v) "]")))

(defn- ->schema-path
  "Rewrite an isaac config path (which keeps a `/`-namespaced segment whole,
   isaac-cgxa) into the bracket-form apron's own `schema-path/schema-at`
   already understands, so `modules.value.local/root` resolves the same
   `:local/root` field `modules.value[:local/root]` does."
  [path-str]
  (apply str (map segment->schema-token (paths/parse-path-segments path-str))))

(defn- table-spec-at [root first-segment]
  (try (schema-path/schema-at root first-segment) (catch Exception _ nil)))

(defn- dynamic-key-spec? [spec]
  (let [spec (some-> spec schema/normalize-spec)]
    (boolean (and spec (:key-spec spec) (:value-spec spec)))))

(defn- substituted-path
  "When `path-str` targets a dynamic-key map (a spec with both a :key-spec
   and a :value-spec) via a literal slot-id segment followed by further
   drilling (e.g. `comms.discord.token`), rewrite the slot segment as
   `.value` so apron's standard walker descends into the value-spec.
   Requires at least three segments so a two-segment typo (e.g.
   `providers.valued`) is not silently rewritten to `providers.value`.
   Returns nil when no substitution applies."
  [root path-str]
  (let [segments (some-> path-str (str/split #"\."))]
    (when (and (<= 3 (count segments))
               (not (#{"value" "key"} (second segments)))
               (dynamic-key-spec? (table-spec-at root (first segments))))
      (str/join "." (cons (first segments) (cons "value" (drop 2 segments)))))))

(defn- resolve-path [root path-str]
  (if (str/blank? path-str)
    root
    (try
      (or (schema-path/schema-at root (->schema-path path-str))
          (when-let [substituted (substituted-path root path-str)]
            (schema-path/schema-at root (->schema-path substituted))))
      (catch Exception _ nil))))

(defn- print-schema! [opts path-str options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [{:keys [config module-index root]} (schema-context opts)
          spec     (resolve-path root path-str)
          {:keys [tree edn json]} options]
      (if spec
        (cond
          (or edn json)
          (do (inspect/print-structured! edn json spec) 0)

          :else
          (let [root?  (or (nil? path-str) (str/blank? path-str))
                output (binding [registered-in/*module-index* module-index
                                  registered-in/*config*       config]
                          (schema-term/spec->term spec {:color?      (common/stdout-tty?)
                                                        :path-prefix (common/path-prefix path-str)
                                                        :deep?       (boolean tree)
                                                        :width       80}))]
            (if output
              (do
                (println (if root? (str output (guidance root)) output))
                0)
              (do
                (binding [*out* *err*]
                  (println (str "Path not found in config schema: " path-str)))
                1))))
        (do
          (binding [*out* *err*]
            (println (str "Path not found in config schema: " path-str)))
          1)))))

(defn run [opts arguments options]
  (print-schema! opts (common/normalize-path (first arguments)) options))

(def subcommand
  {:option-spec option-spec
   :runner      run
   :help-text   help})
