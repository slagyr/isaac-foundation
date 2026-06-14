(ns isaac.foundation
  "Tier-1 public API for Isaac modules. Re-exports fn/protocol/var surfaces only.
   CLI multimethods, fs, logger, and config.paths are direct imports — see FOUNDATION.md."
  (:refer-clojure :exclude [get get-in])
  (:require
    [isaac.module.protocol :as module-protocol]
    [isaac.config.api :as config]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.nexus :as nexus]))

;; module.protocol
(def Module module-protocol/Module)

(defn module
  ([] (module-protocol/module))
  ([opts] (module-protocol/module opts)))

(defn module?
  [value]
  (module-protocol/module? value))

;; config.api (read path — no default-root; use isaac.config.root for bootstrap)
(def load-config! config/load-config!)
(def load-config-result config/load-config-result)
(def snapshot config/snapshot)
(def root config/root)
(def normalize-config config/normalize-config)
(def env config/env)

;; reconfigurable (protocol home — not config.runtime)
(def Reconfigurable reconfigurable/Reconfigurable)
(def on-startup! reconfigurable/on-startup!)
(def on-config-change! reconfigurable/on-config-change!)

;; nexus (factory publish/read)
(def get nexus/get)
(def get-in nexus/get-in)
(def register! nexus/register!)

(defn create-module []
  (module-protocol/module))