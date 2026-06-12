(ns marigold.bridge
  "Fixture module shared by manifest-only and config-berth tests."
  (:require
    [isaac.module :as module]
    [marigold.bridge.comm]))

(defn create-module []
  (module/module))
