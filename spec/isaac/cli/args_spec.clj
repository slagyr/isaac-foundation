(ns isaac.cli.args-spec
  (:require
    [isaac.cli.args :as sut]
    [speclj.core :refer :all]))

(describe "cli args"

  (it "extract-root-flag strips --root <dir>"
    (should= {:args ["chat"] :root "/tmp/flag" :log-file nil :log-level nil :local? false}
             (sut/extract-root-flag ["--root" "/tmp/flag" "chat"])))

  (it "extract-root-flag strips --root=<dir>"
    (should= {:args ["chat"] :root "/tmp/flag" :log-file nil :log-level nil :local? false}
             (sut/extract-root-flag ["--root=/tmp/flag" "chat"])))

  (it "extract-root-flag strips --log-file <path>"
    (should= {:args ["version"] :root nil :log-file "logs/cmd.log" :log-level nil :local? false}
             (sut/extract-root-flag ["--log-file" "logs/cmd.log" "version"])))

  (it "extract-root-flag strips --log-level <level>"
    (should= {:args ["version"] :root nil :log-file nil :log-level :info :local? false}
             (sut/extract-root-flag ["--log-level" "info" "version"])))

  (it "extract-root-flag strips --log-level=<level>"
    (should= {:args ["version"] :root nil :log-file nil :log-level :warn :local? false}
             (sut/extract-root-flag ["--log-level=warn" "version"])))

  (it "extract-root-flag explicitly ignores an unknown log level"
    (should= {:args ["version"] :root nil :log-file nil :log-level nil :local? false}
             (sut/extract-root-flag ["--log-level" "trace" "version"])))

  (it "extract-root-flag strips --local"
    (should= {:args ["sessions" "list"] :root nil :log-file nil :log-level nil :local? true}
             (sut/extract-root-flag ["--local" "sessions" "list"])))

  (it "extract-root-flag leaves args unchanged when --root absent"
    (should= {:args ["chat" "--agent" "bot"] :root nil :log-file nil :log-level nil :local? false}
             (sut/extract-root-flag ["chat" "--agent" "bot"]))))