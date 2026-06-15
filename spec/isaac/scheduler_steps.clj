(ns isaac.scheduler-steps
  "Foundation-grade scheduler fixture/assertion steps for gherclj features and
   step specs. Depends only on foundation scheduler runtime and nexus."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [helper!]]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.spec-helper :as helper])
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

(defn- cell-value [value]
  (let [value (some-> value unquote-string)]
    (when-not (str/blank? value)
      value)))

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
    {:id      id
     :trigger (cond-> {:kind (keyword (cell-value (get row "trigger.kind")))}
                (cell-value (get row "trigger.ms")) (assoc :ms (parse-duration-ms (cell-value (get row "trigger.ms")))))
     :handler (fn [_]
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
    (g/assoc! :scheduler instance)))

(defn- advance-clock! [duration]
  (when-let [clock* (g/get :scheduler-clock*)]
    (swap! clock* #(.plusMillis ^Instant % (parse-duration-ms duration)))))

(defn scheduler-started [iso]
  (start-scheduler! iso))

(defn scheduled-task [table]
  (scheduler/schedule! (current-scheduler) (task-row->task table)))

(defn clock-advances-and-settles [duration]
  (advance-clock! duration)
  (scheduler/tick! (current-scheduler)))

(defn handler-fired [id count]
  (let [id    (unquote-string id)
        count (parse-long (str count))]
    (helper/await-condition #(= count (handler-count id)) 3000)
    (g/should= count (handler-count id))))