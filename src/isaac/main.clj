;; mutation-tested: 2026-05-06
(ns isaac.main
  (:require
    [clojure.string :as str]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config-api]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.log.file :as lfile]
    [isaac.log.output :as log-output]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.cli.args :as cli-args]
    [isaac.config.root :as root]
    [isaac.config.paths :as paths]
    [isaac.startup.cache :as cache]
    [isaac.foundation.version :as version]))

(def ^:dynamic *extra-opts* nil)

(defn- startup-fs [extra-opts]
  ;; Composition boundary: resolve the runtime fs to install. Prefer an
  ;; explicitly-passed fs, then any already-installed nexus fs, otherwise
  ;; default to the real filesystem. Reads slots directly rather than via
  ;; fs/instance, which now throws when no fs is available.
  (or (:fs extra-opts) (nexus/get :fs) (fs/real-fs)))

(defn- read-user-config [root fs*]
  (when root
    (let [result (config-api/load-resolved {:root root :fs fs*})]
      (when-not (:missing-config? result)
        (:config result)))))

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
  [root fs* cmd]
  (try
    (with-redefs [log/log* (fn [& _])]
      (let [config  (or (read-user-config root fs*) {})
            context {:cwd (System/getProperty "user.dir")}
            {:keys [index]}
            (nexus/-with-nested-nexus {:fs fs*}
              (module-loader/discover! config context))]
        (registry/clear-berth-commands!)
        (module-loader/reconcile-modules! index)
        (module-loader/process-manifest-berths! index)))
    (catch Exception _
      nil)))

(defn- env-log-file []
  (let [v (loader/env "ISAAC_LOG_FILE")]
    (when-not (str/blank? v) v)))

(defn- configure-cli-logging! [root fs* log-file-path]
  (let [config (or (read-user-config root fs*) {})]
    (log-output/apply-cli! root config
                           {:log-file-path log-file-path
                            :env-log-file  (env-log-file)})))

(defn- usage-for [cmds]
  (let [max-len (if (seq cmds) (apply max (map #(count (:name %)) cmds)) 0)]
    (str "Usage: isaac [options] <command> [args]\n\n"
         "Global Options:\n"
         "  --root <dir>       Isaac root directory (default: ~/.isaac)\n"
         "  --log-file <path>  Append structured logs to this file (optional)\n"
         "  --help, -h         Show this message\n\n"
         "Commands:\n"
         (str/join "\n" (map (fn [cmd]
                               (str "  " (:name cmd)
                                    (apply str (repeat (- (+ max-len 4) (count (:name cmd))) " "))
                                    (:summary cmd)))
                             cmds)))))

(defn- usage []
  (usage-for (registry/all-commands)))

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
  (let [{after-root :args :keys [root log-file]} (cli-args/extract-root-flag args)
        args          (resolve-alias after-root)
        cmd           (first args)
        opts          (rest args)
        extra-opts    (or *extra-opts* {})
        fs*           (startup-fs extra-opts)
        resolved-root (root/resolve-root root (:root extra-opts) fs*)]
    (nexus/-with-nested-nexus {:fs fs*}
      (binding [module-loader/*resolve-classpath?* (not= "modules" cmd)]
        ;; Startup cache (isaac-clic): when nothing the CLI plans from has
        ;; changed, the fast-path commands (--version, --help) skip module
        ;; discovery/registration entirely and serve from the cache.
        (let [config     (or (read-user-config resolved-root fs*) {})
              watched    (cache/watched-files (paths/root-config-file resolved-root)
                                              config (System/getProperty "user.dir"))
              cache-fresh? (and (not= "modules" cmd) (cache/fresh? fs* resolved-root watched))
              fast-cmd?  (or (nil? cmd) (str/blank? cmd)
                             (contains? #{"--help" "-h" "--version" "-V" "version"} cmd))]
          (if (and cache-fresh? fast-cmd?)
            (do
              (configure-cli-logging! resolved-root fs* log-file)
              (if (contains? #{"--version" "-V" "version"} cmd)
                (do (println (version/version-string)) 0)
                (do (println (usage-for (get-in (cache/read-cache fs* resolved-root) [:data :commands]))) 0)))
            (do
              (register-module-cli-commands! resolved-root fs* cmd)
              (configure-cli-logging! resolved-root fs* log-file)
              (when (and (not cache-fresh?) (not= "modules" cmd))
                (cache/write-cache! fs* resolved-root
                                    {:version cache/cache-version
                                     :basis   (cache/compute-basis fs* watched)
                                     :data    {:commands (mapv #(select-keys % [:name :summary])
                                                               (registry/all-commands))}}))
              (cond
        (or (nil? cmd) (str/blank? cmd) (= "--help" cmd) (= "-h" cmd))
        (do (println (usage)) 0)

        (or (= "--version" cmd) (= "-V" cmd) (= "version" cmd))
        (do (println (version/version-string)) 0)

        (= "help" cmd)
        (if-let [target (first opts)]
          (if-let [command (registry/get-command target)]
            (do (println (registry/command-help command)) 0)
            (do (println (str "Unknown command: " target)) 1))
          (do (println (usage)) 0))

        :else
        (if-let [command (registry/get-command cmd)]
          (binding [root/*root* resolved-root]
            (nexus/-with-nested-nexus {:fs fs*}
              (nexus/init! {:fs fs* :root resolved-root})
              (or ((:run-fn command) (merge extra-opts {:display-root (or root resolved-root)
                                                        :root         resolved-root
                                                        :_raw-args    (vec opts)})) 0)))
          (do (println (str "Unknown command: " cmd))
              (println (usage))
              1))))))))))

(defn -main [& args]
  (let [exit-code (run args)]
    (when (pos? exit-code)
      (System/exit exit-code))))
