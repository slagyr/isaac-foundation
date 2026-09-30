(ns marigold.bridge
  "Fixture module shared by manifest-only and config-berth tests."
  (:require
    [isaac.foundation.module.protocol :as module]
    [marigold.bridge.comm]))

(defn create-module []
  (module/module))
