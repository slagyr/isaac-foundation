(ns isaac.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(defn- tz-schema []
  (-> "src/isaac-manifest.edn"
      slurp
      edn/read-string
      (get-in [:isaac.config/schema :tz :schema])))

(describe "config schema"

  (it "tz conforms an IANA timezone name"
    (should= {:tz "America/Chicago"}
             (schema/conform {:tz (tz-schema)} {:tz "America/Chicago"}))))
