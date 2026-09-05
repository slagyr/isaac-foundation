(ns isaac.launcher
  "Packaged isaac launcher: read config :modules, compose the runtime
   classpath, then boot isaac.main. Dev checkouts use `bb isaac` instead."
  (:require
    [isaac.cli.args :as cli-args]
    [isaac.config.api :as config-api]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.main :as main]
    [isaac.config.paths :as paths]
    [isaac.nexus :as nexus]
    [isaac.startup.cache :as cache]
    [isaac.startup.classpath-cache :as startup-cp]))

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
   delegate remaining args to isaac.main/-main. Threads the already-resolved
   config into main via *extra-opts* so the CLI process loads once (isaac-v1la)."
  [& args]
  (let [{after-root :args :keys [root]} (cli-args/extract-root-flag (vec args))
        fs*           (fs/real-fs)
        resolved-root (root/resolve-root root nil fs*)
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