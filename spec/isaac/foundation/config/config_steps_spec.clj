(ns isaac.foundation.config.config-steps-spec
  (:require
    [isaac.foundation.config.config-steps]
    [speclj.core :refer :all]))

(def ^:private get-path #'isaac.foundation.config.config-steps/get-path)

(describe "config feature step: get-path"

  (it "walks dot-separated keys (legacy behavior)"
    (should= "leaf" (get-path {:a {:b {:c "leaf"}}} "a.b.c")))

  (it "returns nil for a missing dot-path key"
    (should= nil (get-path {} "a.b")))

  (it "walks vector by index via dot-path"
    (should= "second" (get-path {:items ["first" "second"]} "items.1")))

  (it "walks slash-prefixed path treating dots as literal in each segment"
    (let [data {:module-index {:isaac.comm.pigeon {:manifest {:id :isaac.comm.pigeon}}}}]
      (should= :isaac.comm.pigeon
               (get-path data "/module-index/isaac.comm.pigeon/manifest/id"))))

  (it "returns nil for a missing slash-path segment"
    (should= nil (get-path {} "/module-index/missing")))

  (it "blank cell value (empty path) returns nil"
    (should= nil (get-path {:a 1} ""))))
