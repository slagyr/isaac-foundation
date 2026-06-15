(ns isaac.scheduler-steps
  "Foundation-grade scheduler fixture/assertion steps for gherclj features and
   step specs. Depends only on foundation scheduler runtime and nexus."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.spec-helper :as helper]
    [isaac.step-tables :as match])
  (:import
    (java.time Instant OffsetDateTime)))

(helper! isaac.scheduler-steps)

(defn- unquote-string [value]
  (when-not (nil? value)
    (let [value (str value)]
      (if (and (<= 2 (count value))
               (str/starts-with? value "\"")
               (str/ends-with? value "\""))
        (subs value 1 (dec (count value)))
        value))))

(defn- task-id->string [id]
  (if-let [ns (namespace id)]
    (str ns "/" (name id))
    (name id)))

(defn- cell-value [value]
  (let [value (some-> value unquote-string)]
    (when-not (str/blank? value)
      value)))

(defn- present-task [task]
  (assoc task :id (task-id->string (:id task))))

(defn- parse-instant [value]
  (try
    (Instant/parse (unquote-string value))
    (catch Exception _
      (.toInstant (OffsetDateTime/parse (unquote-string value))))))

(defn- parse-duration-ms [value]
  (let [value (unquote-string value)]
    (cond
      (nil? value) nil
      (re-matches #"\d+ms" value) (parse-long (subs value 0 (- (count value) 2)))
      (re-matches #"\d+s" value) (* 1000 (parse-long (subs value 0 (dec (count value)))))
      (re-matches #"\d+" value) (parse-long value)
      :else (throw (ex-info "unsupported duration" {:value value})))))

(defn- bool-value [value]
  (= "true" (str/lower-case (str value))))

(defn- handler-counts* []
  (or (g/get :scheduler-handler-counts*)
      (let [counts* (atom {})]
        (g/assoc! :scheduler-handler-counts* counts*)
        counts*)))

(defn- handler-count [id]
  (get @(handler-counts*) id 0))

(defn- current-scheduler []
  (or (g/get :scheduler)
      (nexus/get :scheduler)))

(defn- task-row->task [table]
  (let [row     (zipmap (:headers table) (first (:rows table)))
        id      (keyword (cell-value (get row "id")))
        runtime (parse-duration-ms (cell-value (get row "handler-runtime")))
        throws? (bool-value (cell-value (get row "handler-throws")))
        counts* (handler-counts*)]
    {:id            id
     :trigger       (cond-> {:kind (keyword (cell-value (get row "trigger.kind")))}
                      (cell-value (get row "trigger.ms"))      (assoc :ms (parse-duration-ms (cell-value (get row "trigger.ms"))))
                      (cell-value (get row "trigger.expr"))    (assoc :expr (cell-value (get row "trigger.expr")))
                      (cell-value (get row "trigger.zone"))    (assoc :zone (cell-value (get row "trigger.zone")))
                      (cell-value (get row "trigger.instant")) (assoc :instant (cell-value (get row "trigger.instant"))))
     :coalesce       (some-> (cell-value (get row "coalesce")) keyword)
     :on-error       (some-> (cell-value (get row "on-error")) keyword)
     :backoff-ms     (some-> (cell-value (get row "backoff-ms")) parse-duration-ms)
     :max-backoff-ms (some-> (cell-value (get row "max-backoff-ms")) parse-duration-ms)
     :retry-attempts (some-> (cell-value (get row "retry-attempts")) parse-long)
     :timeout-ms     (some-> (cell-value (get row "timeout-ms")) parse-duration-ms)
     :handler       (fn [_]
                      (swap! counts* update (name id) (fnil inc 0))
                      (when runtime
                        (Thread/sleep runtime))
                      (when throws?
                        (throw (ex-info "scheduler handler failed" {:id id}))))}))

(defn- start-scheduler! [iso]
  (let [clock*   (atom (parse-instant iso))
        instance (-> (scheduler/create {:clock (fn [] @clock*)})
                     scheduler/start!)]
    (log/set-output! :memory)
    (log/clear-entries!)
    (g/assoc! :scheduler-clock* clock*)
    (g/assoc! :scheduler instance)
    (nexus/reset!)
    (nexus/init!)
    (nexus/register! [:scheduler] instance)))

(defn- stop-scheduler! []
  (when-let [instance (current-scheduler)]
    (scheduler/shutdown! instance))
  (log/clear-entries!))

(g/after-scenario stop-scheduler!)

(defn- advance-clock! [duration]
  (when-let [clock* (g/get :scheduler-clock*)]
    (swap! clock* #(.plusMillis ^Instant % (parse-duration-ms duration)))))

(defn- set-clock! [iso]
  (when-let [clock* (g/get :scheduler-clock*)]
    (reset! clock* (parse-instant iso))))

(defn scheduler-started [iso]
  (start-scheduler! iso))

(defn scheduled-task [table]
  (scheduler/schedule! (current-scheduler) (task-row->task table)))

(defn clock-advances [duration]
  (advance-clock! duration)
  (scheduler/tick! (current-scheduler)))

(defn clock-advances-and-settles [duration]
  (clock-advances duration))

(defn clock-advances-to [iso]
  (set-clock! iso)
  (scheduler/tick! (current-scheduler)))

(defn scheduler-ticks []
  (scheduler/tick! (current-scheduler)))

(defn scheduler-stops []
  (scheduler/stop! (current-scheduler)))

(defn scheduler-shuts-down []
  (scheduler/shutdown! (current-scheduler)))

(defn ask-for-scheduled-tasks []
  (g/assoc! :scheduled-tasks (scheduler/list-tasks (current-scheduler))))

(defn cancel-task [id]
  (scheduler/cancel! (current-scheduler) (keyword (unquote-string id))))

(defn attempt-schedule-task [table]
  (g/assoc! :scheduler-error
            (try
              (scheduler/schedule! (current-scheduler) (task-row->task table))
              nil
              (catch clojure.lang.ExceptionInfo e
                e))))

(defn handler-fired [id count]
  (let [id    (unquote-string id)
        count (parse-long (str count))]
    (helper/await-condition #(= count (handler-count id)) 3000)
    (g/should= count (handler-count id))))

(defn handler-did-not-fire [id]
  (g/should= 0 (handler-count (unquote-string id))))

(defn scheduled-tasks-include [table]
  (let [result* (atom nil)]
    (helper/await-condition
      (fn []
        (let [tasks  (mapv present-task (scheduler/list-tasks (current-scheduler)))
              result (match/match-entries table tasks)]
          (reset! result* result)
          (empty? (:failures result))))
      3000)
    (g/should= [] (:failures @result*))))

(defn scheduled-tasks-do-not-include [id]
  (let [id (unquote-string id)]
    (helper/await-condition
      #(not (some (fn [task] (= id (:id task)))
                  (map present-task (scheduler/list-tasks (current-scheduler)))))
      3000)
    (g/should-not (some #(= id (:id %)) (map present-task (scheduler/list-tasks (current-scheduler)))))))

(defn scheduled-tasks-are-empty []
  (helper/await-condition #(empty? (scheduler/list-tasks (current-scheduler))) 3000)
  (g/should= [] (scheduler/list-tasks (current-scheduler))))

(defn no-error-is-logged []
  (g/should-not (some #(= :error (:level %)) (log/get-entries))))

(defn error-raised [pattern]
  (let [message (some-> (g/get :scheduler-error) .getMessage)]
    (g/should (some? message))
    (g/should (re-find (re-pattern (unquote-string pattern)) message))))

(defn scheduled-tasks-include-id [id]
  (let [id (unquote-string id)]
    (helper/await-condition
      #(some (fn [task] (= id (:id task)))
             (map present-task (scheduler/list-tasks (current-scheduler))))
      3000)))

(defgiven "the scheduler is started with the clock at {string}" isaac.scheduler-steps/scheduler-started)

(defgiven "a scheduled task:" isaac.scheduler-steps/scheduled-task)

(defwhen "the clock advances {string}" isaac.scheduler-steps/clock-advances)

(defwhen "the clock advances {string} and pending handlers complete" isaac.scheduler-steps/clock-advances-and-settles)

(defwhen "the clock advances to {string}" isaac.scheduler-steps/clock-advances-to)

(defwhen "the scheduler ticks" isaac.scheduler-steps/scheduler-ticks)

(defwhen "the scheduler stops" isaac.scheduler-steps/scheduler-stops)

(defwhen "the scheduler shuts down" isaac.scheduler-steps/scheduler-shuts-down)

(defwhen "I ask for the scheduled tasks" isaac.scheduler-steps/ask-for-scheduled-tasks)

(defwhen "I cancel {string}" isaac.scheduler-steps/cancel-task)

(defwhen "I attempt to schedule a task:" isaac.scheduler-steps/attempt-schedule-task)

(defthen "handler {string} has fired {int} times" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} has fired {int} time" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} started {int} times" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} started {int} time" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} has not fired" isaac.scheduler-steps/handler-did-not-fire)

(defthen "the scheduled tasks include:" isaac.scheduler-steps/scheduled-tasks-include)

(defthen "the scheduled tasks do not include {string}" isaac.scheduler-steps/scheduled-tasks-do-not-include)

(defthen "the scheduled tasks include {string}" isaac.scheduler-steps/scheduled-tasks-include-id)

(defthen "the scheduled tasks are empty" isaac.scheduler-steps/scheduled-tasks-are-empty)

(defthen "no error is logged" isaac.scheduler-steps/no-error-is-logged)

(defthen "an error is raised with message matching {string}" isaac.scheduler-steps/error-raised)

(defthen #"an error is raised with message matching \"([^\"]+)\"" isaac.scheduler-steps/error-raised)