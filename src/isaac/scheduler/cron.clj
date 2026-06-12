(ns isaac.scheduler.cron
  (:require
    [clojure.string :as str])
  (:import
    (java.time Duration Instant LocalDateTime OffsetDateTime ZoneId ZonedDateTime)
    (java.time.format DateTimeFormatter)))

(def ^:private offset-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ"))

(def ^:private search-window-minutes
  (* 366 24 60))

(def ^:private field-ranges
  [{:name :minute  :min 0 :max 59}
   {:name :hour    :min 0 :max 23}
   {:name :day     :min 1 :max 31}
   {:name :month   :min 1 :max 12}
   {:name :weekday :min 0 :max 6}])

(defn format-zoned-date-time [zdt]
  (.format offset-formatter zdt))

(defn- cron-weekday [zdt]
  (let [value (-> zdt .getDayOfWeek .getValue)]
    (if (= 7 value) 0 value)))

(defn- zoned-date-time [value zone]
  (cond
    (instance? ZonedDateTime value) (.withZoneSameInstant ^ZonedDateTime value zone)
    (instance? OffsetDateTime value) (-> ^OffsetDateTime value .toInstant (ZonedDateTime/ofInstant zone))
    (instance? Instant value) (ZonedDateTime/ofInstant ^Instant value zone)
    (instance? LocalDateTime value) (.atZone ^LocalDateTime value zone)
    :else (throw (ex-info "unsupported cron timestamp" {:value value}))))

(defn- parse-int [s field]
  (try
    (Long/parseLong s)
    (catch Exception _
      (throw (ex-info "invalid cron field" {:field (:name field) :value s})))))

(defn- normalize-value [n field]
  (if (and (= :weekday (:name field)) (= 7 n))
    0
    n))

(defn- range-end [field end]
  (if (and (= :weekday (:name field)) (= 7 end))
    6
    end))

(defn- expand-range [start end step field]
  (let [start (normalize-value start field)
        end   (range-end field end)
        lo    (:min field)
        hi    (:max field)]
    (when (or (< start lo) (> start hi) (< end lo) (> end hi) (> start end) (<= step 0))
      (throw (ex-info "invalid cron range" {:field (:name field) :start start :end end :step step})))
    (set (take-while #(<= % end) (iterate #(+ % step) start)))))

(defn- expand-segment [segment field]
  (let [[base step-str] (str/split segment #"/" 2)
        step            (if step-str (parse-int step-str field) 1)]
    (cond
      (= "*" base)
      (expand-range (:min field) (:max field) step field)

      (str/includes? base "-")
      (let [[start end] (str/split base #"-" 2)]
        (expand-range (parse-int start field) (parse-int end field) step field))

      :else
      (let [value (normalize-value (parse-int base field) field)]
        (if step-str
          (expand-range value (:max field) step field)
          #{value})))))

(defn- parse-field [field expr]
  (->> (str/split expr #",")
       (mapcat #(expand-segment % field))
       set))

(defn- parse-expression [expr]
  (let [parts (str/split (or expr "") #"\s+")]
    (when-not (= 5 (count parts))
      (throw (ex-info "cron expressions must have 5 fields" {:expr expr})))
    (zipmap (map :name field-ranges)
            (map parse-field field-ranges parts))))

(defn- matches? [parsed zdt]
  (and (contains? (:minute parsed) (.getMinute zdt))
       (contains? (:hour parsed) (.getHour zdt))
       (contains? (:day parsed) (.getDayOfMonth zdt))
       (contains? (:month parsed) (.getMonthValue zdt))
       (contains? (:weekday parsed) (cron-weekday zdt))))

(defn- truncate-to-minute [zdt]
  (-> zdt
      (.withSecond 0)
      (.withNano 0)))

(defn- scan [_parsed cursor step-minutes matches-fn]
  (loop [candidate cursor
         remaining search-window-minutes]
    (cond
      (neg? remaining) nil
      (matches-fn candidate) candidate
      :else (recur (.plusMinutes candidate step-minutes) (dec remaining)))))

(defn next-fire-at [expr prev-fire now zone]
  (let [parsed  (parse-expression expr)
        zone    (or zone (ZoneId/systemDefault))
        start   (-> prev-fire (zoned-date-time zone) truncate-to-minute (.plusMinutes 1))
        limit   (-> now (zoned-date-time zone) (.plusYears 1))]
    (scan parsed start 1 #(and (not (.isAfter % limit)) (matches? parsed %)))))

(defn previous-fire-at [expr now zone]
  (let [parsed (parse-expression expr)
        zone   (or zone (ZoneId/systemDefault))
        start  (-> now (zoned-date-time zone) truncate-to-minute)]
    (scan parsed start -1 #(matches? parsed %))))

(defn late-by-ms [scheduled-at now]
  (.toMillis (Duration/between (.toInstant scheduled-at) (.toInstant now))))
