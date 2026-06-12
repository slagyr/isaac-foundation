(ns isaac.schema.meta-spec
  (:require
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.meta :as sut]
    [speclj.core :refer :all]))

(describe "schema meta"

  (it "conforms a minimal string spec"
    (should= {:type :string}
             (lexicon/conform! sut/spec-schema {:type :string})))

  (it "conforms a recursive map spec"
    (should= {:type   :map
              :schema {:name {:type :string}}}
             (lexicon/conform! sut/spec-schema {:type   :map
                                                :schema {:name {:type :string}}})))

  (it "conforms an open map spec with recursive key and value specs"
    (should= {:type      :map
              :key-spec  {:type :keyword}
              :value-spec {:type :string}}
             (lexicon/conform! sut/spec-schema {:type      :map
                                                :key-spec  {:type :keyword}
                                                :value-spec {:type :string}})))

  (it "conforms a recursive seq spec"
    (should= {:type :seq
              :spec {:type :keyword}}
             (lexicon/conform! sut/spec-schema {:type :seq
                                                :spec {:type :keyword}})))

  (it "conforms the isaac lexicon's symbol type"
    (should= {:type :symbol}
             (lexicon/conform! sut/spec-schema {:type :symbol})))

  (it "conforms a validations shortcut chain"
    (should= {:type :string :validations [:present? [:> 5]]}
             (lexicon/conform! sut/spec-schema {:type :string
                                                :validations [:present? [:> 5]]})))

  (it "fails clearly for an unknown type"
    (let [error (should-throw clojure.lang.ExceptionInfo
                              (lexicon/conform! sut/spec-schema {:type :mystery}))]
      (should= "unknown schema type: :mystery" (.getMessage error))))

  (it "fails clearly when :type is missing"
    (let [error (should-throw clojure.lang.ExceptionInfo
                              (lexicon/conform! sut/spec-schema {:message "oops"}))]
      (should= "schema spec is missing required :type" (.getMessage error))))

  (it "fails clearly when a nested :schema value is not itself a valid spec"
    (let [error (should-throw clojure.lang.ExceptionInfo
                              (lexicon/conform! sut/spec-schema {:type   :map
                                                                 :schema {:name {:type :mystery}}}))]
      (should= "invalid schema spec at [:schema :name]: unknown schema type: :mystery" (.getMessage error)))))
