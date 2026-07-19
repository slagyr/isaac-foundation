(ns bb.test-tasks
  "Shared native babashka runners for `bb spec` / `bb features` / `bb ci`.

  Homed in isaac-foundation-test-support (deps/root `spec-support`) so every
  isaac module can `:require ([bb.test-tasks :as tests])` without copy-paste
  (isaac-x5ru). Foundation itself resolves the same namespace via its own
  `bb/` path (kept as a thin re-export) so both consumers and foundation
  share one implementation.

  Defaults match the common module layout:
    - specs under `spec/`
    - features under `features/`
    - step namespaces matching `isaac.**-steps`

  Override via dynamic vars when a consumer differs (e.g. extra step ns,
  alternate features dir, or foundation's per-module `modules/*/spec`)."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [bb.test-timeout :as tt]
    [gherclj.main :as gherclj]
    [speclj.main :as speclj]))

(def ^:dynamic *spec-dir* "spec")
(def ^:dynamic *features-dir* "features")
(def ^:dynamic *step-globs* ["isaac.**-steps"])
(def ^:dynamic *jvm-spec-cmd* ["clj" "-M:test:spec"])
(def ^:dynamic *jvm-features-cmd* ["clj" "-M:test:features"])

(defn- module-spec-dirs
  "Foundation-only: discover modules/*/spec dirs when present. Consumers
  without a modules/ tree get an empty vector (no-op)."
  []
  (let [modules (java.io.File. "modules")]
    (if (.isDirectory modules)
      (->> (seq (.listFiles modules))
           (filter #(.isDirectory %))
           (map #(java.io.File. % "spec"))
           (filter #(.isDirectory %))
           (mapcat #(vector "-D" (.getAbsolutePath %))))
      [])))

(defn- run-spec* [& args]
  (if (seq args)
    (apply speclj/-main "-c" "-D" *spec-dir* args)
    (apply speclj/-main (concat ["-c" "-D" *spec-dir*] (module-spec-dirs)))))

(defn run-spec! [& args]
  (tt/with-timeout! "spec" #(apply run-spec* args)))

(defn- clean! []
  (fs/delete-tree "target")
  (println "Cleaned target/"))

(defn- step-args []
  (mapcat (fn [g] ["-s" g]) *step-globs*))

(defn- run-features* [& args]
  (clean!)
  (apply gherclj/-main
    (concat ["-f" *features-dir*]
            (step-args)
            ["-t" "~slow" "-t" "~wip"]
            args)))

(defn run-features! [& args]
  (tt/with-timeout! "features" #(apply run-features* args)))

(defn- run-features-slow* [& args]
  (clean!)
  (apply gherclj/-main
    (concat ["-f" *features-dir*]
            (step-args)
            ["-t" "slow" "-t" "~wip"]
            args)))

(defn run-features-slow! [& args]
  (tt/with-timeout! "features-slow" #(apply run-features-slow* args)))

(defn- check-exit! [{:keys [exit]}]
  (when (pos? exit)
    (System/exit exit)))

(defn run-ci! []
  ;; speclj/gherclj call System/exit; subprocesses keep ci alive between suites.
  (check-exit! (if (seq *command-line-args*)
                   (apply process/shell "bb" "spec" *command-line-args*)
                   (process/shell "bb" "spec")))
  (check-exit! (process/shell "bb" "features")))

(defn run-jvm-spec! [& args]
  (apply tt/shell! "jvm-spec" (into (vec *jvm-spec-cmd*) args)))

(defn run-jvm-features! [& args]
  (apply tt/shell! "jvm-features" (into (vec *jvm-features-cmd*) args)))
