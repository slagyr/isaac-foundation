(ns isaac.config.env-spec
  (:require
    [isaac.config.env :as sut]
    [speclj.core :refer :all]))

(describe "isaac.config.env"
  (it "exposes env override API"
    (should-not-be-nil sut/env)
    (should-not-be-nil sut/set-env-override!)
    (should-not-be-nil sut/clear-env-overrides!)
    (should-not-be-nil sut/lock-dotenv!)))
