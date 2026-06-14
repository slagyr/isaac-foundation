(ns isaac.reconfigurable-spec
  (:require
    [isaac.reconfigurable :as sut]
    [speclj.core :refer :all]))

(deftype RecordingNode [state]
  sut/Reconfigurable
  (on-startup! [_ slice]
    (swap! state assoc :startup slice))
  (on-config-change! [_ old-slice new-slice]
    (swap! state assoc :change [old-slice new-slice])))

(describe "isaac.reconfigurable"

  (it "supports on-startup! and on-config-change! dispatch"
    (let [state    (atom {})
          instance (->RecordingNode state)]
      (sut/on-startup! instance {:status :started})
      (sut/on-config-change! instance {:status :started} {:status :changed})
      (should= {:startup {:status :started}
                :change  [{:status :started} {:status :changed}]}
               @state))))