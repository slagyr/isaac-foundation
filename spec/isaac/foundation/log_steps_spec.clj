(ns isaac.foundation.log-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.foundation.log-steps :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(describe "foundation log steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (it)
    (g/reset!))

  (it "matches log rows as an ordered subsequence"
    (let [table   {:headers ["level" "event" "module"]
                   :rows    [[":info" ":module/activated" "isaac.comm.telly"]
                             [":info" ":telly/started" "bert"]]}
          entries [{:level :info :event :module/activated :module "isaac.comm.telly"}
                   {:level :info :event :lifecycle/started :path "comms.bert" :impl "telly"}
                   {:level :info :event :telly/started :module "bert"}]]
      (should= [] (:failures (#'sut/log-match-result table entries)))))

  (it "does not treat boot-phase entries as a failed single-row match when the target is later"
    (let [table   {:headers ["level" "event" "path" "impl"]
                   :rows    [[":info" ":lifecycle/started" "comms.bert" "telly"]]}
          entries [{:level :info :event :server/boot-phase :phase :start}
                   {:level :info :event :lifecycle/started :path "comms.bert" :impl "telly"}]]
      (should= [] (:failures (#'sut/log-match-result table entries)))))

  (it "matches existing logs without waiting for an unrelated turn future"
    (try
      (log/set-output! :memory)
      (log/clear-entries!)
      (log/warn :dispatch/refused :session "s1")
      (g/assoc! :turn-future (future (Thread/sleep 300)))
      (let [started (System/nanoTime)]
        (sut/log-entries-match {:headers ["level" "event" "session"]
                                :rows    [["warn" ":dispatch/refused" "s1"]]})
        (should (< (/ (- (System/nanoTime) started) 1000000.0) 100)))
      (finally
        (log/clear-entries!)
        (log/set-output! :file))))

  (it "keeps polling after a realized turn future until the log row appears"
    (try
      (log/set-output! :memory)
      (log/clear-entries!)
      (log/info :server/boot-phase :phase :start)
      (g/assoc! :turn-future (future :done))
      @(g/get :turn-future)
      (future
        (Thread/sleep 50)
        (log/info :lifecycle/started :path "comms.bert" :impl "telly"))
      (sut/log-entries-match {:headers ["level" "event" "path" "impl"]
                              :rows    [[":info" ":lifecycle/started" "comms.bert" "telly"]]})
      (finally
        (log/clear-entries!)
        (log/set-output! :file)))))
