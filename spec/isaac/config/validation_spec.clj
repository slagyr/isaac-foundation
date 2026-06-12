(ns isaac.config.validation-spec
  (:require
    [isaac.config.validation :as sut]
    [speclj.core :refer :all]))

(describe "config validation"

  (describe "validate-manifest-config"

    (it "reports unknown keys as warnings"
      (should= [{:key "tools.foo.unknown" :value "unknown key"}]
               (:warnings (sut/validate-manifest-config "tools.foo" {:known "x" :unknown "y"}
                                                        {:known {:type :string}}))))))