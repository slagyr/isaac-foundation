(ns isaac.cli.greeter)

(def subcommands
  [{:name "wave" :desc "Wave hello"}
   {:name "bow"  :desc "Take a bow"}])

(defn run-fn [{:keys [_raw-args]}]
  (println "Hello from the greeter module!")
  0)

(defn make-command []
  {:name    "greet"
   :usage   "greet"
   :run-fn  run-fn})
