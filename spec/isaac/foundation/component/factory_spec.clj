(ns isaac.foundation.component.factory-spec
  (:require
    [isaac.foundation.component.factory :as sut]
    [isaac.foundation.component.fixture.widget]
    [isaac.foundation.component.protocol :as protocol]
    [isaac.foundation.schema.registered-in :as registered-in]
    [speclj.core :refer :all]))

(describe "component factory"

  (it "creates a component instance for a contributed id"
    (binding [registered-in/*module-index*
              {:isaac.component.widget
               {:manifest {:isaac/component
                            {:widget {:namespace 'isaac.foundation.component.fixture.widget}}}}}]
      (should (satisfies? protocol/Component
                          (sut/create! :widget {:component-id :widget}))))))