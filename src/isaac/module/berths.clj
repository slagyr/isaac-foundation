(ns isaac.module.berths
  "Manifest berth processing — process-manifest-berths! and builtin berth entry registration."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.string :as str]
    [isaac.logger :as log]
    [isaac.module.coords :as coords]
    [isaac.module.discovery :as discovery]
    [isaac.module.lifecycle :as lifecycle]
    [isaac.schema.lexicon :as lexicon]
    [isaac.module.manifest :as manifest]
    [isaac.schema.registered-in :as registered-in]))

(defn berth-entry-factory-sym [module-index berth-id]
  (some (fn [[_ entry]]
          (get-in entry [:manifest :berths berth-id :schema :value-spec :factory]))
        module-index))

(def ^:dynamic *berth-registration-counts* nil)

(defn berth-entry-id
  [berth-schema entry]
  (cond
    (instance? clojure.lang.MapEntry entry) (key entry)
    (and (map? entry) (:name entry))      (keyword (:name entry))
    (and (map? entry) (:path entry))
    (let [path (str/replace (:path entry) #"^/" "")]
      (keyword (str/replace path #"/" "-")))
    (= :map (:type berth-schema))         (some-> entry key)
    :else                                 nil))

(defn- log-berth-registered!
  [berth-id entry-id module-id]
  (when entry-id
    (log/info :berth/registration
              :berth  berth-id
              :entry  entry-id
              :module (coords/id-str module-id))))

(defn- record-berth-registration!
  [berth-id]
  (when *berth-registration-counts*
    (swap! *berth-registration-counts* update berth-id (fnil inc 0))))

(defn register-builtin-berth-entry!
  "Look up `entry-id` in `berth-id` across builtin manifests and install
   it via the berth's per-entry factory. Called by isaac.tool.builtin to
   lazily register a single built-in tool. Returns nil when the entry is
   not declared in any builtin manifest."
  [berth-id entry-id]
  (let [entry-kw    (keyword entry-id)
        builtin     (discovery/builtin-index)
        factory-sym (berth-entry-factory-sym builtin berth-id)
        pair        (some (fn [[mod-id mod-entry]]
                            (when-let [e (get-in mod-entry [:manifest berth-id entry-kw])]
                              [mod-id e]))
                          builtin)]
    (when (and pair factory-sym)
      (let [[consumer-id entry] pair
            _berth-schema (:schema (some (fn [[_ mod-entry]]
                                           (get-in mod-entry [:manifest :berths berth-id]))
                                         builtin))]
        (binding [registered-in/*module-index* builtin]
          ((lifecycle/resolve-symbol! factory-sym) [entry-kw entry])
          (log-berth-registered! berth-id entry-kw consumer-id)
          (record-berth-registration! berth-id))))))


;; ----- Manifest-only berth processing (isaac-8yxs) -----

(defn collect-berth-declarations
  "Walks `module-index` and returns a seq of [berth-id berth-decl] pairs
   across all modules. Berth declarations live at
   `[<provider-id> :manifest :berths <berth-id>]`."
  [module-index]
  (mapcat (fn [[_ entry]]
            (seq (get-in entry [:manifest :berths] {})))
          module-index))

(defn- manifest-only-berth?
  "A berth declares `:manifest` (the contribution shape) without a
   `:config` shape — i.e., contributions come from manifests only, not
   user config slots."
  [berth-decl]
  (and (contains? berth-decl :schema)
       (not (contains? berth-decl :config))))

(defn entry-factory-symbol
  "Walks a berth's :manifest :schema looking for an entry-level
   :factory. For :type :seq berths it lives on :spec; for :type :map on
   :value-spec; for scalar/map berths it can live at the top of the
   schema. Returns the unresolved symbol or nil."
  [berth-schema]
  (some :factory [berth-schema (:spec berth-schema) (:value-spec berth-schema)]))

(defn berth-contribution-entries
  "Returns the entries `(factory entry)` should be called with, given
   the schema shape and a contribution value. Seq → each element;
   map → each `[id entry]` MapEntry so factories can read both the
   contribution id and value (matters for the :tools case where the
   id is the tool's name); scalar → the value itself."
  [berth-schema contribution]
  (case (:type berth-schema)
    :seq contribution
    :map (seq contribution)
    [contribution]))

(defn contributions-to-berth
  "All [consumer-id contribution-value] pairs in `module-index` for
   `berth-id`."
  [module-index berth-id]
  (keep (fn [[consumer-id entry]]
          (when-let [v (get-in entry [:manifest berth-id])]
            [consumer-id v]))
        module-index))

(defn- process-manifest-berth!
  "For one manifest-only berth: resolve its entry-factory and invoke it
   once per contribution entry across all consumers. Returns a vec of
   error rows (empty on success)."
  [module-index berth-id berth-decl]
  (let [berth-schema (:schema berth-decl)
        factory-sym  (entry-factory-symbol berth-schema)]
    (if-not factory-sym
      ;; Foundation default for berths without an entry-level factory
      ;; (the simple merge-to-[<berth-id>] form) is intentionally
      ;; deferred — see bean's "Out of scope".
      []
      (if-let [factory (try (lifecycle/resolve-symbol! factory-sym) (catch Throwable _ nil))]
        ;; Process consumers in topological (load) order so the gather
        ;; (schema-compose) and the activation agree on last-wins
        ;; ownership. For keyed (:map) berths, a later module's entry
        ;; overriding an earlier one's by id is audible — :<kind>/override
        ;; at :warn — matching the gather's override event (isaac-un18).
        (let [order  (try (zipmap (lifecycle/topological-order module-index) (range)) (catch Throwable _ nil))
              ranked (sort-by (fn [[cid _]] (if order (get order cid) (coords/id-str cid)))
                              (contributions-to-berth module-index berth-id))
              keyed? (= :map (:type berth-schema))
              evt    (keyword (name berth-id) "override")
              seen   (atom #{})]
          (vec
            (mapcat
              (fn [[consumer-id contribution]]
                (keep
                  (fn [entry]
                    (when keyed?
                      (let [id (key entry)]
                        (when (contains? @seen id)
                          (log/warn evt :berth (str berth-id) :entry (coords/id-str id)
                                    :module (when consumer-id (coords/id-str consumer-id))))
                        (swap! seen conj id)))
                    (try
                      (factory entry)
                      (log-berth-registered! berth-id (berth-entry-id berth-schema entry)
                                             consumer-id)
                      (record-berth-registration! berth-id)
                      nil
                      (catch Throwable t
                        ;; Don't let one consumer's broken factory abort
                        ;; the whole berth pass. Log the activation
                        ;; failure (mirrors activate!'s legacy error
                        ;; channel) and collect a structured error row.
                        (log/error :module/activation-failed
                                   :module (when consumer-id (coords/id-str consumer-id))
                                   :berth  (str berth-id)
                                   :error  (.getMessage t))
                        {:key   (str "module-index[\"" (coords/id-str consumer-id) "\"].berths[" berth-id "]")
                         :value (.getMessage t)})))
                  (berth-contribution-entries berth-schema contribution)))
              ranked)))
        [{:key   (str "module-index.berths[" berth-id "].factory")
          :value (str "could not resolve factory symbol: " factory-sym)}]))))

(defn process-manifest-berths!
  "For each berth in `module-index` whose schema declares an entry-level
   `:factory`, invokes `(factory entry)` once per contribution entry.

   Factories typically register the entry in the nexus (routes are the
   canonical case — the foundation hands each route map to its
   registration factory; the entry lands at the conventional path so
   the platform can find it later).

   Run AFTER load-config-result has returned and its nested-nexus
   wrap has exited — otherwise the wrap's `install! previous` rolls
   back any new top-level keys the factories register. Returns a vec
   of error rows (empty on success)."
  [module-index]
  (let [counts (atom {})]
    (binding [registered-in/*module-index* module-index
              *berth-registration-counts* counts]
      (let [errors (vec (mapcat (fn [[berth-id berth-decl]]
                                   (when (manifest-only-berth? berth-decl)
                                     (process-manifest-berth! module-index berth-id berth-decl)))
                                 (collect-berth-declarations module-index)))]
        (when (seq @counts)
          (log/info :berth/registration-summary :counts @counts))
        errors))))

;; Top-level manifest keys that are NOT berth contributions.
(def ^:private reserved-top-level-keys
  (into @#'manifest/known-meta-keys @#'manifest/known-extend-kinds))

(defn contribution-key? [k]
  (and (qualified-keyword? k)
       (not (contains? reserved-top-level-keys k))))

(defn collect-contributions [manifest-map]
  (keep (fn [[k v]]
          (when (contribution-key? k) [k v]))
        manifest-map))

(defn find-berth-decl [module-index berth-key]
  (some (fn [[_provider-id entry]]
          (get-in entry [:manifest :berths berth-key]))
        module-index))

(defn ns-keyword->str [kw]
  (str (namespace kw) "/" (name kw)))

(defn unknown-berth-error [consumer-id berth-key]
  {:key   (str "module-index[\"" (coords/id-str consumer-id) "\"][" berth-key "]")
   :value "berth not declared by any installed module"})

(defn flatten-error-paths
  "Walk a c3kit message-map (nested keywords → message strings) producing
   flat [path-vec message-string] pairs."
  ([m] (flatten-error-paths m []))
  ([m prefix]
   (cond
     (map? m) (mapcat (fn [[k v]] (flatten-error-paths v (conj prefix k))) m)
     :else    [[prefix (str m)]])))

(defn format-contribution-suffix
  "First path segment is the contribution-map's outer key (rendered as
   [<kw>]); subsequent segments are dot-prefixed field names. Matches
   the bean's expected shape: berth[:key].field..."
  [path]
  (let [[head & tail] path]
    (str (when head (str "[" head "]"))
         (apply str (map #(str "." (name %)) tail)))))

(defn berth-lexicon
  "Active lexicon with `:present?` re-messaged for berth contributions —
   apron's default 'is required' becomes 'must be present', which is
   the wording ISAAC surfaces consistently for missing berth fields."
  []
  (-> (@#'lexicon/active-lexicon)
      (assoc-in [:validations :present?]
                {:validate cs/present? :message "must be present"})))

(defn contribution-validation-errors [consumer-id berth-key value berth-schema]
  (let [prefix (str "module-index[\"" (coords/id-str consumer-id) "\"]."
                    (ns-keyword->str berth-key))
        result (try (binding [cs/*lexicon* (berth-lexicon)]
                      (cs/conform berth-schema value))
                    (catch Throwable _ nil))]
    (when (and result (cs/error? result))
      (->> (cs/message-map result)
           flatten-error-paths
           (mapv (fn [[path msg]]
                   {:key   (str prefix (format-contribution-suffix path))
                    :value msg}))))))

(defn validate-contributions! [module-index]
  ;; Bind *module-index* so berth schemas using the :registered-in?
  ;; primitive can resolve sibling contributions across the loaded set
  ;; (the validator is data-only; the foundation supplies the view).
  (binding [registered-in/*module-index* module-index]
    (vec
      (mapcat
        (fn [[consumer-id entry]]
          (mapcat
            (fn [[berth-key value]]
              (if-let [berth-decl (find-berth-decl module-index berth-key)]
                (contribution-validation-errors consumer-id berth-key value
                                                (:schema berth-decl))
                [(unknown-berth-error consumer-id berth-key)]))
            (collect-contributions (:manifest entry))))
        module-index))))

