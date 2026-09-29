(ns bb.test-tasks-spec
  (:require
    [bb.test-timeout :as timeout]
    [bb.test-tasks :as sut]
    [gherclj.parser :as parser]
    [speclj.core :refer :all]))

(describe "slow feature runner"
  (it "runs each slow scenario in a fresh process, excluding wip scenarios"
    (let [commands (atom [])]
      (with-redefs [parser/parse-features-dir (fn [_] [{:source "module/fixture.feature"
                                                    :scenarios [{:line 7 :tags ["slow"]}
                                                                {:line 10 :tags ["wip"]}
                                                                {:line 12 :tags ["slow" "wip"]}
                                                                {:line 18 :tags ["slow"]}]}])
                    timeout/shell! (fn [& command]
                                     (swap! commands conj command))]
        (with-out-str (sut/run-features-slow!))
        (should= [["features-slow" "bb" "-Sforce" "gherclj" "-t" "slow" "-t" "~wip" "features/module/fixture.feature:7"]
                  ["features-slow" "bb" "-Sforce" "gherclj" "-t" "slow" "-t" "~wip" "features/module/fixture.feature:18"]]
                 @commands))))

  (it "stops at the first failing slow scenario"
    (let [commands (atom [])]
      (with-redefs [parser/parse-features-dir (fn [_] [{:source "module/fixture.feature"
                                                    :scenarios [{:line 7 :tags ["slow"]}
                                                                {:line 18 :tags ["slow"]}]}])
                    timeout/shell! (fn [& command]
                                     (swap! commands conj command)
                                     (throw (ex-info "scenario failed" {})))]
        (should-throw clojure.lang.ExceptionInfo
          (with-out-str (sut/run-features-slow!)))
        (should= 1 (count @commands))))))
