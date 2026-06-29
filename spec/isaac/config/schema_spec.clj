(ns isaac.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.marigold :as config-marigold]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.marigold :as marigold]
    [speclj.core :refer :all]))

(defn- manifest []
  (-> "src/isaac-manifest.edn" slurp edn/read-string))

(defn- schema-fragment [key]
  (get-in (manifest) [:isaac.config/schema key :schema]))

(describe "config schema"

  (it "tz conforms an IANA timezone name"
    (should= {:tz "America/Chicago"}
             (schema/conform {:tz (schema-fragment :tz)} {:tz "America/Chicago"})))

  (it "prefer-entity-files conforms a boolean"
    (should= {:prefer-entity-files true}
             (schema/conform {:prefer-entity-files (schema-fragment :prefer-entity-files)}
                             {:prefer-entity-files true})))

  (it "documents prefer-entity-files in the schema description"
    (should (str/includes? (:description (schema-fragment :prefer-entity-files))
                           "per-entity files")))

  (it "logging conforms max-bytes and max-days"
    (should= {:logging {:max-bytes 2000 :max-days 30}}
             (schema/conform {:logging (schema-fragment :logging)}
                             {:logging {:max-bytes 2000 :max-days 30}})))

  (it "registers prefer-entity-files in the composed root schema"
    (let [index  {:isaac.foundation {:coord {} :manifest (manifest) :path nil}}
          fields (schema-base/schema-fields (schema-compose/effective-root-schema index))]
      (should (contains? fields :prefer-entity-files))
      (should= :boolean (get-in fields [:prefer-entity-files :type]))))

  (describe "prefer-entity-files load validation"
    (config-marigold/aboard)

    (it "does not warn unknown key for prefer-entity-files at the root"
      (config-marigold/write-config! (assoc config-marigold/baseline-config
                                            :prefer-entity-files true))
      (let [result (loader/load-config-result {:root marigold/root})]
        (should-not (some #(and (= "prefer-entity-files" (:key %))
                                (= "unknown key" (:value %)))
                          (:warnings result)))))))
