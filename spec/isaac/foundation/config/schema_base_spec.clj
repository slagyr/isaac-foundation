(ns isaac.foundation.config.schema-base-spec
  (:require
    [c3kit.apron.schema :as cs]
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
        (should= {:north {:berth 3} :south {:berth 7}} overlaid))))

  (describe "conform-absent-section"

    ;; isaac-zmub: a config-berth-claimed slice (or a root-level schema
    ;; fragment) that is wholly absent from raw config still conforms as {}
    ;; so nested :default values fill — the fix conform-berth-slices applies
    ;; when `(get-in config path)` is nil, same shape apron would compose for
    ;; a berth like isaac-http's :http.
    (let [section-spec {:type   :map
                        :schema {:power  {:type :int :default 42}
                                 :keeper {:type :string :required true}}}]

      (it "fills nested defaults for a wholly absent section"
        (should= {:power 42} (sut/conform-absent-section section-spec)))

      (it "drops a required field's error instead of reporting or leaking it"
        (let [filled (sut/conform-absent-section section-spec)]
          (should-not-contain :keeper filled)
          (should-be false (boolean (some cs/field-error? (vals filled))))))

      (it "returns nil when the section contributes no defaults"
        (should-be-nil (sut/conform-absent-section {:type :map :schema {:name {:type :string}}})))

      (it "returns nil for a non-map section (nothing to recurse into)"
        (should-be-nil (sut/conform-absent-section {:type :int :default 1}))))))