(ns isaac.main-spec
  (:require
    [clojure.edn :as edn]
    [isaac.cli.api :as cli-api]
    [isaac.cli.registry :as registry]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.module.loader :as module-loader]
    [isaac.main :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn make-greet-command []
  {:name        "greet"
   :usage       "greet"
   :option-spec []
   :run-fn      (fn [_] 0)})

(defmethod cli-api/run :greet [_id _opts] 0)

(defn- foundation-manifest-cli-command-names []
  (->> (keys (:isaac/cli (edn/read-string (slurp "src/isaac-manifest.edn"))))
       (map name)
       set))

(describe "Main CLI"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (binding [*out* (java.io.StringWriter.)]
      (example)))

  (describe "run"

    #_{:clj-kondo/ignore [:private-call]}
    (redefs-around [sut/register-module-cli-commands! (fn [& _] nil)])

    (it "prints usage and returns 0 when no args"
      (should= 0 (sut/run [])))

    (it "prints usage and returns 0 for blank command"
      (should= 0 (sut/run [""])))

    (it "prints usage and returns 0 for help command"
      (should= 0 (sut/run ["help"])))

    (it "returns 1 for unknown command"
      (should= 1 (sut/run ["nonexistent-command-xyz"])))

    (it "dispatches to a registered command"
      (let [received (atom nil)]
        (registry/register! {:name   "test-dispatch"
                             :desc   "Test"
                             :usage  "test-dispatch"
                             :option-spec []
                             :run-fn (fn [opts] (reset! received opts) 0)})
        (should= 0 (sut/run ["test-dispatch" "--agent" "bot"]))
        (should= ["--agent" "bot"] (:_raw-args @received))))

    (it "injects root resolved from the XDG pointer file"
      (let [received (atom nil)
            mem      (fs/mem-fs)]
        (registry/register! {:name   "pointer-dispatch"
                             :desc   "Test"
                             :usage  "pointer-dispatch"
                             :option-spec []
                             :run-fn (fn [opts] (reset! received opts) 0)})
        (binding [root/*user-home*  "/tmp/user"
                  sut/*extra-opts*  {:fs mem}]
          (fs/mkdirs mem "/tmp/user/.config")
          (fs/spit   mem "/tmp/user/.config/isaac.edn" "{:root \"/tmp/pointer\"}")
          (should= 0 (sut/run ["pointer-dispatch"])))
        (should= "/tmp/pointer" (:root @received))))

    (it "lets the top-level --root flag override the pointer file"
      (let [received (atom nil)
            mem      (fs/mem-fs)]
        (registry/register! {:name   "root-flag-dispatch"
                             :desc   "Test"
                             :usage  "root-flag-dispatch"
                             :option-spec []
                             :run-fn (fn [opts] (reset! received opts) 0)})
        (binding [root/*user-home* "/tmp/user"
                  sut/*extra-opts* {:fs mem}]
          (fs/mkdirs mem "/tmp/user/.config")
          (fs/spit   mem "/tmp/user/.config/isaac.edn" "{:root \"/tmp/pointer\"}")
          (should= 0 (sut/run ["--root" "/tmp/flag" "root-flag-dispatch"])))
        (should= "/tmp/flag" (:root @received))
        (should= [] (:_raw-args @received))))

    (it "returns exit code from command run-fn"
      (registry/register! {:name   "fail-cmd"
                           :desc   "Fails"
                           :usage  "fail-cmd"
                           :option-spec []
                           :run-fn (fn [_] 42)})
      (should= 42 (sut/run ["fail-cmd"])))

    (it "returns 0 when run-fn returns nil"
      (registry/register! {:name   "nil-cmd"
                           :desc   "Returns nil"
                           :usage  "nil-cmd"
                           :option-spec []
                           :run-fn (fn [_] nil)})
      (should= 0 (sut/run ["nil-cmd"])))

    (it "shows help for a known command via 'help <cmd>'"
      (registry/register! {:name    "documented"
                           :desc    "A documented command"
                           :usage   "documented [options]"
                           :option-spec [["-v" "--verbose" "Be loud"]]
                           :run-fn  identity})
      (should= 0 (sut/run ["help" "documented"])))

    (it "returns 1 for 'help <unknown>'"
      (should= 1 (sut/run ["help" "no-such-command-xyz"])))

    (it "shows help when --help flag is passed to a command"
      (let [received (atom nil)]
        (registry/register! {:name        "help-flag-test"
                             :desc        "Has help"
                             :usage       "help-flag-test"
                             :option-spec []
                             :run-fn      (fn [opts]
                                            (reset! received opts)
                                            0)})
        (should= 0 (sut/run ["help-flag-test" "--help"]))
        (should= ["--help"] (:_raw-args @received))))

    (it "prints usage and returns 0 for top-level --help"
      (should= 0 (sut/run ["--help"])))

    (it "prints usage and returns 0 for top-level -h"
      (should= 0 (sut/run ["-h"])))

    (it "documents global options in top-level usage output"
      (let [output (with-out-str (should= 0 (sut/run ["--help"])))]
        (should-contain "Usage: isaac [options] <command> [args]" output)
        (should-contain "Global Options:" output)
        (should-contain "--root <dir>    Isaac root directory (default: ~/.isaac)" output)
        (should-not-contain "May also be set" output)
        (should-not-contain "~/.config/isaac.edn" output)
        (should-contain "--help, -h" output)
        (should-contain "Commands:" output))))

  (describe "alias resolution"

    #_{:clj-kondo/ignore [:private-call]}
    (redefs-around [sut/register-module-cli-commands! (fn [& _] nil)])

    (it "resolves 'models auth' to 'auth'"
      (should= ["auth"] (vec (@#'sut/resolve-alias ["models" "auth"]))))

    (it "does not resolve non-alias prefixes"
      (should= 1 (sut/run ["models" "something-else"]))))

  (describe "dispatch payload"

    #_{:clj-kondo/ignore [:private-call]}
    (redefs-around [sut/register-module-cli-commands! (fn [& _] nil)])

    (it "includes _raw-args"
      (let [received (atom nil)]
        (registry/register! {:name   "raw-test"
                             :desc   "Test"
                             :usage  "raw-test"
                             :option-spec []
                             :run-fn (fn [opts] (reset! received opts) 0)})
        (sut/run ["raw-test" "--agent" "x" "extra"])
        (should= ["--agent" "x" "extra"] (:_raw-args @received))))

    (it "includes bound extra opts"
      (let [received (atom nil)]
        (registry/register! {:name        "extra-test"
                             :desc        "Test"
                             :usage       "extra-test"
                             :option-spec []
                             :run-fn      (fn [opts] (reset! received opts) 0)})
        (binding [sut/*extra-opts* {:root (str (System/getProperty "user.dir") "/target/test-state")}]
          (sut/run ["extra-test"]))
        (should= (str (System/getProperty "user.dir") "/target/test-state") (:root @received)))))

  (describe "substitute-env"

    (it "expands ${VAR} strings using loader/env"
      (with-redefs [loader/env (fn [v] (when (= v "MY_ROOT") "/resolved/path"))]
        (should= {:local/root "/resolved/path"}
                 (@#'sut/substitute-env {:local/root "${MY_ROOT}"}))))

    (it "leaves unknown variables unexpanded"
      (with-redefs [loader/env (constantly nil)]
        (should= "${UNKNOWN}" (@#'sut/substitute-env "${UNKNOWN}"))))

    (it "recurses into nested maps and vectors"
      (with-redefs [loader/env (fn [v] (when (= v "X") "y"))]
        (should= {:a {:b "y"} :c ["y" 1]}
                 (@#'sut/substitute-env {:a {:b "${X}"} :c ["${X}" 1]})))))

  (describe "register-module-cli-commands!"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (registry/clear-module-commands!)
        (example)
        (registry/clear-module-commands!)))

    (it "reads module cli config from an explicit fs and registers berth contributions"
      (let [mem         (fs/mem-fs)
            config-path "/tmp/home/.isaac/config/isaac.edn"]
        (fs/mkdirs mem "/tmp/home/.isaac/config")
        (fs/spit mem config-path "{:modules {:hello {}}}")
        (with-redefs [module-loader/discover!
                      (fn [config context]
                        (should= {:modules {:hello {}}} config)
                        (should= {:cwd (System/getProperty "user.dir")} context)
                        ;; Mock both the berth declaration (on foundation) and a
                        ;; contribution from the user module.
                        {:index {:isaac.foundation {:manifest {:id      :isaac.foundation
                                                         :version "1"
                                                         :berths  {:isaac/cli {:description "CLI commands"
                                                                         :schema       {:type       :map
                                                                                        :key-spec   {:type :keyword}
                                                                                        :value-spec {:type    :map
                                                                                                     :factory 'isaac.cli.registry/register-cli-command!}}}}}}
                                 :hello      {:manifest {:id      :hello
                                                         :version "1"
                                                         :isaac/cli {:greet {:summary "Greets"
                                                                             :usage "greet"
                                                                             :namespace 'isaac.main-spec}}}}}})]
          (@#'sut/register-module-cli-commands! "/tmp/home/.isaac" mem nil))
        (should-not-be-nil (registry/get-command "greet"))
        (should= "Greets" (:summary (registry/get-command "greet")))))

    (it "declares foundation command cli contributions in the manifest"
      (should= #{"init" "logs" "config" "modules"} (foundation-manifest-cli-command-names)))

    (it "installs the active fs into runtime init"
      (let [mem       (fs/mem-fs)
            init-opts (atom nil)]
        (registry/register! {:name        "fs-init"
                             :desc        "Test"
                             :usage       "fs-init"
                             :option-spec []
                             :run-fn      (fn [_] 0)})
        #_{:clj-kondo/ignore [:private-call]}
        (with-redefs [nexus/init!       (fn
                                           ([] (reset! init-opts {}))
                                           ([opts] (reset! init-opts opts)))
                      nexus/register!   (fn [& _])
                      sut/register-module-cli-commands! (fn [& _] nil)
                      root/resolve-root  (fn [& _] "/tmp/home")]
          (binding [sut/*extra-opts* {:fs mem}]
            (should= 0 (sut/run ["fs-init"]))))
        (should= mem (:fs @init-opts))))))
