(ns isaac.log-file-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.log.file :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private test-root "/tmp/isaac-log-file-test")

(defn- log-line [n day]
  (format "{:ts \"2026-%sT12:00:%02dZ\" :level :info :event :e%02d}"
          day (mod n 60) n))

(defn- write-log! [fs* path n day]
  (let [lines (->> (range 1 (inc n))
                   (map #(log-line % day))
                   (str/join "\n"))]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path lines)))

(defn- read-lines [fs* path]
  (when (fs/exists? fs* path)
    (remove str/blank? (str/split-lines (or (fs/slurp fs* path) "")))))

(describe "log file lifecycle"

  (around [it]
    (binding [sut/*now* (java.time.Instant/parse "2026-06-29T12:00:00Z")]
      (let [mem (fs/mem-fs)]
        (nexus/-with-nested-nexus {:fs mem}
          (it)))))

  (describe "server log path"

    (it "places the active log under <root>/logs/server.log"
      (should= (str test-root "/logs/server.log")
               (sut/server-log-path test-root))))

  (describe "cli log path"

    (it "places the CLI log under <root>/logs/cli.log"
      (should= (str test-root "/logs/cli.log")
               (sut/cli-log-path test-root))))

  (describe "daily rollover"

    (it "archives server.log to server-YYYYMMDD.log when the active day changes"
      (let [fs*         (nexus/get :fs)
            active      (sut/server-log-path test-root)
            logs-dir    (sut/logs-dir test-root)
            archive     (str logs-dir "/server-20260628.log")]
        (write-log! fs* active 3 "06-28")
        (sut/prepare-active-log! fs* test-root {:tz "UTC"})
        (should (fs/exists? fs* archive))
        (should= 3 (count (read-lines fs* archive)))
        (should (fs/exists? fs* active))
        (should= 0 (count (read-lines fs* active))))))

  (describe "size rollover"

    (it "archives an oversized active log within the same day"
      (let [fs*      (nexus/get :fs)
            active   (sut/server-log-path test-root)
            archive  (str (sut/logs-dir test-root) "/server-20260629.log")]
        (write-log! fs* active 100 "06-29")
        (sut/prepare-active-log! fs* test-root {:tz "UTC" :logging {:max-bytes 2000}})
        (should (fs/exists? fs* archive))
        (should (fs/exists? fs* active))
        (should= 0 (count (read-lines fs* active))))))

  (describe "retention"

    (it "drops archives older than max-days"
      (let [fs*       (nexus/get :fs)
            logs-dir  (sut/logs-dir test-root)
            old       (str logs-dir "/server-20260401.log")
            keep      (str logs-dir "/server-20260601.log")]
        (fs/mkdirs fs* logs-dir)
        (fs/spit fs* old "old")
        (fs/spit fs* keep "keep")
        (sut/prepare-active-log! fs* test-root {:tz "UTC" :logging {:max-days 30}})
        (should-not (fs/exists? fs* old))
        (should (fs/exists? fs* keep)))))

  (describe "configure-server-sink!"

    (it "creates logs/server.log on first write"
      (sut/configure-server-sink! test-root {:tz "UTC"})
      (sut/write-entry! {:ts "2026-06-29T12:00:00Z" :level :info :event :test/one})
      (let [fs* (nexus/get :fs)
            path (sut/server-log-path test-root)]
        (should (fs/exists? fs* path))
        (should= 1 (count (read-lines fs* path)))))))