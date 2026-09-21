(ns isaac.config.comm-kinds-spec
  (:require
    [isaac.config.comm-kinds :as sut]
    [speclj.core :refer :all]))

(defn- entry [kinds]
  {:manifest {:isaac.agent/comm kinds}})

(describe "comm-kinds"

  (it "enumerates kinds from :isaac.agent/comm contributions"
    (should= ["discord" "gchat" "imessage"]
             (sut/comm-kinds
               {:isaac.comm.discord  (entry {:discord  {:namespace 'isaac.comm.discord}})
                :isaac.comm.gchat    (entry {:gchat    {:namespace 'isaac.comm.gchat}})
                :isaac.comm.imessage (entry {:imessage {:namespace 'isaac.comm.imessage}})})))

  (it "ignores contributions under retired comm berth keys"
    (should= []
             (sut/comm-kinds
               {:isaac.comm.discord  {:manifest {:isaac.http/comm   {:discord  {}}}}
                :isaac.comm.imessage {:manifest {:isaac.server/comm {:imessage {}}}}})))

  (it "filters out non-configurable kinds"
    (should= ["discord"]
             (sut/comm-kinds
               {:m1 (entry {:discord  {:namespace 'x}
                            :internal {:namespace 'y :configurable? false}})})))

  (it "sorts and de-duplicates across modules"
    (should= ["discord" "telly"]
             (sut/comm-kinds
               {:m1 (entry {:telly   {:namespace 't}
                            :discord {:namespace 'd}})
                :m2 (entry {:discord {:namespace 'd2}})}))))
