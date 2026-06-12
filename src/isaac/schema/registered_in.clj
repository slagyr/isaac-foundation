(ns isaac.schema.registered-in
  "`:registered-in?` validation primitive — asserts a value is the id of a
   contribution registered to a named berth. Wired into apron's
   validations lexicon at load time so config schemas can write

     :type {:type        :keyword
            :validations [:present? [:registered-in? :isaac.server/comm]]}

   and have the validator pull the live contribution set from the
   ambient module-index. Callers bind `*module-index*` before running
   validation (the contribution-validation pass in
   `isaac.module.loader` does this); for direct use (specs,
   embedders) bind it yourself."
  (:require
    [c3kit.apron.schema :as schema]))

(def ^{:dynamic true
       :doc "Module-index the `:registered-in?` validator should resolve
            berth contributions against. nil ⇒ treated as empty."}
  *module-index* nil)

(def ^{:dynamic true
       :doc "Ambient config snapshot the `:registered-in?` validator
            reads to discover config-side contributions when the
            validation is written as `[:registered-in? <berth-id>
            <config-path>]`. nil ⇒ skip the config-side merge."}
  *config* nil)

;; Cap on listing accepted ids in the failure message — keep small lists
;; informative without flooding the error when a berth has many
;; contributions. Picked low; tune later if needed.
(def ^:private accepted-ids-list-cap 5)

(defn- berth-decl [module-index berth-id]
  (some (fn [[_ entry]]
          (get-in entry [:manifest :berths berth-id]))
        module-index))

(defn- berth-declared? [module-index berth-id]
  (boolean (berth-decl module-index berth-id)))

(defn- manifest-contribution-ids [module-index berth-id]
  (->> module-index
       (mapcat (fn [[_ entry]]
                 (when-let [v (get-in entry [:manifest berth-id])]
                   (when (map? v) (keys v)))))
       (into #{})))

(defn- config-contribution-ids [config config-path]
  (when (and config (seq config-path))
    (let [slice (get-in config config-path)]
      (when (map? slice) (set (keys slice))))))

(defn- ->id [v]
  (cond
    (keyword? v) (name v)
    (string? v)  v
    (symbol? v)  (name v)
    :else        (str v)))

(defn- contributions-for-berth [module-index config berth-id config-path]
  ;; Normalize all contribution ids to plain names (strings) so the
  ;; manifest-side (keys arrive as keywords) and config-side (keys
  ;; may be keywords or strings depending on the user's EDN flavor)
  ;; can be set-unioned without duplicates.
  (into #{}
        (map ->id)
        (concat (or (manifest-contribution-ids module-index berth-id) #{})
                (or (config-contribution-ids config config-path) #{}))))

(defn- fail!
  "Apron resolves a validation failure's message in this order:
   ex-data :message → ex-data :message → ex-message → lex default.
   Throwing with the message in ex-data lets the validator pick the
   message dynamically based on what it actually found."
  [msg]
  (throw (ex-info msg {:message msg})))

(defn registered-in?
  "Validation factory: pass when `value` is a contribution id registered
   to `berth-id`. Manifest contributions are always read from
   `(get-in module [:manifest berth-id])`. An optional `config-path`
   (a vector like `[:providers]`) tells the validator to ALSO union
   the keys at that path in `*config*` — used for config-berth-style
   contributions where users instantiate entries directly.

   Usage:
     `[:registered-in? :isaac.server/comm]`                 — manifest-side only
     `[:registered-in? :isaac.server/provider [:providers]]` — manifest + user-config

   Distinct failure messages for unknown berth, empty contribution
   set, and bad value. Returns a validation map with a `:known` thunk
   so the CLI renderer (isaac.config.cli.validate) can list accepted
   ids alongside the failure."
  ([berth-id]            (registered-in? berth-id nil))
  ([berth-id config-path]
   {:validate (fn [value]
                (let [mi (or *module-index* {})]
                  (cond
                    (not (berth-declared? mi berth-id))
                    (fail! (str "unknown berth: " berth-id))

                    :else
                    (let [accepted (contributions-for-berth mi *config* berth-id config-path)]
                      (cond
                        (empty? accepted)
                        (fail! (str "no registered impls for berth " berth-id))

                        (contains? accepted (->id value))
                        true

                        (<= (count accepted) accepted-ids-list-cap)
                        (fail! (str "must be one of " (vec (sort accepted))))

                        :else
                        (fail! (str "must be a registered contribution to " berth-id)))))))
    :message  (str "must be a registered contribution to " berth-id)
    :known    (fn []
                (let [mi (or *module-index* {})]
                  (->> (contributions-for-berth mi *config* berth-id config-path)
                       sort
                       vec)))}))

;; Wire into apron's validation lexicon so any schema can reference the
;; factory by name: `:validations [[:registered-in? :foo/berth]]`.
(schema/update-lexicon! :validations assoc :registered-in? registered-in?)
