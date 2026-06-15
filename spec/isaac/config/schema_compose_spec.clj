(ns isaac.config.schema-compose-spec
  (:require
    [isaac.config.schema-compose :as sut]
    [speclj.core :refer :all]))

(describe "config schema-compose"

  (describe "inline :schema contributions"

    (it "composes an inline schema map into the root schema"
      (let [index {:mod.x {:manifest {:isaac.config/schema
                                      {:tunes {:schema {:name        :tunes
                                                        :type        :map
                                                        :description "Shanty config"
                                                        :schema      {:volume {:type :int}}}}}}}}
            root  (sut/compose-root-schema index)]
        (should= {:type :int} (get-in root [:schema :tunes :schema :volume]))))

    (it "rejects a contribution whose :schema is not a map"
      (should-throw clojure.lang.ExceptionInfo
                    (sut/compose-root-schema
                      {:mod.x {:manifest {:isaac.config/schema
                                          {:tunes {:schema 42}}}}})))

    (it "meta-validates inline schemas and names the offender"
      (let [e (should-throw clojure.lang.ExceptionInfo
                            (sut/compose-root-schema
                              {:mod.x {:manifest {:isaac.config/schema
                                                  {:tunes {:schema {:type   :map
                                                                    :schema {:volume {:type :warble}}}}}}}}))]
        (should-contain ":warble" (.getMessage e)))))

  (describe "descriptors"

    (it "derives entity metadata from inline :isaac.config/schema contributions"
      (let [index       {:mod.x {:manifest {:isaac.config/schema
                                            {:berths {:schema       {:type :map :key-spec {:type :keyword} :value-spec {:type :map}}
                                                      :entity-dir   "berths"
                                                      :frontmatter? true}
                                             :cron   {:schema     {:type :map :key-spec {:type :keyword} :value-spec {:type :map}}
                                                       :entity-dir "cron"}}}}}
            descriptors (sut/descriptors index)]
        (should= "berths" (:entity-dir (:berths descriptors)))
        (should (true? (:frontmatter? (:berths descriptors))))
        (should= "cron" (:entity-dir (:cron descriptors)))
        (should-be-nil (:frontmatter? (:cron descriptors)))))))
