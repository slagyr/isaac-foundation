(ns isaac.config.warnings-spec
  (:require
    [isaac.config.warnings :as sut]
    [speclj.core :refer :all]))

(describe "isaac.config.warnings"
  (it "collect-unknown-key-warnings flags keys absent from schema fields"
    (let [schema {:type :map :schema {:known {:type :string}}}
          ws (sut/collect-unknown-key-warnings [] "crew" "main" {:known "a" :extra 1} schema)]
      (should= 1 (count ws))
      (should= "crew.main.extra" (:key (first ws)))
      (should= "unknown key" (:value (first ws)))))

  (it "ignores non-map entities"
    (should= [] (sut/collect-unknown-key-warnings [] "crew" "main" ["not-a-map"] {:schema {}}))))
