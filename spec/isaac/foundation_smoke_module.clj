(ns isaac.foundation-smoke-module
  "Fixture module-author namespace for foundation facade smoke tests.
   Requires only isaac.foundation plus documented Tier-1 carve-outs."
  (:require
    [isaac.foundation :as foundation]
    [isaac.cli.api :as cli-api]
    [isaac.fs :as fs]
    [isaac.logger :as logger]
    [isaac.reconfigurable :as reconfigurable]))

(defn create-module []
  (foundation/create-module))

(defrecord SmokeRelay [state*]
  reconfigurable/Reconfigurable
  (on-startup! [_ slice]
    (swap! state* assoc :startup slice)
    (logger/info :smoke/startup slice))
  (on-config-change! [_ old-slice new-slice]
    (swap! state* assoc :change [old-slice new-slice])))

(defn relay []
  (->SmokeRelay (atom {})))

(defn smoke-ready?
  "Module-style entry: facade for module/nexus, direct imports for fs/logger."
  []
  (and (foundation/module? (create-module))
       (if-let [fs* (foundation/nexus-get :fs)]
         (satisfies? fs/Fs fs*)
         true)))

(defmethod cli-api/run :smoke [_id _opts]
  (if (smoke-ready?)
    0
    1))