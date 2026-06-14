(ns isaac.logs.cli-spec
  (:require
    [isaac.cli.registry :as registry]
    [isaac.fs :as fs]
    [isaac.log-viewer :as viewer]
    [isaac.logger :as log]
    [isaac.logs.cli :as sut]
    [speclj.core :refer :all]))

(def ^:private test-root "/tmp/marigold-state")
(def ^:private absolute-log "/tmp/marigold.log")
(def ^:private relative-log "marigold.log")
(def ^:private config-log "logs/bridge-watch.log")
(def ^:private run-log "logs/watch.log")
(def ^:private default-log "/tmp/ship-default.log")

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

  (describe "config-log-path"

    (it "reads the configured log output path"
      (let [mem (fs/mem-fs)]
        (fs/mkdirs mem "/tmp/state/config")
        (fs/spit   mem "/tmp/state/config/isaac.edn" (str "{:log {:output \"" config-log "\"}}"))
        (should= config-log (#'sut/config-log-path "/tmp/state" mem))))

    (it "returns nil when the config file is missing"
      (should= nil (#'sut/config-log-path "/tmp/state" (fs/mem-fs))))

    (it "returns nil when the config file is invalid"
      (let [mem (fs/mem-fs)]
        (fs/mkdirs mem "/tmp/home/.isaac/config")
        (fs/spit   mem "/tmp/home/.isaac/config/isaac.edn" "{:log")
        (should= nil (#'sut/config-log-path "/tmp/home" mem)))))

  (describe "run"

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

    (it "falls back to the configured log path when no explicit file is given"
      (let [captured (atom nil)]
        (with-redefs [sut/config-log-path (fn [_ _] config-log)
                      viewer/tail!        (fn [path opts] (reset! captured [path opts]))]
          (sut/run {:home "/tmp/home" :root test-root :limit 20})
          (should= [(str test-root "/" config-log)
                    {:color? true :zebra? false :follow? false :plain? false :limit 20}]
                   @captured))))

    (it "falls back to the logger default when no config path exists"
      (let [captured (atom nil)]
        (with-redefs [sut/config-log-path (fn [_ _]nil)
                      log/log-file        (fn [] default-log)
                      viewer/tail!        (fn [path opts] (reset! captured [path opts]))]
          (sut/run {:home "/tmp/home" :root test-root :limit 20})
          (should= [default-log
                    {:color? true :zebra? false :follow? false :plain? false :limit 20}]
                   @captured)))))

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
                    :zebra true}
                   @captured))))))
