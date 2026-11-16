(ns marigold.cli.where
  (:require [isaac.foundation.cli.api :as cli-api]))

(defmethod cli-api/run :where [_ _]
  (println "process")
  0)
