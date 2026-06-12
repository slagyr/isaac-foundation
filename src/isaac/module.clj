(ns isaac.module)

(defprotocol Module
  (on-startup [this])
  (on-shutdown [this]))

(defn- missing-hook-implementation? [hook error]
  (and (instance? IllegalArgumentException error)
       (re-matches (re-pattern (str "No implementation of method: :" (name hook)
                                    " of protocol: #'isaac\\.module/Module found for: .*"))
                   (.getMessage error))))

(defn run-startup! [this]
  (try
    (on-startup this)
    (catch IllegalArgumentException e
      (if (missing-hook-implementation? :on-startup e)
        nil
        (throw e)))))

(defn run-shutdown! [this]
  (try
    (on-shutdown this)
    (catch IllegalArgumentException e
      (if (missing-hook-implementation? :on-shutdown e)
        nil
        (throw e)))))

(defn module
  ([] (module {}))
  ([{:keys [on-startup on-shutdown]}]
   (reify Module
     (on-startup [this]
       (when on-startup
         (on-startup this)))
     (on-shutdown [this]
       (when on-shutdown
         (on-shutdown this))))))

(defn module? [value]
  (satisfies? Module value))
