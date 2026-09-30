(ns isaac.foundation.modules.pins-steps
  (:require
    [gherclj.core :refer [defgiven defthen helper!]]
    [isaac.foundation.modules.pins-helpers]))

(helper! isaac.foundation.modules.pins-helpers)

(defgiven "the gitlibs cache holds {url:string} with a remote that points at a deleted path"
  isaac.foundation.modules.pins-helpers/seed-stale-cache!)

(defthen "the gitlibs cache for {url:string} lives under this checkout's {dir:string} directory"
  isaac.foundation.modules.pins-helpers/cache-under-checkout)
