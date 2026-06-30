(ns isaac.config.api-spec
  (:require
    [isaac.config.api :as sut]
    [isaac.config.loader :as loader]

    [speclj.core :refer :all]))

(describe "config api"

  (before (loader/clear-env-overrides!))
  (after  (loader/clear-env-overrides!))

  (it "load-resolved delegates to the loader"
    (with-redefs [loader/load-config-result (fn [& _] {:config {:a 1} :errors []})]
      (should= {:a 1} (:config (sut/load-resolved {})))))

  (it "resolved-config returns the normalized :config map"
    (with-redefs [loader/load-config-result (fn [& _] {:config {:server {:port 4242}}})]
      (should= 4242 (get-in (sut/resolved-config {}) [:server :port]))))

  (it "resolved-slice reads a live subtree"
    (with-redefs [loader/load-config-result
                  (fn [& _] {:config {:comms {:discord {:discord/token "live"}}}})]
      (should= {:discord/token "live"}
                (sut/resolved-slice [:comms :discord] {})))))