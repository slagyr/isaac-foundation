(ns isaac.component.fixture.alpha
  (:require
    [isaac.component.factory :as factory]
    [isaac.component.fixture.events :as events]
    [isaac.component.protocol :as protocol]))

(defn- component []
  (reify protocol/Component
    (start [_]
      (events/record! :alpha :start))
    (stop [_]
      (events/record! :alpha :stop))))

(defmethod factory/create :alpha [_ _ctx]
  (component))