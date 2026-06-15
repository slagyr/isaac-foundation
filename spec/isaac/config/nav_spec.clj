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
      (let [result (sut/path->spec (root) "station.primary")]
        (should (:ok? result))
        (should= :id (:type (:spec result)))))

    (it "returns ok with spec for a dynamic entity path"
      (let [result (sut/path->spec (root) "relay.r1.channel")]
        (should (:ok? result))
        (should= :id (:type (:spec result)))))

    (it "returns ok for an int path"
      (let [result (sut/path->spec (root) "relay.r1.gain")]
        (should (:ok? result))
        (should= :int (:type (:spec result)))))

    (it "returns ok with member for a set-typed terminal"
      (let [result (sut/path->spec (root) "relay.r1.flags.wip")]
        (should (:ok? result))
        (should= :wip (:member result))
        (should (:set-type? (:spec result)))))

    (it "returns ok with namespaced member for a set-typed terminal"
      (let [result (sut/path->spec (root) "relay.r1.flags.role/worker")]
        (should (:ok? result))
        (should= :role/worker (:member result))))

    (it "returns error with failing segment for unknown leaf"
      (let [result (sut/path->spec (root) "relay.r1.bogus")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result))
        (should (str/includes? (:error result) "bogus"))))

    (it "returns error with failing segment for unknown root key"
      (let [result (sut/path->spec (root) "bogus.key")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result))))

    (it "returns ok for a nested path"
      (let [result (sut/path->spec (root) "relay.r1.limits.ceiling")]
        (should (:ok? result)))))

  (describe "set-value"

    (it "sets a scalar value at a known path"
      (let [result (sut/set-value (root) {} "station.primary" "alpha")]
        (should (:ok? result))
        (should= "alpha" (get-in (:config result) [:station :primary]))))

    (it "returns error for unknown path"
      (let [result (sut/set-value (root) {} "relay.r1.bogus" "x")]
        (should-not (:ok? result))
        (should= "bogus" (:segment result))))

    (it "overwrites existing scalar value"
      (let [base   {:station {:primary "old"}}
            result (sut/set-value (root) base "station.primary" "new")]
        (should (:ok? result))
        (should= "new" (get-in (:config result) [:station :primary]))))

    (it "adds a member to a set-typed terminal"
      (let [base   {:relay {:r1 {:flags #{:role/worker}}}}
            result (sut/set-value (root) base "relay.r1.flags.wip" nil)]
        (should (:ok? result))
        (should= #{:role/worker :wip} (get-in (:config result) [:relay :r1 :flags]))))

    (it "is idempotent when adding a set member already present"
      (let [base   {:relay {:r1 {:flags #{:role/worker}}}}
            result (sut/set-value (root) base "relay.r1.flags.role/worker" nil)]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:relay :r1 :flags]))))

    (it "initializes set when adding first member"
      (let [result (sut/set-value (root) {:relay {:r1 {}}} "relay.r1.flags.wip" nil)]
        (should (:ok? result))
        (should= #{:wip} (get-in (:config result) [:relay :r1 :flags])))))

  (describe "unset-value"

    (it "removes a scalar value at a known path"
      (let [base   {:station {:primary "alpha" :backup "beta"}}
            result (sut/unset-value (root) base "station.primary")]
        (should (:ok? result))
        (should-be-nil (get-in (:config result) [:station :primary]))
        (should= "beta" (get-in (:config result) [:station :backup]))))

    (it "is idempotent when scalar value is already absent"
      (let [result (sut/unset-value (root) {} "station.primary")]
        (should (:ok? result))))

    (it "removes a member from a set-typed terminal"
      (let [base   {:relay {:r1 {:flags #{:role/worker :wip}}}}
            result (sut/unset-value (root) base "relay.r1.flags.wip")]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:relay :r1 :flags]))))

    (it "is idempotent when removing a set member not present"
      (let [base   {:relay {:r1 {:flags #{:role/worker}}}}
            result (sut/unset-value (root) base "relay.r1.flags.wip")]
        (should (:ok? result))
        (should= #{:role/worker} (get-in (:config result) [:relay :r1 :flags]))))))