;; mutation-tested: 2026-05-06
(ns isaac.config.companions
  "Inline-vs-.md companion resolution for souls, cron prompts, hook templates, hail prompts."
  (:require
    [isaac.config.companion :as companion]
    [isaac.config.parse :as parse]
    [isaac.config.paths :as paths]
    [isaac.config.schema-base :as schema-base]
    [isaac.logger :as log]))

(def ^:private ->id schema-base/->id)

(defn load-companion-text [path]
  (when path
    {:exists? (parse/exists?* path)
     :text    (when (parse/exists?* path)
                (parse/slurp* path))}))

(defn companion-md-relative [kind id]
  (case kind
    :crew   (paths/soul-relative id)
    :berths (paths/ledger-relative id)
    nil))

(defn resolve-inline-or-md-companion [kind field-key id data load-fn]
  (let [result (companion/resolve-text {:inline  (get data field-key)
                                        :load-fn load-fn})]
    {:data  (cond-> data
                    (:value result) (assoc field-key (:value result)))
     :error (when (and (:inline? result) (:companion-exists? result))
              {:key   (str (name kind) "." id "." (name field-key))
               :value "must be set in .edn OR .md"})}))

(defn resolve-companion-field [ns-prefix field-key id entity load-fn relative]
  (let [result (companion/resolve-text {:inline  (get entity field-key)
                                        :load-fn load-fn})
        errors (cond-> []
                       (and (not (:inline? result)) (not (:companion-exists? result)))
                       (conj {:key   (str ns-prefix id "." (name field-key))
                              :value (str "required (inline or " relative ")")})
                       (and (not (:inline? result)) (:companion-empty? result))
                       (conj {:key   (str ns-prefix id "." (name field-key))
                              :value "must not be empty"}))]
    (when (and (:inline? result) (:companion-exists? result))
      (log/warn :config/companion-inline-wins :field field-key :key (str ns-prefix id) :path relative))
    [(cond-> entity (:value result) (assoc field-key (:value result))) errors]))

(defn resolve-cron-prompt [id job load-fn relative]
  (let [[resolved errors] (resolve-companion-field "cron." :prompt id job load-fn relative)]
    {:job resolved :errors errors}))

(defn resolve-cron-prompts [root data]
  (reduce-kv (fn [{:keys [cron errors]} id job]
               (let [id       (->id id)
                     relative (paths/cron-relative id)
                     path     (str root "/" relative)
                     resolved (resolve-cron-prompt id job #(load-companion-text path) relative)]
                 {:cron   (assoc cron id (:job resolved))
                  :errors (into errors (:errors resolved))}))
             {:cron {} :errors []}
             (or (:cron data) {})))

(defn resolve-hook-template [id hook load-fn relative]
  (let [[resolved errors] (resolve-companion-field "hooks." :template id hook load-fn relative)]
    {:hook resolved :errors errors}))

(defn resolve-hail-prompt [id band load-fn]
  (let [result (companion/resolve-text {:inline  (:prompt band)
                                        :load-fn load-fn})]
    {:band   (cond-> band
                     (:value result) (assoc :prompt (:value result)))
     :errors []}))
