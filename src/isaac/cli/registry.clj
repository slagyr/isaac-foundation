;; mutation-tested: 2026-05-06
(ns isaac.cli.registry
  (:require
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.config.paths :as paths]
    [isaac.fs :as fs]))

;; region ----- Command Registry -----

(defonce ^:private commands (atom {}))

(defn register!
  "Register a CLI command.
   Options:
     :name    - command name (string)
     :usage   - usage line (e.g. \"isaac chat [options]\")
     :desc    - short description for command listing
     :option-spec - clojure.tools.cli option spec
     :run-fn  - (fn [parsed-opts]) to execute the command"
  [{:keys [name] :as cmd}]
  (swap! commands assoc name cmd))

(defn get-command [name]
  (get @commands name))

(defn all-commands []
  (sort-by :name (vals @commands)))

(defn- subcommand-summary [subcommands]
  (let [max-len (apply max (map #(count (:name %)) subcommands))]
    (str/join "\n" (map (fn [{:keys [name desc]}]
                           (str "  " name
                                (apply str (repeat (- (+ max-len 4) (count name)) " "))
                                desc))
                         subcommands))))

(defn command-help [cmd]
  (if-let [help-text (:help-text cmd)]
    (if (fn? help-text) (help-text) help-text)
    (let [summary     (when-let [option-spec (:option-spec cmd)]
                        (-> (tools-cli/parse-opts [] option-spec)
                            :summary
                            str/trim-newline))
          subcommands (:subcommands cmd)]
      (str/join "\n"
                (concat [(str "Usage: isaac " (:usage cmd))
                         ""
                         (:desc cmd)]
                        (when-not (str/blank? summary)
                          [""
                           "Options:"
                           summary])
                        (when (seq subcommands)
                          [""
                           "Subcommands:"
                           (subcommand-summary subcommands)]))))))

;; endregion ^^^^^ Command Registry ^^^^^

;; region ----- Init Command -----

(defn- runtime-fs [opts] (fs/instance opts))

(defn- write-edn! [fs* path value]
  (fs/mkdirs fs* (fs/parent path))
  (binding [*print-namespace-maps* false]
    (fs/spit fs* path (with-out-str (pprint/pprint value)))))

(defn- yaml-scalar [value]
  (cond
    (keyword? value) (pr-str (name value))
    (string? value) (pr-str value)
    (number? value) (str value)
    (true? value) "true"
    (false? value) "false"
    (nil? value) "null"
    :else (throw (ex-info "unsupported YAML frontmatter value" {:value value}))))

(defn- yaml-frontmatter [config]
  (str/join "\n"
            (map (fn [[k v]]
                   (str (name k) ": " (yaml-scalar v)))
                 config)))

(defn- write-markdown-entity! [fs* path config body]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (str "---\n"
                         (yaml-frontmatter config)
                         "\n---\n\n"
                         body)))

(defn- isaac-edn-path [root]
  (paths/root-config-file root))

(defn- created-files []
  ["config/isaac.edn"
   "config/crew/main.md"
   "config/models/llama.edn"
   "config/providers/ollama.edn"
   "config/cron/heartbeat.md"])

(defn- scaffold! [root fs*]
  (write-edn! fs* (paths/config-path root "isaac.edn")
               {:defaults            {:crew :main :model :llama}
                :tz                  "America/Chicago"
                :prefer-entity-files true})
  (write-markdown-entity! fs* (paths/config-path root "crew/main.md")
                           {:model :llama}
                           "You are Isaac, a helpful AI assistant.")
  (write-edn! fs* (paths/config-path root "models/llama.edn") {:model "llama3.2" :provider :ollama})
  (write-edn! fs* (paths/config-path root "providers/ollama.edn") {:base-url "http://localhost:11434" :api :ollama})
  (write-markdown-entity! fs* (paths/config-path root "cron/heartbeat.md")
                           {:expr "*/30 * * * *" :crew :main}
                           "Heartbeat. Anything worth noting?"))

(defn- print-success! [display-root]
  (println (str "Isaac initialized at " display-root "."))
  (println)
  (println "Created:")
  (doseq [path (created-files)]
    (println (str "  " path)))
  (println)
  (println "Isaac uses Ollama locally. If you don't have it:")
  (println)
  (println "  brew install ollama")
  (println "  ollama serve &")
  (println "  ollama pull llama3.2")
  (println)
  (println "Then try:")
  (println)
  (println "  isaac prompt -m \"hello\""))

(defn init-help []
  (str "Usage: isaac init\n\n"
       "Scaffold a default Isaac config for a fresh install."))

(defn init-run [{:keys [display-root root] :as opts}]
  (let [fs*  (runtime-fs opts)
        path (isaac-edn-path root)]
    (if (fs/exists? fs* path)
      (do
        (binding [*out* *err*]
          (println (str "config already exists at " path "; edit it directly.")))
        1)
      (do
        (scaffold! root fs*)
        (print-success! (or display-root root))
        0))))

(defn init-run-fn [{:keys [display-root root _raw-args fs]}]
  (if (some #(or (= "--help" %) (= "-h" %)) (or _raw-args []))
    (do
      (println (init-help))
      0)
    (init-run {:display-root display-root :root root :fs fs})))

;; endregion ^^^^^ Init Command ^^^^^

;; region ----- Berth registration factory -----
;;
;; `:isaac/cli` is a berth declared by
;; isaac.core's manifest. The berth's per-entry factory (called by
;; isaac.module.loader/process-manifest-berths!) is this fn. It
;; resolves the entry's symbol-valued :run-fn / :help-text and
;; registers a command spec with the registry above.

(defn- maybe-resolve [sym]
  (when (symbol? sym) (some-> sym requiring-resolve var-get)))

(defonce ^:private berth-command-names* (atom #{}))

(defn clear-berth-commands!
  "Drop every command previously installed by register-cli-command!.
   Called by register-module-cli-commands! at the start of each
   invocation so stale contributions (modules removed from user
   config since the last run) don't survive in the registry. Commands
   registered statically (e.g., isaac.llm.auth.cli at namespace load)
   are unaffected."
  []
  (let [names @berth-command-names*]
    (swap! commands #(apply dissoc % names))
    (reset! berth-command-names* #{})))

(defn register-cli-command!
  "Per-entry factory for the :isaac/cli berth. The berth is a map of
   command id -> command spec, so each entry arrives as [id spec]; the
   command's name derives from the id, making uniqueness structural
   (a duplicate id doesn't survive EDN parsing). Resolves the
   symbol-valued slots (run-fn / help-text / option-spec /
   subcommands) and registers the command, wrapping the run-fn in
   --help handling so module-supplied commands get a per-command help
   page for free."
  [[id {:keys [desc usage option-spec run-fn help-text subcommands]}]]
  (let [name              (clojure.core/name id)
        resolved-run-fn   (maybe-resolve run-fn)
        resolved-help-fn  (maybe-resolve help-text)
        ;; option-spec may arrive as a symbol (pointing at a defed var
        ;; — the EDN manifest can't inline tools.cli specs cleanly when
        ;; they include fns) or as inline data.
        resolved-options  (if (symbol? option-spec) (maybe-resolve option-spec) option-spec)
        ;; :subcommands is symbol-valued in manifests (the vector
        ;; carries per-subcommand :run fns the EDN can't inline);
        ;; resolve it so command-help renders the subcommand list.
        resolved-subcmds  (if (symbol? subcommands) (maybe-resolve subcommands) subcommands)
        cmd               (cond-> {:name name}
                            desc             (assoc :desc desc)
                            usage            (assoc :usage usage)
                            resolved-options (assoc :option-spec resolved-options)
                            resolved-subcmds (assoc :subcommands resolved-subcmds)
                            resolved-run-fn  (assoc :run-fn  resolved-run-fn)
                            resolved-help-fn (assoc :help-text resolved-help-fn))
        wrapped-run-fn    (when resolved-run-fn
                            (let [help-cmd (dissoc cmd :run-fn)]
                              (fn [{:keys [_raw-args] :as opts}]
                                (if (#{"--help" "-h"} (first (or _raw-args [])))
                                  (do (println (command-help help-cmd)) 0)
                                  (resolved-run-fn opts)))))
        final-cmd         (cond-> cmd
                            wrapped-run-fn (assoc :run-fn wrapped-run-fn))]
    (swap! berth-command-names* conj name)
    (register! final-cmd)))

;; endregion ^^^^^ Berth registration factory -----

;; region ----- Module Command Management -----

(defonce ^:private module-command-names* (atom #{}))

(defn- wrap-module-run-fn [{:keys [run-fn] :as cmd}]
  (let [help-cmd (dissoc cmd :run-fn)]
    (fn [{:keys [_raw-args] :as opts}]
      (if (#{"--help" "-h"} (first (or _raw-args [])))
        (do
          (println (command-help help-cmd))
          0)
        (run-fn opts)))))

(defn register-module-command!
  "Register a module-contributed CLI command. Tracked separately so
    clear-module-commands! can remove only module-contributed entries."
  [{:keys [name] :as cmd}]
  (swap! module-command-names* conj name)
  (register! (assoc cmd :run-fn (wrap-module-run-fn cmd))))

(defn clear-module-commands!
  "Remove all module-contributed commands registered via register-module-command!,
   leaving the core built-in commands intact."
  []
  (let [names @module-command-names*]
    (swap! commands #(apply dissoc % names))
    (reset! module-command-names* #{})))

((requiring-resolve 'isaac.module.loader/register-handler!)
 :clear-registrations clear-module-commands!)

;; endregion ^^^^^ Module Command Management ^^^^^