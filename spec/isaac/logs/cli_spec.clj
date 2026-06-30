(ns isaac.logs.cli-spec
  (:require
    [isaac.cli.registry :as registry]
    [isaac.log-viewer :as viewer]
    [isaac.logs.cli :as sut]
    [isaac.logs.streams :as streams]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private test-root "/tmp/marigold-state")
(def ^:private absolute-log "/tmp/marigold.log")
(def ^:private relative-log "marigold.log")
(def ^:private config-log "logs/bridge-watch.log")
(def ^:private run-log "logs/watch.log")

(describe "logs cli"

  (describe "resolve-path"

    (it "returns nil for nil input"
      (should= nil (#'sut/resolve-path nil test-root)))

    (it "keeps absolute paths"
      (should= absolute-log (#'sut/resolve-path absolute-log test-root)))

    (it "resolves relative paths under root"
      (should= (str test-root "/" relative-log) (#'sut/resolve-path relative-log test-root)))

    (it "returns the relative path when root is unavailable"
      (should= relative-log (#'sut/resolve-path relative-log nil))))

  (describe "run"

    (around [example]
      (nexus/-with-nested-nexus {:isaac/log-streams nil} (example)))

    (it "prefers the explicit file path and forwards viewer options"
      (let [captured (atom nil)]
        (with-redefs [viewer/tail! (fn [path opts] (reset! captured [path opts]))]
          (sut/run {:file      config-log
                    :root test-root
                    :follow    true
                    :limit     5
                    :zebra     true
                    :plain     true
                    :no-color  true})
          (should= [(str test-root "/" config-log)
                    {:color? false :zebra? true :follow? true :plain? true :limit 5}]
                   @captured))))

    (it "tails the named stream's file from the registry"
      (let [captured (atom nil)]
        (streams/set-streams! {:server {:file "logs/server.log" :description "HTTP server logs"}})
        (with-redefs [viewer/tail! (fn [path opts] (reset! captured [path opts]))]
          (sut/run {:arguments ["server"] :root test-root :limit 20})
          (should= [(str test-root "/logs/server.log")
                    {:color? true :zebra? false :follow? false :plain? false :limit 20}]
                   @captured))))

    (it "lists the registered streams when no name and no file are given"
      (streams/set-streams! {:cli    {:file "logs/cli.log" :description "CLI command logs"}
                             :server {:file "logs/server.log" :description "HTTP server logs"}})
      (let [out (with-out-str (should= 0 (sut/run {:root test-root :limit 20})))]
        (should (.contains out "cli"))
        (should (.contains out "logs/cli.log"))
        (should (.contains out "server"))
        (should (.contains out "logs/server.log"))))

    (it "reports an unknown stream and lists the available ones without tailing"
      (let [tailed? (atom false)]
        (streams/set-streams! {:cli {:file "logs/cli.log" :description "CLI command logs"}})
        (with-redefs [viewer/tail! (fn [_ _] (reset! tailed? true))]
          (let [out (with-out-str (should= 0 (sut/run {:arguments ["nope"] :root test-root :limit 20})))]
            (should (.contains out "nope"))
            (should (.contains out "cli"))
            (should-not @tailed?))))))

  (describe "run-fn"

    (it "prints help and returns 0 for --help"
      (with-redefs [registry/get-command  (fn [_] {:name "logs"})
                    registry/command-help (fn [_] "logs help")]
        (let [output (with-out-str (should= 0 (sut/run-fn {:_raw-args ["--help"]})))]
          (should (.contains output "logs help")))))

    (it "prints parse errors and returns 1"
      (let [output (with-out-str (should= 1 (sut/run-fn {:_raw-args ["--limit" "bogus"]})))]
        (should (.contains output "For input string: \"bogus\""))))

    (it "delegates to run with merged parsed options"
      (let [captured (atom nil)]
        (with-redefs [sut/run (fn [opts] (reset! captured opts) 0)]
          (should= 0 (sut/run-fn {:_raw-args ["--file" run-log "--zebra"]
                                   :home      "/tmp/home"
                                   :root test-root}))
          (should= {:home "/tmp/home"
                    :root test-root
                    :file run-log
                    :limit 20
                    :zebra true
                    :arguments []}
                   @captured))))))
