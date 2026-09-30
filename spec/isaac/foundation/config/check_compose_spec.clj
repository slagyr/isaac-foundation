(ns isaac.foundation.config.check-compose-spec
  (:require
    [isaac.foundation.config.check-compose :as sut]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "config check-compose"

  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (context "run-checks"

    (it "runs builtin server check contributions"
      (let [{:keys [errors warnings]} (sut/run-checks {:config         {}
                                                        :raw-providers  {}
                                                        :module-index   (discovery/builtin-index)
                                                        :root           nil
                                                        :result         {}
                                                        :effective-schema (schema-compose/cached-root-schema)})]
        (should (vector? errors))
        (should (vector? warnings))))))