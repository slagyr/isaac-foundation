;; mutation-tested: 2026-05-06
(ns isaac.config.configurator
  (:require
    [isaac.reconfigurable :as reconfigurable]
    [isaac.config.schema-base :as schema-base]
    [clojure.string :as str]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

;; Protocol home is isaac.reconfigurable; aliased here for server-side callers.
(def Reconfigurable reconfigurable/Reconfigurable)
(def on-load reconfigurable/on-load)
(def on-config-change! reconfigurable/on-config-change!)
(def on-unload reconfigurable/on-unload)

(defn ->name [x]
  (cond
    (keyword? x) (name x)
    :else        (str x)))

(defn- dotted [path]
  (str/join "." (map ->name path)))

(defn slot-impl
  "Resolves the comm type for a slot, normalized by id — conformed
   configs carry string discriminators where injected configs carry
   keywords. Returns nil if slice is nil."
  [slot slice]
  (when slice
    (some-> (or (get slice :type)
                (get slice "type")
                (->name slot))
            schema-base/->id)))

(defn- singleton-impl [registry]
  (or (:impl registry)
      (->name (last (:path registry)))))

(defn- start-instance! [factory host path slice impl]
  (let [instance (factory (assoc host :name (last path)))]
    (on-load instance slice)
    (nexus/register! path instance)
    (log/info :lifecycle/started :path (dotted path) :impl impl)
    instance))

(defn- stop-instance! [instance path old-slice impl]
  (on-unload instance old-slice)
  (nexus/deregister! path)
  (log/info :lifecycle/stopped :path (dotted path) :impl impl))

(defn- change-instance! [instance path old-slice new-slice impl]
  (on-config-change! instance old-slice new-slice)
  (log/info :lifecycle/changed :path (dotted path) :impl impl))

(defn- reconcile-component! [host old-cfg new-cfg registry]
  (let [path      (vec (:path registry))
        old-slice (get-in old-cfg path)
        new-slice (get-in new-cfg path)
        existing  (nexus/get-in path)
        factory   (:factory registry)
        impl      (singleton-impl registry)
        host      (assoc host :registry registry)]
    (cond
      (and (nil? old-slice) (some? new-slice) (nil? existing))
      (start-instance! factory host path new-slice impl)

      (and (some? old-slice) (nil? new-slice) existing)
      (stop-instance! existing path old-slice impl)

      (and (not= old-slice new-slice) existing)
      (change-instance! existing path old-slice new-slice impl)

      (and (not= old-slice new-slice) (some? new-slice) (nil? existing))
      (start-instance! factory host path new-slice impl))))

(defn declared-registries
  "The reconcilable components modules declare through :isaac.config/component.
   Foundation walks what is declared; it does not know hooks, cron or hail by
   name, and a module that is not installed simply is not declared
   (isaac-bbe0)."
  [module-index]
  (vec
    (for [[_module-id entry] module-index
          [_id contribution] (get-in entry [:manifest :isaac.config/component])
          :let [factory (:factory contribution)
                factory-fn (cond
                             (fn? factory) factory
                             (symbol? factory) (try (requiring-resolve factory)
                                                    (catch Throwable e
                                                      (log/warn :config.component/factory-unresolved
                                                                :factory factory
                                                                :error (.getMessage e))
                                                      nil))
                             :else nil)]
          :when factory-fn]
      {:kind    :component
       :path    (vec (:path contribution))
       :impl    (:impl contribution)
       :factory factory-fn})))

(defn reconcile!
  "Reconciles singleton :component registries (hail bands, hooks, cron)
   against config slices — boot (old nil), reload, and shutdown (new
   nil). Slot-tree surfaces (comms) reconcile through the config-berth
   engine (isaac.config.berths/reconcile!) instead."
  [host old-cfg new-cfg registry-or-registries]
  (doseq [registry (if (map? registry-or-registries)
                     [registry-or-registries]
                     registry-or-registries)]
    (when (= :component (:kind registry))
      (reconcile-component! host old-cfg new-cfg registry))))
