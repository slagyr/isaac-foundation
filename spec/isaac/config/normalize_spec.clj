(ns isaac.config.normalize-spec
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

(describe "isaac.config.normalize"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "normalize-config"

    (it "normalizes modern map-based sections and preserves optional top-level config"
      (with-redefs [lexicon/conform (fn [_ value] value)
                    cs/error?  (constantly false)]
        (let [helm-kw (keyword marigold/helm-systems)
              cfg     {:watch               {:berth :main :gauge :grover}
                       :berths              {:main {:ledger "You are Isaac." :gauge :grover}}
                       :gauges              {:grover {:reading "echo" :foundry helm-kw}}
                       :foundries           {helm-kw {:api-key "sk-test"}}
                       :cron                {:nightly {:expr "0 0 * * *" :berth :main}}
                       :signals             {(keyword marigold/longwave) {:token "abc"}}
                       :hooks               {(keyword marigold/lettuce-hook) {:token "secret"}}
                       :bulwark             {:port 6674}
                       :station             {:primary "alpha"}
                       :tz                  "UTC"
                       :prefer-entity-files true
                       :modules             {:isaac.comm.pigeon {:local/root "/tmp/pigeon"}}}
              result  (normalize/normalize-config (extended-root-schema) cfg)]
          (should= (:watch cfg) (:watch result))
          (should= {"nightly" {:expr "0 0 * * *" :berth :main}} (:cron result))
          (should= (:signals cfg) (:signals result))
          (should= (:hooks cfg) (:hooks result))
          (should= (:bulwark cfg) (:bulwark result))
          (should= (:station cfg) (:station result))
          (should= (:tz cfg) (:tz result))
          (should= true (:prefer-entity-files result))
          (should= (:modules cfg) (:modules result)))))

    (it "injects default compaction policy into defaults when absent"
      (with-redefs [lexicon/conform (fn [_ value] value)
                    cs/error?  (constantly false)]
        (let [cfg    {:defaults {:crew :main :model :grover}
                      :crew     {"main" {}}
                      :models   {"grover" {:model "echo" :provider "ollama"}}
                      :providers {"ollama" {:api "ollama"}}}
              result (normalize/normalize-config cfg)]
          (should= {:async? false :strategy :rubberband :head 0.3 :threshold 0.8}
                   (get-in result [:defaults :compaction])))))

    (it "keeps :crew when the composed schema has no :defaults table"
      (let [cfg    {:defaults {:crew :main}
                    :crew     {"main" {}}
                    :models   {}
                    :providers {}}
            result (normalize/normalize-config {:name :isaac :type :map :schema {}} cfg)]
        (should= :main (get-in result [:defaults :crew]))
        (should= {:async? false :strategy :rubberband :head 0.3 :threshold 0.8}
                 (get-in result [:defaults :compaction]))))

    (it "normalizes legacy crew lists nested models and provider vectors"
      (with-redefs [lexicon/conform (fn [_ value] value)
                    cs/error?  (constantly false)]
        (let [helm-kw (keyword marigold/helm-systems)
              cfg     {:crew   {:defaults {:crew :main :model :grover}
                                :list     [{:id :main :soul "You are Isaac." :model :grover}
                                           {:id "ketch" :model :grover}]
                                :models   {:grover {:model "echo" :provider helm-kw :context-window 200000}}}
                       :models {:providers [{:name helm-kw :api-key "sk-test"}
                                            {:id :grover :base-url "https://grover.example"}]}}
              result  (normalize/normalize-config cfg)]
          (should= {:crew :main :model :grover
                    :compaction {:async? false :strategy :rubberband :head 0.3 :threshold 0.8}}
                   (:defaults result))
          (should= {"main"  {:id :main :soul "You are Isaac." :model :grover}
                    "ketch" {:id "ketch" :model :grover}}
                   (:crew result))
          (should= {"grover" {:model "echo" :provider helm-kw :context-window 200000}}
                   (:models result))
          (should= {marigold/helm-systems {:api-key "sk-test"}
                    "grover"              {:id :grover :base-url "https://grover.example"}}
                   (:providers result))))))

)
