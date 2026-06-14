(ns isaac.foundation.module-spec
  (:require
    [isaac.module.protocol]
    [isaac.foundation.module :as sut]
    [speclj.core :refer [describe it should]]))

(describe "isaac.foundation.module"

  (describe "create-module"

    (it "returns a module record"
      (should (satisfies? isaac.module.protocol/Module (sut/create-module))))))