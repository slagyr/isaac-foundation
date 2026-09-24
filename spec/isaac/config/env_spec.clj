(ns isaac.config.env-spec
  (:require
    [isaac.config.env :as sut]
    [speclj.core :refer :all]))

(describe "isaac.config.env"
  (it "exposes env override API"
    (should-not-be-nil sut/env)
    (should-not-be-nil sut/set-env-override!)
    (should-not-be-nil sut/clear-env-overrides!)
    (should-not-be-nil sut/lock-dotenv!))

  (describe "lock-dotenv! 2-arity + dotenv-snapshot (isaac-p4oj)"

    (after (sut/clear-env-overrides!))

    (it "dotenv-snapshot is empty before any lock"
      (should= {} (sut/dotenv-snapshot)))

    (it "installs a supplied snapshot directly, without touching the filesystem"
      (sut/lock-dotenv! "/nonexistent/root" {"FOO" "bar"})
      (should= {"FOO" "bar"} (sut/dotenv-snapshot))
      (should= "bar" (sut/env "FOO")))

    (it "an explicitly empty snapshot is installed as empty, not re-read from root"
      (sut/lock-dotenv! "/nonexistent/root" {"FOO" "bar"})
      (sut/lock-dotenv! "/nonexistent/root" {})
      (should= {} (sut/dotenv-snapshot))
      (should-be-nil (sut/env "FOO")))))
