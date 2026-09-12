(ns isaac.foundation.git-helpers
  (:require
    [babashka.process :as process]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g]))

(defonce ^:private fixture-repositories* (atom #{}))

(defn- delete-tree! [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (.delete file)))))

(g/after-scenario
  (fn []
    (doseq [path @fixture-repositories*]
      (delete-tree! path))
    (reset! fixture-repositories* #{})))

(defn- repository-path [name]
  (str (System/getProperty "user.dir") "/" name))

(defn- git! [path & args]
  (let [{:keys [err exit out]} (apply process/shell
                                      {:dir path :out :string :err :string :continue true}
                                      "git" args)]
    (when (pos? exit)
      (throw (ex-info (str "git fixture command failed: " (str/join " " args) "\n" err)
                      {:exit exit})))
    (str/trim out)))

(defn- add-commit! [name message]
  (let [path       (repository-path name)
        commit-no  (inc (count (get-in (g/get :git-fixtures) [name :commits])))
        marker     (str path "/commit.txt")]
    (spit marker (str commit-no " " message "\n") :append true)
    (git! path "add" "commit.txt")
    (git! path "commit" "-m" message)
    (let [sha (git! path "rev-parse" "HEAD")]
      (g/assoc-in! [:git-fixtures name :commits message] sha)
      sha)))

(defn repository-with-commits! [name table]
  (let [path (repository-path name)]
    (delete-tree! path)
    (.mkdirs (io/file path))
    (swap! fixture-repositories* conj path)
    (git! path "init" "--quiet")
    (git! path "config" "user.name" "Isaac Fixture")
    (git! path "config" "user.email" "fixture@isaac.test")
    (g/assoc-in! [:git-fixtures name] {:path path :commits {}})
    (doseq [row (:rows table)]
      (add-commit! name (first row)))))

(defn repository-gains-commit! [name message]
  (add-commit! name message))

(defn interpolate-shas [text]
  (str/replace text #"\{sha of \"([^\"]+)\"\}"
               (fn [[placeholder message]]
                 (or (some (fn [[_ {:keys [commits]}]] (get commits message))
                           (g/get :git-fixtures))
                     placeholder))))
