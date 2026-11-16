(ns isaac.foundation.scheduler.calendar
  "Pure RRULE subset evaluation; local wall-clock candidates are resolved once per date."
  (:require
    [clojure.string :as str])
  (:import
    (java.time Duration Instant LocalDate LocalDateTime ZoneId ZonedDateTime)
    (java.time.format DateTimeFormatter)))

(def ^:private weekdays {"MO" 1 "TU" 2 "WE" 3 "TH" 4 "FR" 5 "SA" 6 "SU" 7})
(def ^:private allowed-frequencies #{"MINUTELY" "HOURLY" "DAILY" "WEEKLY" "MONTHLY" "YEARLY"})
(def ^:private ranges {"BYMONTHDAY" [1 31] "BYMONTH" [1 12] "BYHOUR" [0 23] "BYMINUTE" [0 59]})
(def ^:private local-format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss"))

(defn- bad! [part value]
  (throw (ex-info (str "invalid RRULE " part ": " value) {:part part :value value})))

(defn- positive [part value]
  (let [n (parse-long value)]
    (if (and n (pos? n)) n (bad! part value))))

(defn- numbers [part value]
  (let [[lo hi] (ranges part)
        values (str/split value #"," -1)]
    (set (map (fn [v]
                (let [n (parse-long v)]
                  (if (and n (<= lo n hi)) n (bad! part v)))) values))))

(defn- day-spec [value]
  (let [[_ nth day] (re-matches #"(-?[1-5])?(MO|TU|WE|TH|FR|SA|SU)" value)
        n (when nth (parse-long nth))]
    (if (and day (not= 0 n))
      {:nth n :weekday (weekdays day)}
      (bad! "BYDAY" value))))

(defn- parse-local [part value]
  (try
    (LocalDateTime/parse value local-format)
    (catch Exception _ (bad! part value))))

(defn parse-rule
  "Validate and parse an RRULE body. INTERVAL phase and COUNT require :dtstart."
  [rule dtstart]
  (let [parts (reduce (fn [m segment]
                        (let [[_ key value] (re-matches #"([A-Z]+)=(.+)" segment)]
                          (when-not key (bad! "part" segment))
                          (when-not (contains? (into #{"FREQ" "INTERVAL" "BYDAY" "COUNT" "UNTIL"} (keys ranges)) key)
                            (throw (ex-info (str "unsupported RRULE part: " key) {:part key})))
                          (when (contains? m key) (bad! key "duplicate"))
                          (assoc m key value)))
                      {} (str/split (or rule "") #";" -1))
        freq (get parts "FREQ")
        interval (if-let [v (get parts "INTERVAL")] (positive "INTERVAL" v) 1)
        count (when-let [v (get parts "COUNT")] (positive "COUNT" v))]
    (when-not (allowed-frequencies freq) (bad! "FREQ" freq))
    (when (and (> interval 1) (nil? dtstart))
      (throw (ex-info "INTERVAL > 1 requires :dtstart" {:rule rule})))
    (when (and count (nil? dtstart))
      (throw (ex-info "COUNT requires :dtstart" {:rule rule})))
    (when (and count (get parts "UNTIL")) (bad! "COUNT/UNTIL" "mutually exclusive"))
    {:freq freq :interval interval :count count
     :dtstart (when dtstart
                (try (LocalDateTime/parse dtstart)
                     (catch Exception _ (bad! "dtstart" dtstart))))
     :until (when-let [v (get parts "UNTIL")]
              (if (str/ends-with? v "Z")
                (try (Instant/parse (str (subs v 0 4) "-" (subs v 4 6) "-" (subs v 6 8)
                                         "T" (subs v 9 11) ":" (subs v 11 13) ":" (subs v 13 15) "Z"))
                     (catch Exception _ (bad! "UNTIL" v)))
                (parse-local "UNTIL" v)))
     :byday (when-let [v (get parts "BYDAY")] (mapv day-spec (str/split v #"," -1)))
     :fields (into {} (for [part (keys ranges) :when (get parts part)]
                        [part (numbers part (get parts part))]))}))

(defn- month-diff [^LocalDate anchor ^LocalDate date]
  (+ (* 12 (- (.getYear date) (.getYear anchor)))
     (- (.getMonthValue date) (.getMonthValue anchor))))

(defn- nth-weekday? [^LocalDate date n]
  (let [ordinal (if (pos? n)
                  (inc (quot (dec (.getDayOfMonth date)) 7))
                  (- (inc (quot (- (.lengthOfMonth date) (.getDayOfMonth date)) 7))))]
    (= n ordinal)))

(defn- date-matches? [{:keys [freq interval dtstart byday fields]} ^LocalDate date]
  (let [anchor (some-> ^LocalDateTime dtstart .toLocalDate)
        days (get fields "BYMONTHDAY")
        months (get fields "BYMONTH")
        delta (when anchor
                (case freq
                  "DAILY" (.between java.time.temporal.ChronoUnit/DAYS anchor date)
                  "WEEKLY" (quot (.between java.time.temporal.ChronoUnit/DAYS
                                         (.minusDays anchor (dec (.getValue (.getDayOfWeek anchor))))
                                         (.minusDays date (dec (.getValue (.getDayOfWeek date))))) 7)
                  "MONTHLY" (month-diff anchor date)
                  "YEARLY" (- (.getYear date) (.getYear anchor))
                  0))]
    (and (or (nil? anchor) (and (>= delta 0) (zero? (mod delta interval))))
         (or (nil? months) (contains? months (.getMonthValue date)))
         (or (nil? days) (contains? days (.getDayOfMonth date)))
         (or (nil? byday)
             (some (fn [{:keys [nth weekday]}]
                     (and (= weekday (.getValue (.getDayOfWeek date)))
                          (or (nil? nth) (nth-weekday? date nth)))) byday))
         (case freq
           "WEEKLY" (or byday (nil? anchor) (= (.getDayOfWeek date) (.getDayOfWeek anchor)))
           "MONTHLY" (or byday days (nil? anchor) (= (.getDayOfMonth date) (.getDayOfMonth anchor)))
           "YEARLY" (and (or months (nil? anchor) (= (.getMonthValue date) (.getMonthValue anchor)))
                           (or byday days (nil? anchor) (= (.getDayOfMonth date) (.getDayOfMonth anchor))))
           true))))

(defn- candidates [parsed ^LocalDate date]
  (let [{:keys [freq dtstart fields interval]} parsed
        hours (or (get fields "BYHOUR")
                  (if (#{"MINUTELY" "HOURLY"} freq) (range 24)
                      #{(if dtstart (.getHour ^LocalDateTime dtstart) 0)}))
        minutes (or (get fields "BYMINUTE")
                    (if (= freq "MINUTELY") (range 60)
                        #{(if (= freq "MINUTELY") (if dtstart (.getMinute ^LocalDateTime dtstart) 0) 0)}))]
    (for [hour (sort hours) minute (sort minutes)
          :let [local (.atTime date (int hour) (int minute))
                elapsed (when dtstart (.between java.time.temporal.ChronoUnit/MINUTES dtstart local))]
          :when (and (or (not (#{"MINUTELY" "HOURLY"} freq))
                         (nil? dtstart)
                         (and (>= elapsed 0)
                              (zero? (mod elapsed (* interval (if (= freq "HOURLY") 60 1)))))))]
      local)))

(defn- ->instant [^LocalDateTime local ^ZoneId zone]
  (when-let [offset (first (.getValidOffsets (.getRules zone) local))]
    (.toInstant (.atOffset local offset))))

(defn next-fire-at
  "First occurrence strictly after reference; nil when COUNT/UNTIL is exhausted.
   Skipped DST times do not consume COUNT; repeated times resolve at the first offset."
  [rule dtstart zone reference]
  (let [zone (ZoneId/of zone)
        parsed (parse-rule rule dtstart)
        start (or (:dtstart parsed) (.toLocalDateTime (ZonedDateTime/ofInstant reference zone)))
        begin (if (:count parsed) (.toLocalDate ^LocalDateTime start)
                  (.toLocalDate (ZonedDateTime/ofInstant reference zone)))
        end (.plusYears ^LocalDate begin 400)
        until (:until parsed)
        limit (when until (if (instance? Instant until) until (->instant until zone)))]
    (loop [date begin seen 0]
      (when (and (not (.isAfter ^LocalDate date end))
                 (or (nil? (:count parsed)) (< seen (:count parsed))))
        (let [result (reduce (fn [[seen found] local]
                               (if found (reduced [seen found])
                                 (if-let [instant (->instant local zone)]
                                   (if (and (not (.isBefore ^LocalDateTime local start))
                                            (or (nil? limit) (not (.isAfter ^Instant instant limit))))
                                     (let [seen (inc seen)]
                                       [seen (when (and (or (nil? (:count parsed)) (<= seen (:count parsed)))
                                                        (.isAfter ^Instant instant reference)) instant)])
                                     [seen nil])
                                   [seen nil])))
                             [seen nil] (if (date-matches? parsed date) (candidates parsed date) []))
              [seen found] result]
          (if found found
              (when (or (nil? limit) (not (.isAfter (.toInstant (.atStartOfDay (.plusDays ^LocalDate date 1) zone)) limit)))
                (recur (.plusDays ^LocalDate date 1) seen))))))))
