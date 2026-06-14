(ns bb.test-tasks
  (:require
    [babashka.fs :as fs]
    [bb.test-timeout :as tt]
    [gherclj.main :as gherclj]
    [speclj.main :as speclj]))

(defn- module-spec-dirs []
  (->> (seq (.listFiles (java.io.File. "modules")))
       (filter #(.isDirectory %))
       (map #(java.io.File. % "spec"))
       (filter #(.isDirectory %))
       (mapcat #(vector "-D" (.getAbsolutePath %)))))

(defn run-spec! [& args]
  (tt/with-timeout! "spec"
    (fn []
      (if (seq args)
        (apply speclj/-main "-c" "-D" "spec" args)
        (apply speclj/-main (concat ["-c" "-D" "spec"] (module-spec-dirs)))))))

(defn- clean! []
  (fs/delete-tree "target")
  (println "Cleaned target/"))

(defn run-features! [& args]
  (tt/with-timeout! "features"
    (fn []
      (clean!)
      (apply gherclj/-main
        (concat ["-f" "features"]
                ["-s" "isaac.**-steps" "-t" "~slow" "-t" "~wip"]
                args)))))

(defn run-ci! []
  (tt/with-timeout! "ci"
    (fn []
      (run-spec!)
      (run-features!))))

(defn run-jvm-spec! [& args]
  (apply tt/shell! "jvm-spec" (into ["clj" "-M:test:spec"] args)))

(defn run-jvm-features! [& args]
  (apply tt/shell! "jvm-features" (into ["clj" "-M:test:features"] args)))