(ns bb.test-tasks
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [bb.test-timeout :as tt]
    [gherclj.main :as gherclj]
    [speclj.main :as speclj]))

(defn- module-spec-dirs []
  (->> (seq (.listFiles (java.io.File. "modules")))
       (filter #(.isDirectory %))
       (map #(java.io.File. % "spec"))
       (filter #(.isDirectory %))
       (mapcat #(vector "-D" (.getAbsolutePath %)))))

(defn- run-spec* [& args]
  (if (seq args)
    (apply speclj/-main "-c" "-D" "spec" args)
    (apply speclj/-main (concat ["-c" "-D" "spec"] (module-spec-dirs)))))

(defn run-spec! [& args]
  (tt/with-timeout! "spec" #(apply run-spec* args)))

(defn- clean! []
  (fs/delete-tree "target")
  (println "Cleaned target/"))

(defn- run-features* [& args]
  (clean!)
  (apply gherclj/-main
    (concat ["-f" "features"]
            ["-s" "isaac.**-steps" "-t" "~slow" "-t" "~wip"]
            args)))

(defn run-features! [& args]
  (tt/with-timeout! "features" #(apply run-features* args)))

(defn- run-features-slow* [& args]
  (clean!)
  (apply gherclj/-main
    (concat ["-f" "features"]
            ["-s" "isaac.**-steps" "-t" "slow" "-t" "~wip"]
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
  (apply tt/shell! "jvm-spec" (into ["clj" "-M:test:spec"] args)))

(defn run-jvm-features! [& args]
  (apply tt/shell! "jvm-features" (into ["clj" "-M:test:features"] args)))