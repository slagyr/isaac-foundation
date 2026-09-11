(ns isaac.component.factory-spec
  (:require
    [isaac.component.factory :as sut]
    [isaac.component.fixture.widget]
    [isaac.component.protocol :as protocol]
    [isaac.schema.registered-in :as registered-in]
    [speclj.core :refer :all]))

(describe "component factory"

  (it "creates a component instance for a contributed id"
    (binding [registered-in/*module-index*
              {:isaac.component.widget
               {:manifest {:isaac/component
                            {:widget {:namespace 'isaac.component.fixture.widget}}}}}]
      (should (satisfies? protocol/Component
                          (sut/create! :widget {:component-id :widget}))))))