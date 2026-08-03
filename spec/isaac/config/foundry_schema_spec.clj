(ns isaac.config.foundry-schema-spec
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

(describe "isaac.config.parse"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "isaac.config.loader/foundry_schema"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "foundry type schema validation"

    (config-marigold/aboard)

    (def kombucha-manifest
      (pr-str {:id      :isaac.providers.kombucha
               :version "0.1.0"
               :marigold.chartroom/foundry-template
               {:kombucha {:template {:api      "chat-completions"
                                      :base-url "https://api.kombucha.test/v1"
                                      :auth     "api-key"
                                      :readings ["kombucha-large"]}
                           :schema   {:fizz-level {:type :int}}}}}))

    (defn- write-kombucha-module! []
      (fs/mkdirs (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.providers.kombucha"))
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.providers.kombucha/deps.edn")
               "{:paths [\"resources\"]}")
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.providers.kombucha/resources/isaac-manifest.edn") kombucha-manifest))

    (it "rejects a foundry field that violates the manifest :schema"
      (config-marigold/write-config!
        {:modules {:isaac.providers.kombucha {:local/root (str marigold/home "/.isaac/modules/isaac.providers.kombucha")}}})
      (config-marigold/write-foundry! :my-kombucha {:kind :kombucha :api-key "fizzy-secret" :fizz-level "seven"})
      (write-kombucha-module!)
      (let [result (marigold/load-config)]
        (should (some #(and (= "foundries.my-kombucha.fizz-level" (:key %))
                            (re-find #"can.t coerce .* to int" (:value %)))
                      (:errors result)))))

    (it "accepts a foundry field that conforms to the manifest :schema"
      (config-marigold/write-config!
        {:modules {:isaac.providers.kombucha {:local/root (str marigold/home "/.isaac/modules/isaac.providers.kombucha")}}})
      (config-marigold/write-foundry! :my-kombucha {:kind :kombucha :api-key "fizzy-secret" :fizz-level 3})
      (write-kombucha-module!)
      (let [result (marigold/load-config)]
        (should-not (some #(str/includes? (:key %) "foundries.my-kombucha.fizz-level")
                          (:errors result)))))

    (it "rejects a self-defined foundry with auth api-key but no api-key"
      (with-auth-guarded-config-index
        (fn []
          (config-marigold/write-foundry! :my-thing {:api      "messages"
                                                     :base-url "https://example.test"
                                                     :auth     "api-key"})
          (let [result (marigold/load-config)]
            (should (some #(and (= "foundries.my-thing.api-key" (:key %))
                                (re-find #"is required when auth is api-key" (:value %)))
                          (:errors result)))))))

  )

)
)
