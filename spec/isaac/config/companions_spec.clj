(ns isaac.config.companions-spec
  (:require
    [c3kit.apron.schema :as cs]
    [c3kit.apron.env :as c3env]
    [clojure.string :as str]
    [isaac.config.companion :as companion]
    [isaac.config.marigold :as config-marigold]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [isaac.logger :as log]
    [isaac.config.paths :as paths]
    [isaac.spec-helper :as helper]
    [isaac.config.loader :as sut]
    [isaac.config.env :as env]
    [isaac.config.parse :as parse]
    [isaac.config.companions :as companions]
    [isaac.config.entities :as entities]
    [isaac.config.normalize :as normalize]
    [isaac.config.warnings :as warnings]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.schema.lexicon :as lexicon]
    [isaac.config.validation :as validation]
    [isaac.config.validation-lexicon :as vlex]
    [isaac.fs :as fs]
    [isaac.module.discovery :as discovery]
    [speclj.core :refer :all]))

(defn- with-config-slot [f]
  (nexus/-with-nexus {:config (atom nil)}
    (f)))

(def ^:private test-berth marigold/first-mate)
(def ^:private test-berth-kw (keyword test-berth))
(def ^:private test-berth-file (str "berths/" test-berth ".edn"))
(def ^:private test-berth-md (str "berths/" test-berth ".md"))
(def ^:private test-berth-path (str "berths." test-berth))
(def ^:private test-berth-tmp-path (str "/tmp/" test-berth ".edn"))

(defn- gauge-cfg
  [foundry reading & {:as overrides}]
  (merge {:reading reading :foundry foundry} overrides))

(defn- write-config-with-entities!
  "Write isaac.edn plus per-entity files for tables the loader keeps off the root map."
  [cfg]
  (doseq [[id entity] (:berths cfg)] (config-marigold/write-berth! id entity))
  (doseq [[id entity] (:gauges cfg)] (config-marigold/write-gauge! id entity))
  (doseq [[id entity] (:foundries cfg)] (config-marigold/write-foundry! id entity))
  (config-marigold/write-config! (dissoc cfg :berths :gauges :foundries)))

(def ^:private cron-config-schema
  {:entity-dir         "cron"
   :frontmatter?       true
   :merge-root-entity? true
   :companion          {:field :prompt :mode :required}
   :schema             {:name        "cron table"
                        :type        :map
                        :description "Cron job configurations"
                        :key-spec    {:type :string}
                        :value-spec  {:name   :cron-job
                                      :type   :map
                                      :schema {:berth  {:type :id :validations [:berth-exists?]}
                                               :expr   {:type :string}
                                               :prompt {:type :string}}}}})

(def ^:private hooks-config-schema
  {:entity-dir   "hooks"
   :frontmatter? true
   :companion    {:field :template :mode :required}
   :schema       {:name        :hooks
                  :type        :map
                  :description "Webhook configuration"
                  :key-spec    {:type :string}
                  :value-spec  {:name   :hook
                                :type   :map
                                :schema {:berth       {:type :id :validations [:berth-exists?]}
                                         :id          {:type :id}
                                         :gauge       {:type :id :validations [:gauge-exists?]}
                                         :session-key {:type :string}
                                         :template    {:type :string}}}
                  :schema      {:auth {:name   :hook-auth
                                       :type   :map
                                       :schema {:token {:type :string
                                                        :validations [[:retired? "use :bulwark :auth :token"]]}}}}}})

(def ^:private cron-hooks-manifest
  {:id                  :loader-spec.cron-hooks
   :version             "0.1.0"
   :isaac.config/schema {:cron                cron-config-schema
                         :hooks               hooks-config-schema
                         :bulwark             {:schema {:type :map}}
                         :sessions            {:schema {:type :map}}
                         :gateway             {:schema {:type :map}}
                         :acp                 {:schema {:type :map}}
                         :modules             {:schema {:type :map}}}})

(defn- chartroom-manifest-with-loader-extensions [manifest]
  (assoc manifest
    :isaac.config/schema
    (merge (:isaac.config/schema manifest)
           (:isaac.config/schema cron-hooks-manifest))))

(def ^:private extended-config-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :marigold.chartroom {:coord {}
                        :manifest (chartroom-manifest-with-loader-extensions
                                    config-marigold/baseline-chartroom-manifest)
                        :path nil}})

(def ^:private auth-guarded-config-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :marigold.chartroom {:coord {}
                        :manifest (chartroom-manifest-with-loader-extensions
                                    (assoc-in config-marigold/baseline-chartroom-manifest
                                      [:isaac.config/schema :foundries :schema :value-spec :schema :api-key :validations]
                                      [[:present-when? :auth "api-key"]]))
                        :path nil}})

(defn- extended-root-schema []
  (schema-compose/effective-root-schema extended-config-index))

(defn- with-config-index [config-index f]
  (binding [discovery/*foundation-index-override* config-index]
    (schema-compose/clear-cache!)
    (try
      (f)
      (finally
        (schema-compose/clear-cache!)))))

(defn- with-extended-config-index [f]
  (with-config-index extended-config-index f))

(defn- with-auth-guarded-config-index [f]
  (with-config-index auth-guarded-config-index f))

(describe "isaac.config.companions"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "companion-md-relative"

    (it "builds <entity-dir>/<id>.md from the table's own descriptor, naming no kind"
      (with-extended-config-index
        (fn []
          (should= "cron/nightly.md" (companions/companion-md-relative :cron "nightly"))
          (should= "hooks/webhook.md" (companions/companion-md-relative :hooks "webhook")))))

    (it "is nil for a kind with no entity-dir"
      (with-extended-config-index
        (fn []
          (should= nil (companions/companion-md-relative :bulwark "anything")))))

    )

  (describe "resolve-required-companion"

    (it "reports a missing required field when neither inline nor companion text exists"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           false
                                              :companion-exists? false
                                              :companion-empty?  false})]
        (should= [{:berth :main}
                  [{:key "hooks.lettuce.template" :value "required (inline or hooks/lettuce.md)"}]]
                 (companions/resolve-required-companion :hooks :template "lettuce" {:berth :main} (constantly nil) "hooks/lettuce.md"))))

    (it "reports an empty required companion markdown file"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           false
                                              :companion-exists? true
                                              :companion-empty?  true})]
        (should= [{:berth :main}
                  [{:key "hooks.lettuce.template" :value "must not be empty"}]]
                 (companions/resolve-required-companion :hooks :template "lettuce" {:berth :main} (constantly nil) "hooks/lettuce.md"))))

    (it "warns and keeps the inline value when a companion file also exists"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           true
                                              :companion-exists? true
                                              :companion-empty?  false
                                              :value             "Inline template."})]
        (let [[entity errors] (companions/resolve-required-companion :hooks :template "lettuce" {:template "Inline template."} (constantly nil) "hooks/lettuce.md")
              entry           (last @log/captured-logs)]
          (should= [] errors)
          (should= "Inline template." (:template entity))
          (should= :config/companion-inline-wins (:event entry))
          (should= :template (:field entry))
          (should= "hooks.lettuce" (:key entry)))))

    )

  (describe "resolve-optional-companion"

    (it "fills the field from the companion when inline is unset"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           false
                                              :companion-exists? true
                                              :companion-empty?  false
                                              :value             "Companion text."})]
        (should= [{:prompt "Companion text."} []]
                 (companions/resolve-optional-companion :prompt {} (constantly nil)))))

    (it "never blocks when neither inline nor companion text exists"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           false
                                              :companion-exists? false
                                              :companion-empty?  false})]
        (should= [{} []]
                 (companions/resolve-optional-companion :prompt {} (constantly nil)))))

     )

)
