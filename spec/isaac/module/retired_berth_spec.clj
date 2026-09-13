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

  )
