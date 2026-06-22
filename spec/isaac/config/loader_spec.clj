(ns isaac.config.loader-spec
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
    [isaac.config.schema-compose :as schema-compose]
    [isaac.schema.lexicon :as lexicon]
    [isaac.config.validation :as validation]
    [isaac.config.validation-lexicon :as vlex]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
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
  (binding [module-loader/*foundation-index-override* config-index]
    (schema-compose/clear-cache!)
    (try
      (f)
      (finally
        (schema-compose/clear-cache!)))))

(defn- with-extended-config-index [f]
  (with-config-index extended-config-index f))

(defn- with-auth-guarded-config-index [f]
  (with-config-index auth-guarded-config-index f))

(describe "config loader"

  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "resolve-hook-template"

    (it "reports a missing hook template when neither inline nor companion text exists"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           false
                                              :companion-exists? false
                                              :companion-empty?  false})]
        (should= {:errors [{:key "hooks.lettuce.template"
                            :value "required (inline or hooks/lettuce.md)"}]
                  :hook   {:berth :main}}
                 (#'sut/resolve-hook-template "lettuce" {:berth :main} (constantly nil) "hooks/lettuce.md"))))

    (it "reports an empty hook companion markdown file"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           false
                                              :companion-exists? true
                                              :companion-empty?  true})]
        (should= {:errors [{:key "hooks.lettuce.template"
                            :value "must not be empty"}]
                  :hook   {:berth :main}}
                 (#'sut/resolve-hook-template "lettuce" {:berth :main} (constantly nil) "hooks/lettuce.md"))))

    (it "warns and keeps the inline hook template when a companion file also exists"
      (with-redefs [companion/resolve-text (fn [_]
                                             {:inline?           true
                                              :companion-exists? true
                                              :companion-empty?  false
                                              :value             "Inline template."})]
        (let [result (#'sut/resolve-hook-template "lettuce" {:template "Inline template."} (constantly nil) "hooks/lettuce.md")
              entry  (last @log/captured-logs)]
          (should= [] (:errors result))
          (should= "Inline template." (get-in result [:hook :template]))
          (should= :config/companion-inline-wins (:event entry))
          (should= :template (:field entry))
          (should= "hooks.lettuce" (:key entry)))))

     )

  (describe "read-frontmatter-file"

    (it "parses YAML frontmatter and applies env substitution"
      (sut/set-env-override! "TEST_BERTH" "main")
      (should= {:body "You are Cordelia."
                :data {:berth "main"
                       :gauge "llama"}}
               (#'sut/read-frontmatter-file {:overlay? true
                                             :relative "berths/cordelia.md"
                                             :content  "---\nberth: ${TEST_BERTH}\ngauge: llama\n---\n\nYou are Cordelia."}
                                            true
                                            false)))

    (it "reports YAML syntax errors for malformed frontmatter"
      (should= {:error "YAML syntax error"}
               (#'sut/read-frontmatter-file {:overlay? true
                                             :relative "berths/cordelia.md"
                                             :content  "---\ngauge: [broken\n---\n\nYou are Cordelia."}
                                            true
                                            false))))

  (describe "load-root-config"

    (it "loads root config from overlay content"
      (with-redefs [sut/overlay-for          (fn [_ _] {:content "overlay" :relative "overlay/isaac.edn"})
                    sut/read-edn-string      (fn [_ _] {:berths {:main {}}})
                    sut/resolve-cron-prompts (fn [_ _] {:cron nil :errors []})
                    sut/top-level-warnings   (fn [_] [{:key "overlay" :value "warning"}])
                    lexicon/conform               (fn [_ _] :ok)
                    cs/error?                (constantly false)]
        (let [result (#'sut/load-root-config marigold/home {:substitute-env? true})]
          (should= {:berths {:main {}}} (:data result))
          (should= [] (:errors result))
          (should= [{:key "overlay" :value "warning"}] (:warnings result))
          (should= [(#'sut/source-path "overlay/isaac.edn")] (:sources result)))))

    (it "reports overlay EDN syntax errors"
      (with-redefs [sut/overlay-for (fn [_ _] {:content "{:broken" :relative paths/root-filename})]
        (should= {:data nil
                  :errors [{:key paths/root-filename :value "EDN syntax error"}]
                  :warnings []
                  :sources []}
                 (#'sut/load-root-config marigold/home {}))))

    (it "returns validation errors warnings and sources for an on-disk root file"
      (let [mem  (fs/mem-fs)
            path (str marigold/home "/" paths/root-filename)]
        (fs/mkdirs mem marigold/home)
        (fs/spit mem path "{:watch {:gauge :llama}}")
        (with-redefs [sut/overlay-for          (constantly nil)
                      sut/read-edn-file        (fn [_ _ _]
                                                 {:data {:watch {:gauge :llama}
                                                         :cron  {:health-check {:expr "0 9 * * *" :berth :main}}}})
                      sut/resolve-cron-prompts (fn [_ _]
                                                 {:cron   {"health-check" {:expr "0 9 * * *" :berth "main" :prompt "Ping"}}
                                                  :errors [{:key "cron.health-check.prompt" :value "bad prompt"}]})
                      sut/top-level-warnings   (fn [_] [{:key "root" :value "warning"}])
                      lexicon/conform               (fn [_ data]
                                                 (if (= data {:gauge :llama})
                                                   {:watch-error true}
                                                   :ok))
                      cs/error?                map?
                      validation/schema-error-entries (fn [prefix _]
                                                 [{:key prefix :value "invalid"}])]
          (nexus/-with-nexus {:fs mem}
            (let [result (#'sut/load-root-config marigold/home {:raw-parse-errors? true :substitute-env? true})]
              (should= {:watch {:gauge :llama}
                        :cron  {"health-check" {:expr "0 9 * * *" :berth "main" :prompt "Ping"}}}
                       (:data result))
              (should= [{:key "cron.health-check.prompt" :value "bad prompt"}]
                       (:errors result))
              (should= [{:key "root" :value "warning"}] (:warnings result))
              (should= [(#'sut/source-path paths/root-filename)] (:sources result)))))))

    (it "returns file read errors for an on-disk root file"
      (let [mem  (fs/mem-fs)
            path (str marigold/home "/" paths/root-filename)]
        (fs/mkdirs mem marigold/home)
        (fs/spit mem path "{:broken")
        (with-redefs [sut/overlay-for   (constantly nil)
                      sut/read-edn-file (fn [_ _ _] {:error "EDN syntax error"})]
          (nexus/-with-nexus {:fs mem}
            (should= {:data nil
                      :errors [{:key paths/root-filename :value "EDN syntax error"}]
                      :warnings []
                      :sources []}
                     (#'sut/load-root-config marigold/home {}))))))

    (it "returns an empty result when no root config source exists"
      (let [mem (fs/mem-fs)]
        (with-redefs [sut/overlay-for (constantly nil)]
          (nexus/-with-nexus {:fs mem}
            (should= {:data nil :errors [] :warnings [] :sources []}
                     (#'sut/load-root-config marigold/home {})))))))

  (describe "runtime fs"

    (it "loads the root config from the installed runtime fs without binding fs/*fs*"
      (let [mem  (fs/mem-fs)
            root (paths/config-root marigold/home)
            path (str root "/" paths/root-filename)]
        (fs/mkdirs mem root)
        (fs/spit mem path "{:berths {:main {}}}")
        (with-redefs [sut/overlay-for          (constantly nil)
                      sut/resolve-cron-prompts (fn [_ data] {:cron (:cron data) :errors []})
                      sut/top-level-warnings   (constantly [])
                      lexicon/conform               (fn [_ _] :ok)
                      cs/error?                (constantly false)]
          (nexus/-with-nexus {:fs mem}
            (let [result (#'sut/load-root-config root {:substitute-env? true})]
              (should= {:berths {:main {}}} (:data result))
              (should= [] (:errors result)))))))

    (it "loads config from an explicit fs option without installing runtime fs"
      (let [mem  (fs/mem-fs)
            root (paths/config-root marigold/root)
            path (str root "/" paths/root-filename)]
        (nexus/-with-nexus {:fs mem}
          (fs/mkdirs mem root)
          (fs/spit mem path (pr-str (dissoc config-marigold/baseline-config :berths :gauges :foundries)))
          (config-marigold/write-berth! marigold/captain {:gauge marigold/helm-mark-iii})
          (config-marigold/write-gauge! marigold/helm-mark-iii {:reading "helm-mk-3-1.0"
                                                                :foundry marigold/helm-systems})
          (config-marigold/write-foundry! marigold/helm-systems (merge (select-keys marigold/helm-provider [:api :base-url :auth])
                                                                       {:api-key "helm-test-key"}))
          (let [result (sut/load-config-result {:root marigold/root :fs mem})]
            (should= [] (:errors result))
            (should= "atticus" (get-in result [:config :watch :berth]))))))

    )

  (describe "semantic-errors"

    (it "builds known-id sets once per validation pass"
      ;; Phase 6 (isaac-w7o5): :tool-exists? / known-tool-ids no longer
      ;; live in existence-refs — crew :tools :allow validates via
      ;; [:registered-in? :isaac.server/tools] against the live
      ;; module-index, which short-circuits the known-set memoization
      ;; this test covers for the other capabilities.
      ;; Phase 8 (isaac-qqgv): :comm-exists? no longer lives in
      ;; existence-refs — comm validation goes through
      ;; [:registered-in? :isaac.server/comm [:comms]] which reads
      ;; the live module-index instead of a memoized known-set.
      (let [berth-calls  (atom 0)
            gauge-calls  (atom 0)
            config       {:watch     {:berth "main" :gauge "llama"}
                          :berths    {"main" {:gauge    "llama"
                                              :foundry marigold/starcore}}
                          :gauges    {"llama" {:reading "llama3" :foundry marigold/starcore}}
                          :foundries {marigold/starcore {:api marigold/sky-api}}}]
        (with-redefs-fn {#'vlex/known-berth-ids (fn [_]
                                                  (swap! berth-calls inc)
                                                  ["main"])
                         #'vlex/known-gauge-ids (fn [_]
                                                  (swap! gauge-calls inc)
                                                  ["llama"])}
          #(should= [] (validation/semantic-errors config)))
        (should= 1 @berth-calls)
        (should= 1 @gauge-calls))))

  (describe "load-entity-file"

    (it "adds a string read error using the relative path"
      (with-redefs [sut/read-edn-file (fn [_ _ _] {:error "EDN syntax error"})]
        (should= [{:key test-berth-file :value "EDN syntax error"}]
                 (:errors (#'sut/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                                  marigold/home
                                                  :berths
                                                  {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                                  true
                                                  false)))))

    (it "passes through map-shaped errors unchanged"
      (with-redefs [sut/read-edn-file (fn [_ _ _] {:error {:key (str test-berth-path ".ledger") :value "must be set"}})]
        (should= [{:key (str test-berth-path ".ledger") :value "must be set"}]
                 (:errors (#'sut/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                                  marigold/home
                                                  :berths
                                                  {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                                  true
                                                  false)))))

    (it "reports non-map entity content"
      (with-redefs [sut/read-edn-file (fn [_ _ _] {:data [:not-a-map]})]
        (should= [{:key test-berth-file :value "must contain a map"}]
                 (:errors (#'sut/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                                  marigold/home
                                                  :berths
                                                  {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                                  true
                                                  false)))))

    (it "records schema and id mismatch errors without storing invalid config"
      (with-redefs [sut/read-edn-file                (fn [_ _ _] {:data {:id "parrot" :gauge :grover}})
                    sut/collect-unknown-key-warnings (fn [& _] [{:key (str test-berth-path ".extra") :value "unknown key"}])
                    sut/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'sut/load-entity-file {:config {:berths {test-berth {:gauge "echo"}}}
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
          (should= [(#'sut/source-path test-berth-file)] (:sources result))
          (should= {test-berth {:gauge "echo"}} (get-in result [:config :berths])))))

    (it "stores valid entity config and companion extra errors"
      (with-redefs [sut/read-edn-file                (fn [_ _ _] {:data {:gauge :grover :ledger "You are Cordelia."}})
                    sut/collect-unknown-key-warnings (fn [& _] [])
                    sut/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'sut/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :berths
                                             {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                             true
                                             false)]
          (should= {test-berth {:gauge :grover :ledger "You are Cordelia."}}
                   (get-in result [:config :berths]))
          (should= [(#'sut/source-path test-berth-file)] (:sources result)))))

    (it "records schema errors and source without storing invalid config"
      (with-redefs [sut/read-edn-file                (fn [_ _ _] {:data {:gauge :grover}})
                    sut/collect-unknown-key-warnings (fn [& _] [])
                    sut/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ _] {:error :invalid})
                    cs/error?                        map?
                    validation/schema-error-entries    (fn [prefix _] [{:key prefix :value "invalid schema"}])]
        (let [result (#'sut/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :berths
                                             {:format :edn :path test-berth-tmp-path :relative test-berth-file :id test-berth}
                                             true
                                             false)]
          (should= [{:key test-berth-path :value "invalid schema"}] (:errors result))
          (should= {} (:config result))
          (should= [(#'sut/source-path test-berth-file)] (:sources result)))))

    (it "parses overlay edn content directly"
      (with-redefs [sut/read-edn-string              (fn [_ _] {:gauge :grover :ledger "Overlay ledger"})
                    sut/collect-unknown-key-warnings (fn [& _] [])
                    sut/schema-for                   (fn [_] ::berths)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'sut/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :berths
                                             {:format :edn :overlay? true :content "{:gauge :grover}" :relative test-berth-file :id test-berth}
                                             true
                                             false)]
          (should= {test-berth {:gauge :grover :ledger "Overlay ledger"}}
                   (get-in result [:config :berths]))
          (should= [(#'sut/source-path test-berth-file)] (:sources result)))))

    (it "loads markdown frontmatter hooks and records template errors"
      (with-redefs [sut/read-frontmatter-file         (fn [_ _ _] {:data {:berth :main} :body "Template body"})
                    schema-compose/descriptor-for      (fn [_] {:companion {:field :template}})
                    sut/resolve-hook-template         (fn [_ data _ _] {:hook (assoc data :template "Template body")
                                                                         :errors [{:key "hooks.webhook.template" :value "warn"}]})
                    sut/collect-unknown-key-warnings (fn [& _] [])
                    sut/schema-for                   (fn [_] ::hook)
                    lexicon/conform                       (fn [_ data] data)
                    cs/error?                        (constantly false)]
        (let [result (#'sut/load-entity-file {:config {} :root {} :errors [] :warnings [] :sources []}
                                             marigold/home
                                             :hooks
                                             {:format :md-frontmatter :relative "hooks/webhook.md" :id "webhook"}
                                             true
                                             false)]
          (should= {"webhook" {:berth :main :template "Template body"}}
                   (get-in result [:config :hooks]))
          (should= [{:key "hooks.webhook.template" :value "warn"}] (:errors result))
          (should= [(#'sut/source-path "hooks/webhook.md")] (:sources result))))))

  (describe "config-compose collision boundary"

    (it "a table-shell collision returns a located error row instead of throwing"
      (let [index {:mod.a {:manifest {:isaac.config/schema {:tools {:schema {:type :map :description "A"}}}}}
                   :mod.b {:manifest {:isaac.config/schema {:tools {:schema {:type :map :description "B"}}}}}}
            [schema error] (#'sut/compose-or-fallback index)]
        ;; fell back to the builtin composition rather than throwing
        (should-not-be-nil schema)
        (should= "config-schema.tools" (:key error))
        (should (re-find #"collision" (:value error))))))

  (describe "load-config-result"

    (it "discovers declared modules before conforming the config schema"
      (let [mem     (fs/mem-fs)
            root    (paths/config-root marigold/root)
            path    (str root "/" paths/root-filename)
            modules {:isaac.comm.pigeon {:local/root "/marigold/.isaac/modules/isaac.comm.pigeon"}}
            events  (atom [])]
        (fs/mkdirs mem root)
        (fs/spit mem path (pr-str {:modules modules}))
        (with-redefs [module-loader/discover! (fn [config _context]
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
      (with-redefs [sut/env (fn [name] (when (= "HELM_API_KEY" name) "sk-test-123"))]
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
              result  (sut/normalize-config (extended-root-schema) cfg)]
          (should= (:watch cfg) (:watch result))
          (should= {"nightly" {:expr "0 0 * * *" :berth :main}} (:cron result))
          (should= (:signals cfg) (:signals result))
          (should= (:hooks cfg) (:hooks result))
          (should= (:bulwark cfg) (:bulwark result))
          (should= (:station cfg) (:station result))
          (should= (:tz cfg) (:tz result))
          (should= true (:prefer-entity-files result))
          (should= (:modules cfg) (:modules result)))))

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
              result  (sut/normalize-config cfg)]
          (should= {:crew :main :model :grover} (:defaults result))
          (should= {"main"  {:id :main :soul "You are Isaac." :model :grover}
                    "ketch" {:id "ketch" :model :grover}}
                   (:crew result))
          (should= {"grover" {:model "echo" :provider helm-kw :context-window 200000}}
                   (:models result))
          (should= {marigold/helm-systems {:api-key "sk-test"}
                    "grover"              {:id :grover :base-url "https://grover.example"}}
                   (:providers result))))))

  (describe "semantic-errors"

    (it "reports undefined watch berths gauges foundry cron berth and hook refs"
      (let [schema       (extended-root-schema)
            module-index {:marigold.chartroom {:manifest (get-in extended-config-index [:marigold.chartroom :manifest])}}]
        (should= (sort-by :key
                          [{:key "hooks.auth.token"          :value "retired; use :bulwark :auth :token" :bad-value "secret"      :valid-values nil}
                           {:key "hooks.webhook.berth"       :value "references undefined berth"         :bad-value "ghost"       :valid-values [test-berth]}
                           {:key "hooks.webhook.gauge"       :value "references undefined gauge"         :bad-value "phantom"     :valid-values [marigold/anvil-x]}
                           {:key (str test-berth-path ".gauge") :value "references undefined gauge"    :bad-value "phantom"     :valid-values [marigold/anvil-x]}
                           {:key "watch.berth"               :value "references undefined berth"         :bad-value "ghost"       :valid-values [test-berth]}
                           {:key "watch.gauge"               :value "references undefined gauge"         :bad-value "llama"       :valid-values [marigold/anvil-x]}
                           {:key "cron.nightly.berth"        :value "references undefined berth"         :bad-value "ghost"       :valid-values [test-berth]}
                           {:key (str "gauges." marigold/anvil-x ".reading") :value "is required" :bad-value nil :valid-values nil}
                           {:key (str "gauges." marigold/anvil-x ".foundry")
                            :value "no registered impls for berth :marigold.chartroom/foundry" :bad-value "imaginarium" :valid-values []}])
                 (sort-by :key
                          (mapv #(select-keys % [:key :value :bad-value :valid-values])
                                (validation/semantic-errors {:watch        {:berth "ghost" :gauge "llama"}
                                                    :berths       {test-berth {:gauge "phantom"}}
                                                    :gauges       {marigold/anvil-x {:foundry "imaginarium"}}
                                                    :foundries    {}
                                                    :cron         {"nightly" {:berth "ghost"}}
                                                    :hooks        {"webhook" {:berth "ghost" :gauge "phantom"}
                                                                   :auth      {:token "secret"}}
                                                    :module-index module-index}
                                                   nil
                                                   schema)))))

    )

    (it "returns no semantic errors when all references resolve"
      (let [schema       (extended-root-schema)
            module-index {:marigold.chartroom {:manifest (get-in extended-config-index [:marigold.chartroom :manifest])}}]
        (should= []
                 (validation/semantic-errors {:watch        {:berth "main" :gauge "llama"}
                                              :berths       {"main" {:gauge "llama"}}
                                              :gauges       {"llama" {:reading "llama3" :foundry marigold/helm-systems}}
                                              :foundries    {marigold/helm-systems {}}
                                              :cron         {"nightly" {:berth "main"}}
                                              :hooks        {"webhook" {:berth "main" :gauge "llama"}}
                                              :module-index module-index}
                                             nil
                                             schema)))))

  (describe "module discovery integration"

    (marigold/aboard)

    (it "attaches :module-index to loaded config for declared modules"
      (marigold/write-config! {:modules {:isaac.comm.pigeon {:local/root "/marigold/.isaac/modules/isaac.comm.pigeon"}}})
      (fs/mkdirs (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.pigeon"))
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.pigeon/deps.edn")
               "{:paths [\"resources\"]}")
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.pigeon/resources/isaac-manifest.edn")
               "{:id :isaac.comm.pigeon :version \"0.1.0\"}")
      (let [result (marigold/load-config)]
        (should= [] (:errors result))
        (should= :isaac.comm.pigeon
                 (get-in result [:config :module-index :isaac.comm.pigeon :manifest :id]))
        (should= "/marigold/.isaac/modules/isaac.comm.pigeon"
                 (get-in result [:config :module-index :isaac.comm.pigeon :path]))))

    (it "adds validation errors when a local/root path is not found"
      (marigold/write-config! {:modules {:isaac.comm.ghost {:local/root "/marigold/.isaac/modules/isaac.comm.ghost"}}})
      (let [result (marigold/load-config)]
        (should (some #(and (= "modules[\"isaac.comm.ghost\"]" (:key %))
                            (= "local/root path does not resolve" (:value %)))
                      (:errors result)))))

    (it "adds validation errors when a module manifest is invalid"
      (marigold/write-config! {:modules {:isaac.comm.pigeon {:local/root "/marigold/.isaac/modules/isaac.comm.pigeon"}}})
      (fs/mkdirs (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.pigeon"))
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.pigeon/deps.edn")
               "{:paths [\"resources\"]}")
      (fs/spit (nexus/get :fs) (str marigold/home "/.isaac/modules/isaac.comm.pigeon/resources/isaac-manifest.edn")
               "{:id :isaac.comm.pigeon}")
      (let [result (marigold/load-config)]
        (should (some #(= "module-index[\"isaac.comm.pigeon\"].version" (:key %))
                      (:errors result)))))

    (it "simulates the feature scenario: separate write binding then load binding"
      ;; Mimics the feature runner: write files in one binding, load in another
      (let [mem (fs/mem-fs)]
        ;; write phase (like "the isaac file" step)
        (nexus/-with-nested-nexus {:fs mem}
          (fs/mkdirs mem (str marigold/home "/.isaac/modules/isaac.comm.pigeon"))
          (fs/spit   mem (str marigold/home "/.isaac/modules/isaac.comm.pigeon/deps.edn")
                          "{:paths [\"resources\"]}")
          (fs/spit   mem (str marigold/home "/.isaac/modules/isaac.comm.pigeon/resources/isaac-manifest.edn")
                          "{:id :isaac.comm.pigeon :version \"0.1.0\"}")
          (fs/mkdirs mem (str marigold/home "/.isaac/config"))
          (fs/spit   mem (str marigold/home "/.isaac/config/isaac.edn")
                          "{:modules {:isaac.comm.pigeon {:local/root \"/marigold/.isaac/modules/isaac.comm.pigeon\"}}}"))
        ;; load phase (like "when the config is loaded" step — NEW binding to SAME mem)
        (nexus/-with-nested-nexus {:fs mem}
          (let [result (marigold/load-config)]
            (should-not-be-nil (get-in result [:config :module-index :isaac.comm.pigeon]))
            (should= :isaac.comm.pigeon
                     (get-in result [:config :module-index :isaac.comm.pigeon :manifest :id])))))))

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

  (describe "snapshot"

    (around [it]
      (with-config-slot it))

    (after (sut/set-snapshot! nil "spec"))

    (it "returns nil before any snapshot is set"
      (sut/set-snapshot! nil "spec")
      (should-be-nil (sut/snapshot "spec")))

    (it "returns the config after set-snapshot!"
      (sut/set-snapshot! {:berths {"main" {:ledger "You are helpful."}}} "spec")
      (should= {:berths {"main" {:ledger "You are helpful."}}} (sut/snapshot "spec")))

    (it "returns the latest value after multiple set-snapshot! calls"
      (sut/set-snapshot! {:first true} "spec")
      (sut/set-snapshot! {:second true} "spec")
      (should= {:second true} (sut/snapshot "spec")))

    (it "writes through the system config atom"
      (let [cfg* (atom nil)]
        (nexus/-with-nexus {:config cfg*}
          (sut/set-snapshot! {:berths {"main" {:ledger "Hi"}}} "spec")
          (should= {:berths {"main" {:ledger "Hi"}}} @cfg*)))))

  (describe "load-config!"

    (around [it]
      (with-config-slot it))

    (after (sut/set-snapshot! nil "spec"))

    (it "loads, commits, and returns the config"
      (with-redefs [sut/load-config-result (fn [_] {:config {:berths {"main" {}}} :errors []})]
        (should= {:berths {"main" {}}} (sut/load-config! "/sd" (fs/mem-fs) "spec"))
        (should= {:berths {"main" {}}} (sut/snapshot "spec"))))

    (it "throws carrying ALL validation errors when the config is invalid, and does not commit"
      (with-redefs [sut/load-config-result (fn [_] {:config {} :errors [{:key "a" :value "bad"}
                                                                        {:key "b" :value "worse"}]})]
        (let [ex (try (sut/load-config! "/sd" (fs/mem-fs) "spec") nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (should-not-be-nil ex)
          (should= 2 (count (:errors (ex-data ex))))
          (should-be-nil (sut/snapshot "spec")))))

    (it "commits the empty default for a missing config without throwing"
      (with-redefs [sut/load-config-result (fn [_] {:config {:root "/sd"}
                                                    :errors [{:key "config" :value "missing"}]
                                                    :missing-config? true})]
        (should= {:root "/sd"} (sut/load-config! "/sd" (fs/mem-fs) "spec")))))
