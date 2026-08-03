(ns isaac.config.load-result-spec
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

  (describe "isaac.config.loader/load_result"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "load-config-result"

    (it "discovers declared modules before conforming the config schema"
      (let [mem     (fs/mem-fs)
            root    (paths/config-root marigold/root)
            path    (str root "/" paths/root-filename)
            modules {:isaac.comm.pigeon {:local/root "/marigold/.isaac/modules/isaac.comm.pigeon"}}
            events  (atom [])]
        (fs/mkdirs mem root)
        (fs/spit mem path (pr-str {:modules modules}))
        (with-redefs [discovery/discover! (fn [config _context]
                                                (swap! events conj [:discover (:modules config)])
                                                {:index {} :errors []})
                      lexicon/conform              (fn [_ data]
                                                (swap! events conj [:conform data])
                                                data)
                      cs/error?               (constantly false)]
          (sut/load-config-result {:root marigold/root :fs mem :skip-entity-files? true})
          (should= [:discover modules] (first @events)))))

    (it "returns an honest empty config when no files exist"
      (let [result (marigold/load-config)]
        (should= [{:key "config"
                   :value (str "no config found; run `isaac init` or create " marigold/home "/.isaac/config/isaac.edn")}]
                 (:errors result))
        (should= {:root (str marigold/home "/.isaac")} (:config result))
        (should= true (:missing-config? result))
        (should= [] (:warnings result))
        (should= [] (:sources result))))

    (it "loads berths from per-entity files with inline ledger"
      (config-marigold/write-berth! test-berth-kw {:gauge :llama :ledger "You are Cordelia."})
      (let [result (marigold/load-config)]
        (should= "llama" (get-in result [:config :berths test-berth :gauge]))
        (should= "You are Cordelia." (get-in result [:config :berths test-berth :ledger]))))

    (it "loads berths from a single markdown file with YAML frontmatter"
      (write-config-with-entities!
        {:gauges    {:llama (gauge-cfg (keyword marigold/flicker-labs) "llama3.2")}
         :foundries {(keyword marigold/flicker-labs) {:api marigold/groves-api}}})
      (config-marigold/write-berth-md! test-berth-kw (str "---\n"
                                                            "gauge: llama\n"
                                                            "---\n\n"
                                                            "You are Cordelia."))
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "llama" (get-in result [:config :berths test-berth :gauge]))))

    (it "prefers single-file berth markdown over legacy files and warns"
      (write-config-with-entities!
        {:gauges    {:grover (gauge-cfg (keyword marigold/helm-systems) "helm-mk-3-1.0")}
         :foundries {(keyword marigold/helm-systems) {:api marigold/helm-api}}})
      (config-marigold/write-berth! test-berth-kw {:gauge :llama})
      (config-marigold/write-berth-md! test-berth-kw (str "---\n"
                                                            "gauge: grover\n"
                                                            "---\n\n"
                                                            "You are Cordelia."))
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "grover" (get-in result [:config :berths test-berth :gauge]))
        (should= [{:key test-berth-md
                   :value (str "single-file config overrides legacy " test-berth-file)}]
                 (filter #(= test-berth-md (:key %)) (:warnings result)))))

    (it "reports duplicate ids across isaac.edn and per-entity files"
      (config-marigold/write-config! {:berths {test-berth-kw {:ledger "First"}}})
      (config-marigold/write-berth! test-berth-kw {:ledger "Second"})
      (let [result (marigold/load-config)]
        (should= [{:key test-berth-path
                   :value (str "defined in both isaac.edn and " test-berth-file)}]
                 (:errors result))))

    (it "reports malformed berth EDN with the relative file path"
      (marigold/write-raw! test-berth-file "{:gauge :llama")
      (let [result (marigold/load-config)]
        (should= [{:key test-berth-file
                   :value "EDN syntax error"}]
                  (:errors result))))

    (it "reports malformed berth YAML frontmatter with the relative file path"
      (marigold/write-raw! test-berth-md "---\ngauge: [broken\n---\n\nYou are Cordelia.")
      (let [result (marigold/load-config)]
        (should= [{:key test-berth-md
                   :value "YAML syntax error"}]
                 (:errors result))))

    (it "reports a ledger conflict when both edn and companion md define ledger"
      (config-marigold/write-berth! test-berth-kw {:ledger "Inline ledger."})
      (config-marigold/write-berth-md! test-berth-kw "File ledger.")
      (let [result (marigold/load-config)]
        (should= [{:key (str test-berth-path ".ledger")
                   :value "must be set in .edn OR .md"}]
                 (:errors result))))

    (it "warns about unknown keys in entity files but still loads"
      (config-marigold/write-berth! test-berth-kw {:berth {test-berth-kw {:gauge :llama}}})
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= [{:key (str test-berth-path ".berth") :value "unknown key"}] (:warnings result))))

    (it "warns about unknown keys in inline root entities"
      (write-config-with-entities! {:watch     {:berth :main :gauge :llama}
                                    :berths    {:main {:experimental true}}
                                    :gauges    {:llama {:reading "llama3.3:1b" :foundry :anthropic}}
                                    :foundries {:anthropic {}}})
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should-contain {:key "berths.main.experimental" :value "unknown key"}
                        (:warnings result))))

    (it "warns about a dangling berth markdown companion without a matching entry"
      (write-config-with-entities! config-marigold/baseline-config)
      (config-marigold/write-berth-md! :ghost "I have no matching entity.")
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= [{:key "berths/ghost.md" :value "dangling: no matching berths entry"}]
                 (filter #(= "berths/ghost.md" (:key %)) (:warnings result)))))

    (it "warns about a dangling cron markdown companion without a matching cron job"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! config-marigold/baseline-config)
          (marigold/write-cron-md! :ghost "I have no matching cron job.")
          (let [result (marigold/load-config)]
            (should= [] (:errors result))
            (should= [{:key "cron/ghost.md" :value "dangling: no matching cron entry"}]
                     (filter #(= "cron/ghost.md" (:key %)) (:warnings result)))))))

    (it "does not warn when a berth markdown companion has a matching entity file"
      (write-config-with-entities! config-marigold/baseline-config)
      (config-marigold/write-berth! marigold/captain {:gauge (keyword marigold/helm-mark-iii)})
      (config-marigold/write-berth-md! marigold/captain "You are Atticus.")
      (let [result (marigold/load-config)]
        (should= [] (filter #(= (str "berths/" marigold/captain ".md") (:key %)) (:warnings result)))))

    (it "treats camelCase config keys as unknown after the hard cutover"
      (config-marigold/write-foundry! :helm-systems {:apiKey "${HELM_API_KEY}"})
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= [{:key "foundries.helm-systems.apiKey" :value "unknown key"}] (:warnings result))))

    (it "validates semantic references across watch berths gauges and foundries"
      (write-config-with-entities!
        {:watch     {:berth :ghost :gauge :llama}
         :berths    {test-berth-kw {:gauge :gpt}}
         :gauges    {:grover (gauge-cfg (keyword marigold/helm-systems) "helm-mk-3-1.0")}
         :foundries {(keyword marigold/helm-systems) {}}})
      (let [result (marigold/load-config)]
        (should= [{:key (str test-berth-path ".gauge") :value "references undefined gauge" :bad-value "gpt" :valid-values ["grover"]}
                  {:key "watch.berth" :value "references undefined berth" :bad-value "ghost" :valid-values [test-berth]}
                  {:key "watch.gauge" :value "references undefined gauge" :bad-value "llama" :valid-values ["grover"]}]
                 (mapv #(select-keys % [:key :value :bad-value :valid-values]) (:errors result)))))

    (it "rejects gauge references to a manifest template that is not instantiated in user config"
      (write-config-with-entities!
        {:gauges {(keyword marigold/helm-mark-iii)
                  (gauge-cfg (keyword marigold/helm-systems) "helm-mk-3-1.0")}})
      (let [result (marigold/load-config)]
        (should= [{:key      (str "gauges." marigold/helm-mark-iii ".foundry")
                   :value    "no registered impls for berth :marigold.chartroom/foundry"
                   :bad-value marigold/helm-systems}]
                 (mapv #(select-keys % [:key :value :bad-value]) (:errors result)))))

    (it "accepts a gauge reference once the template is instantiated via an empty entity file"
      (write-config-with-entities!
        {:gauges {(keyword marigold/helm-mark-iii)
                  (gauge-cfg (keyword marigold/helm-systems) "helm-mk-3-1.0")}})
      (config-marigold/write-foundry! marigold/helm-systems {})
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= marigold/helm-systems (get-in result [:config :gauges marigold/helm-mark-iii :foundry]))))

    (it "loads foundry entity overrides on top of built-in foundries"
      (write-config-with-entities!
        {:gauges {(keyword marigold/helm-mark-iii)
                  (gauge-cfg (keyword marigold/helm-systems) "helm-mk-3-1.0")}})
      (config-marigold/write-foundry! marigold/helm-systems
        {:api-key  "sk-test"
         :base-url (:base-url marigold/helm-provider)})
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= (:base-url marigold/helm-provider) (get-in result [:config :foundries marigold/helm-systems :base-url]))
        (should= "sk-test" (get-in result [:config :foundries marigold/helm-systems :api-key]))))

    (it "reports unknown foundries with the configured foundry list"
      (write-config-with-entities! {:gauges    {:mystery {:reading "enigmatic-1"
                                                           :foundry :foo}}
                                    :foundries {(keyword marigold/helm-systems) {}
                                                (keyword marigold/starcore)     {}}})
      (let [result (marigold/load-config)
            valid  (vec (sort [marigold/helm-systems marigold/starcore]))]
        (should= [{:key          "gauges.mystery.foundry"
                   :value        (str "must be one of " valid)
                   :bad-value    "foo"
                   :valid-values [marigold/helm-systems marigold/starcore]}]
                 (mapv #(select-keys % [:key :value :bad-value :valid-values]) (:errors result)))))

    (it "substitutes environment variables in loaded config"
      (config-marigold/write-foundry! marigold/helm-systems
        (merge (select-keys marigold/helm-provider [:api :base-url :auth])
               {:api-key "${HELM_API_KEY}"}))
      (with-redefs [env/env (fn [name] (when (= "HELM_API_KEY" name) "sk-test-123"))]
        (let [result (marigold/load-config)]
          (should= [] (:errors result))
          (should= "sk-test-123" (get-in result [:config :foundries marigold/helm-systems :api-key])))))

    (it "substitutes environment variables from the isaac .env file"
      (marigold/write-env-file! "ISAAC_ENV_FILE_TEST_KEY=sk-from-isaac\n")
      (config-marigold/write-foundry! marigold/helm-systems
        (merge (select-keys marigold/helm-provider [:api :base-url :auth])
               {:api-key "${ISAAC_ENV_FILE_TEST_KEY}"}))
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "sk-from-isaac" (get-in result [:config :foundries marigold/helm-systems :api-key]))))

    (it "rejects the retired hooks.auth.token slot"
      (with-extended-config-index
        (fn []
          (config-marigold/write-config! {:bulwark {:auth {:token "s3cr3t"}}
                                          :hooks   {:auth {:token "leftover"}}})
          (let [result (marigold/load-config)]
            (should= [{:key "hooks.auth.token"
                       :value "retired; use :bulwark :auth :token"}]
                     (mapv #(select-keys % [:key :value]) (:errors result)))))))

    (it "prefers c3env values over the isaac .env file"
      (marigold/write-env-file! "ISAAC_ENV_FILE_TEST_KEY=sk-from-isaac\n")
      (config-marigold/write-foundry! marigold/helm-systems
        (merge (select-keys marigold/helm-provider [:api :base-url :auth])
               {:api-key "${ISAAC_ENV_FILE_TEST_KEY}"}))
      (c3env/override! "ISAAC_ENV_FILE_TEST_KEY" "sk-from-override")
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= "sk-from-override" (get-in result [:config :foundries marigold/helm-systems :api-key]))))

    (it "loads config when the isaac .env file is absent"
      (write-config-with-entities!
        {:watch     {:berth :main :gauge :llama}
         :berths    {:main {}}
         :gauges    {:llama (gauge-cfg (keyword marigold/helm-systems) "llama3.3:1b")}
         :foundries {(keyword marigold/helm-systems) {}}})
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= :main (get-in result [:config :watch :berth]))))

    (it "preserves cron jobs and timezone from the root config"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}
                                          :tz     "America/Chicago"
                                          :cron   {:health-check {:expr   "0 9 * * *"
                                                                  :berth  :main
                                                                  :prompt "Run the health checkin."}}})
          (let [result (marigold/load-config)]
            (should= "America/Chicago" (get-in result [:config :tz]))
            (should= {:expr   "0 9 * * *"
                      :berth  "main"
                      :prompt "Run the health checkin."}
                     (get-in result [:config :cron "health-check"])))))

    )

    (it "loads cron prompt from a companion markdown file"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}
                                        :cron   {:health-check {:expr "0 9 * * *"
                                                                :berth :main}}})
          (marigold/write-cron-md! :health-check "Run the daily health checkin.")
          (let [result (marigold/load-config)]
            (should= [] (:errors result))
            (should= "Run the daily health checkin."
                     (get-in result [:config :cron "health-check" :prompt]))))))

    (it "loads cron jobs from a single markdown file with YAML frontmatter"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}})
          (marigold/write-cron-md! :health-check (str "---\n"
                                                      "expr: \"0 9 * * *\"\n"
                                                      "berth: main\n"
                                                      "---\n\n"
                                                      "Run the daily health checkin."))
          (let [result (marigold/load-config)]
            (should= [] (:errors result))
            (should= {:expr   "0 9 * * *"
                      :berth  "main"
                      :prompt "Run the daily health checkin."}
                     (get-in result [:config :cron "health-check"]))))))

    (it "loads cron jobs from legacy edn and markdown files"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}})
          (marigold/write-cron! :health-check {:expr "0 9 * * *"
                                               :berth :main})
          (marigold/write-cron-md! :health-check "Run the daily health checkin.")
          (let [result (marigold/load-config)]
            (should= [] (:errors result))
            (should= {:expr   "0 9 * * *"
                      :berth  "main"
                      :prompt "Run the daily health checkin."}
                     (get-in result [:config :cron "health-check"]))))))

    (it "loads hooks from a single markdown file with YAML frontmatter"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths  {:main {}}
                                          :bulwark {:auth {:token "secret123"}}})
          (marigold/write-hook-md! :lettuce (str "---\n"
                                                 "berth: main\n"
                                                 "session-key: hook:lettuce\n"
                                                 "---\n\n"
                                                 "Emergency lettuce report: {{leaves}} leaves remaining."))
          (let [result (marigold/load-config)]
            (should= [] (:errors result))
            (should= "secret123" (get-in result [:config :bulwark :auth :token]))
            (should= {:berth        "main"
                      :session-key  "hook:lettuce"
                      :template     "Emergency lettuce report: {{leaves}} leaves remaining."}
                     (get-in result [:config :hooks "lettuce"]))))))

    (it "loads hooks from legacy edn and markdown files"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}})
          (marigold/write-hook! :lettuce {:berth :main
                                          :session-key "hook:lettuce"})
          (marigold/write-hook-md! :lettuce "Emergency lettuce report: {{leaves}} leaves remaining.")
          (let [result (marigold/load-config)]
            (should= [] (:errors result))
            (should= {:berth        "main"
                      :session-key  "hook:lettuce"
                      :template     "Emergency lettuce report: {{leaves}} leaves remaining."}
                     (get-in result [:config :hooks "lettuce"]))))))

    (it "reports an error when a cron prompt is missing inline and in markdown"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}
                                        :cron   {:health-check {:expr "0 9 * * *"
                                                                :berth :main}}})
          (let [result (marigold/load-config)]
            (should= [{:key "cron.health-check.prompt"
                       :value "required (inline or cron/health-check.md)"}]
                     (filter #(= "cron.health-check.prompt" (:key %)) (:errors result)))))))

    (it "reports an error when a cron companion markdown file is empty"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}
                                        :cron   {:health-check {:expr "0 9 * * *"
                                                                :berth :main}}})
          (marigold/write-cron-md! :health-check "")
          (let [result (marigold/load-config)]
            (should= [{:key "cron.health-check.prompt"
                       :value "must not be empty"}]
                     (filter #(= "cron.health-check.prompt" (:key %)) (:errors result)))))))

    (it "warns and keeps the inline cron prompt when both inline and markdown are present"
      (with-extended-config-index
        (fn []
          (write-config-with-entities! {:berths {:main {}}
                                        :cron   {:health-check {:expr   "0 9 * * *"
                                                                :berth  :main
                                                                :prompt "Inline prompt."}}})
          (marigold/write-cron-md! :health-check "Markdown prompt.")
          (let [result (marigold/load-config)
                entry  (last @log/captured-logs)]
            (should= [] (:errors result))
            (should= "Inline prompt." (get-in result [:config :cron "health-check" :prompt]))
            (should= :config/companion-inline-wins (:event entry))
            (should= :prompt (:field entry))
            (should= "cron.health-check" (:key entry)))))))

)
)
