(ns isaac.logs.streams
  "Registry of viewable log streams. Modules declare their stream(s) via the
   :isaac/log-stream berth (name -> {:file :description}); the per-entry
   factory `register-stream!` merges each into a nexus-held registry that
   `isaac logs` reads to list and select streams. Foundation stays neutral —
   it hardcodes no file names; every stream is listable because a module
   declared it, decoupled from whether the file exists or its writer is
   active in this process."
  (:require
    [isaac.nexus :as nexus]))

(def ^:private streams-key :isaac/log-streams)

(defn register-stream!
  "Per-entry factory for the :isaac/log-stream berth. The berth is a map of
   stream id -> {:file :description}, so each entry arrives as [id decl];
   merges it into the nexus-held registry under its keyword name (last-wins,
   per the module-contribution collision policy)."
  [[id decl]]
  (nexus/register! [streams-key (keyword id)]
                   (select-keys decl [:file :description])))

(defn streams
  "The registered streams as {stream-keyword {:file :description}}."
  []
  (or (nexus/get streams-key) {}))

(defn set-streams!
  "Replace the whole registry with `m` ({stream-keyword {:file :description}}).
   The test seam behind the 'the registered log streams' step."
  [m]
  (nexus/register! [streams-key] m))

(defn clear-streams!
  "Drop every registered stream."
  []
  (nexus/deregister! [streams-key]))
