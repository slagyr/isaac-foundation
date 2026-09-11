(ns isaac.component.runtime-steps
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.component.fixture.alpha]
    [isaac.component.fixture.bravo]
    [isaac.component.fixture.events :as fixture-events]
    [isaac.component.fixture.widget]
    [isaac.component.registry :as component-registry]
    [isaac.component.runtime :as component-runtime]
    [isaac.config.api :as config-api]
    [isaac.foundation.root-steps :as froot]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.runner :as runner]))

(helper! isaac.component.runtime-steps)

(def ^:private fixture-modules
  {:isaac.component.widget
   {:coord {}
    :manifest {:id :isaac.component.widget
               :factory 'isaac.module.protocol/module
               :isaac/component {:widget {:namespace 'isaac.component.fixture.widget}}}
    :path nil}
   :isaac.component.bravo
   {:coord {}
    :manifest {:id :isaac.component.bravo
               :factory 'isaac.module.protocol/module
               :isaac/component {:bravo {:namespace 'isaac.component.fixture.bravo}}}
    :path nil}
   :isaac.component.alpha
   {:coord {}
    :manifest {:id :isaac.component.alpha
               :factory 'isaac.module.protocol/module
               :deps {:isaac.component.bravo {}}
               :isaac/component {:alpha {:namespace 'isaac.component.fixture.alpha}}}
    :path nil}})

(defn reset-component-state!
  ([]
   (reset-component-state! nil))
  ([_root]
   (fixture-events/clear!)
   (reset! component-registry/*registry* (component-registry/fresh-registry))
   (component-runtime/reset-state!)))

(froot/register-root-setup-hook! reset-component-state!)

(g/before-scenario #(reset-component-state!))

(defn- inject-modules! [module-ids]
  (g/update! :server-config
             #(assoc (or % {}) :inject-module-index
                     (select-keys fixture-modules module-ids))))

(defn widget-test-component-is-registered []
  (inject-modules! [:isaac.component.widget]))

(defn topo-test-components-are-registered []
  (inject-modules! [:isaac.component.bravo :isaac.component.alpha]))

(defn component-start-order-is [expected]
  (let [started (->> (fixture-events/events)
                     (filter #(= :start (second %)))
                     (map first)
                     vec)]
    (g/should= (mapv keyword (str/split expected #"\s*,\s*")) started)))

(defn component-stop-order-is [expected]
  (let [stopped (->> (fixture-events/events)
                     (filter #(= :stop (second %)))
                     (map first)
                     vec)]
    (g/should= (mapv keyword (str/split expected #"\s*,\s*")) stopped)))

(defn isaac-runner-is-stopped []
  (runner/stop!)
  (g/dissoc! :server-port))

(defgiven "the widget test component module is registered" isaac.component.runtime-steps/widget-test-component-is-registered
  "Injects the widget fixture module into :module-index (implementations
   are on the spec-support classpath).")

(defgiven "the alpha and bravo test component modules are registered" isaac.component.runtime-steps/topo-test-components-are-registered
  "Injects fixture modules with :deps so bravo precedes alpha.")

(defthen "the component start order is {expected:string}" isaac.component.runtime-steps/component-start-order-is)

(defthen "the component stop order is {expected:string}" isaac.component.runtime-steps/component-stop-order-is)

(defn isaac-runner-is-started []
  (let [root (g/get :root)
        fs*  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))
        cfg  (or (:config (config-api/load-resolved {:root root :fs fs*})) {})
        injected (:inject-module-index (g/get :server-config))]
    (runner/start! {:root root
                    :fs fs*
                    :config cfg
                    :module-index (merge {:isaac.foundation {:coord {} :manifest {} :path nil}}
                                         injected)})))

(defwhen "the Isaac runner is started" isaac.component.runtime-steps/isaac-runner-is-started)

(defwhen "the Isaac runner is stopped" isaac.component.runtime-steps/isaac-runner-is-stopped)