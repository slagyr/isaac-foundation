(ns isaac.cli.api-spec
  (:require
    [isaac.cli.api :as sut]
    [speclj.core :refer :all]))

(describe "cli api"

  (it "option-spec defaults to nil for commands that take no options"
    (should-be-nil (sut/option-spec :no-such-command)))

  (it "subcommands defaults to nil"
    (should-be-nil (sut/subcommands :no-such-command)))

  (it "help defaults to nil so the registry generates a help page"
    (should-be-nil (sut/help :no-such-command)))

  (it "run has no default — unimplemented commands are detectable"
    (should-be-nil (get-method sut/run :no-such-command)))

  (it "dispatches run on the command id"
    (defmethod sut/run ::probe [_id opts] [:ran opts])
    (try
      (should= [:ran {:x 1}] (sut/run ::probe {:x 1}))
      (finally (remove-method sut/run ::probe)))))
