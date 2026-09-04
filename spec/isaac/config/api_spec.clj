(ns isaac.config.api-spec
  (:require
    [isaac.config.api :as sut]
    [isaac.config.loader :as loader]
    [isaac.config.env :as env]

    [speclj.core :refer :all]))

(describe "config api"

  (before (env/clear-env-overrides!))
  (after  (env/clear-env-overrides!))

  (it "load-resolved delegates to the loader"
    (sut/clear-process-memo!)
    (with-redefs [loader/load-config-result (fn [& _] {:config {:a 1} :errors []})]
      (should= {:a 1} (:config (sut/load-resolved {})))))

  (it "resolved-config returns the normalized :config map"
    (sut/clear-process-memo!)
    (with-redefs [loader/load-config-result (fn [& _] {:config {:server {:port 4242}}})]
      (should= 4242 (get-in (sut/resolved-config {}) [:server :port]))))

  (it "resolved-slice reads a live subtree"
    (sut/clear-process-memo!)
    (with-redefs [loader/load-config-result
                  (fn [& _] {:config {:comms {:discord {:discord/token "live"}}}})]
      (should= {:discord/token "live"}
                (sut/resolved-slice [:comms :discord] {}))))

  (it "load-resolved memoizes identical (root, fs, opts) within the process"
    (sut/clear-process-memo!)
    (let [calls (atom 0)]
      (with-redefs [loader/load-config-result
                    (fn [_]
                      (swap! calls inc)
                      {:config {:n @calls} :errors []})]
        (let [opts {:root "/x" :fs :same}]
          (should= {:n 1} (:config (sut/load-resolved opts)))
          (should= {:n 1} (:config (sut/load-resolved opts)))
          (should= 1 @calls)))))

  (it "clear-process-memo! forces the next load-resolved to re-read"
    (sut/clear-process-memo!)
    (let [calls (atom 0)]
      (with-redefs [loader/load-config-result
                    (fn [_]
                      (swap! calls inc)
                      {:config {:n @calls} :errors []})]
        (let [opts {:root "/x" :fs :same}]
          (sut/load-resolved opts)
          (sut/clear-process-memo!)
          (should= {:n 2} (:config (sut/load-resolved opts)))
          (should= 2 @calls))))))