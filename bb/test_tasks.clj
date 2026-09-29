(ns bb.test-tasks
  "Foundation-local re-export of the shared native runners.
  Canonical home: isaac-foundation-test-support (spec-support/src/bb/).
  Kept so foundation's :paths [\".\"] continue to resolve bb.test-tasks
  without depending on its own test-support coord (isaac-x5ru).

  Prefer editing the canonical copy under spec-support/src/bb/ and
  mirroring here when foundation-local resolution is still needed."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [bb.test-timeout :as tt]
    [gherclj.main :as gherclj]
    [gherclj.parser :as parser]
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

(defn- slow-scenario-locations []
  (for [{:keys [source scenarios]} (parser/parse-features-dir *features-dir*)
        {:keys [line tags]} scenarios
        :when (and (some #{"slow"} tags)
                   (not (some #{"wip"} tags)))]
    (str *features-dir* "/" source ":" line)))

(defn- run-features-slow* [& args]
  ;; tools.deps/add-deps changes the current process classpath permanently.
  ;; A fresh subprocess per scenario keeps its builtin manifests out of the
  ;; subsequent scenario's schema and module index. Clean target before each
  ;; run and force a fresh bb classpath: cached :local/root gitlib paths are
  ;; otherwise invalidated when the previous scenario's target is deleted.
  (doseq [location (if (seq args) args (slow-scenario-locations))]
    (clean!)
    (tt/shell! "features-slow" "bb" "-Sforce" "gherclj" "-t" "slow" "-t" "~wip" location)))

(defn run-features-slow! [& args]
  (apply run-features-slow* args))

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
