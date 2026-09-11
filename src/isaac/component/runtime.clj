(ns isaac.component.runtime
  "Process component lifecycle. Only isaac.runner invokes start-all! /
   stop-all! — CLI and other entry points gather component contributions but
   never start them."
  (:require
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.schema.registered-in :as registered-in]
    [isaac.component.factory :as factory]
    [isaac.component.protocol :as protocol]
    [isaac.component.registry :as registry]))

(defonce ^:private started* (atom []))

(declare stop-all!)

(defn started-components
  "Started entries in boot order — each `{:id :module-id :instance}`."
  []
  @started*)

(defn- component-entries [module-index]
  (mapcat (fn [[module-id entry]]
            (when-let [components (get-in entry [:manifest :isaac/component])]
              (map (fn [[component-id contribution]]
                     {:module-id    module-id
                      :component-id component-id
                      :contribution contribution})
                   components)))
          module-index))

(defn- ranked-entries [module-index]
  (let [order (zipmap (module-loader/topological-order module-index) (range))]
    (sort-by (fn [{:keys [module-id]}] (get order module-id)) (component-entries module-index))))

(defn start-all!
  "Instantiate and start every :isaac/component contribution in module
   topological order. `ctx` is process-runner context available to each
   component factory. Returns :started."
  ([module-index] (start-all! module-index {}))
  ([module-index ctx]
   (binding [registered-in/*module-index* module-index]
     (let [entries (ranked-entries module-index)
           started (atom [])]
       (reset! started* [])
       (try
         (doseq [{:keys [module-id component-id contribution]} entries]
           (when-let [instance (factory/create! component-id (merge ctx
                                                                    {:module-id    module-id
                                                                     :component-id component-id
                                                                     :contribution contribution}))]
             (protocol/run-start! instance)
             (registry/register-instance! component-id instance)
             (log/info :component/started :component (name component-id) :module (name module-id))
             (swap! started conj {:id component-id :module-id module-id :instance instance})))
         (reset! started* @started)
         :started
         (catch Throwable t
           (reset! started* @started)
           (stop-all!)
           (throw t)))))))

(defn reset-state!
  "Clear started-component bookkeeping. For tests only."
  []
  (reset! started* []))

(defn stop-all!
  "Stop started components in reverse topological order."
  []
  (doseq [{:keys [id instance module-id]} (reverse @started*)]
    (try
      (protocol/run-stop! instance)
      (registry/deregister-instance! id)
      (log/info :component/stopped :component (name id) :module (name module-id))
      (catch Throwable t
        (log/error :component/stop-failed
                   :component (name id)
                   :module  (name module-id)
                   :error   (.getMessage t)))))
  (reset! started* []))