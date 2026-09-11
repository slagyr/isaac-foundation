(ns isaac.component.fixture.bravo
  (:require
    [isaac.component.factory :as factory]
    [isaac.component.fixture.events :as events]
    [isaac.component.protocol :as protocol]))

(defn- component []
  (reify protocol/Component
    (start [_]
      (events/record! :bravo :start))
    (stop [_]
      (events/record! :bravo :stop))))

(defmethod factory/create :bravo [_ _ctx]
  (component))