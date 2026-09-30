(ns isaac.foundation.component.fixture.alpha
  (:require
    [isaac.foundation.component.factory :as factory]
    [isaac.foundation.component.fixture.events :as events]
    [isaac.foundation.component.protocol :as protocol]))

(defn- component []
  (reify protocol/Component
    (start [_]
      (events/record! :alpha :start))
    (stop [_]
      (events/record! :alpha :stop))))

(defmethod factory/create :alpha [_ _ctx]
  (component))