(ns isaac.config.check-compose-spec
  (:require
    [isaac.config.check-compose :as sut]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.module.discovery :as discovery]
    [speclj.core :refer :all]))

(describe "config check-compose"

  (describe "run-checks"

    (it "runs builtin server check contributions"
      (let [{:keys [errors warnings]} (sut/run-checks {:config         {}
                                                        :raw-providers  {}
                                                        :module-index   (discovery/builtin-index)
                                                        :root           nil
                                                        :result         {}
                                                        :effective-schema (schema-compose/cached-root-schema)})]
        (should (vector? errors))
        (should (vector? warnings))))))