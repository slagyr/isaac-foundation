(ns isaac.foundation.pin-lint-spec
  (:require
    [clojure.string :as str]
    [isaac.foundation.pin-lint :as sut]
    [speclj.core :refer :all]))

(describe "isaac.foundation.pin-lint"

  (it "a pin whose sha the fetch seam reports reachable passes"
    (let [edn {:deps {'io.github.slagyr/isaac-agent {:git/url "https://github.com/slagyr/isaac-agent.git"
                                                     :git/sha "abc1234"}}}]
      (binding [sut/*fetch-sha* (fn [_url _sha] true)]
        (should= [] (sut/findings "deps.edn" edn)))))

  (it "an unreachable sha fails naming file, dep and sha"
    (let [edn {:deps {'io.github.slagyr/isaac-agent {:git/url "https://github.com/slagyr/isaac-agent.git"
                                                     :git/sha "deadbeef"}}}]
      (binding [sut/*fetch-sha* (fn [_url _sha] false)]
        (let [hits (sut/findings "deps.edn" edn)]
          (should= 1 (count hits))
          (should= "deps.edn" (:file (first hits)))
          (should= 'io.github.slagyr/isaac-agent (:dep (first hits)))
          (should= "deadbeef" (:sha (first hits)))))))

  (it "ISAAC_LINT_PINS=0 warns and passes"
    (let [warned (atom [])]
      (with-redefs [sut/env (fn [k] (when (= "ISAAC_LINT_PINS" k) "0"))
                    println (fn [& args] (swap! warned conj (str/join " " args)))]
        (should= :skipped (sut/lint! ["."]))
        (should (some #(re-find #"ISAAC_LINT_PINS=0" %) @warned)))))

  (it "non-isaac deps (c3kit, gherclj, …) are ignored"
    (let [edn {:deps {'com.cleancoders.c3kit/apron {:mvn/version "3.0.0"}
                      'io.github.slagyr/gherclj    {:git/tag "v1.4.0" :git/sha "85245b1"}
                      'io.github.slagyr/dry4clj    {:git/url "https://github.com/slagyr/dry4clj"
                                                    :git/sha "8274f45"}}}]
      (binding [sut/*fetch-sha* (fn [_url _sha] (throw (ex-info "must not fetch" {})))]
        (should= [] (sut/findings "bb.edn" edn)))))

  )
