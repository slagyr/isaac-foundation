(ns isaac.logger-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.logger :as sut]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def test-log "/tmp/isaac-test.log")

(def ^:dynamic *fs* nil)

(defn- read-entries []
  (when (fs/exists? *fs* test-log)
    (let [lines (remove str/blank? (str/split-lines (fs/slurp *fs* test-log)))]
      (mapv edn/read-string lines))))

(describe "Logger"

  (around [it]
    (let [mem (fs/mem-fs)]
      (nexus/-with-nested-nexus {:fs mem}
        (binding [*fs* mem]
          (it)))))

  (before (fs/spit *fs* test-log "")
          (sut/set-level! :debug)
          (sut/set-output! :file)
          (sut/clear-entries!)
          (sut/set-log-file! test-log))
  (after  (sut/set-output! :file)
          (sut/clear-entries!)
          (sut/set-log-file! "/tmp/isaac.log"))

  ;; region ----- Writing Entries -----

  (describe "log entry format"

    (it "writes a single EDN line per log call"
      (sut/info :test/hello)
      (let [lines (str/split-lines (fs/slurp *fs* test-log))]
        (should= 1 (count lines))))

    (it "each entry is a readable EDN map"
      (sut/info :test/hello :value 42)
      (let [entry (first (read-entries))]
        (should (map? entry))
        (should= :test/hello (:event entry))
        (should= 42 (:value entry))))

    (it "includes an ISO-8601 UTC timestamp string"
      (sut/info :test/ts)
      (let [entry (first (read-entries))]
        (should (string? (:ts entry)))
        (should (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z" (:ts entry)))))

    (it "writes :ts, :level, :event as the first three keys in the output line"
      (sut/info :test/order :extra "data")
      (let [line (first (str/split-lines (fs/slurp *fs* test-log)))]
        (should (re-find #"^\{:ts \"[^\"]+\",? :level :[^,]+,? :event :[^ ,]+" line))))

    (it "preserves :ts :level :event ordering for entries with more than 8 context fields"
      (sut/info :test/many-keys
                :field-a "a"
                :field-b "b"
                :field-c "c"
                :field-d "d"
                :field-e "e"
                :field-f "f"
                :field-g "g")
      (let [line (first (str/split-lines (fs/slurp *fs* test-log)))]
        (should (re-find #"^\{:ts \"[^\"]+\",? :level :[^,]+,? :event :[^ ,]+" line))))

    (it "includes the log level"
      (sut/warn :test/level)
      (let [entry (first (read-entries))]
        (should= :warn (:level entry))))

    (it "includes the source file"
      (sut/info :test/file)
      (let [entry (first (read-entries))]
        (should (string? (:file entry)))
        (should (str/ends-with? (:file entry) ".clj"))))

    (it "normalizes absolute source file paths to workspace-relative paths"
      (let [cwd   (System/getProperty "user.dir")
            entry (@#'sut/build-entry :info :test/file {}
                                      (str cwd "/src/isaac/logger.clj")
                                      42)]
        (should= "src/isaac/logger.clj" (:file entry))))

    (it "normalizes classpath-style source paths to stable repo-relative paths"
      (let [entry (@#'sut/build-entry :info :test/file {} "isaac/logger.clj" 42)]
        (should= "src/isaac/logger.clj" (:file entry))))

    (it "preserves already normalized source file paths"
      (let [entry (@#'sut/build-entry :info :test/file {} "spec/isaac/logger_spec.clj" 42)]
        (should= "spec/isaac/logger_spec.clj" (:file entry))))

    (it "includes the source line"
      (sut/info :test/line)
      (let [entry (first (read-entries))]
        (should (number? (:line entry)))
        (should (> (:line entry) 0))))

    (it "appends multiple entries without overwriting"
      (sut/info :test/first)
      (sut/info :test/second)
      (let [entries (read-entries)]
        (should= 2 (count entries))
        (should= :test/first  (:event (first entries)))
        (should= :test/second (:event (second entries))))))

  ;; endregion ^^^^^ Writing Entries ^^^^^

  ;; region ----- Level Macros -----

  (describe "level macros"

    (it "error logs with :error level"
      (sut/error :test/e)
      (should= :error (:level (first (read-entries)))))

    (it "warn logs with :warn level"
      (sut/warn :test/w)
      (should= :warn (:level (first (read-entries)))))

    (it "report logs with :report level"
      (sut/report :test/r)
      (should= :report (:level (first (read-entries)))))

    (it "info logs with :info level"
      (sut/info :test/i)
      (should= :info (:level (first (read-entries)))))

    (it "debug logs with :debug level"
      (sut/debug :test/d)
      (should= :debug (:level (first (read-entries))))))

  ;; endregion ^^^^^ Level Macros ^^^^^

  ;; region ----- Level Filtering -----

  (describe "level filtering"

    (it "logs entries at or above the configured level"
      (sut/set-level! :warn)
      (sut/error :test/err)
      (sut/warn :test/wrn)
      (sut/info :test/inf)
      (let [entries (read-entries)]
        (should= 2 (count entries))
        (should= #{:error :warn} (set (map :level entries)))))

    (it "logs nothing when level is above all entries"
      (sut/set-level! :error)
      (sut/warn :test/w)
      (sut/info :test/i)
      (sut/debug :test/d)
      (should= 0 (count (read-entries))))

    (it "logs all entries when level is :debug"
      (sut/set-level! :debug)
      (sut/error :test/e)
      (sut/warn :test/w)
      (sut/report :test/r)
      (sut/info :test/i)
      (sut/debug :test/d)
      (should= 5 (count (read-entries))))

    (it "includes :report level as lowest"
      (sut/set-level! :report)
      (sut/error :test/e)
      (sut/warn :test/w)
      (sut/report :test/r)
      (sut/info :test/i)
      (sut/debug :test/d)
      (let [entries (read-entries)]
        (should= 1 (count entries))
        (should= #{:report} (set (map :level entries))))))

  ;; endregion ^^^^^ Level Filtering ^^^^^

  ;; region ----- Memory Output -----

  (describe "memory output"

    (before (sut/set-output! :memory))

    (it "stores entries in memory instead of file"
      (sut/info :test/mem)
      (should= 0 (count (read-entries)))
      (should= 1 (count (sut/get-entries))))

    (it "get-entries returns all accumulated entries"
      (sut/info :test/one)
      (sut/info :test/two)
      (should= 2 (count (sut/get-entries))))

    (it "clear-entries! empties the in-memory log"
      (sut/info :test/x)
      (sut/clear-entries!)
      (should= 0 (count (sut/get-entries))))

    (it "entries include level and event"
      (sut/error :test/fail)
      (let [entry (first (sut/get-entries))]
        (should= :error (:level entry))
        (should= :test/fail (:event entry)))))

  ;; endregion ^^^^^ Memory Output ^^^^^

  )
