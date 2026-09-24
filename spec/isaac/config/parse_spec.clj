(ns isaac.config.parse-spec
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

  (describe "read-frontmatter-file"

    (it "parses YAML frontmatter and applies env substitution"
      (env/set-env-override! "TEST_BERTH" "main")
      (should= {:body "You are Cordelia."
                :data {:berth "main"
                       :gauge "llama"}}
               (#'parse/read-frontmatter-file {:overlay? true
                                             :relative "berths/cordelia.md"
                                             :content  "---\nberth: ${TEST_BERTH}\ngauge: llama\n---\n\nYou are Cordelia."}
                                            true
                                            false)))

    (it "reports YAML syntax errors for malformed frontmatter"
      (should= {:error "YAML syntax error"}
               (#'parse/read-frontmatter-file {:overlay? true
                                             :relative "berths/cordelia.md"
                                             :content  "---\ngauge: [broken\n---\n\nYou are Cordelia."}
                                            true
                                            false))))

  (describe "unresolvable ${VAR} references (isaac-rxun)"

    (it "substitutes a reference that resolves"
      (env/set-env-override! "RXUN_KEY" "sk-real")
      (should= "sk-real" (parse/substitute-env "${RXUN_KEY}")))

    (it "resolves to absent rather than the literal when the variable is unset"
      (should-be-nil (parse/substitute-env "${RXUN_MISSING}")))

    (it "treats a partly-resolvable string as unresolvable — a half-substituted literal still gets sent"
      (env/set-env-override! "RXUN_KEY" "sk-real")
      (should-be-nil (parse/substitute-env "${RXUN_KEY}-${RXUN_MISSING}")))

    (it "leaves a string with no references alone"
      (should= "plain" (parse/substitute-env "plain")))

    (it "names every unresolvable reference in a string, once each"
      (env/set-env-override! "RXUN_KEY" "sk-real")
      (should= ["RXUN_MISSING" "RXUN_ALSO_MISSING"]
               (parse/unresolved-references "${RXUN_KEY} ${RXUN_MISSING} ${RXUN_ALSO_MISSING} ${RXUN_MISSING}")))

    (it "drops a map field whose reference is unset, keeping its siblings"
      (env/set-env-override! "RXUN_KEY" "sk-real")
      (should= {:model "gpt-5" :key "sk-real"}
               (parse/substitute-env-recursive {:model "gpt-5" :key "${RXUN_KEY}" :secret "${RXUN_MISSING}"})))

    (it "records the dropped field's path and the variable that was not set"
      (binding [parse/*unresolved-refs* (atom [])]
        (parse/substitute-env-recursive {:episodes {:embedding {:api-key "${RXUN_MISSING}"}}})
        (should= [{:path [:episodes :embedding :api-key] :ref "RXUN_MISSING"}]
                 @parse/*unresolved-refs*)))

    (it "records nothing when every reference resolves"
      (env/set-env-override! "RXUN_KEY" "sk-real")
      (binding [parse/*unresolved-refs* (atom [])]
        (parse/substitute-env-recursive {:key "${RXUN_KEY}"})
        (should= [] @parse/*unresolved-refs*)))

    (it "prefixes recorded paths with *reference-path* so an entity file reports its full path"
      (binding [parse/*unresolved-refs* (atom [])
                parse/*reference-path*  [:crew "main"]]
        (parse/substitute-env-recursive {:gauge "${RXUN_MISSING}"})
        (should= [{:path [:crew "main" :gauge] :ref "RXUN_MISSING"}]
                 @parse/*unresolved-refs*)))

    (it "drops an unresolvable entry from a sequence and records its index"
      (env/set-env-override! "RXUN_KEY" "sk-real")
      (binding [parse/*unresolved-refs* (atom [])]
        (should= {:args ["sk-real"]}
                 (parse/substitute-env-recursive {:args ["${RXUN_KEY}" "${RXUN_MISSING}"]}))
        (should= [{:path [:args 1] :ref "RXUN_MISSING"}] @parse/*unresolved-refs*)))

    (it "keeps an explicit nil — absent by reference is the only thing it drops"
      (should= {:gauge nil} (parse/substitute-env-recursive {:gauge nil})))

    (it "keeps an explicit nil inside a sequence, where position is meaning"
      (should= {:args ["a" nil "b"]} (parse/substitute-env-recursive {:args ["a" nil "b"]})))

    (it "leaves non-string scalars alone"
      (should= {:port 8080 :on? true} (parse/substitute-env-recursive {:port 8080 :on? true})))

    (it "drops the field when read through read-edn-string"
      (should= {:providers {:zane {:model "gpt-5"}}}
               (parse/read-edn-string "{:providers {:zane {:model \"gpt-5\" :api-key \"${RXUN_MISSING}\"}}}" true)))

    (it "keeps the literal when substitution is off — raw reads are unaffected"
      (should= {:api-key "${RXUN_MISSING}"}
               (parse/read-edn-string "{:api-key \"${RXUN_MISSING}\"}" false))))

)
