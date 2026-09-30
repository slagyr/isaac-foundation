(ns isaac.foundation.config.schema-base-spec
  (:require
    [isaac.foundation.config.schema-base :as sut]
    [isaac.foundation.schema.lexicon :as lexicon]
    [speclj.core :refer :all]))

(describe "config schema-base"

  (describe "->id"

    (it "coerces keywords and strings"
      (should= "main" (sut/->id :main))
      (should= "main" (sut/->id "main"))
      (should-be-nil (sut/->id nil))))

  (describe "schema-fields"

    (it "returns the inner :schema map"
      (should= {:modules {}} (sut/schema-fields {:schema {:modules {}}}))))

  (describe "strip-validation-annotations"

    (it "removes :validations recursively"
      (should= {:type :string}
               (sut/strip-validation-annotations {:type :string :validations [:present?]}))))

  (describe "base-root"

    (it "contains process-owned config"
      (let [fields (sut/schema-fields sut/base-root)]
        (should= #{:hot-reload :module-registry :modules}
                 (set (keys fields))))))

  (describe "overlay-conformed"

    (it "unifies a raw keyword key with its conformed twin under a :string key-spec, no phantom \":north\" twin (isaac-4eay)"
      (let [table-schema {:type        :map
                          :key-spec    {:type :string}
                          :value-spec  {:type :map :schema {:berth {:type :int}}}}
            raw          {:north {:berth "3"} :south {:berth 7}}
            conformed    (lexicon/conform! table-schema raw)
            overlaid     (sut/overlay-conformed raw conformed)]
        (should= 2 (count overlaid))
        (should= {:north {:berth 3} :south {:berth 7}} overlaid)))))