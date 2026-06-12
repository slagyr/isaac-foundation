(ns isaac.config.schema-base-spec
  (:require
    [isaac.config.schema-base :as sut]
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

    (it "contains only :modules"
      (should= #{:modules} (set (keys (sut/schema-fields sut/base-root)))))))