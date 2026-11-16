(ns isaac.foundation.scheduler.calendar-spec
  (:require
    [isaac.foundation.scheduler.calendar :as sut]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(defn next-at [rule dtstart reference]
  (sut/next-fire-at rule dtstart "America/Chicago" (Instant/parse reference)))

(describe "Calendar RRULE"
  (it "defaults a daily hour to minute zero"
    (should= (Instant/parse "2026-11-16T15:00:00Z")
             (next-at "FREQ=DAILY;BYHOUR=9" nil "2026-11-16T14:00:00Z")))
  (it "counts missed occurrences from dtstart"
    (should= nil
             (next-at "FREQ=DAILY;BYHOUR=9;COUNT=2" "2026-11-14T09:00:00" "2026-11-16T14:00:00Z")))
  (it "parses UTC UNTIL"
    (should= nil
             (next-at "FREQ=DAILY;BYHOUR=9;UNTIL=20261116T145900Z" nil "2026-11-16T14:00:00Z")))
  (it "rejects unsupported rule parts"
    (should-throw clojure.lang.ExceptionInfo #"BYSECOND"
                  (sut/parse-rule "FREQ=DAILY;BYSECOND=0" nil)))
  (it "keeps hour-phase from dtstart for hourly intervals"
    (should= (Instant/parse "2026-11-16T16:00:00Z")
             (next-at "FREQ=HOURLY;INTERVAL=2" "2026-11-16T08:00:00" "2026-11-16T14:30:00Z")))
  )
