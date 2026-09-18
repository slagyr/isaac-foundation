;; mutation-tested: 2026-05-06
(ns isaac.main
  (:require
    [clojure.string :as str]
    [isaac.cli.host :as host]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config-api]
    [isaac.config.env :as env]
    [isaac.fs :as fs]
    [isaac.log.output :as log-output]
    [isaac.logger :as log]
    [isaac.module.berths :as berths]
    [isaac.module.classpath :as classpath]
    [isaac.module.discovery :as discovery]
    [isaac.module.lifecycle :as lifecycle]
    [isaac.nexus :as nexus]
    [isaac.cli.args :as cli-args]
    [isaac.config.root :as root]
    [isaac.config.paths :as paths]
    [isaac.startup.cache :as cache]
    [isaac.startup.classpath-cache :as startup-cp]
    [isaac.foundation.version :as version]))

(def ^:dynamic *extra-opts* nil)
(def ^:dynamic *remote-runner* nil)

(defn- startup-fs [extra-opts]
  ;; Composition boundary: resolve the runtime fs to install. Prefer an
  ;; explicitly-passed fs, then any already-installed nexus fs, otherwise
  ;; default to the real filesystem. Reads slots directly rather than via
  ;; fs/instance, which now throws when no fs is available.
  (or (:fs extra-opts) (nexus/get :fs) (fs/real-fs)))

(defn- extra-load-result [extra-opts]
  (:load-result extra-opts))

(defn- read-user-config [root fs* extra-opts]
  (when root
    (or (:config extra-opts)
        (when-let [result (extra-load-result extra-opts)]
          (when-not (:missing-config? result)
            (:config result)))
        (let [result (config-api/load-resolved {:root root :fs fs*})]
          (when-not (:missing-config? result)
            (:config result))))))

(defn- command-summaries []
  (mapv #(select-keys % [:name :summary]) (registry/all-commands)))

(defn- register-module-cli-commands!
  "`:isaac/cli` is a berth, so all CLI
   contributions — built-in (foundation + server `:isaac/cli [...]`) and
   module-supplied alike — flow through process-manifest-berths!.
   discover! always merges builtin manifests into the index, so built-in
   :isaac/cli contributions
   (the built-in commands) register on every invocation, even when
   no user config exists. We bracket discover! in -with-nested-nexus
   so it sees mem-fs (when set), but process-manifest-berths! has to
   run AFTER the wrap exits or its side-effects (CLI registry
   installations) would be inside the wrap's restore scope.

   For `isaac modules` (config-only), skip remote classpath resolution
   so install/list never pull git coordinates onto the classpath."
  ([root fs* cmd]
   (register-module-cli-commands! root fs* cmd {}))
  ([root fs* _cmd extra-opts]
   (try
     (binding [log/*quiet?* true]
       (let [config  (or (read-user-config root fs* extra-opts) {})
             context {:cwd (host/cwd)}
             {:keys [index]}
             (nexus/-with-nested-nexus {:fs fs*}
               (discovery/discover! config context))]
         (registry/clear-berth-commands!)
         (lifecycle/reconcile-modules! index)
         (berths/process-manifest-berths! index)))
     (catch Exception _
       nil))))

(defn- env-log-file []
  (let [v (env/env "ISAAC_LOG_FILE")]
    (when-not (str/blank? v) v)))

(defn- configure-cli-logging!
  ([root fs* log-file-path]
   (configure-cli-logging! root fs* log-file-path nil {}))
  ([root fs* log-file-path log-level extra-opts]
   (let [config (or (read-user-config root fs* extra-opts) {})]
     (log-output/apply-cli! root config
                            :log-file-path log-file-path
                            :env-log-file (env-log-file)
                            :log-level log-level))))

(defn- resolve-alias
  "Resolve command aliases. 'models auth ...' → 'auth ...', 'gateway ...' → 'server ...'"
  [args]
  (cond
    (and (= "models" (first args)) (= "auth" (second args)))
    (rest args)

    (= "gateway" (first args))
    (vec (cons "server" (rest args)))

    :else args))

(defn run
  "Run the CLI. Returns exit code."
  [args]
  (config-api/clear-process-memo!)
  (let [{after-root :args :keys [root log-file log-level local?]} (cli-args/extract-root-flag args)
        args          (resolve-alias after-root)
        cmd           (first args)
        opts          (rest args)
        extra-opts    (or *extra-opts* {})
        fs*           (startup-fs extra-opts)
        resolved-root (root/resolve-root root (:root extra-opts) fs*)
        remote        (get-in (root/pointer-config fs*) [:cli :remote])
        env-local?    (= "1" (env/env "ISAAC_CLI_LOCAL"))
        local-only?   (contains? #{"server" "service" "modules" "remote"} cmd)
        route-remote? (and remote (not local?) (not env-local?) (not local-only?))]
    (if route-remote?
      (if-let [runner (or *remote-runner*
                          (try (requiring-resolve 'isaac.cli-proxy.client/run!)
                               (catch Exception _ nil)))]
        (try
          (runner {:url (:url remote) :argv (vec args) :remote remote})
          (catch Exception e
            (binding [*out* *err*]
              (println (str (:url remote) " is not reachable: " (.getMessage e)))
              (println "run with --local to bypass"))
            69))
        (do
          (binding [*out* *err*]
            (println "remote CLI setting requires the isaac.cli-proxy module")
            (println "run with --local to bypass"))
          69))
      (nexus/-with-nested-nexus {:fs fs*}
      (binding [classpath/*resolve-classpath?* (not= "modules" cmd)]
        ;; Startup cache (isaac-clic): when nothing the CLI plans from has
        ;; changed, the fast-path commands (--version, --help) skip module
        ;; discovery/registration entirely and serve from the cache.
        ;; isaac-v1la: resolve config once and thread it through logging,
        ;; module registration, and the command handler.
        (let [load-result (or (:load-result extra-opts)
                              (config-api/load-resolved {:root resolved-root :fs fs*}))
              config     (or (:config load-result) {})
              extra-opts (assoc extra-opts :config config :load-result load-result)
              watched    (cache/watched-files (paths/root-config-file resolved-root)
                                              config (host/cwd))
              cache-fresh? (and (not= "modules" cmd) (cache/fresh? fs* resolved-root watched))
              fast-cmd?  (or (nil? cmd) (str/blank? cmd)
                             (contains? #{"--help" "-h" "--version" "-V" "version"} cmd))
               cwd          (host/cwd)
               compose      (when (and (not= "modules" cmd)
                                       classpath/*resolve-classpath?*
                                       (not (and cache-fresh? fast-cmd?)))
                              (startup-cp/compose-with-cache!
                                fs* resolved-root config cwd watched))
               pairs        (:pairs compose)]
          (if (and cache-fresh? fast-cmd?)
            (do
              (configure-cli-logging! resolved-root fs* log-file log-level extra-opts)
              (if (contains? #{"--version" "-V" "version"} cmd)
                (do (println (version/version-string)) 0)
                (do (println (registry/usage-text
                               (get-in (cache/read-cache fs* resolved-root) [:data :commands])))
                    0)))
            (do
              (binding [classpath/*skip-preload-planned?* (boolean pairs)
                         classpath/*planned-classpath-pairs* pairs]
                 (register-module-cli-commands! resolved-root fs* cmd extra-opts))
              (configure-cli-logging! resolved-root fs* log-file log-level extra-opts)
              (when (and (not= "modules" cmd)
                         (or (not cache-fresh?) (not (:from-cache? compose))))
                (startup-cp/write-classpath-cache!
                  fs* resolved-root watched config
                  (or pairs [])
                  (command-summaries)))
              (cond
        (or (nil? cmd) (str/blank? cmd) (= "--help" cmd) (= "-h" cmd))
        (do (println (registry/usage-text)) 0)

        (or (= "--version" cmd) (= "-V" cmd) (= "version" cmd))
        (do (println (version/version-string)) 0)

        :else
        (if-let [command (registry/get-command cmd)]
          (binding [root/*root* resolved-root]
            (nexus/-with-nested-nexus {:fs fs*}
              (nexus/init! {:fs fs* :root resolved-root})
              (or ((:run-fn command) (merge extra-opts {:display-root (or root resolved-root)
                                                        :root         resolved-root
                                                        :log-level    log-level
                                                        :_raw-args    (vec opts)})) 0)))
          (do (println (str "Unknown command: " cmd))
              (println (registry/usage-text))
              1)))))))))))

(defn -main [& args]
  (let [exit-code (binding [host/*host* host/process-host]
                    (try
                      (run args)
                      (catch clojure.lang.ExceptionInfo e
                        (if-some [code (:isaac.cli/process-exit (ex-data e))]
                          code
                          (throw e)))))]
    (when (pos? exit-code)
      (System/exit exit-code))))
