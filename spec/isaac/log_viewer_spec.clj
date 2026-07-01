(ns isaac.log-viewer-spec
  (:require
    [clojure.string :as str]
    [isaac.marigold :as marigold]
    [isaac.log-viewer :as sut]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(def ^:private bridge-event :bridge/started)

(describe "Log viewer"

  (describe "format-time"

    (it "formats UTC timestamp to local HH:MM:SS.mmm"
      (let [result (sut/format-time "2026-05-12T15:24:51.491Z")]
        (should (re-matches #"\d{2}:\d{2}:\d{2}\.\d{3}" result))))

    (it "handles timestamps without sub-second precision"
      (let [result (sut/format-time "2026-05-12T15:24:51Z")]
        (should (re-matches #"\d{2}:\d{2}:\d{2}\.\d{3}" result))))

    (it "returns a 12-char string"
      (should= 12 (count (sut/format-time "2026-05-12T15:24:51.491Z")))))

  (describe "color-for-level"

    (it "returns red for :error"
      (should (str/includes? (sut/color-for-level :error) "31")))

    (it "returns yellow for :warn"
      (should (str/includes? (sut/color-for-level :warn) "33")))

    (it "returns cyan for :info"
      (should (str/includes? (sut/color-for-level :info) "36")))

    (it "returns dim for :debug"
      (should (str/includes? (sut/color-for-level :debug) "2")))

    (it "returns dim for :trace"
      (should (str/includes? (sut/color-for-level :trace) "2"))))

  (describe "color-for-value"

    (it "returns red for nil"
      (should (str/includes? (sut/color-for-value nil) "31")))

    (it "returns yellow for booleans"
      (should (str/includes? (sut/color-for-value true) "33"))
      (should (str/includes? (sut/color-for-value false) "33")))

    (it "returns green for numbers"
      (should (str/includes? (sut/color-for-value 42) "32")))

    (it "returns magenta for keywords"
      (should (str/includes? (sut/color-for-value :foo) "35")))

    (it "returns soft gray for strings"
      (should (str/includes? (sut/color-for-value "hello") "38;5;222"))))

  (describe "color-for-ns"

    (it "is deterministic — same ns always returns same color"
      (should= (sut/color-for-ns marigold/skybeam)
               (sut/color-for-ns marigold/skybeam)))

    (it "returns a non-empty 256-color ANSI string"
      (should (str/includes? (sut/color-for-ns "bridge") "\033[38;5;")))

    (it "spreads typical event namespaces across multiple palette entries"
      ;; Guards against the regression where most events ended up purple.
      (let [namespaces ["bridge" marigold/longwave marigold/skybeam "chartroom"
                        "quarters" "ballast" marigold/signal-flare "dispatch"
                        "chronometer" "galley" "lookout" "rigging"]
            distinct-colors (count (set (map sut/color-for-ns namespaces)))]
        (should (<= 6 distinct-colors)))))

  (describe "color-for-session"

    (it "is deterministic — same session always returns same color"
      (should= (sut/color-for-session "abc-123")
               (sut/color-for-session "abc-123")))

    (it "returns a non-empty 256-color ANSI string"
      (should (str/includes? (sut/color-for-session "xyz") "\033[38;5;"))))

  (describe "format-entry"

    (it "assembles time, level, event in fixed columns"
      (let [entry  {:ts "2026-05-12T15:24:51.491Z" :level :info :event bridge-event :port 8080}
            result (sut/format-entry entry false)]
        (should (re-find #"\d{2}:\d{2}:\d{2}\.\d{3}" result))
        (should (str/includes? result "INFO "))
        (should (str/includes? result (str bridge-event)))
        (should (str/includes? result "{:port 8080}"))))

    (it "renders the trailing payload as a Clojure map literal"
      (let [result (sut/format-entry {:ts "2026-05-12T00:00:00Z" :level :info :event :a
                                      :client "192.168.1.10" :uri (str "/" marigold/skybeam)}
                                     false)]
        (should (str/includes? result (str "{:client \"192.168.1.10\" :uri \"/" marigold/skybeam "\"}")))
        (should-not (str/includes? result "client="))
        (should-not (str/includes? result "uri="))))

    (it "pads level to 5 chars"
      (doseq [[level expected] {:info "INFO " :error "ERROR" :warn "WARN " :debug "DEBUG" :trace "TRACE"}]
        (let [result (sut/format-entry {:ts "2026-05-12T00:00:00Z" :level level :event :x} false)]
          (should (str/includes? result (str expected "  "))))))

    (it "drops :file and :line from output"
      (let [result (sut/format-entry {:ts "2026-05-12T00:00:00Z" :level :info :event :x
                                      :file "src/foo.clj" :line 42} false)]
        (should-not (str/includes? result "file="))
        (should-not (str/includes? result "line="))
        (should-not (str/includes? result "src/foo.clj"))))

    (it "includes ANSI codes when color? is true"
      (let [result (sut/format-entry {:ts "2026-05-12T00:00:00Z" :level :info :event bridge-event} true)]
        (should (str/includes? result "\033["))))

    (it "emits no ANSI codes when color? is false"
      (let [result (sut/format-entry {:ts "2026-05-12T00:00:00Z" :level :info :event bridge-event} false)]
        (should-not (str/includes? result "\033[")))))

    (it "expands escaped throwable stacktraces on indented continuation lines"
      (let [entry  {:ts "2026-05-12T00:00:00Z" :level :error :event :scheduler/handler-error
                    :error "Output closed"
                    :throwable {:class "clojure.lang.ExceptionInfo"
                                :message "Output closed"
                                :stacktrace "clojure.lang.ExceptionInfo: Output closed\\n\tat isaac.scheduler.runtime/finish_run_BANG_(runtime.clj:295)"}}
            result (sut/format-entry entry false)
            lines  (str/split-lines result)]
        (should (str/includes? (first lines) ":scheduler/handler-error"))
        (should (str/includes? (first lines) ":message \"Output closed\""))
        (should-not (str/includes? (first lines) ":stacktrace"))
        (should= 3 (count lines))
        (should (str/includes? (second lines) "ExceptionInfo: Output closed"))
        (should (str/starts-with? (second lines) "    "))
        (should (str/includes? (nth lines 2) "finish_run_BANG_"))))

  (describe "format-line"

    (it "formats a valid EDN log line"
      (let [result (sut/format-line (str "{:ts \"2026-05-12T15:24:51.491Z\" :level :info :event " bridge-event "}") false)]
        (should (str/includes? result (str bridge-event)))))

    (it "formats a single-line scheduler error with expanded stacktrace"
      (let [line   (pr-str {:ts "2026-05-12T00:00:00Z" :level :error :event :scheduler/handler-error
                            :error "Output closed"
                            :throwable {:class "clojure.lang.ExceptionInfo"
                                        :message "Output closed"
                                        :stacktrace "java.lang.Exception: Output closed\\n\tat worker.run(worker.clj:10)"}})
            result (sut/format-line line false)]
        (should (str/includes? result ":scheduler/handler-error"))
        (should (str/includes? result "worker.run"))))

    (it "passes unparseable lines through as-is"
      (should= "this is not edn" (sut/format-line "this is not edn" false)))

    (it "returns nil for blank lines"
      (should-be-nil (sut/format-line "   " false))
      (should-be-nil (sut/format-line "" false))
      (should-be-nil (sut/format-line nil false))))

  (describe "tail!"

    (it "dims odd rows when zebra? and color? are true"
      (let [f    (java.io.File/createTempFile "test-log" ".log")
            dim2 "[2m[2m"]   ; double-dim = zebra prefix + entry's own dim
        (try
          (spit (.getAbsolutePath f)
                (str "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :a}\n"
                     "{:ts \"2026-05-12T00:00:01Z\" :level :info :event :b}\n"
                     "{:ts \"2026-05-12T00:00:02Z\" :level :info :event :c}\n"))
          (let [result (with-out-str
                         (sut/tail! (.getAbsolutePath f) {:color? true :follow? false :zebra? true}))
                lines  (str/split-lines result)]
            (should-not (str/starts-with? (nth lines 0) dim2))
            (should (str/starts-with? (nth lines 1) dim2))
            (should-not (str/starts-with? (nth lines 2) dim2)))
          (finally (.delete f)))))

    (it "re-applies dim after every internal reset on zebra rows"
      (let [f (java.io.File/createTempFile "test-log" ".log")]
        (try
          (spit (.getAbsolutePath f)
                (str "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :a}\n"
                     "{:ts \"2026-05-12T00:00:01Z\" :level :info :event :bridge/started :port 8080}\n"))
          (let [result  (with-out-str
                          (sut/tail! (.getAbsolutePath f) {:color? true :follow? false :zebra? true}))
                lines   (str/split-lines result)
                zebra   (count (re-seq #"\[2m" (nth lines 1)))
                normal  (count (re-seq #"\[2m" (nth lines 0)))]
            ;; zebra row has extra dim codes re-applied after each internal reset
            (should (> zebra normal)))
          (finally (.delete f)))))

    (it "limits the initial dump to the last N entries when :limit is set"
      (let [f (java.io.File/createTempFile "test-log" ".log")]
        (try
          (spit (.getAbsolutePath f)
                (str/join "\n"
                          (map #(format "{:ts \"2026-05-12T00:00:%02dZ\" :level :info :event :e%02d}" (mod % 60) %)
                               (range 1 6))))
          (let [result (with-out-str
                         (sut/tail! (.getAbsolutePath f) {:color? false :follow? false :limit 2}))]
            (should-not (str/includes? result ":e01"))
            (should-not (str/includes? result ":e03"))
            (should (str/includes? result ":e04"))
            (should (str/includes? result ":e05")))
          (finally (.delete f)))))

    (it "reads only the tail of large files when :limit is set"
      (let [f (java.io.File/createTempFile "test-log" ".log")]
        (try
          (with-open [w (java.io.BufferedWriter. (java.io.FileWriter. f))]
            (dotimes [i 5000]
              (.write w (format "{:event :e%04d}\n" i))))
          (let [result (with-out-str
                         (sut/tail! (.getAbsolutePath f) {:color? false :follow? false :limit 2}))]
            (should-not (str/includes? result ":e4997"))
            (should (str/includes? result ":e4998"))
            (should (str/includes? result ":e4999")))
          (finally (.delete f)))))

    (it ":limit 0 shows every entry"
      (let [f (java.io.File/createTempFile "test-log" ".log")]
        (try
          (spit (.getAbsolutePath f)
                (str/join "\n"
                          (map #(format "{:ts \"2026-05-12T00:00:%02dZ\" :level :info :event :e%02d}" % %)
                               (range 1 4))))
          (let [result (with-out-str
                         (sut/tail! (.getAbsolutePath f) {:color? false :follow? false :limit 0}))]
            (should (str/includes? result ":e01"))
            (should (str/includes? result ":e02"))
            (should (str/includes? result ":e03")))
          (finally (.delete f)))))

    (it "in plain mode echoes original lines verbatim with no parsing"
      (let [f (java.io.File/createTempFile "test-log" ".log")]
        (try
          (spit (.getAbsolutePath f)
                (str "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :foo :port 8080}\n"
                     "not edn at all\n"))
          (let [result (with-out-str
                         (sut/tail! (.getAbsolutePath f) {:color? true :follow? false :zebra? true :plain? true}))]
            (should (str/includes? result "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :foo :port 8080}"))
            (should (str/includes? result "not edn at all"))
            (should-not (str/includes? result "\033[")))
          (finally (.delete f)))))

    (it "does not dim rows when zebra? is false"
      (let [f    (java.io.File/createTempFile "test-log" ".log")
            dim2 "[2m[2m"]
        (try
          (spit (.getAbsolutePath f)
                (str "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :a}\n"
                     "{:ts \"2026-05-12T00:00:01Z\" :level :info :event :b}\n"))
          (let [result (with-out-str
                         (sut/tail! (.getAbsolutePath f) {:color? true :follow? false :zebra? false}))]
            (should-not (str/includes? result dim2)))
          (finally (.delete f)))))

    (it "does not dim rows when color? is false"
      (let [f    (java.io.File/createTempFile "test-log" ".log")
            dim2 "[2m[2m"]
        (try
          (spit (.getAbsolutePath f)
                (str "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :a}\n"
                     "{:ts \"2026-05-12T00:00:01Z\" :level :info :event :b}\n"))
          (let [result (with-out-str
                         (sut/tail! (.getAbsolutePath f) {:color? false :follow? false :zebra? true}))]
            (should-not (str/includes? result dim2)))
          (finally (.delete f)))))

    (it "prints a friendly message and returns when the file is missing without follow"
      (let [missing (str (System/getProperty "java.io.tmpdir") "/isaac-missing-" (rand-int 100000) ".log")]
        (try
          (let [result (with-out-str
                          (sut/tail! missing {:color? false :follow? false}))]
            (should (str/includes? result "No log file at"))
            (should (str/includes? result missing))
            (should (str/includes? result "--file")))
          (finally
            (.delete (java.io.File. missing))))))

    (it "waits for a missing file when follow is true, then tails new entries"
      (let [missing (str (System/getProperty "java.io.tmpdir") "/isaac-follow-" (rand-int 100000) ".log")
            line    "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :appeared}\n"
            writer  (java.io.StringWriter.)
            run*    (future
                      (binding [*out* writer]
                        (binding [sut/*follow-sleep-ms* 0]
                          (sut/tail! missing {:color? false :follow? true}))))]
        (try
          (Thread/sleep 50)
          (spit missing line)
          (helper/await-condition #(str/includes? (str writer) ":appeared") 5000)
          (should (str/includes? (str writer) ":appeared"))
          (finally
            (future-cancel run*)
            (.delete (java.io.File. missing))))))

    (it "does not skip a line appended between the initial dump and follow seek"
      (let [f             (java.io.File/createTempFile "test-log" ".log")
            first-line    "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :first}\n"
            second-line   "{:ts \"2026-05-12T00:00:01Z\" :level :info :event :second}\n"
            writer        (java.io.StringWriter.)
            original-print @#'sut/print-line!
            path          (.getAbsolutePath f)]
        ;; Seed before tail! so row 0 is the initial dump (append lands before follow).
        (spit path first-line)
        (let [run* (future
                    (binding [*out* writer]
                      (binding [sut/*follow-sleep-ms* 0]
                        (with-redefs [sut/print-line!
                                      (fn [line row opts]
                                        (let [printed? (original-print line row opts)]
                                          (when (and printed? (zero? row))
                                            (spit path second-line :append true))
                                          printed?))]
                          (sut/tail! path {:color? false :follow? true :limit 20})))))]
          (try
            (helper/await-condition #(str/includes? (str writer) ":first") 5000)
            (helper/await-condition #(str/includes? (str writer) ":second") 5000)
            (should (str/includes? (str writer) ":second"))
            (finally
              (future-cancel run*)
              (.delete f)))))

    (it "follows across log rotation when the path is replaced"
      (let [dir        (java.nio.file.Files/createTempDirectory "isaac-log-rotate-" (into-array java.nio.file.attribute.FileAttribute []))
            path       (str dir "/server.log")
            archive    (str dir "/server-20260701.log")
            before     "{:ts \"2026-05-12T00:00:00Z\" :level :info :event :before-rotate}\n"
            after      "{:ts \"2026-05-12T00:00:01Z\" :level :info :event :after-rotate}\n"
            writer     (java.io.StringWriter.)
            run*       (future
                         (binding [*out* writer]
                           (binding [sut/*follow-sleep-ms* 0]
                             (sut/tail! path {:color? false :follow? true}))))]
        (try
          (spit path before)
          (helper/await-condition #(str/includes? (str writer) ":before-rotate") 5000)
          (java.nio.file.Files/move
            (.toPath (java.io.File. path))
            (.toPath (java.io.File. archive))
            (into-array java.nio.file.CopyOption [(java.nio.file.StandardCopyOption/ATOMIC_MOVE)]))
          (spit path after)
          (helper/await-condition #(str/includes? (str writer) ":after-rotate") 5000)
          (should (str/includes? (str writer) ":after-rotate"))
          (finally
            (future-cancel run*)
            (clojure.java.io/delete-file dir true))))))))
