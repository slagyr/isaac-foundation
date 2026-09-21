(ns isaac.comm.factory-spec
  (:require
    [isaac.comm.factory :as sut]
    [speclj.core :refer :all]))

(describe "manifest comm contributions"

  (it "reads a contribution from :isaac.agent/comm"
    (should= {:namespace 'isaac.comm.discord}
             (#'sut/manifest-comm-contribution
               {:manifest {:isaac.agent/comm {:discord {:namespace 'isaac.comm.discord}}}}
               :discord)))

  (it "does not read contributions from retired berth keys"
    (should-be-nil
      (#'sut/manifest-comm-contribution
        {:manifest {:isaac.http/comm {:discord {:namespace 'isaac.comm.discord}}}}
        :discord))
    (should-be-nil
      (#'sut/manifest-comm-contribution
        {:manifest {:isaac.server/comm {:imessage {:namespace 'isaac.comm.imessage}}}}
        :imessage))))
