(ns isaac.module.protocol)

(defprotocol Module
  (on-load [this])
  (on-unload [this]))

(defn- missing-hook-implementation? [hook error]
  (and (instance? IllegalArgumentException error)
       (re-matches (re-pattern (str "No implementation of method: :" (name hook)
                                    " of protocol: #'isaac\\.module\\.protocol/Module found for: .*"))
                   (.getMessage error))))

(defn run-load! [this]
  (try
    (on-load this)
    (catch IllegalArgumentException e
      (if (missing-hook-implementation? :on-load e)
        nil
        (throw e)))))

(defn run-unload! [this]
  (try
    (on-unload this)
    (catch IllegalArgumentException e
      (if (missing-hook-implementation? :on-unload e)
        nil
        (throw e)))))

(defn module
  ([] (module {}))
  ([{:keys [on-load on-unload]}]
   (reify Module
     (on-load [this]
       (when on-load
         (on-load this)))
     (on-unload [this]
       (when on-unload
         (on-unload this))))))

(defn module? [value]
  (satisfies? Module value))