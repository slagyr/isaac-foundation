(ns isaac.foundation.config.entities-spec
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

(describe "isaac.foundation.config.entities"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "load-entity-file"

    (it "adds a string read error using the relative path"
      (with-redefs [parse/read-edn-file (fn [_ _ _] {:error "EDN syntax error"})]
        (should= [{:key test-berth-file :value "EDN syntax error"}]
                 (:errors (#'entities/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                                  marigold/home
                                                  :berths
                                                  {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                                  true
                                                  false)))))

    (it "passes through map-shaped errors unchanged"
      (with-redefs [parse/read-edn-file (fn [_ _ _] {:error {:key (str test-berth-path ".ledger") :value "must be set"}})]
        (should= [{:key (str test-berth-path ".ledger") :value "must be set"}]
                 (:errors (#'entities/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                                  marigold/home
                                                  :berths
                                                  {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                                  true
                                                  false)))))

    (it "reports non-map entity content"
      (with-redefs [parse/read-edn-file (fn [_ _ _] {:data [:not-a-map]})]
        (should= [{:key test-berth-file :value "must contain a map"}]
                 (:errors (#'entities/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                                  marigold/home
                                                  :berths
                                                  {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                                  true
                                                  false)))))

    (it "records schema and id mismatch errors without storing invalid config"
      (with-redefs [parse/read-edn-file                (fn [_ _ _] {:data {:id "parrot" :gauge :grover}})
                    warnings/collect-unknown-key-warnings (fn [& _] [{:key (str test-berth-path ".extra") :value "unknown key"}])
                    entities/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'entities/load-entity-file {:config {:berths {test-berth {:gauge "echo"}}}
                                              :root   {:berths {test-berth {:gauge "echo"}}}
                                              :errors []
                                              :warnings []
                                              :sources []}
                                             marigold/home
                                             :berths
                                             {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                             true
                                             false)]
          (should= [{:key (str test-berth-path ".id") :value "must match filename (got \"parrot\")"}
                    {:key test-berth-path :value (str "defined in both isaac.edn and " test-berth-file)}]
                   (:errors result))
          (should= [{:key (str test-berth-path ".extra") :value "unknown key"}] (:warnings result))
          (should= [(#'parse/source-path test-berth-file)] (:sources result))
          (should= {test-berth {:gauge "echo"}} (get-in result [:config :berths])))))

    (it "stores valid entity config and companion extra errors"
      (with-redefs [parse/read-edn-file                (fn [_ _ _] {:data {:gauge :grover :ledger "You are Cordelia."}})
                    warnings/collect-unknown-key-warnings (fn [& _] [])
                    entities/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'entities/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :berths
                                             {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                             true
                                             false)]
          (should= {test-berth {:gauge :grover :ledger "You are Cordelia."}}
                   (get-in result [:config :berths]))
          (should= [(#'parse/source-path test-berth-file)] (:sources result)))))

    (it "records schema errors and source without storing invalid config"
      (with-redefs [parse/read-edn-file                (fn [_ _ _] {:data {:gauge :grover}})
                    warnings/collect-unknown-key-warnings (fn [& _] [])
                    entities/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ _] {:error :invalid})
                    cs/error?                        map?
                    validation/schema-error-entries    (fn [prefix _] [{:key prefix :value "invalid schema"}])]
        (let [result (#'entities/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :berths
                                             {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                             true
                                             false)]
          (should= [{:key test-berth-path :value "invalid schema"}] (:errors result))
          (should= {} (:config result))
          (should= [(#'parse/source-path test-berth-file)] (:sources result)))))

    (it "parses overlay edn content directly"
      (with-redefs [parse/read-edn-string              (fn [_ _] {:gauge :grover :ledger "Overlay ledger"})
                    warnings/collect-unknown-key-warnings (fn [& _] [])
                    entities/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'entities/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :berths
                                             {:format :edn :overlay? true :content "{:gauge :grover}" :relative test-berth-file :id test-berth}
                                             true
                                             false)]
          (should= {test-berth {:gauge :grover :ledger "Overlay ledger"}}
                   (get-in result [:config :berths]))
          (should= [(#'parse/source-path test-berth-file)] (:sources result)))))

    (it "loads markdown frontmatter hooks and records template errors"
      (with-redefs [parse/read-frontmatter-file         (fn [_ _ _] {:data {:berth :main} :body "Template body"})
                    schema-compose/descriptor-for      (fn [_] {:companion {:field :template :mode :required}})
                    companions/resolve-required-companion    (fn [_ _ _ data _ _] [(assoc data :template "Template body")
                                                                              [{:key "hooks.webhook.template" :value "warn"}]])
                    warnings/collect-unknown-key-warnings (fn [& _] [])
                    entities/schema-for                   (fn [_] ::hook)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'entities/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :hooks
                                             {:format :md-frontmatter :relative "hooks/webhook.md" :id "webhook"}
                                             true
                                             false)]
          (should= {"webhook" {:berth :main :template "Template body"}}
                   (get-in result [:config :hooks]))
          (should= [{:key "hooks.webhook.template" :value "warn"}] (:errors result))
          (should= [(#'parse/source-path "hooks/webhook.md")] (:sources result))))))

)
