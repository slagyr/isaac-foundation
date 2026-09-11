(ns isaac.component.fixture.widget
  (:require
    [isaac.component.factory :as factory]
    [isaac.component.fixture.events :as events]
    [isaac.component.protocol :as protocol]))

(defn- component []
  (reify protocol/Component
    (start [_]
      (events/record! :widget :start))
    (stop [_]
      (events/record! :widget :stop))))

(defmethod factory/create :widget [_ _ctx]
  (component))