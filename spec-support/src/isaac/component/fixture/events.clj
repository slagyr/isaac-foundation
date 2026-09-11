(ns isaac.component.fixture.events)

(defonce timeline (atom []))

(defn record! [component-id event]
  (swap! timeline conj [(keyword (name component-id)) event]))

(defn clear! []
  (reset! timeline []))

(defn events []
  @timeline)