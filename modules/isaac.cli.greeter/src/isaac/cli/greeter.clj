(ns isaac.cli.greeter
  (:require
    [isaac.cli.api :as cli-api]))

(def subcommands
  [{:name "wave" :summary "Wave hello"}
   {:name "bow"  :summary "Take a bow"}])

(defmethod cli-api/run :greet [_id {:keys [_raw-args]}]
  (println "Hello from the greeter module!")
  0)

(defmethod cli-api/subcommands :greet [_id]
  subcommands)

(defn make-command []
  {:name   "greet"
   :usage  "greet"
   :run-fn (fn [opts] (cli-api/run :greet opts))})
