(ns isaac.reconfigurable-spec
  (:require
    [isaac.reconfigurable :as sut]
    [speclj.core :refer :all]))

(deftype RecordingNode [state]
  sut/Reconfigurable
  (on-load [_ slice]
    (swap! state assoc :load slice))
  (on-config-change! [_ old-slice new-slice]
    (swap! state assoc :change [old-slice new-slice]))
  (on-unload [_ slice]
    (swap! state assoc :unload slice)))

(describe "isaac.reconfigurable"

  (it "supports on-load, on-config-change!, and on-unload dispatch"
    (let [state    (atom {})
          instance (->RecordingNode state)]
      (sut/on-load instance {:status :loaded})
      (sut/on-config-change! instance {:status :loaded} {:status :changed})
      (sut/on-unload instance {:status :loaded})
      (should= {:load    {:status :loaded}
                :change  [{:status :loaded} {:status :changed}]
                :unload  {:status :loaded}}
               @state))))