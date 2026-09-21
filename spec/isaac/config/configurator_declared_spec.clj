(ns isaac.config.configurator-declared-spec
  (:require
    [isaac.config.configurator :as sut]
    [speclj.core :refer :all]))

(defn fake-factory [& _] ::instance)

(describe "declared reconcilable components (isaac-bbe0)"

  (it "finds nothing when no module declares one"
    (should= [] (sut/declared-registries {:isaac.thing {:manifest {}}})))

  (it "builds a registry from what a module declared"
    (let [index {:isaac.hooks
                 {:manifest {:isaac.config/component
                             {:hooks {:path [:hooks]
                                      :impl "hooks"
                                      :factory 'isaac.config.configurator-declared-spec/fake-factory}}}}}
          [registry :as all] (sut/declared-registries index)]
      (should= 1 (count all))
      (should= :component (:kind registry))
      (should= [:hooks] (:path registry))
      (should= "hooks" (:impl registry))
      (should= ::instance ((:factory registry)))))

  (it "collects declarations across modules"
    (let [index {:a {:manifest {:isaac.config/component
                                {:one {:path [:hooks] :factory 'isaac.config.configurator-declared-spec/fake-factory}}}}
                 :b {:manifest {:isaac.config/component
                                {:two {:path [:cron] :factory 'isaac.config.configurator-declared-spec/fake-factory}}}}}]
      (should= #{[:hooks] [:cron]} (set (mapv :path (sut/declared-registries index))))))

  (it "skips a declaration whose factory cannot be resolved, and says so"
    ;; A module that is not installed is simply not declared; a declaration
    ;; that points at nothing is a bug worth a log line, not a crash.
    (let [index {:a {:manifest {:isaac.config/component
                                {:gone {:path [:nope] :factory 'no.such.ns/missing}}}}}]
      (should= [] (sut/declared-registries index))))
  )
