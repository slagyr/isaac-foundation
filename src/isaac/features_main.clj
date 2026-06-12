(ns isaac.features-main
  (:require
    [clojure.java.shell :as shell]))

(defn command-args [args]
  (into ["bb" "features"] args))

(defn -main [& args]
  (let [{:keys [out err exit]} (apply shell/sh (command-args args))]
    (when (seq out)
      (print out))
    (when (seq err)
      (binding [*out* *err*]
        (print err)))
    (when (pos? exit)
      (System/exit exit))
    exit))
