(ns isaac.component.factory
  "The :isaac/component berth's factory. Component modules contribute
   inert data only ({:namespace …}); instantiation happens at process boot
   via the `create` multimethod, keyed by component id."
  (:require
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.schema.registered-in :as registered-in]))

(defmulti create
  "Instantiate the Component for a manifest contribution id. Component
   modules implement this for each id they contribute."
  (fn [component-id _ctx] component-id))

(defn- contribution [module-index component-key]
  (some (fn [[module-id entry]]
          (when-let [contribution (get-in entry [:manifest :isaac/component component-key])]
            [module-id contribution]))
        module-index))

(defn- ensure-impl!
  [module-index component-key]
  (when-let [[module-id entry] (contribution module-index component-key)]
    (let [activated (try
                      (module-loader/activate! module-id module-index)
                      (catch clojure.lang.ExceptionInfo _ :failed))
          required  (when-not (get-method create component-key)
                      (when-let [ns-sym (:namespace entry)]
                        (try
                          (require ns-sym)
                          nil
                          (catch Throwable t
                            (log/error :module/activation-failed
                                       :error  (.getMessage t)
                                       :component (name component-key)
                                       :module (name module-id))
                            :failed))))]
      (when (or (= :failed activated) (= :failed required))
        :failed))))

(defn create!
  "Resolve and invoke `create` for `component-id`, loading the contributing
   module on first use. Returns nil when no implementation can be found."
  [component-id ctx]
  (let [failed? (= :failed (ensure-impl! registered-in/*module-index* component-id))]
    (if-let [instance (when (get-method create component-id)
                        (create component-id ctx))]
      instance
      (do (when-not failed?
            (log/error :module/activation-failed
                       :error (str "no implementation creates component " (pr-str component-id))
                       :component (name component-id)))
          nil))))