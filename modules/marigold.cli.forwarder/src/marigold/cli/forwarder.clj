(ns marigold.cli.forwarder
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foundation.fs :as fs]))

(defn create-forwarder []
  (fn [{:keys [argv root]}]
    (println (str "forwarded: " (str/join " " argv)))
    (let [file (str root "/forwarder.edn")
          response (if (fs/exists? (fs/instance) file) (edn/read-string (fs/slurp (fs/instance) file)) {:exit 0})]
      (if (= :no-server (:result response)) {:no-server true} response))))
