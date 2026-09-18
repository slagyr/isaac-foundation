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

    (it "contains process-owned config and retired server settings"
      (let [fields (sut/schema-fields sut/base-root)]
        (should= #{:hot-reload :module-registry :modules :server}
                 (set (keys fields)))
        (should= [[:retired? "use :http :host"]]
                 (get-in fields [:server :schema :host :validations]))
        (should= [[:retired? "use :bridge :suspend-timeout-ms"]]
                 (get-in fields [:server :schema :suspend-timeout-ms :validations]))))))