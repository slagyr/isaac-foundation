(ns isaac.foundation.component.fixture.widget
  (:require
    [isaac.foundation.component.factory :as factory]
    [isaac.foundation.component.fixture.events :as events]
    [isaac.foundation.component.protocol :as protocol]))

(defn- component []
  (reify protocol/Component
    (start [_]
      (events/record! :widget :start))
    (stop [_]
      (events/record! :widget :stop))))

(defmethod factory/create :widget [_ _ctx]
  (component))