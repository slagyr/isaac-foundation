(ns isaac.foundation.modules.setup
  "Domain logic for the `:isaac/setup` berth: a module opts into setup by
   contributing `{:fn <symbol> :description <string>}`, where `:fn` resolves
   to a `(fn [config])` that proposes config writes — `[[<dotted-path>
   <value>] ...]` — plus optional `:hints` (plain lines printed after the
   writes, for things config can't do). Foundation looks the contribution up
   by module id, never overwrites a path that already has a value, and
   applies the rest through the same validated, atomic `set-many!` path
   `isaac config set` uses. No CLI concerns, no stdout — see
   isaac.foundation.modules.cli for `modules install` / `modules upgrade` /
   `modules setup` wiring."
  (:require
    [isaac.foundation.config.mutate :as mutate]
    [isaac.foundation.config.paths :as paths]
    [isaac.foundation.module.lifecycle :as lifecycle]))

(defn find-setup
  "The :isaac/setup descriptor module `id` contributes in `module-index`, or
   nil when it contributes none."
  [module-index id]
  (get-in module-index [id :manifest :isaac/setup]))

(defn- resolve-setup-fn [descriptor]
  (lifecycle/resolve-symbol! (:fn descriptor)))

(defn proposed-writes
  "Calls descriptor's :fn with the current `config`, returning
   {:writes [[path value] ...] :hints [...]}."
  [descriptor config]
  ((resolve-setup-fn descriptor) config))

(defn- value-at [config path-str]
  (reduce (fn [value [kind v]]
            (if (nil? value)
              (reduced nil)
              (case kind
                :key   (get value v)
                :str   (get value v)
                :index (when (sequential? value) (nth value v nil)))))
          config
          (paths/parse-path-segments path-str)))

(defn missing-writes
  "Filters `writes` (`[[path value] ...]`) down to the pairs `config` has no
   value at yet — setup adds what is missing and never overwrites."
  [config writes]
  (vec (remove (fn [[path _]] (some? (value-at config path))) writes)))

(defn apply-writes!
  "Applies `writes` (`[[path value] ...]`) as one atomic set-many! batch."
  [root writes]
  (mutate/set-many! root (mapv (fn [[path value]] {:op :set :path path :value value}) writes)))
