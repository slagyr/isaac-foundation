(ns marigold.bridge.signal
  "Per-entry registration factory for the :marigold.bridge/signal-route
   berth. Foundation calls register-route! once per route entry; the
   factory installs the handler in the nexus keyed by [method path]."
  (:require
    [isaac.nexus :as nexus]))

(defn register-route!
  [{:keys [method path handler]}]
  (nexus/register! [:marigold.bridge/signal-route [method path]] handler))
