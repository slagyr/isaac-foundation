(ns isaac.module.berths-spec
  (:require
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.berths :as berths]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

;; ----- process-manifest-berths! helpers -----
;; The loader's `resolve-symbol!` is `requiring-resolve`, so test
;; factories need to be real namespaced fns. These live at the spec
;; namespace's top level so symbols like
;; isaac.module.berths-spec/record-route! resolve cleanly during tests.

(def ^:dynamic *factory-calls* nil)

(defn record-route!
  "Test factory: records the contribution entry into a per-example atom
   and registers it in the nexus at [::test-berth [<method> <path>]]
   so the spec can also assert the nexus side effect."
  [{:keys [method path handler] :as entry}]
  (when *factory-calls* (swap! *factory-calls* conj entry))
  (when (and method path)
    (nexus/register! [::test-berth [method path]] handler)))

(defn- berth-decl-with-factory [factory-sym]
  {:description "test berth"
   :schema      {:type :seq
                  :spec {:type    :map
                         :factory factory-sym
                         :schema  {:method  {:type :keyword}
                                   :path    {:type :string}
                                   :handler {:type :symbol}}}}})

(defn- index-with-berth+contributions
  "Build a module-index where `:provider` declares a berth with a
   per-entry factory and each consumer in `consumers` contributes the
   listed routes."
  [berth-id factory-sym consumers]
  (reduce-kv
    (fn [acc consumer-id routes]
      (assoc acc consumer-id {:manifest {berth-id (vec routes)}}))
    {:provider {:manifest {:berths {berth-id (berth-decl-with-factory factory-sym)}}}}
    consumers))

(describe "process-manifest-berths!"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (binding [*factory-calls* (atom [])]
        (example))))

  (it "invokes the entry-level factory once per contribution entry"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.module.berths-spec/record-route!
                         {:consumer-a [{:method :get  :path "/a" :handler 'consumer-a/a-handler}]
                          :consumer-b [{:method :post :path "/b" :handler 'consumer-b/b-handler}
                                       {:method :put  :path "/c" :handler 'consumer-b/c-handler}]})]
      (should= [] (berths/process-manifest-berths! module-index))
      (should= 3 (count @*factory-calls*))
      (should= #{:get :post :put} (set (map :method @*factory-calls*)))))

  (it "writes each entry's registration into the ambient nexus"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.module.berths-spec/record-route!
                         {:consumer-a [{:method :get :path "/a" :handler 'consumer-a/a-handler}]})]
      (berths/process-manifest-berths! module-index)
      (should= 'consumer-a/a-handler (nexus/get-in [::test-berth [:get "/a"]]))))

  (it "skips berths whose schema declares no entry-level :factory"
    (let [module-index {:provider {:manifest {:berths {:provider/silent
                                                        {:description "no factory"
                                                         :schema      {:type :seq
                                                                        :spec {:type :map}}}}}}
                        :consumer {:manifest {:provider/silent [{:k :v}]}}}]
      (should= [] (berths/process-manifest-berths! module-index))
      (should= [] @*factory-calls*)))

  (it "skips berths that also declare a :config slot (not manifest-only)"
    (let [module-index (-> (index-with-berth+contributions
                             :provider/routes
                             'isaac.module.berths-spec/record-route!
                             {:consumer-a [{:method :get :path "/a"}]})
                           (assoc-in [:provider :manifest :berths :provider/routes :config]
                                     {:path [:routes]}))]
      (should= [] (berths/process-manifest-berths! module-index))
      (should= [] @*factory-calls*)))

  (it "returns an error row when the factory symbol cannot be resolved"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.module.berths-spec.nope/missing-factory!
                         {:consumer-a [{:method :get :path "/a"}]})
          errors       (berths/process-manifest-berths! module-index)]
      (should= 1 (count errors))
      (should= "module-index.berths[:provider/routes].factory"
               (:key (first errors)))
      (should= "could not resolve factory symbol: isaac.module.berths-spec.nope/missing-factory!"
               (:value (first errors)))
      (should= [] @*factory-calls*)))

  (it "logs :berth/registration for each installed entry"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.module.berths-spec/record-route!
                         {:consumer-a [{:method :get :path "/a" :handler 'consumer-a/a-handler}]})]
      (log/capture-logs
        (berths/process-manifest-berths! module-index)
        (should= [{:level :info :event :berth/registration :berth :provider/routes
                   :entry :a :module "consumer-a"}]
                 (->> @log/captured-logs
                      (filter #(= :berth/registration (:event %)))
                      (mapv #(select-keys % [:level :event :berth :entry :module])))))))

  (it "logs :berth/registration-summary with per-berth counts"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.module.berths-spec/record-route!
                         {:consumer-a [{:method :get :path "/a" :handler 'consumer-a/a-handler}]
                          :consumer-b [{:method :post :path "/b" :handler 'consumer-b/b-handler}]})]
      (log/capture-logs
        (berths/process-manifest-berths! module-index)
        (should= {:provider/routes 2}
                 (:counts (first (filter #(= :berth/registration-summary (:event %))
                                         @log/captured-logs))))))))
