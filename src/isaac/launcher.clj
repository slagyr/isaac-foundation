(ns isaac.launcher
  "Packaged isaac launcher: read config :modules, compose the runtime
   classpath, then boot isaac.main. Dev checkouts use `bb isaac` instead."
  (:require
    [isaac.cli.args :as cli-args]
    [isaac.config.api :as config-api]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]))

(defn- read-user-config [root fs*]
  (when root
    (let [result (config-api/load-resolved {:root root :fs fs*})]
      (when-not (:missing-config? result)
        (:config result)))))

(defn compose-classpath!
  "Add every valid config :modules coordinate to the runtime classpath."
  [config]
  (nexus/-with-nexus {:fs (fs/real-fs)}
    (module-loader/compose-config-modules! config)))

(defn -main
  "Launcher entrypoint: resolve --root, compose classpath from :modules,
   delegate remaining args to isaac.main/-main."
  [& args]
  (let [{after-root :args :keys [root]} (cli-args/extract-root-flag (vec args))
        fs*           (fs/real-fs)
        resolved-root (root/resolve-root root nil fs*)
        config        (or (read-user-config resolved-root fs*) {})]
    (compose-classpath! config)
    (let [main (requiring-resolve 'isaac.main/-main)
          argv (if root
                 (into ["--root" root] after-root)
                 after-root)]
      (apply main argv))))