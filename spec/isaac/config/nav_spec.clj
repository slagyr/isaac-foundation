(ns isaac.config.nav-spec
  (:require
    [clojure.string :as str]
    [isaac.config.marigold :as config-marigold]
    [isaac.config.nav :as sut]
    [speclj.core :refer :all]))

(defn- root []
  (config-marigold/test-root-schema))

(describe "config nav"

  (config-marigold/with-manifest)

  (describe "path->spec"

    (it "returns ok with spec for a known scalar path"
      (let [result (sut/path->spec (root) "defaults.crew")]
        (should (:ok? result))
        (should= :id (:type (:spec result)))))

    (it "returns ok with spec for a crew entity path"
      (let [result (sut/path->spec (root) "crew.joe.model")]
        (should (:ok? result))
        (should= :id (:type (:spec result)))))

    (it "returns error with failing segment for unknown leaf"
      (let [result (sut/path->spec (root) "crew.joe.bogus")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result))
        (should (str/includes? (:error result) "bogus"))))

    (it "returns error with failing segment for unknown root key"
      (let [result (sut/path->spec (root) "bogus.key")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result)))))

  (describe "set-value"

    (it "sets a scalar value at a known path"
      (let [result (sut/set-value (root) {} "defaults.crew" "marvin")]
        (should (:ok? result))
        (should= "marvin" (get-in (:config result) [:defaults :crew]))))

    (it "returns error for unknown path"
      (let [result (sut/set-value (root) {} "crew.joe.bogus" "x")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result))))

    (it "overwrites existing scalar value"
      (let [base   {:defaults {:crew "old"}}
            result (sut/set-value (root) base "defaults.crew" "new")]
        (should (:ok? result))
        (should= "new" (get-in (:config result) [:defaults :crew])))))

  (describe "unset-value"

    (it "removes a scalar value at a known path"
      (let [base   {:defaults {:crew "marvin" :model "grover"}}
            result (sut/unset-value (root) base "defaults.crew")]
        (should (:ok? result))
        (should-be-nil (get-in (:config result) [:defaults :crew]))
        (should= "grover" (get-in (:config result) [:defaults :model]))))

    (it "is idempotent when scalar value is already absent"
      (let [result (sut/unset-value (root) {} "defaults.crew")]
        (should (:ok? result))))))