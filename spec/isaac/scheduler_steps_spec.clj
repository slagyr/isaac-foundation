(ns isaac.scheduler-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.scheduler-steps :as sut]
    [speclj.core :refer [around describe it should]])
  (:import
    (java.util.concurrent TimeUnit)))

(defn- task-table [& rows]
  {:headers ["id" "trigger.kind" "trigger.ms" "handler-runtime"]
   :rows    rows})

(describe "scheduler feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (try
      (it)
      (finally
        (when-let [instance (#'sut/current-scheduler)]
          (scheduler/shutdown! instance))
        (g/reset!))))

  (it "does not wait for unrelated hung handlers before later assertions poll"
    (sut/scheduler-started "2026-05-20T10:00:00Z")
    (sut/scheduled-task (task-table ["slow" "interval" "100" "5s"]))
    (sut/scheduled-task (task-table ["fast" "interval" "100" "1ms"]))
    (let [started-at (System/nanoTime)]
      (sut/clock-advances-and-settles "300ms")
      (should (< (.toMillis TimeUnit/NANOSECONDS (- (System/nanoTime) started-at))
                 500)))
    (sut/handler-fired "fast" 3)))