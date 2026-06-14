(ns bb.test-timeout
  (:require
    [babashka.process :as process]))

(def ^:const test-timeout-ms 60000)

(defn- timed-out! [label]
  (println (str label " timed out after " (/ test-timeout-ms 1000) "s"))
  (System/exit 124))

(defn with-timeout!
  "Run f under the test-suite timeout. Exits 124 on timeout."
  [label f]
  (let [result (deref (future (f)) test-timeout-ms ::timeout)]
    (when (= result ::timeout)
      (timed-out! label))
    result))

(defn shell!
  "Run a subprocess under the test-suite timeout. Exits 124 on timeout."
  [label & cmd]
  (let [result (deref (future (apply process/shell cmd)) test-timeout-ms ::timeout)]
    (when (= result ::timeout)
      (timed-out! label))
    (when (pos? (:exit result))
      (System/exit (:exit result)))))