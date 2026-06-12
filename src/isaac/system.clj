(ns isaac.system
  (:refer-clojure :exclude [get])
  (:require
    [isaac.nexus :as nexus]))

(defn get [k]
  (nexus/get k))

(defmacro with-system [runtime & body]
  `(nexus/-with-nexus ~runtime ~@body))

(defmacro with-nested-system [runtime & body]
  `(nexus/-with-nested-nexus ~runtime ~@body))
