(ns marigold.bridge.cli
  "Per-entry factory for the :marigold.bridge/cli berth. Each
   contribution entry is a map with at least :name and a symbol-valued
   :run-fn. The factory resolves run-fn and registers a command with
   the top-level isaac.cli.registry — same intent as
   isaac.cli.registry/register-cli-command! but scoped to bridge's own berth so
   the test can exercise berth machinery without coupling to
   isaac.core's :isaac/cli berth."
  (:require
    [isaac.cli.registry :as registry]))

(defn- maybe-resolve [sym]
  (when (symbol? sym) (some-> sym requiring-resolve var-get)))

(defn register-cli-command!
  [{:keys [name desc run-fn]}]
  (registry/register! (cond-> {:name name}
                        desc   (assoc :summary desc)
                        run-fn (assoc :run-fn (maybe-resolve run-fn)))))
