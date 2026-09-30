(ns isaac.foundation.module.berth-registration-steps
  (:require
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.berths :as berths]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.nexus :as nexus]))

(helper! isaac.foundation.module.berth-registration-steps)

(defn register-demo-route!
  "No-op route factory for berth-registration feature fixtures."
  [_entry]
  nil)

(defn- route-berth-decl []
  {:description "HTTP routes (test fixture)."
   :schema      {:type       :map
                 :key-spec   {:type :keyword}
                 :value-spec {:type    :map
                              :factory 'isaac.foundation.module.berth-registration-steps/register-demo-route!
                              :schema  {:method  {:type :keyword}
                                        :path    {:type :string}
                                        :handler {:type :symbol}}}}})

(defn- demo-route-module-index [module-name]
  (let [module-id (keyword module-name)]
    {:isaac.http {:manifest {:berths {:isaac.http/route (route-berth-decl)}}}
     module-id    {:manifest {:isaac.http/route
                              {:ping {:method  :get
                                      :path    "/ping"
                                      :handler 'demo/ping-handler}}}}}))

(defn module-contributing-route-entry [module-name _entry-id]
  (g/assoc! :berth-registration-index (demo-route-module-index module-name)))

(defn server-boots []
  (log/clear-entries!)
  (let [index (or (g/get :berth-registration-index)
                  (discovery/builtin-index))]
    (berths/process-manifest-berths! index)))

(defn berth-registration-summary-present []
  (let [summary (first (filter #(= :berth/registration-summary (:event %))
                               (log/get-entries)))]
    (g/should= :berth/registration-summary (:event summary))
    (g/should (pos? (count (:counts summary))))))

;; region ----- Routing -----

(defgiven #"a module \"([^\"]+)\" contributing a :isaac\.server/route entry :(\w+)"
  isaac.foundation.module.berth-registration-steps/module-contributing-route-entry
  "Builds a test module-index with :isaac.http/route :ping from demo.")

(defwhen "the server boots" isaac.foundation.module.berth-registration-steps/server-boots
  "Runs process-manifest-berths! — the registration phase of server boot.")

(defthen "the log contains a berth registration summary"
  isaac.foundation.module.berth-registration-steps/berth-registration-summary-present
  "Asserts :berth/registration-summary was emitted with non-empty counts.")

;; endregion