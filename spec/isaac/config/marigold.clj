(ns isaac.config.marigold
  "Test fixtures for config CLI, mutate, and schema rendering specs.
   Binds a fictional :marigold.server module alongside foundation so
   specs exercise composed schema without isaac-agent."
  (:require
    [c3kit.apron.env :as c3env]
    [isaac.config.loader :as loader]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [speclj.core :as speclj]))

(def baseline-server-manifest
  "Fictional consumer module for config-spec schema composition."
  {:id       :marigold.server
   :version  "0.1.0"
   :builtin? true
   :factory  'isaac.module.protocol/module

   :berths   {:marigold.server/comm
              {:description "Communication channel impls (config-spec fixtures)."
               :schema      {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type   :map
                                          :schema {:namespace     {:type :symbol :validations [:present?]}
                                                   :extra-schema  {:type :schema-map}
                                                   :configurable? {:type :boolean}}}}}
              :marigold.server/provider-template
              {:description "Provider templates (config-spec fixtures)."
               :schema      {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type   :map
                                          :schema {:template {:type :map}
                                                   :schema   {:type :map}}}}}
              :marigold.server/provider
              {:description "Materialized providers (config-spec fixtures)."
               :schema      {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type :map}}}}

   :marigold.server/comm
   {(keyword marigold/longwave) {:namespace 'marigold.comm.stub}
    (keyword marigold/skybeam)  {:namespace 'marigold.comm.stub}
    (keyword marigold/logbook)  {:namespace 'marigold.comm.stub}}

   :marigold.server/provider-template
   {(keyword marigold/helm-systems) {:template (select-keys marigold/helm-provider [:api :base-url :auth])}
    (keyword marigold/starcore)     {:template (select-keys marigold/starcore-provider [:api :base-url :auth])}
    (keyword marigold/flicker-labs) {:template marigold/flicker-provider}
    (keyword marigold/grover-stub)  {:template {:api marigold/grover-api :auth "none"}}}

   :isaac.config/schema
   {:providers {:entity-dir         "providers"
                 :merge-root-entity? true
                 :schema             {:name           "provider table"
                                      :type           :map
                                      :snapshot-only? true
                                      :description    "Provider configurations (map of id -> provider config)"
                                      :key-spec       {:type :string}
                                      :value-spec     {:name           :provider
                                                       :type           :map
                                                       :dynamic-schema {:berth :marigold.server/provider-template
                                                                        :path  [:schema]}
                                                       :schema         {:api-key  {:type :string :description "API key"}
                                                                        :auth     {:type :string
                                                                                   :description "Authentication mode (e.g. \"oauth-device\")"}
                                                                        :base-url {:type :string :description "API base URL"}
                                                                        :type     {:type        :id
                                                                                   :description "Manifest provider id to inherit template from"
                                                                                   :validations [[:registered-in? :marigold.server/provider-template]]}}}}}
    :tools     {:schema {:type        :map
                        :description "Tool configuration (per-tool config maps)"
                        :schema      {:web_search {:type        :map
                                                   :description "Web search tool config"
                                                   :schema      {:provider {:type :keyword
                                                                            :validations [[:one-of? :brave]]}
                                                                 :api-key  {:type :string
                                                                            :validations [:present?]}}}}}}
    :crew      {:entity-dir         "crew"
                :frontmatter?       true
                :merge-root-entity? true
                :companion          {:field :soul :mode :exclusive}
                :schema             {:name           "crew table"
                                     :type           :map
                                     :snapshot-only? true
                                     :description    "Crew member configurations (map of id -> crew config)"
                                     :key-spec       {:type :string}
                                     :value-spec     {:name   :crew
                                                      :type   :map
                                                      :schema {:id       {:type :id
                                                                          :description "Crew member id; must match filename when present"}
                                                               :model    {:type        :id
                                                                          :description "ID of the model this crew member uses."
                                                                          :validations [:model-exists?]}
                                                               :soul     {:type        :string
                                                                          :description "The personality of this crew member. Alternatively saved at config/crew/<id>.md"}
                                                               :provider {:type        :id
                                                                          :description "Provider id for direct provider/model crews"
                                                                          :validations [[:registered-in? :marigold.server/provider [:providers]]]}}}}}
    :defaults  {:schema {:name        :defaults
                         :type        :map
                         :description "Default crew and model selections"
                         :schema      {:crew  {:type        :id
                                               :default     "main"
                                               :description "Default crew member id"
                                               :validations [:crew-exists?]}
                                       :model {:type        :id
                                               :default     "llama"
                                               :description "Default model alias"
                                               :validations [:model-exists?]}}}}
    :models    {:entity-dir         "models"
                :merge-root-entity? true
                :schema             {:name           "model table"
                                     :type           :map
                                     :snapshot-only? true
                                     :description    "Model configurations (map of id -> model config)"
                                     :key-spec       {:type :string}
                                     :value-spec     {:name   :model
                                                      :type   :map
                                                      :schema {:id       {:type :id
                                                                          :description "Model alias; must match filename when present"}
                                                               :model    {:type        :string
                                                                          :description "Provider-specific model name or id"
                                                                          :validations [:present?]
                                                                          :required?   true}
                                                               :provider {:type        :id
                                                                          :description "Provider alias"
                                                                          :validations [:present?
                                                                                        [:registered-in? :marigold.server/provider [:providers]]]
                                                                          :required?   true}}}}}
    :comms     {:schema {:name        "comms table"
                         :type        :map
                         :description "Communication channel configurations (map of name -> comm config)"
                         :key-spec    {:type :id}
                         :value-spec  {:name           :comm
                                       :type           :map
                                       :factory        'isaac.comm.factory/create!
                                       :dynamic-schema {:berth :marigold.server/comm :path [:extra-schema]}
                                       :schema         {:type {:type        :id
                                                               :options-from :comms
                                                               :description "Manifest comm kind to instantiate"
                                                               :validations [[:registered-in? :marigold.server/comm [:comms]]]}
                                                        :crew {:type        :id
                                                               :description "Crew id this comm routes into"
                                                               :validations [:crew-exists?]}}}}}}})

(def ^:private baseline-config-test-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :marigold.server  {:coord {} :manifest baseline-server-manifest :path nil}})

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

(defn with-manifest
  "Bind foundation + marigold.server schema manifests for config schema/CLI specs."
  []
  (speclj/around [example]
    (binding [module-loader/*foundation-index-override* baseline-config-test-index]
      (schema-compose/clear-cache!)
      (try
        (example)
        (finally
          (schema-compose/clear-cache!))))))

(defn aboard
  "Mem-fs scene with marigold.server schema contributions for mutate specs."
  []
  (speclj/around [example]
    (let [mem (fs/mem-fs)]
      (nexus/-with-nested-nexus {:fs mem}
        (binding [module-loader/*foundation-index-override* baseline-config-test-index]
          (reset! c3env/-overrides {})
          (loader/clear-env-overrides!)
          (schema-compose/clear-cache!)
          (example))))))