;; mutation-tested: 2026-05-06
(ns isaac.foundation.config.companions
  "Inline-vs-.md companion resolution. A table's `:companion` descriptor
   (`:field` + `:mode`) and `:entity-dir` — both read from the composed
   schema — decide which field a `.md` companion fills and how strictly it's
   enforced. Foundation names no kind here: `:mode :exclusive` (inline OR
   .md, never both), `:required` (inline or .md, either is fine, empty .md
   is an error), and `:optional` (.md fills the field when inline is unset;
   never blocks) cover every module's companion field the same way
   (isaac-kcck)."
  (:require
    [isaac.foundation.config.companion :as companion]
    [isaac.foundation.config.parse :as parse]
    [isaac.foundation.config.schema-base :as schema-base]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.logger :as log]))

(def ^:private ->id schema-base/->id)

(defn load-companion-text [path]
  (when path
    {:exists? (parse/exists?* path)
     :text    (when (parse/exists?* path)
                (parse/slurp* path))}))

(defn companion-md-relative
  "The `.md` path for `kind`'s companion field: `<entity-dir>/<id>.md`, where
   `entity-dir` comes from the owning module's own schema descriptor. nil
   when `kind` declares no `:entity-dir` (isaac-kcck)."
  [kind id]
  (when-let [{:keys [entity-dir]} (schema-compose/descriptor-for kind)]
    (when entity-dir
      (str entity-dir "/" id ".md"))))

(defn resolve-inline-or-md-companion
  "`:mode :exclusive` companion resolution: the field may be set inline OR via
   its `.md` companion, never both."
  [kind field-key id data load-fn]
  (let [result (companion/resolve-text {:inline  (get data field-key)
                                        :load-fn load-fn})]
    {:data  (cond-> data
                    (:value result) (assoc field-key (:value result)))
     :error (when (and (:inline? result) (:companion-exists? result))
              {:key   (str (name kind) "." id "." (name field-key))
               :value "must be set in .edn OR .md"})}))

(defn resolve-required-companion
  "`:mode :required` companion resolution: `field-key` must be set inline or
   via its `.md` companion at `relative`; an existing but empty `.md` is also
   an error. `kind` names the owning table only for the error-key prefix
   (e.g. \"cron.<id>.prompt\"). Returns `[entity errors]`."
  [kind field-key id entity load-fn relative]
  (let [ns-prefix (str (name kind) ".")
        result    (companion/resolve-text {:inline  (get entity field-key)
                                           :load-fn load-fn})
        errors    (cond-> []
                          (and (not (:inline? result)) (not (:companion-exists? result)))
                          (conj {:key   (str ns-prefix id "." (name field-key))
                                 :value (str "required (inline or " relative ")")})
                          (and (not (:inline? result)) (:companion-empty? result))
                          (conj {:key   (str ns-prefix id "." (name field-key))
                                 :value "must not be empty"}))]
    (when (and (:inline? result) (:companion-exists? result))
      (log/warn :config/companion-inline-wins :field field-key :key (str ns-prefix id) :path relative))
    [(cond-> entity (:value result) (assoc field-key (:value result))) errors]))

(defn resolve-optional-companion
  "`:mode :optional` companion resolution: `field-key` is filled from the
   `.md` companion when not set inline; never blocks. Returns
   `[entity errors]` (`errors` always empty — kept for a uniform call shape
   with `resolve-required-companion`)."
  [field-key entity load-fn]
  (let [result (companion/resolve-text {:inline  (get entity field-key)
                                        :load-fn load-fn})]
    [(cond-> entity (:value result) (assoc field-key (:value result))) []]))

(defn resolve-cron-prompts
  "Root-level companion resolution for `:cron` entries still inline in
   isaac.edn (before any per-id file split): each job's `:prompt` resolves
   the same `:required` way `resolve-required-companion` resolves any other
   table's companion field. `relative` is nil when no module declares a
   `:cron` schema (no `:entity-dir` to derive a `.md` path from) — the
   companion side only applies to an entry with an actual entity file, so a
   nil `relative` must not be stringified into a bogus `<root>/` path that
   can crash reading an existing directory as a file (isaac-208u); the job
   simply resolves from its inline fields instead."
  [root data]
  (reduce-kv (fn [{:keys [cron errors]} id job]
               (let [id       (->id id)
                     relative (companion-md-relative :cron id)
                     path     (when relative (str root "/" relative))
                     [resolved-job job-errors]
                     (resolve-required-companion :cron :prompt id job #(load-companion-text path) relative)]
                 {:cron   (assoc cron id resolved-job)
                  :errors (into errors job-errors)}))
             {:cron {} :errors []}
             (or (:cron data) {})))
