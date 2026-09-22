(ns isaac.config.nav
  "Schema-aware path walker and data mutators for Isaac config.

   Provides a walker that traverses dotted data-paths against a schema
   tree, returning the spec at the terminus or a structured error that
   names the failing segment. The schema drives interpretation:

   - Dynamic maps (those with :value-spec) absorb one segment as an
     entity-ID and advance to their value-spec.
   - Static maps look up the segment as a keyword field in :schema.
   - Set-typed specs (those with :set-type? true) treat the next
     segment as the set member — the path terminates there.

   Splitting on '.' (not '/') preserves namespaced keyword segments
   so 'tags.role/worker' correctly yields member :role/worker."
  (:require
    [clojure.string :as str]))

(defn- advance-spec [spec seg]
  (let [has-dynamic? (or (:value-spec spec) (:key-spec spec))]
    (if has-dynamic?
      (:value-spec spec)
      (when (map? (:schema spec))
        (get (:schema spec) (keyword seg))))))

(defn- field-name
  "Render a schema field key the way a caller would type it in a path —
   a qualified keyword keeps its namespace (isaac-cgxa)."
  [k]
  (if (qualified-keyword? k) (str (namespace k) "/" (name k)) (name k)))

(defn- undeclared-segment-error
  "Error map for a segment absent from a STATIC schema'd map (`spec` has a
   `:schema` and no `:value-spec`/`:key-spec` of its own). Includes
   `:parent-path` (the dotted path consumed so far) and `:known-keys` (the
   fields that level declares) so a refusing caller (isaac-a5dx) can name
   both without re-walking the schema. Omitted when `spec` isn't a static
   schema'd map (e.g. the set-terminus-overrun case), which carries no
   'known keys' to report."
  [spec path-str seg consumed]
  (cond-> {:ok? false
           :error   (str "unknown path: " path-str " (unrecognized segment: " seg ")")
           :segment seg}
    (map? (:schema spec))
    (assoc :parent-path (str/join "." consumed)
           :known-keys  (vec (sort (map field-name (keys (:schema spec))))))))

(defn path->spec
  "Walk dotted path-str against root-schema using data-path semantics.
   Returns:
     {:ok? true :spec <spec>}                  — scalar terminus
     {:ok? true :spec <spec> :member <keyword>} — set-typed terminus
     {:ok? false :error <msg> :segment <seg>
      :parent-path <path> :known-keys [<key> ...]} — unknown segment under
        a static schema'd map (:parent-path/:known-keys omitted otherwise)"
  [root-schema path-str]
  (let [segments (str/split path-str #"\.")]
    (loop [spec      root-schema
           remaining segments
           consumed  []]
      (if (empty? remaining)
        {:ok? true :spec spec}
        (let [seg (first remaining)]
          (cond
            ;; Set-typed spec: current segment is the set member (terminal)
            (:set-type? spec)
            (if (empty? (rest remaining))
              {:ok? true :spec spec :member (keyword seg)}
              {:ok? false
               :error   (str "path continues past set terminus in: " path-str)
               :segment seg})

            ;; Advance into next spec node
            :else
            (let [next-spec (advance-spec spec seg)]
              (if (nil? next-spec)
                (undeclared-segment-error spec path-str seg consumed)
                (recur next-spec (rest remaining) (conj consumed seg))))))))))

(defn- path-keys [path-str]
  (mapv keyword (str/split path-str #"\.")))

(defn- parent-path [path-str]
  (str/join "." (butlast (str/split path-str #"\."))))

(defn set-value
  "Returns {:ok? true :config <new-config>} with value at path-str set,
   or {:ok? false :error <msg> :segment <seg>} if the path is unknown.
   For set-typed termini, conj's the implied member into the existing set."
  [root-schema config path-str value]
  (let [result (path->spec root-schema path-str)]
    (if-not (:ok? result)
      (select-keys result [:ok? :error :segment])
      (if-let [member (:member result)]
        (let [parent-ks  (path-keys (parent-path path-str))
              current    (get-in config parent-ks)
              new-set    (conj (or current #{}) member)]
          {:ok? true :config (assoc-in config parent-ks new-set)})
        {:ok? true :config (assoc-in config (path-keys path-str) value)}))))

(defn unset-value
  "Returns {:ok? true :config <new-config>} with path-str cleared; idempotent.
   For set-typed termini, disj's the implied member from the existing set."
  [root-schema config path-str]
  (let [result (path->spec root-schema path-str)]
    (if-not (:ok? result)
      (select-keys result [:ok? :error :segment])
      (if-let [member (:member result)]
        (let [parent-ks  (path-keys (parent-path path-str))
              current    (get-in config parent-ks)
              new-set    (disj (or current #{}) member)]
          {:ok? true :config (assoc-in config parent-ks new-set)})
        (let [ks     (path-keys path-str)
              parent (vec (butlast ks))
              leaf   (last ks)]
          {:ok?    true
           :config (if (empty? parent)
                     (dissoc config leaf)
                     (update-in config parent dissoc leaf))})))))
