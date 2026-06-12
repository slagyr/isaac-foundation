(ns isaac.scheduler.runtime
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.scheduler.cron :as cron]
    [isaac.logger :as log])
  (:import
    (java.time Instant OffsetDateTime ZoneId ZonedDateTime)
    (java.util UUID)
    (java.util.concurrent Executors Future ScheduledExecutorService
                          ScheduledFuture ThreadFactory TimeUnit)
    (java.util.concurrent.atomic AtomicLong)))

(def ^:private default-tick-ms 50)

(defn- default-pool-size []
  (max 2 (* 2 (.availableProcessors (Runtime/getRuntime)))))

(defn- parse-instant [value]
  (cond
    (nil? value) nil
    (instance? Instant value) value
    (instance? OffsetDateTime value) (.toInstant ^OffsetDateTime value)
    (string? value) (try
                      (Instant/parse value)
                      (catch Exception _
                        (.toInstant (OffsetDateTime/parse value))))
    :else (throw (ex-info "unsupported instant value" {:value value}))))

(def trigger-schema
  {:name   :scheduler-trigger
   :type   :map
   :schema {:kind    {:type :keyword :validations [[:one-of :interval :delay :cron :at]]
                      :description "Trigger kind: :interval, :delay, :cron, or :at"}
            :ms      {:type :long
                      :validations [[:present-when? :kind :interval]
                                    [:present-when? :kind :delay]
                                    [:maybe? :pos?]]
                      :description "Relative delay or interval in milliseconds for :delay and :interval triggers"}
            :expr    {:type        :string
                      :validations [[:present-when? :kind :cron]]
                      :description "Cron expression for :cron triggers"}
            :zone    {:type :string :description "IANA time zone name used to evaluate :cron triggers"}
            :instant {:type        :ignore
                      :coerce      [parse-instant]
                      :validations [[:present-when? :kind :at]]
                      :description "Absolute instant for :at triggers; accepts java.time values or ISO-8601 strings"}}})

(def task-schema
  {:name   :scheduler-task
   :type   :map
   :schema {:id            {:type        :keyword :validate schema/present? :message "must be present"
                            :description "Stable task identifier used for registration and cancellation"}
            :trigger       {:type        :map :schema (:schema trigger-schema) :validate schema/present? :message "must be present"
                            :description "Scheduling trigger definition"}
            :handler       {:type :fn
                            :validations [schema/required]
                            :description "Function invoked when the task fires"}
            :coalesce      {:type :keyword
                            :validations [[:maybe? [:one-of :queue :skip]]]
                            :description "Overlap policy for due fires while a prior run is still active; supported values are :queue and :skip"}
            :on-error       {:type :keyword
                             :validations [[:maybe? [:one-of :log :retry]]]
                             :description "Handler failure policy; supported values are :log and :retry"}
            :backoff-ms     {:type :long
                             :validations [[:maybe? :pos?]]
                             :description "Initial retry delay in milliseconds when :on-error is :retry. Defaults to 1000."}
            :max-backoff-ms {:type :long
                             :validations [[:maybe? :pos?]]
                             :description "Cap on exponentially growing backoff in milliseconds. Defaults to 60000."}
            :retry-attempts {:type :long
                             :validations [[:maybe? :pos?]]
                             :description "Number of consecutive failures after which a :retry task is disabled. Defaults to 3."}
            :timeout-ms    {:type :long
                            :validations [[:maybe? :pos?]]
                            :description "Maximum runtime in milliseconds before interrupting a handler"}}})

(defn- cron-next-time [{:keys [expr zone]} reference]
  (let [zone-id       (ZoneId/of (or zone (str (ZoneId/systemDefault))))
        reference-zdt (ZonedDateTime/ofInstant reference zone-id)]
    (some-> (cron/next-fire-at expr reference-zdt reference-zdt zone-id)
            .toInstant)))

(defn- next-time [{:keys [kind ms expr zone instant]} now]
  (case kind
    :interval (.plusMillis now ms)
    :delay    (.plusMillis now ms)
    :cron     (cron-next-time {:expr expr :zone zone} now)
    :at       instant))

(defn- ^ThreadFactory daemon-thread-factory []
  (let [counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable)
          (.setDaemon true)
          (.setName (str "isaac-scheduler-" (.incrementAndGet counter))))))))

(defn create
  "Creates a scheduler runtime value.

   The returned scheduler is explicit state passed to `schedule!`, `tick!`,
   `start!`, `stop!`, and `shutdown!`. Integration layers may also register
   it in `isaac.nexus` as the process-wide shared scheduler.

   Backed by a single `ScheduledExecutorService` that carries handler runs,
   timeout watchers, and the tick loop. Default pool size is
   `(max 2 (* 2 cpu-count))`; override via `:pool-size`. Threads are daemons
   named `isaac-scheduler-N`."
  [{:keys [clock pool-size]}]
  (let [size     (or pool-size (default-pool-size))
        executor (Executors/newScheduledThreadPool size (daemon-thread-factory))]
    {:clock       (or clock #(Instant/now))
     :tick-ms     default-tick-ms
     :pool-size   size
     :executor    executor
     :tasks       (atom {})
     :running?    (atom false)
     :tick-future (atom nil)}))

(defn- coalesce-mode [task]
  (or (:coalesce task) :queue))

(defn- on-error-mode [task]
  (or (:on-error task) :log))

(defn- done? [task]
  (and (nil? (:next-fire-at task))
       (empty? (:pending-fire-ats task))
       (nil? (:active-run task))))

(declare finish-run! timeout-run! begin-run!)

(defn- swap-with-action!
  "Like swap! but `f` returns [new-value action]. Returns the action from
   the CAS that won — never from a retried losing attempt. Lets transition
   logic stay pure: effects (logging, thread start, interrupt) run after
   the swap commits."
  [a f]
  (loop []
    (let [old @a
          [new action] (f old)]
      (if (compare-and-set! a old new)
        action
        (recur)))))

(defn- build-run
  "Pure. Returns a run claim with no executor handles yet."
  [_scheduler _id task scheduled-at]
  {:token        (str (UUID/randomUUID))
   :scheduled-at scheduled-at
   :timeout-ms   (:timeout-ms task)
   :handler      (:handler task)})

(defn- handler-runnable [scheduler id run]
  ^Runnable
  (fn []
    (try
      ((:handler run) {:id id :scheduled-at (:scheduled-at run) :now ((:clock scheduler))})
      (finish-run! scheduler id (:token run) :success nil (:scheduled-at run))
      (catch InterruptedException e
        (finish-run! scheduler id (:token run) :interrupted e (:scheduled-at run)))
      (catch Exception e
        (finish-run! scheduler id (:token run) :error e (:scheduled-at run))))))

(defn- begin-run!
  "Submits the handler runnable and (optionally) a timeout watcher to the
   scheduler's executor. Stores both futures back into the task's active-run
   so finish-run!/timeout-run!/shutdown! can cancel them. If the active-run
   slot has already been replaced (a race with timeout/cancel), the futures
   are still safe: handler future just runs and its finish-run! call will
   token-mismatch into a no-op; timeout future will token-mismatch too."
  [scheduler id run]
  (let [^ScheduledExecutorService executor (:executor scheduler)
        ^Runnable runnable (handler-runnable scheduler id run)
        ^Future   handler-future (.submit executor runnable)
        ^ScheduledFuture timeout-future
        (when-let [tms (:timeout-ms run)]
          (.schedule executor
                     ^Runnable (fn [] (timeout-run! scheduler id (:token run) (:scheduled-at run)))
                     ^long tms
                     TimeUnit/MILLISECONDS))]
    (swap! (:tasks scheduler)
           (fn [tasks]
             (if (= (:token run) (get-in tasks [id :active-run :token]))
               (-> tasks
                   (assoc-in [id :active-run :future]         handler-future)
                   (assoc-in [id :active-run :timeout-future] timeout-future))
               tasks)))))

(defn- next-run-action [scheduler id task scheduled-at]
  (let [run (build-run scheduler id task scheduled-at)]
    {:id id :run run}))

(defn- exponential-backoff-ms
  "Computes the next retry delay: min(max-ms, base-ms * 2^(n-1)), capped
   at 2^30 multiplier to stay well clear of long overflow."
  [base-ms max-ms consecutive-errors]
  (let [shift (max 0 (min 30 (dec consecutive-errors)))]
    (min max-ms (* base-ms (long (Math/pow 2 shift))))))

(defn- after-error
  "Pure. Returns [new-task notes] where notes is a map of side-effect data
   (log payloads, etc.) for the caller to act on after the swap commits."
  [task scheduled-at error]
  (let [consecutive-errors (inc (or (:consecutive-errors task) 0))
        task               (assoc task :consecutive-errors consecutive-errors)
        error-note         {:handler-error {:id           (:id task)
                                            :scheduled-at scheduled-at
                                            :error-msg    (.getMessage ^Exception error)}}]
    (case (on-error-mode task)
      :retry
      (if (>= consecutive-errors (:retry-attempts task))
        [(assoc task :pending-fire-ats [] :next-fire-at nil :disabled? true)
         (assoc error-note :disabled {:id (:id task) :attempts consecutive-errors})]
        (let [delay-ms (exponential-backoff-ms (:backoff-ms task)
                                                (:max-backoff-ms task)
                                                consecutive-errors)]
          [(-> task
               (assoc :pending-fire-ats [])
               (assoc :next-fire-at (.plusMillis scheduled-at delay-ms)))
           error-note]))

      [task error-note])))

(defn- due-fires [task now]
  (loop [scheduled-at (:next-fire-at task)
         task         task
         fires        []]
    (if (and scheduled-at (not (.isAfter ^Instant scheduled-at now)))
      (if (#{:delay :at} (get-in task [:trigger :kind]))
        {:fires (conj fires scheduled-at)
         :task  (assoc task :next-fire-at nil :remaining-fires 0)}
        (let [next-at (next-time (:trigger task) scheduled-at)]
          (recur next-at
                 (assoc task :next-fire-at next-at)
                 (conj fires scheduled-at))))
      {:fires fires :task task})))

(defn- enqueue-fires [task fires]
  (if (= :skip (coalesce-mode task))
    task
    (update task :pending-fire-ats into fires)))

(defn- plan-due-run [scheduler id task fires]
  (let [task   (assoc task :pending-fire-ats (if (= :skip (coalesce-mode task)) [] (vec (rest fires))))
        action (next-run-action scheduler id task (first fires))]
    {:task   (assoc task :active-run (:run action))
     :action action}))

(defn- compute-finish-transition
  "Pure. Returns [new-tasks notes]. Notes carry side-effect data:
   `:next-action`, `:handler-error`, `:disabled`, `:timeout-future-to-cancel`."
  [scheduler id token outcome error scheduled-at tasks]
  (if-let [task (get tasks id)]
    (if (= token (get-in task [:active-run :token]))
      (let [timeout-future (get-in task [:active-run :timeout-future])
            task           (assoc task :active-run nil)
            [task notes]   (case outcome
                             :success     [(assoc task :consecutive-errors 0) {}]
                             :error       (after-error task scheduled-at error)
                             :interrupted [task {}]
                             [task {}])
            notes          (cond-> notes
                             timeout-future (assoc :timeout-future-to-cancel timeout-future))]
        (cond
          (:disabled? task)
          [(dissoc tasks id) notes]

          (and (= :error outcome) (= :retry (on-error-mode task)))
          [(assoc tasks id task) notes]

          (and (not= :interrupted outcome) (seq (:pending-fire-ats task)))
          (let [next-task   (assoc task :pending-fire-ats (vec (rest (:pending-fire-ats task))))
                next-action (next-run-action scheduler id task (first (:pending-fire-ats task)))]
            [(assoc tasks id (assoc next-task :active-run (:run next-action)))
             (assoc notes :next-action next-action)])

          (done? task)
          [(dissoc tasks id) notes]

          :else
          [(assoc tasks id task) notes]))
      [tasks {}])
    [tasks {}]))

(defn- finish-run!
  [scheduler id token outcome error scheduled-at]
  (let [{:keys [next-action handler-error disabled timeout-future-to-cancel]}
        (swap-with-action! (:tasks scheduler)
                           #(compute-finish-transition scheduler id token outcome error scheduled-at %))]
    (when-let [^ScheduledFuture tf timeout-future-to-cancel]
      (.cancel tf false))
    (when-let [{:keys [id scheduled-at error-msg]} handler-error]
      (log/error :scheduler/handler-error :id id :scheduled-at (str scheduled-at) :error error-msg))
    (when-let [{:keys [id attempts]} disabled]
      (log/warn :scheduler/disabled :id id :reason :too-many-errors :attempts attempts))
    (when next-action
      (begin-run! scheduler id (:run next-action)))))

(defn- compute-timeout-transition
  "Pure. Returns [new-tasks notes] with `:future-to-cancel` and `:timed-out`."
  [id token scheduled-at tasks]
  (if-let [task (get tasks id)]
    (if (= token (get-in task [:active-run :token]))
      [(assoc tasks id (-> task (assoc :active-run nil) (assoc :pending-fire-ats [])))
       {:future-to-cancel (get-in task [:active-run :future])
        :timed-out        {:id (:id task) :scheduled-at scheduled-at}}]
      [tasks {}])
    [tasks {}]))

(defn- timeout-run!
  [scheduler id token scheduled-at]
  (let [{:keys [future-to-cancel timed-out]}
        (swap-with-action! (:tasks scheduler)
                           #(compute-timeout-transition id token scheduled-at %))]
    (when-let [{:keys [id scheduled-at]} timed-out]
      (log/warn :scheduler/timeout :id id :scheduled-at (str scheduled-at)))
    (when-let [^Future f future-to-cancel]
      (.cancel f true))))

(defn running?
  "Returns true when the scheduler's background tick loop is running."
  [scheduler]
  (boolean @(:running? scheduler)))

(defn list-tasks
  "Returns the currently scheduled tasks in registration order."
  [scheduler]
  (->> @(:tasks scheduler)
       vals
       (sort-by :created-at)
       vec))

(def ^:private default-backoff-ms     1000)
(def ^:private default-max-backoff-ms 60000)
(def ^:private default-retry-attempts 3)

(defn- apply-retry-defaults [task]
  (if (= :retry (on-error-mode task))
    (cond-> task
      (nil? (:backoff-ms     task)) (assoc :backoff-ms     default-backoff-ms)
      (nil? (:max-backoff-ms task)) (assoc :max-backoff-ms default-max-backoff-ms)
      (nil? (:retry-attempts task)) (assoc :retry-attempts default-retry-attempts))
    task))

(defn schedule!
  "Registers a repeating or one-shot task.

   Task shape is validated against `task-schema`. Re-registering an existing
   `:id` throws. When `:on-error` is `:retry`, missing `:backoff-ms`,
   `:max-backoff-ms`, and `:retry-attempts` are filled with defaults
   (1000ms, 60000ms, 3 respectively)."
  [scheduler task]
  (let [now            ((:clock scheduler))
        validated-task (schema/conform! task-schema task)
        validated-task (apply-retry-defaults validated-task)
        task           (assoc validated-task
                         :created-at now
                         :next-fire-at (next-time (:trigger validated-task) now)
                         :remaining-fires (when (#{:delay :at} (get-in validated-task [:trigger :kind])) 1)
                         :pending-fire-ats []
                         :consecutive-errors 0
                         :active-run nil)]
    (swap! (:tasks scheduler)
           (fn [tasks]
              (when (contains? tasks (:id task))
               (throw (ex-info (str "task already scheduled: " (:id task)) {:id (:id task)})))
             (assoc tasks (:id task) task)))
    task))

(defn cancel!
  "Cancels the task with the given id. Unknown ids are a silent no-op."
  [scheduler id]
  (swap! (:tasks scheduler) dissoc id)
  nil)

(defn schedule-once!
  "Registers a one-shot task. Alias for `schedule!`; one-shot semantics come
   from `:delay` and `:at` triggers."
  [scheduler task]
  (schedule! scheduler task))

;; ----- Convenience constructors --------------------------------------------
;; Thin wrappers that build a task map for the common trigger kinds. Each
;; returns the auto-generated task id so callers can later `cancel!` it.
;; Pass `:opts` for extra task fields (`:on-error`, `:timeout-ms`, etc).

(defn- auto-id []
  (keyword "isaac.sched.auto" (str (UUID/randomUUID))))

(defn every!
  "Schedules a repeating task that fires every `ms` milliseconds."
  ([scheduler ms handler]
   (every! scheduler ms handler {}))
  ([scheduler ms handler opts]
   (let [id (auto-id)]
     (schedule! scheduler (merge opts {:id id :trigger {:kind :interval :ms ms} :handler handler}))
     id)))

(defn after!
  "Schedules a one-shot task that fires once after `ms` milliseconds.
   `ms` ≤ 0 is normalized to 1ms (the minimum), making `(after! sched 0 ...)`
   a fire-and-forget alias for \"run on the next tick.\""
  ([scheduler ms handler]
   (after! scheduler ms handler {}))
  ([scheduler ms handler opts]
   (let [ms (max 1 ms)
         id (auto-id)]
     (schedule! scheduler (merge opts {:id id :trigger {:kind :delay :ms ms} :handler handler}))
     id)))

(defn at!
  "Schedules a one-shot task that fires at the absolute `instant`."
  ([scheduler instant handler]
   (at! scheduler instant handler {}))
  ([scheduler instant handler opts]
   (let [id (auto-id)]
     (schedule! scheduler (merge opts {:id id :trigger {:kind :at :instant instant} :handler handler}))
     id)))

(defn cron!
  "Schedules a recurring task driven by a cron expression. `zone` is an
   optional IANA name; omitted, the JVM's default zone is used."
  ([scheduler expr handler]
   (cron! scheduler expr nil handler {}))
  ([scheduler expr zone handler]
   (cron! scheduler expr zone handler {}))
  ([scheduler expr zone handler opts]
   (let [id      (auto-id)
         trigger (cond-> {:kind :cron :expr expr}
                   zone (assoc :zone zone))]
     (schedule! scheduler (merge opts {:id id :trigger trigger :handler handler}))
     id)))

(defn- compute-tick-transition
  "Pure. Recomputes due-fires against the *current* tasks map so the
   transition stays consistent if a concurrent finish-run!/timeout-run!
   already updated :next-fire-at or :active-run between the doseq
   snapshot and the swap.

   Returns [new-tasks {:next-action ...}|{}]."
  [scheduler id now tasks]
  (if-let [current (get tasks id)]
    (let [{:keys [fires task]} (due-fires current now)]
      (if (empty? fires)
        [tasks {}]
        (let [current (assoc current
                        :next-fire-at (:next-fire-at task)
                        :remaining-fires (:remaining-fires task))]
          (if (:active-run current)
            [(assoc tasks id (enqueue-fires current fires)) {}]
            (let [{:keys [task action]} (plan-due-run scheduler id current fires)]
              [(assoc tasks id task) {:next-action action}])))))
    [tasks {}]))

(defn tick!
  "Runs all tasks whose `:next-fire-at` is due according to the scheduler clock."
  [scheduler]
  (let [now ((:clock scheduler))]
    (doseq [[id _] @(:tasks scheduler)]
      (let [{:keys [next-action]}
            (swap-with-action! (:tasks scheduler)
                               #(compute-tick-transition scheduler id now %))]
        (when next-action
          (begin-run! scheduler id (:run next-action))))))
  nil)

(defn start!
  "Starts the scheduler's background tick loop if it is not already running.
   Uses `ScheduledExecutorService.scheduleAtFixedRate` so the tick lands at a
   regular cadence even under load."
  [scheduler]
  (when-not (running? scheduler)
    (reset! (:running? scheduler) true)
    (let [^ScheduledExecutorService executor (:executor scheduler)
          tick-ms                            (:tick-ms scheduler)
          tick-future (.scheduleAtFixedRate executor
                                            ^Runnable (fn [] (tick! scheduler))
                                            0
                                            ^long tick-ms
                                            TimeUnit/MILLISECONDS)]
      (reset! (:tick-future scheduler) tick-future)))
  scheduler)

(defn stop!
  "Halts the background tick loop. Scheduled tasks are preserved so that
   calling `start!` again resumes ticking against the same state. Any
   handler runs currently in flight continue to completion; their state
   transitions land back in the task atom even after the loop is stopped.

   For a hard tear-down — cancel active runs and drop all tasks — call
   `shutdown!` instead."
  [scheduler]
  (reset! (:running? scheduler) false)
  (when-let [^ScheduledFuture tf @(:tick-future scheduler)]
    (.cancel tf false))
  (reset! (:tick-future scheduler) nil)
  nil)

(defn shutdown!
  "Hard tear-down: stops the tick loop, cancels active handler runs,
   clears all tasks, and shuts down the executor. Use at process/server
   shutdown."
  [scheduler]
  (stop! scheduler)
  (doseq [[_ task] @(:tasks scheduler)]
    (when-let [^Future f (get-in task [:active-run :future])]
      (.cancel f true))
    (when-let [^ScheduledFuture tf (get-in task [:active-run :timeout-future])]
      (.cancel tf false)))
  (reset! (:tasks scheduler) {})
  (when-let [^ScheduledExecutorService executor (:executor scheduler)]
    (.shutdownNow executor))
  nil)
