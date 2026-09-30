(ns isaac.foundation.module.berths-spec
  (:require
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.berths :as berths]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

;; ----- process-manifest-berths! helpers -----
;; The loader's `resolve-symbol!` is `requiring-resolve`, so test
;; factories need to be real namespaced fns. These live at the spec
;; namespace's top level so symbols like
;; isaac.foundation.module.berths-spec/record-route! resolve cleanly during tests.

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
                         'isaac.foundation.module.berths-spec/record-route!
                         {:consumer-a [{:method :get  :path "/a" :handler 'consumer-a/a-handler}]
                          :consumer-b [{:method :post :path "/b" :handler 'consumer-b/b-handler}
                                       {:method :put  :path "/c" :handler 'consumer-b/c-handler}]})]
      (should= [] (berths/process-manifest-berths! module-index))
      (should= 3 (count @*factory-calls*))
      (should= #{:get :post :put} (set (map :method @*factory-calls*)))))

  (it "writes each entry's registration into the ambient nexus"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.foundation.module.berths-spec/record-route!
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
                             'isaac.foundation.module.berths-spec/record-route!
                             {:consumer-a [{:method :get :path "/a"}]})
                           (assoc-in [:provider :manifest :berths :provider/routes :config]
                                     {:path [:routes]}))]
      (should= [] (berths/process-manifest-berths! module-index))
      (should= [] @*factory-calls*)))

  (it "returns an error row when the factory symbol cannot be resolved"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.foundation.module.berths-spec.nope/missing-factory!
                         {:consumer-a [{:method :get :path "/a"}]})
          errors       (berths/process-manifest-berths! module-index)]
      (should= 1 (count errors))
      (should= "module-index.berths[:provider/routes].factory"
               (:key (first errors)))
      (should= "could not resolve factory symbol: isaac.foundation.module.berths-spec.nope/missing-factory!"
               (:value (first errors)))
      (should= [] @*factory-calls*)))

  (it "logs :berth/registration for each installed entry"
    (let [module-index (index-with-berth+contributions
                         :provider/routes
                         'isaac.foundation.module.berths-spec/record-route!
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
                         'isaac.foundation.module.berths-spec/record-route!
                         {:consumer-a [{:method :get :path "/a" :handler 'consumer-a/a-handler}]
                          :consumer-b [{:method :post :path "/b" :handler 'consumer-b/b-handler}]})]
      (log/capture-logs
        (berths/process-manifest-berths! module-index)
        (should= {:provider/routes 2}
                 (:counts (first (filter #(= :berth/registration-summary (:event %))
                                         @log/captured-logs))))))))

(describe "contribution-validation-errors"

  (it "accepts a seq-of-strings contribution against a :seq berth"
    (should-be-nil (berths/contribution-validation-errors
                     :isaac.google :isaac.google/scopes ["openid" "https://www.googleapis.com/auth/chat.messages"]
                     {:type :seq :spec {:type :string}})))

  (it "reports a seq element of the wrong type"
    (let [errors (berths/contribution-validation-errors
                   :isaac.google :isaac.google/scopes [1 "not-a-port"]
                   {:type :seq :spec {:type :int}})]
      (should-not (empty? errors))
      (should-contain "module-index[\"isaac.google\"].isaac.google/scopes" (:key (first errors)))))

  (it "keeps the entry path for a map berth's field error"
    (let [errors (berths/contribution-validation-errors
                   :isaac.google :isaac.http/identity {:google-pubsub {:ttl "soon"}}
                   {:type :map :key-spec {:type :keyword}
                    :value-spec {:type :map :schema {:ttl {:type :int}}}})]
      (should= "module-index[\"isaac.google\"].isaac.http/identity[:google-pubsub].ttl" (:key (first errors))))))

(describe "module-report"

  ;; isaac-ppyj: `modules show` / isaac-handbook introspection — a module's
  ;; description, handbook doc, declared berths, and contributions.
  (def bridge-index
    {:marigold.bridge
     {:manifest {:id          :marigold.bridge
                 :version     "1.0.0"
                 :description "The ship's bridge: where channels are declared."
                 :berths      {:marigold.bridge/comm
                               {:description "Comm channels."
                                :schema      {:type       :map
                                              :key-spec   {:type :keyword}
                                              :value-spec {:type :map}}}}}}
     :marigold.longwave
     {:manifest {:id                   :marigold.longwave
                 :version              "0.1.0"
                 :description          "Long-wave radio for the far reaches."
                 :handbook             "marigold/longwave/handbook.md"
                 :marigold.bridge/comm {:longwave {:label "long-wave radio"}}}}})

  (it "reports description, handbook, declared berths, and contributions for the declaring module"
    (should= {:description "The ship's bridge: where channels are declared."
              :handbook    nil
              :declares    {:marigold.bridge/comm {:description "Comm channels."}}
              :contributes {}}
             (berths/module-report bridge-index :marigold.bridge)))

  (it "reports contributed entry ids for a keyed (:map) berth, with no berths of its own"
    (should= {:description "Long-wave radio for the far reaches."
              :handbook    "marigold/longwave/handbook.md"
              :declares    {}
              :contributes {:marigold.bridge/comm [:longwave]}}
             (berths/module-report bridge-index :marigold.longwave)))

  (it "reports the raw value for an unkeyed (:seq) berth contribution"
    (let [index {:marigold.bridge
                 {:manifest {:id     :marigold.bridge
                              :berths {:marigold.bridge/signal-route
                                       {:description "Routes a signal to a handler."
                                        :schema      {:type :seq}}}}}
                 :marigold.longwave
                 {:manifest {:id                            :marigold.longwave
                              :marigold.bridge/signal-route [{:method :get :path "/ping"}]}}}]
      (should= [{:method :get :path "/ping"}]
               (get-in (berths/module-report index :marigold.longwave)
                       [:contributes :marigold.bridge/signal-route]))))

  (it "skips a contribution whose berth isn't declared by any module"
    (let [index {:marigold.longwave
                 {:manifest {:id :marigold.longwave :marigold.mystery/thing {:x 1}}}}]
      (should= {} (:contributes (berths/module-report index :marigold.longwave))))))
