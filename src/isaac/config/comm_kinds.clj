(ns isaac.config.comm-kinds
  "Enumerate user-configurable comm kinds from the manifest index."
  (:require
    [isaac.module.loader :as module-loader]))

(defn comm-kinds
  "Returns sorted user-configurable comm kind names from the given module index.
   Filters out entries where :configurable? is false. With no args, falls back
   to the builtin manifest index."
  ([] (comm-kinds (module-loader/builtin-index)))
  ([module-index]
   (->> (vals module-index)
        (mapcat (fn [entry]
                  (or (get-in entry [:manifest :isaac.server/comm])
                      (get-in entry [:manifest :isaac.agent/comm]))))
        (remove (fn [[_ v]] (false? (:configurable? v))))
        (map (fn [[k _]] (name k)))
        sort
        distinct
        vec)))