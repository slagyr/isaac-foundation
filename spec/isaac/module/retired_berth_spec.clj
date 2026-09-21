(ns isaac.module.retired-berth-spec
  (:require
    [isaac.module.berths :as sut]
    [speclj.core :refer :all]))

(describe "retired component berth"

  (it "directs :isaac.http/service contributors to :isaac/component"
    (let [errors (sut/validate-contributions!
                   {:isaac.discord
                    {:manifest {:isaac.http/service
                                {:discord {:namespace 'isaac.comm.discord.service}}}}})]
      (should= [{:key   "module-index[\"isaac.discord\"][:isaac.http/service]"
                 :value ":isaac.http/service is retired; use :isaac/component"}]
               errors)))

  (it "directs :isaac.http/comm contributors to :isaac.agent/comm"
    (let [errors (sut/validate-contributions!
                   {:isaac.comm.gchat
                    {:manifest {:isaac.http/comm
                                {:gchat {:namespace 'isaac.comm.gchat}}}}})]
      (should= [{:key   "module-index[\"isaac.comm.gchat\"][:isaac.http/comm]"
                 :value ":isaac.http/comm is retired; use :isaac.agent/comm"}]
               errors)))

  (it "directs :isaac.server/comm contributors to :isaac.agent/comm"
    (let [errors (sut/validate-contributions!
                   {:isaac.comm.imessage
                    {:manifest {:isaac.server/comm
                                {:imessage {:namespace 'isaac.comm.imessage}}}}})]
      (should= [{:key   "module-index[\"isaac.comm.imessage\"][:isaac.server/comm]"
                 :value ":isaac.server/comm is retired; use :isaac.agent/comm"}]
               errors)))

  )
