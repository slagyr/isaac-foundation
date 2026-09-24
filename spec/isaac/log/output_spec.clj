(ns isaac.log.output-spec
  (:require
    [isaac.fs :as fs]
    [isaac.log.file :as lfile]
    [isaac.log.output :as sut]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "log output from config"

  (around [it]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (it)))

  (before (lfile/clear-sink-config!)
          (log/set-level! :debug)
          (log/set-output! :stderr)
          (log/set-log-file! nil))

  (after (lfile/clear-sink-config!)
         (log/set-level! :debug)
         (log/set-output! :stderr)
         (log/set-log-file! nil))

  (it "defaults to :file when :logging.output is absent"
    (should= :file (sut/output-from-config {:tz "UTC"})))

  (it "reads :logging.output from config"
    (should= :stdout (sut/output-from-config {:logging {:output :stdout}})))

  (it "falls back to :file for unknown output values"
    (should= :file (sut/output-from-config {:logging {:output :syslog}})))

  (it "defaults the log level to :debug"
    (should= :debug (sut/level-from-config {})))

  (it "reads :logging.level from config"
    (should= :info (sut/level-from-config {:logging {:level :info}})))

  (it "falls back to :debug for unknown log levels"
    (should= :debug (sut/level-from-config {:logging {:level :trace}})))

  (describe "apply-server!"

    (it "activates the server sink for default :file output"
      (let [root "/srv"]
        (sut/apply-server! root {:tz "UTC"})
        (should= :file (log/output))
        (should (lfile/server-sink?))))

    (it "skips the server sink for :stdout output"
      (let [root "/srv"]
        (sut/apply-server! root {:logging {:output :stdout}})
        (should= :stdout (log/output))
        (should-not (lfile/server-sink?))))

    (it "applies the configured server log level"
      (sut/apply-server! "/srv" {:logging {:level :warn}})
      (should= :warn (log/level)))

    (it "lets the explicit server log level override config"
      (sut/apply-server! "/srv" {:logging {:level :warn}} :log-level :error)
      (should= :error (log/level)))

    (it "preserves :memory output and binds no server sink"
      (log/set-output! :memory)
      (sut/apply-server! "/srv" {:tz "UTC"})
      (should= :memory (log/output))
      (should-not (lfile/server-sink?)))

    (it "an explicit :logging.output in config wins over harness :memory"
      (log/set-output! :memory)
      (sut/apply-server! "/srv" {:logging {:output :file}})
      (should= :file (log/output))
      (should (lfile/server-sink?)))

    (it "drops a sink left by an earlier file-mode boot when output is :memory"
      (sut/apply-server! "/srv" {:tz "UTC"})
      (should (lfile/server-sink?))
      (log/set-output! :memory)
      (sut/apply-server! "/srv" {:tz "UTC"})
      (should-not (lfile/server-sink?))))

  (describe "apply-cli!"

    (it "defaults to cli.log for :file output"
      (sut/apply-cli! "/cli-root" {})
      (should= :file (log/output))
      (should= "/cli-root/logs/cli.log" (log/log-file))))

    (it "honors :logging.output without configuring a file sink"
      (sut/apply-cli! "/cli-root" {:logging {:output :stderr}})
      (should= :stderr (log/output))
      (should-be-nil (log/log-file)))

    (it "applies the configured CLI log level"
      (sut/apply-cli! "/cli-root" {:logging {:level :info}})
      (should= :info (log/level)))

    (it "lets the explicit CLI log level override config"
      (sut/apply-cli! "/cli-root" {:logging {:level :info}} :log-level :error)
      (should= :error (log/level)))

    (it "routes to the terminal when --log-level is given without --log-file (isaac-89q1)"
      (sut/apply-cli! "/cli-root" {} :log-level :warn)
      (should= :stderr (log/output))
      (should-be-nil (log/log-file)))

    (it "still prefers an explicit --log-file over a bare --log-level"
      (sut/apply-cli! "/cli-root" {} :log-level :warn :log-file-path "cmd.log")
      (should= :file (log/output))
      (should= "/cli-root/cmd.log" (log/log-file)))

  (describe "provisional-cli-sink!"

    (it "defaults to the file sink at logs/cli.log before config is known"
      (sut/provisional-cli-sink! "/cli-root")
      (should= :file (log/output))
      (should= "/cli-root/logs/cli.log" (log/log-file)))

    (it "routes to the terminal for a bare --log-level, before config is loaded"
      (sut/provisional-cli-sink! "/cli-root" :log-level :warn)
      (should= :stderr (log/output)))

    (it "honors an explicit --log-file before config is loaded"
      (sut/provisional-cli-sink! "/cli-root" :log-file-path "cmd.log")
      (should= :file (log/output))
      (should= "/cli-root/cmd.log" (log/log-file)))

    (it "leaves a harness-set :memory output alone"
      (log/set-output! :memory)
      (sut/provisional-cli-sink! "/cli-root")
      (should= :memory (log/output))
      (should-be-nil (log/log-file)))

    (it "does nothing without a root"
      (sut/provisional-cli-sink! nil)
      (should= :stderr (log/output)))))