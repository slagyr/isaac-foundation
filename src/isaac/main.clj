;; mutation-tested: 2026-05-06
(ns isaac.main
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config]
    [isaac.config.paths :as paths]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.cli.args :as cli-args]
    [isaac.config.root :as root]
    [isaac.foundation.version :as version]))

(def ^:dynamic *extra-opts* nil)

(defn- startup-fs [extra-opts]
  (or (fs/instance extra-opts) (fs/real-fs)))

(defn- substitute-env [x]
  (cond
    (string? x) (str/replace x #"\$\{([^}]+)\}"
                   (fn [[_ var]] (or (config/env var) (str "${" var "}"))))
    (map? x)    (into {} (map (fn [[k v]] [k (substitute-env v)]) x))
    (coll? x)   (mapv substitute-env x)
    :else        x))

(defn- read-user-config [root fs*]
  (when root
    (let [config-file (paths/root-config-file root)]
      (when (fs/exists? fs* config-file)
        (try (substitute-env (edn/read-string (fs/slurp fs* config-file)))
             (catch Exception _ nil))))))

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
   installations) would be inside the wrap's restore scope."
  [root fs*]
  (try
    (with-redefs [log/log* (fn [& _])]
      (let [config  (or (read-user-config root fs*) {})
            context {:cwd (System/getProperty "user.dir")}
            {:keys [index]}
            (nexus/-with-nested-nexus {:fs fs*}
              (module-loader/discover! config context))]
        (registry/clear-berth-commands!)
        (module-loader/process-manifest-berths! index)))
    (catch Exception _
      nil)))

(defn- usage []
  (let [cmds (registry/all-commands)
        max-len (if (seq cmds) (apply max (map #(count (:name %)) cmds)) 0)]
    (str "Usage: isaac [options] <command> [args]\n\n"
         "Global Options:\n"
         "  --root <dir>    Override Isaac's root directory (default: ~/.isaac)\n"
         "                  May also be set via ISAAC_ROOT, ~/.config/isaac.edn, or ~/.isaac.edn\n"
         "  --help, -h      Show this message\n\n"
         "Commands:\n"
         (str/join "\n" (map (fn [cmd]
                               (str "  " (:name cmd)
                                    (apply str (repeat (- (+ max-len 4) (count (:name cmd))) " "))
                                    (:summary cmd)))
                             cmds)))))

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
  (let [{after-root :args :keys [root]} (cli-args/extract-root-flag args)
         args (resolve-alias after-root)
         cmd  (first args)
         opts (rest args)
         extra-opts    (or *extra-opts* {})
         fs*           (startup-fs extra-opts)
         resolved-root (root/resolve-root root (:root extra-opts) fs*)]
    (register-module-cli-commands! resolved-root fs*)
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
              (nexus/init! {:fs fs*})
              (or ((:run-fn command) (merge extra-opts {:display-root (or root resolved-root)
                                                        :root         resolved-root
                                                        :_raw-args    (vec opts)})) 0)))
          (do (println (str "Unknown command: " cmd))
              (println (usage))
              1)))))

(defn -main [& args]
  (let [exit-code (run args)]
    (when (pos? exit-code)
      (System/exit exit-code))))
