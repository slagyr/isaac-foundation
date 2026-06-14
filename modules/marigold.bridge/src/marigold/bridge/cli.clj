(ns marigold.bridge.cli
  "Per-entry factory for the :marigold.bridge/cli berth. Each
   contribution entry is a map with at least :name and a symbol-valued
   :run-fn. The factory resolves run-fn and registers a command with
   the top-level isaac.cli.registry — same intent as
   isaac.cli.registry/register-cli-command! but scoped to bridge's own berth so
   the test can exercise berth machinery without coupling to
   isaac.foundation's :isaac/cli berth.

   Registry is resolved at call time (not :require) so module boundary
   enforcement stays static-Tier-1."
  (:require
    [clojure.string]))

(defn- maybe-resolve [sym]
  (when (symbol? sym) (some-> sym requiring-resolve var-get)))

(defn- registry-register! [cmd]
  ((requiring-resolve 'isaac.cli.registry/register!) cmd))

(defn register-cli-command!
  [{:keys [name desc run-fn]}]
  (registry-register! (cond-> {:name name}
                        desc   (assoc :summary desc)
                        run-fn (assoc :run-fn (maybe-resolve run-fn)))))