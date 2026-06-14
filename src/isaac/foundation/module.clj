(ns isaac.foundation.module
  "Builtin `:isaac.foundation` module factory — manifest `:factory` entry only."
  (:require
    [isaac.module.protocol :as module]))

(defn create-module []
  (module/module))