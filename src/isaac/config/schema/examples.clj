(ns isaac.config.schema.examples
  "Generates the `isaac config schema ...` Try:/Examples command lines from a
   root schema, so no CLI namespace has to hard-code another module's field
   names. Rule (same for the live root command and the static --help text):

     - the bare command: `isaac config schema`
     - `isaac config schema <field>` for the first ROOT field, sorted by
       name, whose type is a plain leaf (not :map, :seq, or :one-of) — omitted
       when no such field exists
     - `isaac config schema <table>` and `isaac config schema <table>.value`
       for the first ROOT field, sorted by name, that has BOTH a :key-spec and
       a :value-spec (a dynamic-key map) — omitted when no such field exists
     - always end with `isaac config schema --tree`"
  (:require
    [c3kit.apron.schema :as schema]))

(defn- leaf-field?
  [spec]
  (let [spec (schema/normalize-spec spec)]
    (not (contains? #{:map :seq :one-of} (:type spec)))))

(defn- dynamic-key-field?
  [spec]
  (let [spec (schema/normalize-spec spec)]
    (boolean (and (= :map (:type spec)) (:key-spec spec) (:value-spec spec)))))

(defn- sorted-root-fields
  [root]
  (sort-by (comp name key) (dissoc (:schema root) :*)))

(defn- first-field-name
  [root pred]
  (some (fn [[k spec]] (when (pred spec) (name k))) (sorted-root-fields root)))

(defn command-lines
  "Vector of full `isaac config schema ...` command-line strings for the
   Try:/Examples block, generated from `root` (a composed or base root
   schema map with a top-level :schema map of fields)."
  [root]
  (let [leaf-field  (first-field-name root leaf-field?)
        table-field (first-field-name root dynamic-key-field?)]
    (into ["isaac config schema"]
          (concat
            (when leaf-field [(str "isaac config schema " leaf-field)])
            (when table-field
              [(str "isaac config schema " table-field)
               (str "isaac config schema " table-field ".value")])
            ["isaac config schema --tree"]))))
