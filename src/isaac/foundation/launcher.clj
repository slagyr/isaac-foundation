(ns isaac.foundation.launcher
  "Packaged isaac launcher: read config :modules, compose the runtime
   classpath, then boot isaac.foundation.main. Dev checkouts use `bb isaac` instead."
  (:require
    [isaac.foundation.cli.args :as cli-args]
    [isaac.foundation.config.api :as config-api]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.log.output :as log-output]
    [isaac.foundation.main :as main]
    [isaac.foundation.config.paths :as paths]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.startup.cache :as cache]
    [isaac.foundation.startup.classpath-cache :as startup-cp]))

(defn- read-user-config [root fs*]
  (when root
    (let [result (config-api/load-resolved {:root root :fs fs*})]
      (when-not (:missing-config? result)
        result))))

(defn compose-classpath!
  "Add every valid config :modules coordinate to the runtime classpath."
  [root fs* config]
  (let [cwd (System/getProperty "user.dir")
        watched (cache/watched-files (paths/root-config-file root) config cwd)]
    (nexus/-with-nexus {:fs fs*}
      (startup-cp/compose-with-cache! fs* root config cwd watched))))

(defn -main
  "Launcher entrypoint: resolve --root, compose classpath from :modules,
   delegate remaining args to isaac.foundation.main/-main. Threads the already-resolved
   config into main via *extra-opts* so the CLI process loads once (isaac-v1la)."
  [& args]
  (let [{after-root :args :keys [root log-file log-level]} (cli-args/extract-root-flag (vec args))
        fs*           (fs/real-fs)
        resolved-root (root/resolve-root root nil fs*)
        ;; The launcher's own config load below is the FIRST load of the
        ;; process and can log (an unknown key, an unresolved ${VAR}). Install
        ;; the CLI's quiet-by-default sink before it, exactly as isaac.foundation.main
        ;; does for its own loads, so no structured log line reaches the
        ;; terminal unless --log-file/--log-level asked for it (isaac-89q1).
        _             (nexus/-with-nexus {:fs fs*}
                        (log-output/provisional-cli-sink! resolved-root
                                                          :log-file-path log-file
                                                          :env-log-file  (System/getenv "ISAAC_LOG_FILE")
                                                          :log-level     log-level))
        load-result   (read-user-config resolved-root fs*)
        config        (or (:config load-result) {})]
    (compose-classpath! resolved-root fs* config)
    (let [argv (if root
                 (into ["--root" root] after-root)
                 after-root)]
      (binding [main/*extra-opts* {:load-result load-result
                                   :config      config
                                   :fs          fs*}]
        (apply main/-main argv)))))