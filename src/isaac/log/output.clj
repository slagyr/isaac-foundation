(ns isaac.log.output
  (:require
    [isaac.log.file :as lfile]
    [isaac.logger :as log]))

(def default-output :file)

(def valid-outputs #{:file :stderr :stdout :none})

(defn- normalize-output [output]
  (if (valid-outputs output) output default-output))

(defn output-from-config
  ([config]
   (normalize-output (get-in config [:logging :output] default-output))))

(defn apply-cli!
  "Configure CLI logging from config and optional overrides.
   --log-file / ISAAC_LOG_FILE always force :file; harness :memory is left alone."
  [root config & {:keys [log-file-path env-log-file]}]
  (cond
    (or log-file-path env-log-file)
    (let [path (or log-file-path env-log-file)
          abs  (lfile/configure-cli-sink! root path)]
      (when abs
        (log/set-log-file! abs)
        (log/set-output! :file)
        (log/debug :cli/log-file :path abs)))

    (= :memory (log/output))
    nil

    :else
    (let [output (output-from-config config)]
      (log/set-output! output)
      (when (= :file output)
        (when-let [abs (lfile/configure-cli-sink! root lfile/cli-log-rel-path)]
          (log/set-log-file! abs))))))

(defn apply-server!
  "Configure server logging from config. :file activates the rotating server
   sink; :stdout/:stderr/:none stream without a durable server log file.

   A harness-set :memory output is preserved and binds NO server sink: tests
   log to memory, full stop. Any sink left by an earlier boot in the same
   process is dropped so a memory-mode boot never inherits a file. (This
   supersedes the isaac-3692 \"memory and file\" behaviour — nothing read the
   file; see isaac-zqyw.) Production boots :file/:stderr per config."
  [root config]
  (if (= :memory (log/output))
    (lfile/clear-sink-config!)
    (let [output (output-from-config config)]
      (log/set-output! output)
      (when (= :file output)
        (lfile/configure-server-sink! root config)))))
