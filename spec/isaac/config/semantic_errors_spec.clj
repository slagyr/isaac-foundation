(ns isaac.config.semantic-errors-spec
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

  (describe "isaac.config.loader/semantic_errors"
  (config-marigold/aboard)
  (helper/with-captured-logs)

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

)
)
