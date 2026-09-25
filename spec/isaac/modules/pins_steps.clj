(ns isaac.modules.pins-steps
  (:require
    [gherclj.core :refer [defgiven defthen helper!]]
    [isaac.modules.pins-helpers]))

(helper! isaac.modules.pins-helpers)

(defgiven "the gitlibs cache holds {url:string} with a remote that points at a deleted path"
  isaac.modules.pins-helpers/seed-stale-cache!)

(defthen "the gitlibs cache for {url:string} lives under this checkout's {dir:string} directory"
  isaac.modules.pins-helpers/cache-under-checkout)
