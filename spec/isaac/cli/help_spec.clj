(ns isaac.cli.help-spec
  (:require
    [isaac.cli.api :as api]
    [isaac.cli.help :as sut]
    [isaac.cli.registry :as registry]
    [isaac.main :as main]
    [speclj.core :refer :all]))

(describe "cli help command"

  (it "lists only the root topic in v1"
    (should= ["root"] (sut/topic-names)))

  (it "renders root topic from root-lookup-precedence"
    (let [text (sut/topic-help "root")]
      (should-contain "--root" text)
      (should-contain "ISAAC_ROOT" text)
      (should-contain "~/.config/isaac.edn" text)
      (should-contain "~/.isaac.edn" text)
      (should-contain "~/.isaac" text)))

  (it "returns nil for unknown topics"
    (should-be-nil (sut/topic-help "nope")))

  (it "help text documents usage and Topics section from the topics map"
    (let [text (sut/help-text)]
      (should-contain "Usage: isaac help" text)
      (should-contain "command or topic" text)
      (should-contain "Topics:" text)
      (should-contain "root" text)))

  (it "api/help :help returns the same help text"
    (should= (sut/help-text) (api/help :help)))

  (describe "run"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (binding [*out* (java.io.StringWriter.)]
        (example)))

    (it "with no target prints top-level usage"
      (let [output (with-out-str (should= 0 (sut/run {:_raw-args []})))]
        (should-contain "Usage: isaac [options] <command> [args]" output)
        (should-contain "Commands:" output)
        (should-contain "isaac help help" output)))

    (it "with a known command prints that command's help"
      (registry/register! {:name    "documented"
                           :desc    "A documented command"
                           :usage   "documented [options]"
                           :summary "A documented command"
                           :option-spec [["-v" "--verbose" "Be loud"]]
                           :run-fn  identity})
      (let [output (with-out-str (should= 0 (sut/run {:_raw-args ["documented"]})))]
        (should-contain "Usage: isaac documented [options]" output)
        (should-contain "A documented command" output)))

    (it "prefers command over topic when names collide"
      ;; "help" is a command once registered; help help is self-help, not a topic
      (registry/register-cli-command!
        [:help {:usage     "help [command-or-topic]"
                :summary   "Show help for a command or topic"
                :namespace 'isaac.cli.help}])
      (let [output (with-out-str (should= 0 (sut/run {:_raw-args ["help"]})))]
        (should-contain "Usage: isaac help" output)
        (should-contain "Topics:" output)
        (should-contain "root" output)))

    (it "with a known topic prints the topic text"
      (let [output (with-out-str (should= 0 (sut/run {:_raw-args ["root"]})))]
        (should-contain "--root" output)
        (should-contain "ISAAC_ROOT" output)))

    (it "unknown target uses the command-or-topic message and exits 1"
      (let [output (with-out-str (should= 1 (sut/run {:_raw-args ["bogus"]})))]
        (should-contain "Unknown command or topic: bogus" output)
        (should-not-contain "Unknown command: bogus" output)))))
