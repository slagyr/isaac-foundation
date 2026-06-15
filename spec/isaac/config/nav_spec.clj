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

    (it "returns ok for an effort path (int type)"
      (let [result (sut/path->spec (root) "crew.joe.effort")]
        (should (:ok? result))
        (should= :int (:type (:spec result)))))

    (it "returns ok with member for a set-typed terminal"
      (let [result (sut/path->spec (root) "crew.joe.tags.wip")]
        (should (:ok? result))
        (should= :wip (:member result))
        (should (:set-type? (:spec result)))))

    (it "returns ok with namespaced member for a set-typed terminal"
      (let [result (sut/path->spec (root) "crew.joe.tags.role/worker")]
        (should (:ok? result))
        (should= :role/worker (:member result))))

    (it "returns error with failing segment for unknown leaf"
      (let [result (sut/path->spec (root) "crew.joe.bogus")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result))
        (should (str/includes? (:error result) "bogus"))))

    (it "returns error with failing segment for unknown root key"
      (let [result (sut/path->spec (root) "bogus.key")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result))))

    (it "returns ok for a nested compaction path"
      (let [result (sut/path->spec (root) "crew.joe.compaction.threshold")]
        (should (:ok? result)))))

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
        (should= "new" (get-in (:config result) [:defaults :crew]))))

    (it "adds a member to a set-typed terminal"
      (let [base   {:crew {:joe {:tags #{:role/worker}}}}
            result (sut/set-value (root) base "crew.joe.tags.wip" nil)]
        (should (:ok? result))
        (should= #{:role/worker :wip} (get-in (:config result) [:crew :joe :tags]))))

    (it "is idempotent when adding a set member already present"
      (let [base   {:crew {:joe {:tags #{:role/worker}}}}
            result (sut/set-value (root) base "crew.joe.tags.role/worker" nil)]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:crew :joe :tags]))))

    (it "initializes set when adding first member"
      (let [result (sut/set-value (root) {:crew {:joe {}}} "crew.joe.tags.wip" nil)]
        (should (:ok? result))
        (should= #{:wip} (get-in (:config result) [:crew :joe :tags])))))

  (describe "unset-value"

    (it "removes a scalar value at a known path"
      (let [base   {:defaults {:crew "marvin" :model "grover"}}
            result (sut/unset-value (root) base "defaults.crew")]
        (should (:ok? result))
        (should-be-nil (get-in (:config result) [:defaults :crew]))
        (should= "grover" (get-in (:config result) [:defaults :model]))))

    (it "is idempotent when scalar value is already absent"
      (let [result (sut/unset-value (root) {} "defaults.crew")]
        (should (:ok? result))))

    (it "removes a member from a set-typed terminal"
      (let [base   {:crew {:joe {:tags #{:role/worker :wip}}}}
            result (sut/unset-value (root) base "crew.joe.tags.wip")]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:crew :joe :tags]))))

    (it "is idempotent when removing a set member not present"
      (let [base   {:crew {:joe {:tags #{:role/worker}}}}
            result (sut/unset-value (root) base "crew.joe.tags.wip")]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:crew :joe :tags]))))))