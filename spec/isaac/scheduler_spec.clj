(ns isaac.scheduler-spec
  (:require
    [isaac.logger :as log]
    [isaac.spec-helper :as helper]
    [isaac.scheduler :as sut]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(defn- schedule-error-data [task]
  (let [scheduler (sut/create {:clock (fn [] (Instant/parse "2026-05-20T10:00:00Z"))})]
    (try
      (sut/schedule! scheduler task)
      nil
      (catch clojure.lang.ExceptionInfo e
        (ex-data e)))))

(defn- fast-started-scheduler [clock]
  (-> (sut/create {:clock clock})
      (assoc :tick-ms 1)
      sut/start!))

(describe "scheduler"

  (helper/with-captured-logs)

  (it "lists scheduled tasks in registration order"
    (let [scheduler (sut/create {:clock (fn [] (Instant/parse "2026-05-20T10:00:00Z"))})]
      (sut/schedule! scheduler {:id :tick-a :trigger {:kind :interval :ms 100} :handler (fn [_] nil)})
      (sut/schedule! scheduler {:id :tick-b :trigger {:kind :delay :ms 200} :handler (fn [_] nil)})
      (should= [{:id :tick-a :trigger {:kind :interval :ms 100}}
                {:id :tick-b :trigger {:kind :delay :ms 200}}]
               (mapv #(select-keys % [:id :trigger]) (sut/list-tasks scheduler)))))

  (it "rejects re-registering an existing id"
    (let [scheduler (sut/create {:clock (fn [] (Instant/parse "2026-05-20T10:00:00Z"))})]
      (sut/schedule! scheduler {:id :tick :trigger {:kind :interval :ms 100} :handler (fn [_] nil)})
      (let [error (try
                    (sut/schedule! scheduler {:id :tick :trigger {:kind :interval :ms 200} :handler (fn [_] nil)})
                    (catch clojure.lang.ExceptionInfo e e))]
        (should= "task already scheduled: :tick" (.getMessage error)))))

  (it "cancels a scheduled task"
    (let [scheduler (sut/create {:clock (fn [] (Instant/parse "2026-05-20T10:00:00Z"))})]
      (sut/schedule! scheduler {:id :tick :trigger {:kind :interval :ms 100} :handler (fn [_] nil)})
      (sut/cancel! scheduler :tick)
      (should= [] (sut/list-tasks scheduler))))

  (it "stop! preserves tasks and start! resumes ticking"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (fast-started-scheduler (fn [] @now*))]
      (sut/schedule! scheduler {:id :tick :trigger {:kind :interval :ms 100} :handler (fn [_] (swap! fired* inc))})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.100Z"))
      (helper/await-condition #(= 1 @fired*))
      (sut/stop! scheduler)
      (should= [:tick] (mapv :id (sut/list-tasks scheduler)))
      (should-not (sut/running? scheduler))
      (sut/start! scheduler)
      (reset! now* (Instant/parse "2026-05-20T10:00:00.200Z"))
      (helper/await-condition #(= 2 @fired*))
      (should= 2 @fired*)
      (sut/shutdown! scheduler)))

  (it "shutdown! clears tasks and stops the loop"
    (let [scheduler (fast-started-scheduler (fn [] (Instant/parse "2026-05-20T10:00:00Z")))]
      (sut/schedule! scheduler {:id :tick :trigger {:kind :interval :ms 100} :handler (fn [_] nil)})
      (sut/shutdown! scheduler)
      (should= [] (sut/list-tasks scheduler))
      (should-not (sut/running? scheduler))))

  (it "fires interval tasks on each elapsed boundary"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom [])
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id :tick :trigger {:kind :interval :ms 100} :handler (fn [_] (swap! fired* conj :tick))})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.350Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 3 (count @fired*)))
      (should= [:tick :tick :tick] @fired*)))

  (it "fires delay tasks once"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id :retry :trigger {:kind :delay :ms 500} :handler (fn [_] (swap! fired* inc))})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.499Z"))
      (sut/tick! scheduler)
      (should= 0 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:00.500Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (should= 1 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:02Z"))
      (sut/tick! scheduler)
      (should= 1 @fired*)))

  (it "computes the next cron fire from the trigger zone"
    (let [now*      (atom (Instant/parse "2026-05-20T07:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id :nightly
                                :trigger {:kind :cron :expr "0 3 * * *" :zone "America/Chicago"}
                                :handler (fn [_] (swap! fired* inc))})
      (reset! now* (Instant/parse "2026-05-20T07:59:59Z"))
      (sut/tick! scheduler)
      (should= 0 @fired*)
      (reset! now* (Instant/parse "2026-05-20T08:00:00Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (should= 1 @fired*)))

  (it "fires :at tasks once at the absolute instant"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule-once! scheduler {:id :alarm
                                     :trigger {:kind :at :instant "2026-05-20T10:00:30Z"}
                                     :handler (fn [_] (swap! fired* inc))})
      (reset! now* (Instant/parse "2026-05-20T10:00:29Z"))
      (sut/tick! scheduler)
      (should= 0 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:30Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (should= 1 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:01:00Z"))
      (sut/tick! scheduler)
      (should= 1 @fired*)))

  (it "fires past :at tasks on the next tick only once"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule-once! scheduler {:id :late
                                      :trigger {:kind :at :instant "2026-05-20T09:00:00Z"}
                                      :handler (fn [_] (swap! fired* inc))})
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (should= 1 @fired*)
      (sut/tick! scheduler)
      (should= 1 @fired*)))

  (it "validates task shape before scheduling"
    (should= "is required"
             (get-in (schedule-error-data {:id :bad :trigger {:kind :interval :ms 100}})
                     [:handler :message])))

  (it "validates task id before scheduling"
    (should= "must be present"
             (get-in (schedule-error-data {:trigger {:kind :interval :ms 100}
                                           :handler (fn [_] nil)})
                     [:id :message])))

  (it "validates trigger presence before scheduling"
    (should= "must be present"
             (get-in (schedule-error-data {:id :bad :handler (fn [_] nil)})
                     [:trigger :message])))

  (it "validates handler type before scheduling"
    (should= "must be a function"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :interval :ms 100}
                                           :handler 42})
                     [:handler :message])))

  (it "validates trigger kind values before scheduling"
    (should= "must be one of [:interval :delay :cron :at]"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :bogus :ms 100}
                                           :handler (fn [_] nil)})
                     [:trigger :kind :message])))

  (it "validates positive trigger ms before scheduling"
    (should= "must be positive"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :interval :ms 0}
                                           :handler (fn [_] nil)})
                     [:trigger :ms :message])))

  (it "validates interval trigger requires ms before scheduling"
    (should= "is required when kind is interval"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :interval}
                                           :handler (fn [_] nil)})
                     [:trigger :ms :message])))

  (it "validates delay trigger requires ms before scheduling"
    (should= "is required when kind is delay"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :delay}
                                           :handler (fn [_] nil)})
                     [:trigger :ms :message])))

  (it "validates trigger requirements before scheduling"
    (should= "is required when kind is at"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :at}
                                           :handler (fn [_] nil)})
                     [:trigger :instant :message])))

  (it "validates cron trigger expr requirement before scheduling"
    (should= "is required when kind is cron"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :cron}
                                           :handler (fn [_] nil)})
                     [:trigger :expr :message])))

  (it "validates coalesce values before scheduling"
    (should= "must be one of [:queue :skip]"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :delay :ms 100}
                                           :handler (fn [_] nil)
                                           :coalesce :bogus})
                     [:coalesce :message])))

  (it "validates on-error values before scheduling"
    (should= "must be one of [:log :retry]"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :delay :ms 100}
                                           :handler (fn [_] nil)
                                           :on-error :bogus})
                     [:on-error :message])))

  (it "validates positive backoff-ms"
    (should= "must be positive"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :delay :ms 100}
                                           :handler (fn [_] nil)
                                           :on-error  :retry
                                           :backoff-ms 0})
                     [:backoff-ms :message])))

  (it "validates positive max-backoff-ms"
    (should= "must be positive"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :delay :ms 100}
                                           :handler (fn [_] nil)
                                           :on-error       :retry
                                           :max-backoff-ms 0})
                     [:max-backoff-ms :message])))

  (it "validates positive retry-attempts"
    (should= "must be positive"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :delay :ms 100}
                                           :handler (fn [_] nil)
                                           :on-error       :retry
                                           :retry-attempts 0})
                     [:retry-attempts :message])))

  (it "applies defaults when :on-error is :retry and fields are omitted"
    (let [scheduler (sut/create {:clock (fn [] (Instant/parse "2026-05-20T10:00:00Z"))})
          task      (sut/schedule! scheduler {:id       :retry-defaults
                                               :trigger  {:kind :interval :ms 100}
                                               :handler  (fn [_] nil)
                                               :on-error :retry})]
      (should= 1000  (:backoff-ms     task))
      (should= 60000 (:max-backoff-ms task))
      (should= 3     (:retry-attempts task))))

  (it "validates positive timeout-ms"
    (should= "must be positive"
             (get-in (schedule-error-data {:id :bad
                                           :trigger {:kind :delay :ms 100}
                                           :handler (fn [_] nil)
                                           :timeout-ms 0})
                     [:timeout-ms :message])))

  (it "queues overlapping fires sequentially when coalesce is :queue"
    (let [now*          (atom (Instant/parse "2026-05-20T10:00:00Z"))
          release-first (promise)
          started*      (atom 0)
          scheduler     (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id       :slow
                                :trigger  {:kind :interval :ms 100}
                                :coalesce :queue
                                :handler  (fn [_]
                                            (let [n (swap! started* inc)]
                                              (when (= 1 n)
                                                @release-first)))})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.300Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @started*))
      (deliver release-first true)
      (helper/await-condition #(= 3 @started*))
      (should= 3 @started*)))

  (it "drops overlapping fires when coalesce is :skip"
    (let [now*          (atom (Instant/parse "2026-05-20T10:00:00Z"))
          release-first (promise)
          started*      (atom 0)
          scheduler     (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id       :slow
                                :trigger  {:kind :interval :ms 100}
                                :coalesce :skip
                                :handler  (fn [_]
                                            (let [n (swap! started* inc)]
                                              (when (= 1 n)
                                                @release-first)))})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.300Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @started*))
      (deliver release-first true)
      (helper/await-condition #(nil? (:active-run (first (sut/list-tasks scheduler)))))
      (should= 1 @started*)))

  (it "logs handler errors and keeps scheduling by default"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id      :flaky
                                :trigger {:kind :interval :ms 100}
                                :handler (fn [_]
                                           (swap! fired* inc)
                                           (throw (ex-info "boom" {})))})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.300Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 3 @fired*))
      (helper/await-condition #(= 3 (count (filter (fn [entry] (= :scheduler/handler-error (:event entry))) @log/captured-logs))))
      (should= 3 @fired*)
      (should= 3 (count (filter (fn [entry] (= :scheduler/handler-error (:event entry))) @log/captured-logs)))))

  (it "retries with exponential backoff after a handler error"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})
          retry-at  (fn [] (-> (sut/list-tasks scheduler) first :next-fire-at))]
      (sut/schedule! scheduler {:id             :retry
                                :trigger        {:kind :interval :ms 100}
                                :on-error       :retry
                                :backoff-ms     500
                                :max-backoff-ms 60000
                                :retry-attempts 10
                                :handler        (fn [_]
                                                  (swap! fired* inc)
                                                  (throw (ex-info "boom" {})))})
      ;; First fire at 100ms throws. Next retry uses 500ms backoff -> fires at 600ms.
      (reset! now* (Instant/parse "2026-05-20T10:00:00.100Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (reset! now* (Instant/parse "2026-05-20T10:00:00.599Z"))
      (sut/tick! scheduler)
      (should= 1 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:00.600Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 2 @fired*))
      (helper/await-condition #(= (Instant/parse "2026-05-20T10:00:01.600Z") (retry-at)))
      ;; Second retry uses 1000ms backoff (500 * 2^1) -> next fire at 1600ms.
      (reset! now* (Instant/parse "2026-05-20T10:00:01.599Z"))
      (sut/tick! scheduler)
      (should= 2 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:01.600Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 3 @fired*))
      (should= 3 @fired*)))

  (it "caps exponential backoff at :max-backoff-ms"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})
          await-settled! #(helper/await-condition
                            (fn [] (nil? (get-in @(:tasks scheduler) [:retry :active-run]))))]
      (sut/schedule! scheduler {:id             :retry
                                :trigger        {:kind :interval :ms 100}
                                :on-error       :retry
                                :backoff-ms     1000
                                :max-backoff-ms 1500
                                :retry-attempts 10
                                :handler        (fn [_]
                                                  (swap! fired* inc)
                                                  (throw (ex-info "boom" {})))})
      ;; First fire throws; would be 1000ms backoff = +1100ms.
      (reset! now* (Instant/parse "2026-05-20T10:00:00.100Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (reset! now* (Instant/parse "2026-05-20T10:00:01.100Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 2 @fired*))
      (await-settled!)
      ;; Second backoff would be 2000ms uncapped but capped to 1500ms -> next fire at 1100 + 1500 = 2600ms.
      (reset! now* (Instant/parse "2026-05-20T10:00:02.599Z"))
      (sut/tick! scheduler)
      (should= 2 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:02.600Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 3 @fired*))
      (should= 3 @fired*)))

  (it "disables a task after :retry-attempts consecutive handler errors"
    (let [now*           (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*         (atom 0)
          scheduler      (sut/create {:clock (fn [] @now*)})
          await-settled! #(helper/await-condition
                            (fn [] (nil? (get-in @(:tasks scheduler) [:flaky :active-run]))))]
      (sut/schedule! scheduler {:id             :flaky
                                :trigger        {:kind :interval :ms 100}
                                :on-error       :retry
                                :backoff-ms     1
                                :max-backoff-ms 1
                                :retry-attempts 3
                                :handler        (fn [_]
                                                  (swap! fired* inc)
                                                  (throw (ex-info "boom" {})))})
      ;; First fire throws -> backoff schedules retry at +1ms.
      (reset! now* (Instant/parse "2026-05-20T10:00:00.100Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (await-settled!)
      (reset! now* (Instant/parse "2026-05-20T10:00:00.101Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 2 @fired*))
      (await-settled!)
      (reset! now* (Instant/parse "2026-05-20T10:00:00.102Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 3 @fired*))
      ;; Third consecutive error reaches :retry-attempts -> disabled.
      (helper/await-condition #(empty? (sut/list-tasks scheduler)))
      (helper/await-condition
        #(seq (filter (fn [entry] (= :scheduler/disabled (:event entry))) @log/captured-logs)))
      (should= 3 @fired*)
      (should= [{:level :warn :event :scheduler/disabled :id :flaky :reason :too-many-errors :attempts 3}]
               (mapv #(select-keys % [:level :event :id :reason :attempts])
                     (filter (fn [entry] (= :scheduler/disabled (:event entry))) @log/captured-logs)))))

  (it "times out hung handlers"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          release*  (promise)
          started*  (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id         :hang
                                 :trigger    {:kind :interval :ms 100}
                                 :timeout-ms 5
                                 :handler    (fn [_]
                                               (swap! started* inc)
                                               @release*)})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.300Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @started*))
      (helper/await-condition #(= 1 (count (filter (fn [entry] (= :scheduler/timeout (:event entry))) @log/captured-logs))))
      (deliver release* true)
      (should= 1 @started*)))

  (it "does not let a hung handler block other tasks"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          release*  (promise)
          fast*     (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/schedule! scheduler {:id      :slow
                                :trigger {:kind :interval :ms 100}
                                :handler (fn [_] @release*)})
      (sut/schedule! scheduler {:id      :fast
                                :trigger {:kind :interval :ms 100}
                                :handler (fn [_] (swap! fast* inc))})
      (reset! now* (Instant/parse "2026-05-20T10:00:00.300Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 3 @fast*))
      (deliver release* true)
      (should= 3 @fast*)))

  (it "every! schedules a repeating task and returns its id"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})
          id        (sut/every! scheduler 100 (fn [_] (swap! fired* inc)))]
      (should (keyword? id))
      (reset! now* (Instant/parse "2026-05-20T10:00:00.200Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 2 @fired*))
      (should= 2 @fired*)
      (sut/cancel! scheduler id)
      (should= [] (sut/list-tasks scheduler))))

  (it "after! schedules a one-shot delay"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/after! scheduler 100 (fn [_] (swap! fired* inc)))
      (sut/tick! scheduler)
      (should= 0 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:00.100Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (reset! now* (Instant/parse "2026-05-20T10:00:00.500Z"))
      (sut/tick! scheduler)
      (should= 1 @fired*)))

  (it "at! schedules at an absolute instant"
    (let [now*      (atom (Instant/parse "2026-05-20T10:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/at! scheduler (Instant/parse "2026-05-20T10:00:00.500Z") (fn [_] (swap! fired* inc)))
      (reset! now* (Instant/parse "2026-05-20T10:00:00.499Z"))
      (sut/tick! scheduler)
      (should= 0 @fired*)
      (reset! now* (Instant/parse "2026-05-20T10:00:00.500Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (should= 1 @fired*)))

  (it "cron! schedules a recurring task with an optional zone"
    (let [now*      (atom (Instant/parse "2026-05-20T07:00:00Z"))
          fired*    (atom 0)
          scheduler (sut/create {:clock (fn [] @now*)})]
      (sut/cron! scheduler "0 3 * * *" "America/Chicago" (fn [_] (swap! fired* inc)))
      (reset! now* (Instant/parse "2026-05-20T07:59:59Z"))
      (sut/tick! scheduler)
      (should= 0 @fired*)
      (reset! now* (Instant/parse "2026-05-20T08:00:00Z"))
      (sut/tick! scheduler)
      (helper/await-condition #(= 1 @fired*))
      (should= 1 @fired*)))

  (it "convenience constructors accept extra opts (e.g. :on-error, :timeout-ms)"
    (let [scheduler (sut/create {:clock (fn [] (Instant/parse "2026-05-20T10:00:00Z"))})
          id        (sut/every! scheduler 100 (fn [_] nil)
                                {:on-error :retry :timeout-ms 200})
          task      (first (sut/list-tasks scheduler))]
      (should= id           (:id task))
      (should= :retry       (:on-error task))
      (should= 200          (:timeout-ms task))
      (should= 1000         (:backoff-ms task))
      (should= 60000        (:max-backoff-ms task))
      (should= 3            (:retry-attempts task)))))
