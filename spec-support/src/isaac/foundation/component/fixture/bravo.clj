(ns isaac.foundation.component.fixture.bravo
  (:require
    [isaac.foundation.component.factory :as factory]
    [isaac.foundation.component.fixture.events :as events]
    [isaac.foundation.component.protocol :as protocol]))

(defn- component []
  (reify protocol/Component
    (start [_]
      (events/record! :bravo :start))
    (stop [_]
      (events/record! :bravo :stop))))

(defmethod factory/create :bravo [_ _ctx]
  (component))