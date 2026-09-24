(ns isaac.log.output
  (:require
    [isaac.log.file :as lfile]
    [isaac.logger :as log]))

(def default-output :file)

(def valid-outputs #{:file :stderr :stdout :none})
(def valid-levels #{:report :error :warn :info :debug})

(defn- normalize-output [output]
  (if (valid-outputs output) output default-output))

(defn output-from-config [config]
  (normalize-output (get-in config [:logging :output] default-output)))

(defn level-from-config [config]
  (let [level (get-in config [:logging :level] :debug)]
    (if (valid-levels level) level :debug)))

(defn- apply-level! [config override]
  (log/set-level! (if (valid-levels override) override (level-from-config config))))

(defn- explicit-output-override!
  "Applies the one operator-driven decision that overrides the CLI's quiet-
   by-default sink: --log-file/ISAAC_LOG_FILE forces the file sink at that
   path; a bare --log-level (no file) is the opt-in to see logs live on the
   terminal (isaac-89q1), so it routes output to :stderr. Both are known from
   argv alone, with no config needed. Returns true when it decided the
   output (so the caller must not also apply its own default); false when
   there is no explicit override. A harness-set :memory output always counts
   as already decided and is left alone."
  [root {:keys [log-file-path env-log-file log-level]}]
  (cond
    (= :memory (log/output))
    true

    (or log-file-path env-log-file)
    (let [path (or log-file-path env-log-file)
          abs  (lfile/configure-cli-sink! root path)]
      (when abs
        (log/set-log-file! abs)
        (log/set-output! :file)
        (log/debug :cli/log-file :path abs))
      true)

    log-level
    (do (log/set-output! :stderr) true)

    :else false))

(defn provisional-cli-sink!
  "Installs the CLI's log sink before config has loaded, so a warning raised
   while loading that very config (isaac-89q1: an unknown key, an
   unresolved ${VAR}) never reaches the terminal by default and is never
   lost. --log-file/--log-level are parsed from argv before any config
   touch, so an explicit one is honored immediately, exactly as apply-cli!
   ultimately would; with neither, defaults to the file sink at
   logs/cli.log. apply-cli! reapplies once config is loaded — it may still
   redirect this default (e.g. :logging :output :stdout) but never
   overrides an explicit flag already decided here. A harness-set :memory
   output is left alone."
  [root & {:as opts}]
  (when (and root (not (explicit-output-override! root opts)))
    (log/set-output! :file)
    (when-let [abs (lfile/configure-cli-sink! root lfile/cli-log-rel-path)]
      (log/set-log-file! abs))))

(defn apply-cli!
  "Configure CLI logging from config and optional overrides.
   --log-file / ISAAC_LOG_FILE always force :file; a bare --log-level opts
   into terminal output (:stderr); harness :memory is left alone."
  [root config & {:as opts}]
  (apply-level! config (:log-level opts))
  (when-not (explicit-output-override! root opts)
    (let [output (output-from-config config)]
      (log/set-output! output)
      (when (= :file output)
        (when-let [abs (lfile/configure-cli-sink! root lfile/cli-log-rel-path)]
          (log/set-log-file! abs))))))

(defn apply-server!
  "Configure server logging from config. :file activates the rotating server
   sink; :stdout/:stderr/:none stream without a durable server log file.

   A harness-set :memory output is preserved and binds NO server sink — tests
   log to memory — unless the config names a :logging.output explicitly, in
   which case the scenario asked for that output and it applies as in
   production. Under :memory any sink left by an earlier boot in the same
   process is dropped so a memory-mode boot never inherits a file. (Supersedes
   the isaac-3692 \"memory and file\" behaviour; see isaac-zqyw.)"
  [root config & {:keys [log-level]}]
  (apply-level! config log-level)
  (let [explicit? (some? (get-in config [:logging :output]))]
    (if (and (= :memory (log/output)) (not explicit?))
      (lfile/clear-sink-config!)
      (let [output (output-from-config config)]
        (log/set-output! output)
        (when (= :file output)
          (lfile/configure-server-sink! root config))))))
