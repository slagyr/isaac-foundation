(ns isaac.config.schema-compose-spec
  (:require
    [isaac.config.schema-compose :as sut]
    [speclj.core :refer :all]))

(def crew-table
  {:type :map :key-spec {:type :keyword} :value-spec {:type :map}})

(def cron-table
  {:type :map :key-spec {:type :keyword} :value-spec {:type :map}})

(describe "config schema-compose"

  (describe "descriptors"

    (it "derives entity metadata from :isaac.config/schema contributions"
      (let [index       {:mod.x {:manifest {:isaac.config/schema
                                            {:crew {:fragment     'isaac.config.schema-compose-spec/crew-table
                                                    :entity-dir   "crew"
                                                    :frontmatter? true}
                                             :cron {:fragment   'isaac.config.schema-compose-spec/cron-table
                                                    :entity-dir "cron"}}}}}
            descriptors (sut/descriptors index)]
        (should= "crew" (:entity-dir (:crew descriptors)))
        (should (true? (:frontmatter? (:crew descriptors))))
        (should= "cron" (:entity-dir (:cron descriptors)))
        (should-be-nil (:frontmatter? (:cron descriptors)))))))
