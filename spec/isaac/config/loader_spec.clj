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

  (describe "config loader"
  (config-marigold/aboard)
  (helper/with-captured-logs)

  (describe "load-root-config"

    (it "loads root config from overlay content"
      (with-redefs [entities/overlay-for          (fn [_ _] {:content "overlay" :relative "overlay/isaac.edn"})
                    parse/read-edn-string      (fn [_ _] {:berths {:main {}}})
                    companions/resolve-cron-prompts (fn [_ _] {:cron nil :errors []})
                    warnings/top-level-warnings   (fn [_] [{:key "overlay" :value "warning"}])
                    lexicon/conform               (fn [_ _] :ok)
                    cs/error?                (constantly false)]
        (let [result (#'sut/load-root-config marigold/home {:substitute-env? true})]
          (should= {:berths {:main {}}} (:data result))
          (should= [] (:errors result))
          (should= [{:key "overlay" :value "warning"}] (:warnings result))
          (should= [(#'parse/source-path "overlay/isaac.edn")] (:sources result)))))

    (it "reports overlay EDN syntax errors"
      (with-redefs [entities/overlay-for (fn [_ _] {:content "{:broken" :relative paths/root-filename})]
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
        (with-redefs [entities/overlay-for          (constantly nil)
                      parse/read-edn-file        (fn [_ _ _]
                                                 {:data {:watch {:gauge :llama}
                                                         :cron  {:health-check {:expr "0 9 * * *" :berth :main}}}})
                      companions/resolve-cron-prompts (fn [_ _]
                                                 {:cron   {"health-check" {:expr "0 9 * * *" :berth "main" :prompt "Ping"}}
                                                  :errors [{:key "cron.health-check.prompt" :value "bad prompt"}]})
                      warnings/top-level-warnings   (fn [_] [{:key "root" :value "warning"}])
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
              (should= [(#'parse/source-path paths/root-filename)] (:sources result)))))))

    (it "returns file read errors for an on-disk root file"
      (let [mem  (fs/mem-fs)
            path (str marigold/home "/" paths/root-filename)]
        (fs/mkdirs mem marigold/home)
        (fs/spit mem path "{:broken")
        (with-redefs [entities/overlay-for   (constantly nil)
                      parse/read-edn-file (fn [_ _ _] {:error "EDN syntax error"})]
          (nexus/-with-nexus {:fs mem}
            (should= {:data nil
                      :errors [{:key paths/root-filename :value "EDN syntax error"}]
                      :warnings []
                      :sources []}
                     (#'sut/load-root-config marigold/home {}))))))

    (it "returns an empty result when no root config source exists"
      (let [mem (fs/mem-fs)]
        (with-redefs [entities/overlay-for (constantly nil)]
          (nexus/-with-nexus {:fs mem}
            (should= {:data nil :errors [] :warnings [] :sources []}
                     (#'sut/load-root-config marigold/home {})))))))

  (describe "runtime fs"

    (it "loads the root config from the installed runtime fs without binding a thread-local fs"
      (let [mem  (fs/mem-fs)
            root (paths/config-root marigold/home)
            path (str root "/" paths/root-filename)]
        (fs/mkdirs mem root)
        (fs/spit mem path "{:berths {:main {}}}")
        (with-redefs [entities/overlay-for          (constantly nil)
                      companions/resolve-cron-prompts (fn [_ data] {:cron (:cron data) :errors []})
                      warnings/top-level-warnings   (constantly [])
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

  (describe "config-compose collision boundary"

    (it "a table-shell collision returns a located error row instead of throwing"
      (let [index {:mod.a {:manifest {:isaac.config/schema {:tools {:schema {:type :map :description "A"}}}}}
                   :mod.b {:manifest {:isaac.config/schema {:tools {:schema {:type :map :description "B"}}}}}}
            [schema error] (#'sut/compose-or-fallback index)]
        ;; fell back to the builtin composition rather than throwing
        (should-not-be-nil schema)
        (should= "config-schema.tools" (:key error))
        (should (re-find #"collision" (:value error))))))

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

)
)
