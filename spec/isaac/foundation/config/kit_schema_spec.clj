(ns isaac.foundation.config.kit-schema-spec
  (:require
    [c3kit.apron.schema :as cs]
    [c3kit.apron.env :as c3env]
    [clojure.string :as str]
    [isaac.foundation.config.companion :as companion]
    [isaac.foundation.config.marigold :as config-marigold]
    [isaac.foundation.marigold :as marigold]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.logger :as log]
    [isaac.foundation.config.paths :as paths]
    [isaac.foundation.spec-helper :as helper]
    [isaac.foundation.config.loader :as sut]
    [isaac.foundation.config.env :as env]
    [isaac.foundation.config.parse :as parse]
    [isaac.foundation.config.companions :as companions]
    [isaac.foundation.config.entities :as entities]
    [isaac.foundation.config.normalize :as normalize]
    [isaac.foundation.config.warnings :as warnings]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.schema.lexicon :as lexicon]
    [isaac.foundation.config.validation :as validation]
    [isaac.foundation.config.validation-lexicon :as vlex]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.discovery :as discovery]
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

(describe "isaac.foundation.config.parse"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "isaac.foundation.config.loader/kit_schema"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "kit schema validation"

    (config-marigold/aboard)

    (it "rejects a missing required kit field from the statically-declared schema"
      (config-marigold/write-config!
        {:kit {:distant-read {:vendor :brave}}})
      (let [result (marigold/load-config)]
        (should (some #(and (= "kit.distant-read.api-key" (:key %))
                            (re-find #"is required" (:value %)))
                      (:errors result)))))

    (it "rejects a kit vendor that falls outside the schema enum"
      (config-marigold/write-config!
        {:kit {:distant-read {:vendor :duckduckgo
                             :api-key "search-key"}}})
      (let [result (marigold/load-config)]
        (should (some #(and (= "kit.distant-read.vendor" (:key %))
                            (re-find #"must be one of" (:value %)))
                      (:errors result)))))

    (it "warns on an unknown kit config key"
      (config-marigold/write-config!
        {:kit {:distant-read {:vendor :brave :api-key "k" :mystery "x"}}})
      (let [result (marigold/load-config)]
        (should (some #(and (= "kit.distant-read.mystery" (:key %))
                            (= "unknown key" (:value %)))
                      (:warnings result))))))

)
)
