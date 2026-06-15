(ns isaac.config.marigold
  "Test fixtures for config CLI, mutate, and schema rendering specs.
   Binds a fictional :marigold.chartroom module alongside foundation so
   specs exercise composed schema without agent or server vocabulary."
  (:require
    [c3kit.apron.env :as c3env]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [speclj.core :as speclj]))

(def baseline-chartroom-manifest
  "Fictional chartroom module for config-spec schema composition."
  {:id       :marigold.chartroom
   :version  "0.1.0"
   :builtin? true
   :factory  'isaac.module.protocol/module

   :berths   {:marigold.chartroom/signal
              {:description "Signal channel impls (config-spec fixtures)."
               :schema      {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type   :map
                                          :schema {:namespace     {:type :symbol :validations [:present?]}
                                                   :extra-schema  {:type :schema-map}
                                                   :configurable? {:type :boolean}}}}}
              :marigold.chartroom/foundry-template
              {:description "Foundry templates (config-spec fixtures)."
               :schema      {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type   :map
                                          :schema {:template {:type :map}
                                                   :schema   {:type :map}}}}}
              :marigold.chartroom/foundry
              {:description "Materialized foundries (config-spec fixtures)."
               :schema      {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type :map}}}}

   :marigold.chartroom/signal
   {(keyword marigold/longwave) {:namespace 'marigold.comm.stub}
    (keyword marigold/skybeam)  {:namespace 'marigold.comm.stub}
    (keyword marigold/logbook)  {:namespace 'marigold.comm.stub}}

   :marigold.chartroom/foundry-template
   {(keyword marigold/helm-systems) {:template (select-keys marigold/helm-provider [:api :base-url :auth])}
    (keyword marigold/starcore)     {:template (select-keys marigold/starcore-provider [:api :base-url :auth])}
    (keyword marigold/flicker-labs) {:template marigold/flicker-provider}
    (keyword marigold/grover-stub)  {:template {:api marigold/grover-api :auth "none"}}}

   :isaac.config/schema
   {:foundries {:entity-dir         "foundries"
                :merge-root-entity? true
                :schema             {:name           "foundry table"
                                     :type           :map
                                     :snapshot-only? true
                                     :description    "Foundry configurations (map of id -> foundry config)"
                                     :key-spec       {:type :string}
                                     :value-spec     {:name           :foundry
                                                      :type           :map
                                                      :dynamic-schema {:berth :marigold.chartroom/foundry-template
                                                                        :path  [:schema]}
                                                      :schema         {:api-key  {:type :string :description "API key"}
                                                                       :auth     {:type :string
                                                                                  :description "Authentication mode (e.g. \"oauth-device\")"}
                                                                       :base-url {:type :string :description "API base URL"}
                                                                       :kind     {:type        :id
                                                                                  :description "Manifest foundry kind to inherit template from"
                                                                                  :validations [[:registered-in? :marigold.chartroom/foundry-template]]}}}}}
    :kit       {:schema {:type        :map
                        :description "Implement configuration (per-implement config maps)"
                        :schema      {:distant-read {:type        :map
                                                     :description "Distant-read implement config"
                                                     :schema      {:vendor  {:type :keyword
                                                                             :validations [[:one-of? :brave]]}
                                                                   :api-key {:type :string
                                                                             :validations [:present?]}}}}}}
    :berths    {:entity-dir         "berths"
                :frontmatter?       true
                :merge-root-entity? true
                :companion          {:field :ledger :mode :exclusive}
                :schema             {:name           "berth table"
                                     :type           :map
                                     :snapshot-only? true
                                     :description    "Berth configurations (map of id -> berth config)"
                                     :key-spec       {:type :string}
                                     :value-spec     {:name   :berth
                                                      :type   :map
                                                      :schema {:id      {:type :id
                                                                        :description "Berth id; must match filename when present"}
                                                               :gauge   {:type        :id
                                                                         :description "Gauge this berth reads."
                                                                         :validations [:gauge-exists?]}
                                                               :ledger  {:type        :string
                                                                         :description "Standing orders. Alternatively saved at config/berths/<id>.md"}
                                                               :foundry {:type        :id
                                                                         :description "Foundry id for direct foundry/gauge berths"
                                                                         :validations [[:registered-in? :marigold.chartroom/foundry [:foundries]]]}}}}}
    :station   {:schema {:name   :station
                         :type   :map
                         :schema {:primary {:type :id}
                                  :backup  {:type :id}}}}
    :relay     {:entity-dir "relay"
                :schema     {:name     "relay table"
                             :type     :map
                             :key-spec {:type :string}
                             :value-spec {:name   :relay
                                          :type   :map
                                          :schema {:id      {:type :id}
                                                   :channel {:type :id}
                                                   :gain    {:type :int}
                                                   :flags   {:type :ignore :set-type? true}
                                                   :limits  {:type   :map
                                                             :schema {:ceiling {:type :double}}}}}}}
    :watch     {:schema {:name        :watch
                         :type        :map
                         :description "Default berth and gauge on the watch"
                         :schema      {:berth {:type        :id
                                               :default     "main"
                                               :description "Default berth id"
                                               :validations [:berth-exists?]}
                                       :gauge {:type        :id
                                               :default     "llama"
                                               :description "Default gauge alias"
                                               :validations [:gauge-exists?]}}}}
    :gauges    {:entity-dir         "gauges"
                :merge-root-entity? true
                :schema             {:name           "gauge table"
                                     :type           :map
                                     :snapshot-only? true
                                     :description    "Gauge configurations (map of id -> gauge config)"
                                     :key-spec       {:type :string}
                                     :value-spec     {:name   :gauge
                                                      :type   :map
                                                      :schema {:id      {:type :id
                                                                        :description "Gauge alias; must match filename when present"}
                                                               :reading {:type        :string
                                                                         :description "Foundry-specific gauge reading"
                                                                         :validations [:present?]
                                                                         :required?   true}
                                                               :foundry {:type        :id
                                                                         :description "Foundry alias"
                                                                         :validations [:present?
                                                                                       [:registered-in? :marigold.chartroom/foundry [:foundries]]]
                                                                         :required?   true}}}}}
    :signals   {:schema {:name        "signals table"
                         :type        :map
                         :description "Signal channel configurations (map of name -> signal config)"
                         :key-spec    {:type :id}
                         :value-spec  {:name           :signal
                                       :type           :map
                                       :factory        'isaac.comm.factory/create!
                                       :dynamic-schema {:berth :marigold.chartroom/signal :path [:extra-schema]}
                                       :schema         {:kind  {:type         :id
                                                               :options-from :signals
                                                               :description  "Manifest signal kind to instantiate"
                                                               :validations  [[:registered-in? :marigold.chartroom/signal [:signals]]]}
                                                        :berth {:type        :id
                                                                :description "Berth id this signal routes into"
                                                                :validations [:berth-exists?]}}}}}}})

(def baseline-config
  "Fully-valid baseline isaac.edn for config-spec tests."
  {:watch     {:berth marigold/captain :gauge marigold/helm-mark-iii}
   :foundries {(keyword marigold/helm-systems) (merge (select-keys marigold/helm-provider [:api :base-url :auth])
                                                     {:api-key "helm-test-key"})}
   :gauges    {(keyword marigold/helm-mark-iii) {:reading "helm-mk-3-1.0"
                                                 :foundry (keyword marigold/helm-systems)}}
   :berths    {(keyword marigold/captain) {:gauge marigold/helm-mark-iii}}})

(defn berth-cfg
  [name & {:as overrides}]
  (merge {:ledger (str "You are " (str/capitalize name) ".")} overrides))

(defn- config-path [suffix]
  (str marigold/root "/config/" suffix))

(defn write-config!
  [data]
  (fs/spit (nexus/get :fs) (config-path "isaac.edn") (pr-str data)))

(defn write-baseline!
  []
  (write-config! baseline-config))

(defn write-berth!
  [berth-id cfg & {:keys [ledger]}]
  (fs/spit (nexus/get :fs) (config-path (str "berths/" (name berth-id) ".edn")) (pr-str cfg))
  (when ledger
    (fs/spit (nexus/get :fs) (config-path (str "berths/" (name berth-id) ".md")) ledger)))

(defn write-berth-md!
  [berth-id body]
  (fs/spit (nexus/get :fs) (config-path (str "berths/" (name berth-id) ".md")) body))

(defn write-gauge!
  [gauge-id cfg]
  (fs/spit (nexus/get :fs) (config-path (str "gauges/" (name gauge-id) ".edn")) (pr-str cfg)))

(defn write-foundry!
  [foundry-id cfg]
  (fs/spit (nexus/get :fs) (config-path (str "foundries/" (name foundry-id) ".edn")) (pr-str cfg)))

(def ^:private baseline-config-test-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :marigold.chartroom {:coord {} :manifest baseline-chartroom-manifest :path nil}})

(defn fixture-modules-root
  "Path to marigold.* config-spec module fixtures on disk."
  []
  (str (System/getProperty "user.dir") "/spec/isaac/config/fixtures/modules"))

(defn install-fixture-module!
  "Copy a config-spec module fixture tree from disk onto the runtime mem-fs.
   Loader discovery checks manifest paths via runtime-fs, not the real fs."
  [module-dir-name]
  (let [fs*  (nexus/get :fs)
        disk (fs/real-fs)
        root (str (fixture-modules-root) "/" module-dir-name)]
    (when (fs/dir? disk root)
      (fs/copy-tree! disk fs* root))))

(defn install-config-modules!
  "Seed mem-fs fixtures for every :modules :local/root declared in `config`."
  [config]
  (doseq [[_module-id coord] (:modules config)
          :let [local-root (:local/root coord)
                module-dir (when local-root (.getName (java.io.File. local-root)))]
          :when module-dir]
    (install-fixture-module! module-dir)))

(defn test-root-schema
  "Composed config schema used by config-cli specs."
  []
  (schema-compose/effective-root-schema baseline-config-test-index))

(defn chartroom-test-index
  "The foundation+chartroom module index used by config schema/CLI specs.
   Bind it to module-loader/*foundation-index-override* (see with-manifest)
   so the chartroom berths (:signals, :foundries, ...) are declared."
  []
  baseline-config-test-index)

(defn with-manifest
  "Bind foundation + marigold.chartroom schema manifests for config schema/CLI specs."
  []
  (speclj/around [example]
    (binding [module-loader/*foundation-index-override* baseline-config-test-index]
      (schema-compose/clear-cache!)
      (try
        (example)
        (finally
          (schema-compose/clear-cache!))))))

(defn aboard
  "Mem-fs scene with marigold.chartroom schema contributions for mutate specs."
  []
  (speclj/around [example]
    (let [mem (fs/mem-fs)]
      (nexus/-with-nested-nexus {:fs mem}
        (binding [module-loader/*foundation-index-override* baseline-config-test-index]
          (reset! c3env/-overrides {})
          (loader/clear-env-overrides!)
          (schema-compose/clear-cache!)
          (example))))))