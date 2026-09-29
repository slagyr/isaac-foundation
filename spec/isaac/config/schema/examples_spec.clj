(ns isaac.config.schema.examples-spec
  (:require [isaac.config.schema.examples :as sut]
            [speclj.core :refer [describe it should=]]))

(describe "schema.examples/command-lines"

  (it "returns just the bare command and --tree when there are no matching fields"
    (should= ["isaac config schema" "isaac config schema --tree"]
             (sut/command-lines {:schema {:server {:type :map :schema {}}}})))

  (it "adds the first leaf field, sorted by name"
    (should= ["isaac config schema"
              "isaac config schema hot-reload"
              "isaac config schema --tree"]
             (sut/command-lines {:schema {:hot-reload {:type :boolean}
                                          :module-registry {:type :string}}})))

  (it "adds the first dynamic-key table plus its .value, sorted by name"
    (should= ["isaac config schema"
              "isaac config schema modules"
              "isaac config schema modules.value"
              "isaac config schema --tree"]
             (sut/command-lines {:schema {:modules {:type       :map
                                                    :key-spec   {:type :keyword}
                                                    :value-spec {:type :map}}
                                          :server   {:type :map :schema {}}}})))

  (it "orders leaf line before table lines regardless of alphabetical order"
    (should= ["isaac config schema"
              "isaac config schema hot-reload"
              "isaac config schema modules"
              "isaac config schema modules.value"
              "isaac config schema --tree"]
             (sut/command-lines {:schema {:hot-reload {:type :boolean}
                                          :modules    {:type       :map
                                                       :key-spec   {:type :keyword}
                                                       :value-spec {:type :map}}}})))

  (it "picks the first-by-name leaf and first-by-name table independently"
    (should= ["isaac config schema"
              "isaac config schema hot-reload"
              "isaac config schema comms"
              "isaac config schema comms.value"
              "isaac config schema --tree"]
             (sut/command-lines {:schema {:hot-reload {:type :boolean}
                                          :zeta       {:type :string}
                                          :comms      {:type       :map
                                                       :key-spec   {:type :keyword}
                                                       :value-spec {:type :map}}
                                          :crew       {:type       :map
                                                       :key-spec   {:type :keyword}
                                                       :value-spec {:type :map}}}})))

  (it "does not treat a plain object map (schema, no key/value-spec) as a leaf or a table"
    (should= ["isaac config schema" "isaac config schema --tree"]
             (sut/command-lines {:schema {:server {:type :map :schema {:port {:type :int}}}}})))

  (it "ignores a seq or one-of field as a leaf candidate"
    (should= ["isaac config schema"
              "isaac config schema modules"
              "isaac config schema modules.value"
              "isaac config schema --tree"]
             (sut/command-lines {:schema {:aliases {:type :seq :spec {:type :string}}
                                          :flavor  {:type :one-of :specs [{:type :string} {:type :int}]}
                                          :modules {:type       :map
                                                    :key-spec   {:type :keyword}
                                                    :value-spec {:type :map}}}}))))
