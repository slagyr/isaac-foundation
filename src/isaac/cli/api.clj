(ns isaac.cli.api
  "The foundation's CLI command surface. A command's manifest entry
   carries data only — :usage, :summary, and the :namespace that
   implements the command. Behavior attaches by implementing these
   multimethods, keyed by command id. `run` is required; the others
   default to nil (no options, no subcommands, generated help)."
  (:refer-clojure :exclude [run]))

(defmulti run
  "Execute command `id` with `opts`. Every command must implement this;
   the registry reports a structured error when dispatch would miss."
  (fn [id _opts] id))

(defmulti option-spec
  "clojure.tools.cli option spec for command `id`, rendered in the
   generated help. Default: nil (the command takes no options)."
  identity)

(defmethod option-spec :default [_id] nil)

(defmulti subcommands
  "Subcommand descriptors ({:name ... :summary ...}) for command `id`,
   rendered in the generated help. Default: nil."
  identity)

(defmethod subcommands :default [_id] nil)

(defmulti help
  "Full custom help text for command `id`. Default: nil — the registry
   generates a help page from :usage/:summary plus option-spec and
   subcommands."
  identity)

(defmethod help :default [_id] nil)
