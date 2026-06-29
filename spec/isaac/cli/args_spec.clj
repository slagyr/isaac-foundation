(ns isaac.cli.args-spec
  (:require
    [isaac.cli.args :as sut]
    [speclj.core :refer :all]))

(describe "cli args"

  (it "extract-root-flag strips --root <dir>"
    (should= {:args ["chat"] :root "/tmp/flag" :log-file nil}
             (sut/extract-root-flag ["--root" "/tmp/flag" "chat"])))

  (it "extract-root-flag strips --root=<dir>"
    (should= {:args ["chat"] :root "/tmp/flag" :log-file nil}
             (sut/extract-root-flag ["--root=/tmp/flag" "chat"])))

  (it "extract-root-flag strips --log-file <path>"
    (should= {:args ["version"] :root nil :log-file "logs/cmd.log"}
             (sut/extract-root-flag ["--log-file" "logs/cmd.log" "version"])))

  (it "extract-root-flag leaves args unchanged when --root absent"
    (should= {:args ["chat" "--agent" "bot"] :root nil :log-file nil}
             (sut/extract-root-flag ["chat" "--agent" "bot"]))))