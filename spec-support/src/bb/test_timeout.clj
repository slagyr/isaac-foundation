(ns bb.test-timeout
  "Shared test-suite timeout helpers for isaac module bb.edn tasks.
  Homed in isaac-foundation-test-support (deps/root spec-support) so
  every consumer can :require it without copy-paste (isaac-x5ru)."
  (:require
    [babashka.process :as process]))

(def default-test-timeout-ms 60000)

(defn test-timeout-ms
  "Per-suite budget in ms. ISAAC_TEST_TIMEOUT_MS overrides the 60s default
   (CI runners are slower than dev boxes; a green suite must not exit 124)."
  []
  (let [raw (System/getenv "ISAAC_TEST_TIMEOUT_MS")]
    (or (when (and raw (re-matches #"\d+" raw)) (Long/parseLong raw))
        default-test-timeout-ms)))

(defn- timed-out! [label]
  (println (str label " timed out after " (/ (test-timeout-ms) 1000) "s"
                " (override with ISAAC_TEST_TIMEOUT_MS)"))
  (System/exit 124))

(defn- handle-babashka-exit!
  "babashka turns System/exit into ExceptionInfo inside futures.
   Exit 0 means success — return without killing the parent task (e.g. ci).
   Non-zero exits propagate."
  [^java.util.concurrent.ExecutionException e]
  (let [cause (.getCause e)]
    (when (instance? clojure.lang.ExceptionInfo cause)
      (when-some [code (:babashka/exit (ex-data cause))]
        (if (zero? code)
          nil
          (System/exit code))))
    (throw cause)))

(defn with-timeout!
  "Run f under the test-suite timeout. Exits 124 on timeout."
  [label f]
  (try
    (let [result (deref (future (f)) (test-timeout-ms) ::timeout)]
      (when (= result ::timeout)
        (timed-out! label))
      result)
    (catch java.util.concurrent.ExecutionException e
      (handle-babashka-exit! e))))

(defn shell!
  "Run a subprocess under the test-suite timeout. Exits 124 on timeout."
  [label & cmd]
  (try
    (let [result (deref (future (apply process/shell cmd)) (test-timeout-ms) ::timeout)]
      (when (= result ::timeout)
        (timed-out! label))
      (when (pos? (:exit result))
        (System/exit (:exit result))))
    (catch java.util.concurrent.ExecutionException e
      (handle-babashka-exit! e))))
