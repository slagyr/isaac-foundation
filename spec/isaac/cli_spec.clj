(ns isaac.cli-spec
  (:require
    [clojure.edn :as edn]
    [isaac.cli.api :as api]
    [isaac.cli.registry :as sut]
    [isaac.main :as main]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(def ^:dynamic *fs* nil)

(def sample-subcommands
  [{:name "install" :summary "Install the thing"}
   {:name "logs"    :summary "Tail the logs"}])

(def sample-received-args (atom nil))

;; this spec ns doubles as the :namespace implementing the :svc command
(defmethod api/subcommands :svc [_id] sample-subcommands)
(defmethod api/run :svc [_id opts]
  (reset! sample-received-args (:_raw-args opts))
  0)

;; region ----- Registry -----

(describe "CLI Registry"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (let [saved       @(deref #'sut/commands)
          saved-berth @(deref #'sut/berth-command-names*)]
      (reset! @#'sut/commands {})
      (reset! @#'sut/berth-command-names* #{})
      (example)
      (reset! @#'sut/commands saved)
      (reset! @#'sut/berth-command-names* saved-berth)))

  (describe "register!"

    (it "registers a command by name"
      (sut/register! {:name "test-cmd" :summary "A test" :run-fn identity})
      (should-not-be-nil (sut/get-command "test-cmd")))

    (it "stores all command fields"
      (let [cmd {:name    "greet"
                 :usage   "greet [name]"
                 :summary    "Say hello"
                 :option-spec [["-l" "--loud" "Shout it"]]
                 :run-fn  identity}]
        (sut/register! cmd)
        (let [stored (sut/get-command "greet")]
          (should= "greet" (:name stored))
          (should= "greet [name]" (:usage stored))
          (should= "Say hello" (:summary stored))
          (should= [["-l" "--loud" "Shout it"]] (:option-spec stored)))))

    (it "overwrites a command with the same name"
      (sut/register! {:name "dup" :summary "First" :run-fn identity})
      (sut/register! {:name "dup" :summary "Second" :run-fn identity})
      (should= "Second" (:summary (sut/get-command "dup")))))

  (describe "register-module-command!"

    (it "wraps module commands so --help prints generic command help"
      (let [called? (atom false)]
        (sut/register-module-command! {:name   "greet"
                                       :usage  "greet"
                                       :summary   "Print a greeting"
                                       :run-fn (fn [_]
                                                 (reset! called? true)
                                                 0)})
        (let [output (with-out-str ((:run-fn (sut/get-command "greet")) {:_raw-args ["--help"]}))]
          (should-contain "Usage: isaac greet" output)
          (should-contain "Print a greeting" output)
          (should= false @called?))))

    (it "delegates to the underlying run-fn for normal invocation"
      (let [called? (atom nil)]
        (sut/register-module-command! {:name   "greet"
                                       :usage  "greet"
                                       :summary   "Print a greeting"
                                       :run-fn (fn [opts]
                                                 (reset! called? opts)
                                                 7)})
        (should= 7 ((:run-fn (sut/get-command "greet")) {:_raw-args [] :flag true}))
        (should= {:_raw-args [] :flag true} @called?))))

  (describe "register-cli-command!"

    (it "renders api/subcommands implementations in command-help"
      (sut/register-cli-command! [:svc {:usage     "svc <subcommand>"
                                        :summary   "Manage a service"
                                        :namespace 'isaac.cli-spec}])
      (let [help (sut/command-help (sut/get-command "svc"))]
        (should-contain "Subcommands:" help)
        (should-contain "install" help)
        (should-contain "Tail the logs" help)))

    (it "dispatches run through api/run, letting it handle subcommand args"
      (reset! sample-received-args nil)
      (sut/register-cli-command! [:svc {:usage     "svc <subcommand>"
                                        :summary   "Manage a service"
                                        :namespace 'isaac.cli-spec}])
      (should= 0 ((:run-fn (sut/get-command "svc")) {:_raw-args ["install" "--flag"]}))
      (should= ["install" "--flag"] @sample-received-args))

    (it "reports a structured failure when a command implements no api/run"
      (sut/register-cli-command! [:ghost {:usage     "ghost"
                                          :summary   "未implemented"
                                          :namespace 'isaac.cli-spec}])
      (let [err (StringWriter.)]
        (binding [*err* err]
          (should= 1 ((:run-fn (sut/get-command "ghost")) {:_raw-args []})))
        (should-contain "implements no isaac.cli.api/run" (str err))))

    (it "a later registration of the same id overrides the earlier one (last-wins)"
      (sut/register-cli-command! [:svc {:usage "svc" :summary "first"  :namespace 'isaac.cli-spec}])
      (sut/register-cli-command! [:svc {:usage "svc" :summary "second" :namespace 'isaac.cli-spec}])
      (should= "second" (:summary (sut/get-command "svc")))))

  (describe "get-command"

    (it "returns nil for unknown command"
      (should-be-nil (sut/get-command "nonexistent")))

    (it "returns the registered command"
      (sut/register! {:name "found" :summary "Here" :run-fn identity})
      (should= "Here" (:summary (sut/get-command "found")))))

  (describe "all-commands"

    (it "returns empty list when nothing registered"
      (should= [] (sut/all-commands)))

    (it "returns all commands sorted by name"
      (sut/register! {:name "beta" :summary "B" :run-fn identity})
      (sut/register! {:name "alpha" :summary "A" :run-fn identity})
      (sut/register! {:name "gamma" :summary "G" :run-fn identity})
      (let [names (map :name (sut/all-commands))]
        (should= ["alpha" "beta" "gamma"] names))))

  (describe "command-help"

    (it "formats help text with usage, summary, and options"
      (let [cmd {:name    "chat"
                 :usage   "chat [options]"
                 :summary    "Start a chat"
                 :option-spec [["-m" "--model MODEL" "Model to use"]
                               ["-r" "--resume" "Resume session"]]}
            help (sut/command-help cmd)]
        (should-contain "Usage: isaac chat [options]" help)
        (should-contain "Start a chat" help)
        (should-contain "Options:" help)
        (should-contain "--model MODEL" help)
        (should-contain "--resume" help)))

    (it "renders help without options"
      (let [cmd  {:name "info" :usage "info" :summary "Show info" :option-spec []}
            help (sut/command-help cmd)]
        (should-contain "Usage: isaac info" help)
        (should-contain "Show info" help)
        (should-not-contain "Options:" help)))

    (it "renders subcommands when present"
      (let [cmd  {:name "service"
                  :usage "service [options] <subcommand>"
                  :summary "Manage Isaac as a background service"
                  :subcommands [{:name "install" :summary "Install Isaac as a launchd service"}
                                {:name "logs" :summary "Tail Isaac service logs"}]}
            help (sut/command-help cmd)]
        (should-contain "Usage: isaac service [options] <subcommand>" help)
        (should-contain "Subcommands:" help)
        (should-contain "install" help)
        (should-contain "Tail Isaac service logs" help)))))

;; endregion ^^^^^ Registry ^^^^^

;; region ----- Init -----

(def test-home "/test/init")

(defn- slurp-edn [path]
  (edn/read-string (fs/slurp *fs* path)))

(describe "CLI Init"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (let [mem (fs/mem-fs)]
      (nexus/-with-nested-nexus {:fs mem}
        (binding [*out* (StringWriter.)
                  *err* (StringWriter.)
                  *fs*  mem]
          ;; Phase 4 of the berth epic: init no longer side-effect-
          ;; registers at isaac.cli.registry load time — it's a foundation
          ;; manifest :isaac/cli contribution. Process core's berths so
          ;; tests see init in the registry without going through
          ;; main/run first.
          (module-loader/process-manifest-berths! (module-loader/builtin-index))
          (example)))))

  (it "registers the init command"
    (should-not-be-nil (sut/get-command "init")))

  (it "scaffolds the default config files in a fresh root"
    (should= 0 (sut/init-run {:root test-home}))
    (should= {:defaults {:crew :main :model :llama}
               :tz "America/Chicago"
               :prefer-entity-files true}
              (slurp-edn (str test-home "/config/isaac.edn")))
    (should= (str "---\n"
                  "model: \"llama\"\n"
                  "---\n\n"
                  "You are Isaac, a helpful AI assistant.")
             (fs/slurp *fs* (str test-home "/config/crew/main.md")))
    (should= {:model "llama3.2" :provider :ollama}
             (slurp-edn (str test-home "/config/models/llama.edn")))
    (should= {:base-url "http://localhost:11434" :api :ollama}
              (slurp-edn (str test-home "/config/providers/ollama.edn")))
    (should= (str "---\n"
                  "expr: \"*/30 * * * *\"\n"
                  "crew: \"main\"\n"
                  "---\n\n"
                  "Heartbeat. Anything worth noting?")
             (fs/slurp *fs* (str test-home "/config/cron/heartbeat.md"))))

  (it "prints the scaffold summary and ollama setup instructions on success"
    (should= 0 (sut/init-run {:root test-home}))
    (should= (str "Isaac initialized at " test-home ".\n\n"
                  "Created:\n"
                  "  config/isaac.edn\n"
                  "  config/crew/main.md\n"
                  "  config/models/llama.edn\n"
                  "  config/providers/ollama.edn\n"
                  "  config/cron/heartbeat.md\n\n"
                  "Isaac uses Ollama locally. If you don't have it:\n\n"
                  "  brew install ollama\n"
                  "  ollama serve &\n"
                  "  ollama pull llama3.2\n\n"
                  "Then try:\n\n"
                  "  isaac prompt -m \"hello\"\n")
             (str *out*)))

  (it "refuses when a config already exists"
    (fs/mkdirs *fs* (str test-home "/config"))
    (fs/spit   *fs* (str test-home "/config/isaac.edn") "{}")
    (should= 1 (sut/init-run {:root test-home}))
    (should= (str "config already exists at " test-home "/config/isaac.edn; edit it directly.\n")
             (str *err*)))

  (it "appears in top-level help output"
    (binding [main/*extra-opts* {:fs *fs*}]
      (let [output (with-out-str (should= 0 (main/run ["--help"])))]
        (should-contain "init" output))))

  (it "scaffolds config under an explicit root flag"
    (binding [main/*extra-opts* {:fs *fs*}]
      (should= 0 (main/run ["--root" test-home "init"])))
    (should (fs/exists? *fs* (str test-home "/config/isaac.edn"))))

  (it "accepts an explicit fs via opts"
    (let [mem (fs/mem-fs)]
      (should= 0 (sut/init-run {:root test-home :fs mem}))
      (should= {:defaults {:crew :main :model :llama}
                :tz "America/Chicago"
                :prefer-entity-files true}
               (edn/read-string (fs/slurp mem (str test-home "/config/isaac.edn")))))))

;; endregion ^^^^^ Init ^^^^^
