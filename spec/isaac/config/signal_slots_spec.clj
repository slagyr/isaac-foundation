(ns isaac.config.signal-slots-spec
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

  (describe "isaac.config.loader/signal_slots"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "signal slot validation"

    (config-marigold/aboard)

    (def telly-manifest
      (pr-str {:id      :isaac.comm.telly
               :version "0.1.0"
               :marigold.chartroom/signal {:telly {:namespace 'isaac.comm.telly
                                                   :extra-schema {:loft  {:type :string
                                                                           :validations [[:present-when? :kind :telly]]}
                                                                  :color {:type :string}
                                                                  :mood  {:type :string
                                                                          :validations [[:one-of? "happy" "sad" "grumpy"]]}}}}}))

    (defn- write-telly-module! []
      (fs/mkdirs (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.telly"))
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.telly/deps.edn")
               "{:paths [\"resources\"]}")
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.telly/resources/isaac-manifest.edn") telly-manifest))

    (def crow-manifest
      (pr-str {:id      :isaac.comm.crow
               :version "0.1.0"
               :marigold.chartroom/signal {:crow {:namespace 'isaac.comm.crow
                                                  :extra-schema {:token       {:type :string}
                                                                 :message-cap {:type :int}
                                                                 :allow-from  {:type :map}}}}}))

    (defn- write-crow-module! []
      (fs/mkdirs (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.crow"))
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.crow/deps.edn")
               "{:paths [\"resources\"]}")
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.crow/resources/isaac-manifest.edn") crow-manifest))

    (it "conforms berth-claimed slices: extension fields coerce like base fields"
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :signals {:bert {:kind :telly :loft 42 :mood "happy"}}})
      (write-telly-module!)
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "42" (get-in result [:config :signals "bert" :loft]))))

    (it "validates declared module signal slot fields with no error for valid value"
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :signals {:bert {:kind :telly :loft "rooftop" :mood "happy"}}})
      (write-telly-module!)
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "rooftop" (get-in result [:config :signals "bert" :loft]))
        (should= "happy" (get-in result [:config :signals "bert" :mood]))))

    (it "generates a conform error for an uncoercible module signal slot field"
      (config-marigold/write-config!
        {:modules {:isaac.comm.crow {:local/root "/marigold/.isaac/modules/isaac.comm.crow"}}
         :signals {:mychan {:kind :crow :message-cap "not-a-number"}}})
      (write-crow-module!)
      (let [result (marigold/load-config)]
        (should (some #(and (= "signals[:mychan].message-cap" (:key %))
                            (re-find #"can't coerce" (:value %)))
                      (:errors result)))))

    (it "requires a manifest field guarded by [:present-when? :kind :telly]"
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :signals {:bert {:kind :telly}}})
      (write-telly-module!)
      (let [result (marigold/load-config)]
        (should (some #(and (= "signals[:bert].loft" (:key %))
                            (re-find #"is required when kind is telly" (:value %)))
                      (:errors result)))))

    (it "applies composed impl fields to a slot whose id names the impl (no :kind)"
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :signals {:telly {:loft 42}}})
      (write-telly-module!)
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "42" (get-in result [:config :signals "telly" :loft]))))

    (it "does not warn 'unknown key' on a base signal-instance field"
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :berths  {:tempest {}}
         :signals {:bert {:kind :telly :berth "tempest" :loft "rooftop"}}})
      (write-telly-module!)
      (let [result (marigold/load-config)]
        (should-not (some #(= "signals.bert.berth" (:key %))
                          (:warnings result)))))

    (it "resolves :berth-exists? refs inside manifest-supplied schemas"
      (let [berth-aware (pr-str {:id      :isaac.comm.telly
                                 :version "0.1.0"
                                 :marigold.chartroom/signal {:telly {:namespace 'isaac.comm.telly
                                                                     :schema  {:override-berth {:type :string
                                                                                                :validations [[:berth-exists?]]}}}}})]
        (fs/mkdirs (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.telly"))
        (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.telly/deps.edn")
                 "{:paths [\"resources\"]}")
        (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.telly/resources/isaac-manifest.edn") berth-aware))
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :berths  {:tempest {}}
         :signals {:bert {:kind :telly :berth "tempest" :override-berth "tempest"}}})
      (let [result (marigold/load-config)]
        (should-not (some #(and (= "signals.bert.override-berth" (:key %))
                                (re-find #"undefined berth" (:value %)))
                          (:errors result)))))

    (it "rejects a manifest enum value outside [:one-of? ...]"
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :signals {:bert {:kind :telly :loft "rooftop" :mood "elated"}}})
      (write-telly-module!)
      (let [result (marigold/load-config)]
        (should (some #(and (= "signals[:bert].mood" (:key %))
                            (re-find #"must be one of" (:value %)))
                      (:errors result)))))

    (it "accepts a manifest enum value inside [:one-of? ...]"
      (config-marigold/write-config!
        {:modules {:isaac.comm.telly {:local/root "/marigold/.isaac/modules/isaac.comm.telly"}}
         :signals {:bert {:kind :telly :loft "rooftop" :mood "happy"}}})
      (write-telly-module!)
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "happy" (get-in result [:config :signals "bert" :mood]))))

    (it "fails fast when a manifest schema references an unregistered ref"
      (fs/mkdirs (nexus/get :fs) "/marigold/.isaac/modules/isaac.comm.broken")
      (fs/spit   (nexus/get :fs) "/marigold/.isaac/modules/isaac.comm.broken/deps.edn"
                 "{:paths [\"resources\"]}")
      (fs/spit   (nexus/get :fs) "/marigold/.isaac/modules/isaac.comm.broken/resources/isaac-manifest.edn"
                 (pr-str {:id      :isaac.comm.broken
                          :version "0.1.0"
                          :marigold.chartroom/signal {:broken {:namespace 'isaac.comm.broken
                                                               :extra-schema {:thing {:type :string
                                                                                      :validations [:no-such-ref?]}}}}}))
      (config-marigold/write-config!
        {:modules {:isaac.comm.broken {:local/root "/marigold/.isaac/modules/isaac.comm.broken"}}})
      (let [result (marigold/load-config)]
        (should (some #(and (= "module-index[\"isaac.comm.broken\"].marigold.chartroom/signal[:broken].extra-schema" (:key %))
                            (= "must be a schema map of field → spec" (:value %)))
                      (:errors result)))))

    (it "generates unknown-key warnings for signal slot fields when module is not declared"
      (config-marigold/write-config!
        {:signals {:bert {:kind :telly :loft "rooftop"}}})
      (let [result (marigold/load-config)]
        (should (some #(and (= "signals[:bert].loft" (:key %))
                            (= "unknown key" (:value %)))
                      (:warnings result)))))

    (it "does not warn for a module-declared signal slot when its module is declared"
      (config-marigold/write-config!
        {:modules {:isaac.comm.crow {:local/root "/marigold/.isaac/modules/isaac.comm.crow"}}
         :signals {:mychan {:kind :crow :token "abc"}}})
      (write-crow-module!)
      (let [result (marigold/load-config)]
        (should-not (some #(str/includes? (:key %) "signals.mychan") (:warnings result))))))

)
)
