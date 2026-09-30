(ns isaac.foundation.module
  "Builtin `:isaac.foundation` module factory — manifest `:factory` entry only."
  (:require
    [isaac.foundation.module.protocol :as module]))

(defn create-module []
  (module/module))