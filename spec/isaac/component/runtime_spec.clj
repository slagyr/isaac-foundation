(ns isaac.component.runtime-spec
  (:require
    [isaac.logger :as log]
    [isaac.component.fixture.alpha]
    [isaac.component.fixture.bravo]
    [isaac.component.fixture.events :as events]
    [isaac.component.fixture.widget]
    [isaac.component.protocol :as protocol]
    [isaac.component.registry :as registry]
    [isaac.component.runtime :as sut]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(defn- alpha-entry []
  {:manifest {:deps {:isaac.component.bravo {}}
              :isaac/component {:alpha {:namespace 'isaac.component.fixture.alpha}}}})

(defn- bravo-entry []
  {:manifest {:isaac/component {:bravo {:namespace 'isaac.component.fixture.bravo}}}})

(defn- widget-entry []
  {:manifest {:isaac/component {:widget {:namespace 'isaac.component.fixture.widget}}}})

(describe "component runtime"

  (helper/with-captured-logs)

  (before
    (events/clear!)
    (sut/reset-state!)
    (reset! registry/*registry* (registry/fresh-registry)))

  (it "starts components in module topological order"
    (let [module-index {:isaac.component.bravo (bravo-entry)
                        :isaac.component.alpha (alpha-entry)}]
      (sut/start-all! module-index)
      (should= [[:bravo :start] [:alpha :start]] (events/events))
      (sut/stop-all!)
      (should= [[:bravo :start] [:alpha :start] [:alpha :stop] [:bravo :stop]] (events/events))))

  (it "logs :component/started for each started component"
    (let [module-index {:isaac.component.widget (widget-entry)}]
      (sut/start-all! module-index)
      (sut/stop-all!)
      (should (some #(= :component/started (:event %)) @log/captured-logs))
      (should (some #(= :component/stopped (:event %)) @log/captured-logs))))

  (it "rolls back already-started components when a later start fails"
    (let [module-index {:isaac.component.bravo (bravo-entry)
                        :isaac.component.alpha (alpha-entry)}
          real-run-start! protocol/run-start!
          start-count     (atom 0)]
      (with-redefs [protocol/run-start! (fn [instance]
                                          (swap! start-count inc)
                                          (if (= 2 @start-count)
                                            (throw (ex-info "boom" {}))
                                            (real-run-start! instance)))]
        (should-throw (sut/start-all! module-index))
        (should= [[:bravo :start] [:bravo :stop]] (events/events))
        (should= [] (sut/started-components))
        (should= nil (registry/instance-for :bravo))))))