(ns isaac.config.templating
  "Config templating: any config map entity may inherit from a sibling
   template via an explicit `:_base`.

   Generalised from isaac-hail's band-local mechanism (`template-band?` /
   `merge-bands` / `base`), which only hail could use. Nothing is inherited by
   proximity: an entry names its own template, and that template must be a
   sibling in the same map.

   The `_` convention, shared with isaac-49zp:

   - `_` **exactly** means \"this map's own values\" (a filename, isaac-49zp).
     It is never a template.
   - `_<name>` marks a **template**: inherited from, never addressable as a
     real entity. A template is not a crew, not a signal, not a band, so it is
     dropped from the config before validation and before any factory runs.

   Merge is one level: map-valued keys merge key-wise; scalars and vectors
   replace. A single `:_base`, not a list — multiple inheritance invites
   ordering questions nobody has asked for. A template may itself declare
   `:_base`, so chains work; a cycle is a load error naming the cycle."
  (:require
    [clojure.string :as str]))

(def base-key
  "The reserved field naming an entry's template. Leading `_` keeps it from
   colliding with a module's legitimate `:base` key."
  :_base)

(defn entry-name
  "The name of a config entry key or `:_base` value, or nil when the value
   cannot name one. Entry keys are strings in some tables and keywords in
   others, so `:_base \"_worker\"` must find `:_worker` and vice versa."
  [k]
  (cond
    (string? k)                    k
    (or (keyword? k) (symbol? k))  (name k)
    :else                          nil))

(defn template-name?
  "`_<name>` marks a template. `_` exactly is isaac-49zp's \"this map's own
   values\" sentinel — same prefix, different meaning, distinguished by exact
   match."
  [k]
  (let [s (entry-name k)]
    (boolean (and s (str/starts-with? s "_") (not= "_" s)))))

(defn merge-entries
  "One-level merge: map-valued keys merge key-wise; scalars and vectors
   replace. The child's keys win."
  [base child]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b))
                  (merge a b)
                  b))
              base
              child))

(defn- base-ref
  "The template name an entry declares, ::none when it declares none, or
   ::unnameable when `:_base` holds something that cannot name one."
  [entry]
  (if-not (contains? entry base-key)
    ::none
    (or (entry-name (get entry base-key)) ::unnameable)))

(defn- resolve-entry
  "Resolve one entry against `index` (entry-name -> raw entry), following
   `:_base` transitively. Returns {:entry resolved} or {:error message}.
   `trail` is the chain walked so far, newest last, for the cycle message."
  [entry index trail]
  (let [base (base-ref entry)]
    (cond
      (= ::none base)
      {:entry (dissoc entry base-key)}

      (= ::unnameable base)
      {:error (str "invalid " base-key ": " (pr-str (get entry base-key)))}

      (some #{base} trail)
      {:error (str "template cycle: " (str/join " -> " (conj (vec trail) base)))}

      :else
      (let [base-entry (get index base)]
        (cond
          (nil? base-entry)
          {:error (str "missing template: " base)}

          (not (map? base-entry))
          {:error (str "template is not a map: " base)}

          :else
          (let [resolved (resolve-entry base-entry index (conj (vec trail) base))]
            (if-let [error (:error resolved)]
              {:error error}
              {:entry (merge-entries (:entry resolved) (dissoc entry base-key))})))))))

(defn- templating-marked?
  "True when `table` uses templating at all — a `_<name>` entry or an entry
   declaring `:_base`. Tables that use neither are returned untouched, so a
   config without templates is not rebuilt."
  [table]
  (boolean
    (or (some template-name? (keys table))
        (some #(and (map? %) (contains? % base-key)) (vals table)))))

(defn entity-table?
  "True when `v` looks like a table of config entities: a map with at least
   one map-valued entry. Deliberately structural — templating is not
   kind-specific and works for a key no module declares."
  [v]
  (and (map? v) (boolean (some map? (vals v)))))

(defn resolve-table
  "Resolve `:_base` inheritance within one entity table. Returns
   {:table addressable-entries :errors rows}. Templates and entries whose
   resolution failed are dropped; non-map values pass through untouched.
   Error rows are keyed `<table>.<entry>` — the loader's berth normalization
   rewrites that to the table's own error-key shape."
  [table-key table]
  (if-not (templating-marked? table)
    {:table table :errors []}
    (let [index (reduce-kv (fn [acc k v]
                             (if-let [n (entry-name k)] (assoc acc n v) acc))
                           {}
                           table)]
      (reduce-kv
        (fn [acc entry-key entry]
          (cond
            (not (map? entry))
            (assoc-in acc [:table entry-key] entry)

            :else
            (let [{:keys [entry error]} (resolve-entry entry index [(entry-name entry-key)])]
              (cond
                error
                (update acc :errors conj {:key   (str (name table-key) "." (entry-name entry-key))
                                          :value error})

                (template-name? entry-key)
                acc

                :else
                (assoc-in acc [:table entry-key] entry)))))
        {:table {} :errors []}
        table))))

(defn resolve-config
  "Resolve `:_base` inheritance in every entity table of `config`. Returns
   {:config resolved :errors rows}. Tables that use no templating are left
   identical, so a config without templates passes through unchanged."
  [config]
  (reduce-kv
    (fn [acc table-key table]
      (if-not (and (entity-table? table) (templating-marked? table))
        acc
        (let [{:keys [table errors]} (resolve-table table-key table)]
          (-> acc
              (assoc-in [:config table-key] table)
              (update :errors into errors)))))
    {:config config :errors []}
    config))
