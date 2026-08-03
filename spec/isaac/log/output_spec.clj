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
          (log/set-output! :stderr)
          (log/set-log-file! nil))

  (after (lfile/clear-sink-config!)
         (log/set-output! :stderr)
         (log/set-log-file! nil))

  (it "defaults to :file when :logging.output is absent"
    (should= :file (sut/output-from-config {:tz "UTC"})))

  (it "reads :logging.output from config"
    (should= :stdout (sut/output-from-config {:logging {:output :stdout}})))

  (it "falls back to :file for unknown output values"
    (should= :file (sut/output-from-config {:logging {:output :syslog}})))

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
        (should-not (lfile/server-sink?)))))

  (describe "apply-cli!"

    (it "defaults to cli.log for :file output"
      (sut/apply-cli! "/cli-root" {})
      (should= :file (log/output))
      (should= "/cli-root/logs/cli.log" (log/log-file))))

    (it "honors :logging.output without configuring a file sink"
      (sut/apply-cli! "/cli-root" {:logging {:output :stderr}})
      (should= :stderr (log/output))
      (should-be-nil (log/log-file))))