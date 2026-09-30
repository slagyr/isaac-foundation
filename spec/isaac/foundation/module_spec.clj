(ns isaac.foundation.module-spec
  (:require
    [isaac.foundation.module.protocol]
    [isaac.foundation.module :as sut]
    [speclj.core :refer [describe it should]]))

(describe "isaac.foundation.module"

  (describe "create-module"

    (it "returns a module record"
      (should (satisfies? isaac.foundation.module.protocol/Module (sut/create-module))))))