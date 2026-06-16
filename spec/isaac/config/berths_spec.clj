(ns isaac.config.berths-spec
  (:require
    [isaac.config.berths :as sut]
    [isaac.config.schema-base :as schema-base]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.registered-in :as registered-in]
    [speclj.core :refer :all]))

(defn build-node [path slice]
  {:type       (:type slice)
   :path       path
   :station    (:station slice)
   :relay/band (:relay/band slice)})

(def module-index
  {:marigold.chartroom
   {:manifest
    {:berths
     {:marigold.chartroom/signal
      {:description "Signal channels."
       :config      {:path   [:signals]
                     :schema {:type       :map
                              :key-spec   {:type :keyword}
                              :value-spec {:type           :map
                                           :schema         {:type    {:type        :keyword
                                                                       :validations [:present?
                                                                                     [:registered-in? :marigold.chartroom/signal]]}
                                                            :station {:type :string}}
                                           :dynamic-schema [:extra-schema]
                                           :factory        'isaac.config.berths-spec/build-node}}}}}}}
   :marigold.skybeam
   {:manifest
    {:marigold.chartroom/signal
     {:longwave
      {:extra-schema
       {:relay/band {:type        :string
                     :validations [:present?]}}}}}}})

(describe "config berths"

  (describe "reconcile!"

    (defn- things-index [factory]
      {:mod.x {:manifest {:berths {:mod.x/things
                                   {:description "things"
                                    :config {:path   [:things]
                                             :schema {:type       :map
                                                      :key-spec   {:type :keyword}
                                                      :value-spec {:type    :map
                                                                   :factory factory}}}}}}}})

    (it "creates a node when a slot appears and removes it when the slot goes"
      (nexus/-with-nested-nexus {}
        (let [index (things-index (fn [_path _slice] ::node))]
          (sut/reconcile! {:config {:things {:a {:x 1}}} :module-index index})
          (should= ::node (nexus/get-in [:things :a]))
          (sut/reconcile! {:config {} :old-config {:things {:a {:x 1}}} :module-index index})
          (should-be-nil (nexus/get-in [:things :a])))))

    (it "delivers on-config-change! to a Reconfigurable node instead of recreating it"
      (nexus/-with-nested-nexus {}
        (let [changes (atom [])
              node    (reify sut/Reconfigurable
                        (on-load [_ _])
                        (on-config-change! [_ old new] (swap! changes conj [old new]))
                        (on-unload [_ _]))
              index   (things-index (fn [_ _] node))]
          (sut/reconcile! {:config {:things {:a {:x 1}}} :module-index index})
          (sut/reconcile! {:config     {:things {:a {:x 2}}}
                           :old-config {:things {:a {:x 1}}}
                           :module-index index})
          (should= node (nexus/get-in [:things :a]))
          (should= [[{:x 1} {:x 2}]] @changes))))

    (it "recreates a non-Reconfigurable node when its slice changes"
      (nexus/-with-nested-nexus {}
        (let [made  (atom 0)
              index (things-index (fn [_ _] (swap! made inc) [::node @made]))]
          (sut/reconcile! {:config {:things {:a {:x 1}}} :module-index index})
          (sut/reconcile! {:config     {:things {:a {:x 2}}}
                           :old-config {:things {:a {:x 1}}}
                           :module-index index})
          (should= [::node 2] (nexus/get-in [:things :a])))))

    (it "unifies string and keyword slot keys across boot and reload"
      (nexus/-with-nested-nexus {}
        (let [made  (atom 0)
              index (things-index (fn [_ _] (swap! made inc) ::node))]
          (sut/reconcile! {:config {:things {:a {:x 1}}} :module-index index})
          (sut/reconcile! {:config     {:things {"a" {:x 1}}}
                           :old-config {:things {:a {:x 1}}}
                           :module-index index})
          (should= 1 @made))))

    (describe ":isaac.config/schema factory tables"

      (defn- schema-index [factory]
        ;; A :isaac.config/schema table that declares a :factory in its
        ;; value-spec and NO berth :config — the new source the engine
        ;; must reconcile alongside berth :config-claimed paths.
        {:mod.y {:manifest {:isaac.config/schema
                            {:relays {:schema {:name       :relays
                                               :type       :map
                                               :key-spec   {:type :keyword}
                                               :value-spec {:type    :map
                                                            :factory factory
                                                            :schema  {:freq {:type :string}}}}}}}}})

      (it "exposes a config-schema-declared factory path as a reconcilable path"
        (should-contain [:relays] (sut/config-paths (schema-index (fn [_ _] ::node)))))

      (it "reports a config-schema-declared factory path as claimed"
        (should (sut/claims-path? (schema-index (fn [_ _] ::node)) [:relays])))

      (it "creates a node when a config-schema slot appears and removes it when it goes"
        (nexus/-with-nested-nexus {}
          (let [index (schema-index (fn [_path _slice] ::node))]
            (sut/reconcile! {:config {:relays {:a {:freq "1"}}} :module-index index})
            (should= ::node (nexus/get-in [:relays :a]))
            (sut/reconcile! {:config {} :old-config {:relays {:a {:freq "1"}}} :module-index index})
            (should-be-nil (nexus/get-in [:relays :a])))))

      (it "delivers on-config-change! to a Reconfigurable config-schema node"
        (nexus/-with-nested-nexus {}
          (let [changes (atom [])
                node    (reify sut/Reconfigurable
                          (on-load [_ _])
                          (on-config-change! [_ old new] (swap! changes conj [old new]))
                          (on-unload [_ _]))
                index   (schema-index (fn [_ _] node))]
            (sut/reconcile! {:config {:relays {:a {:freq "1"}}} :module-index index})
            (sut/reconcile! {:config     {:relays {:a {:freq "2"}}}
                             :old-config {:relays {:a {:freq "1"}}}
                             :module-index index})
            (should= node (nexus/get-in [:relays :a]))
            (should= [[{:freq "1"} {:freq "2"}]] @changes))))

      (it "leaves a pure-data config-schema table (no factory) untouched"
        (nexus/-with-nested-nexus {}
          (let [index {:mod.z {:manifest {:isaac.config/schema
                                          {:knobs {:schema {:name   :knobs
                                                            :type   :map
                                                            :schema {:volume {:type :int}}}}}}}}]
            (should-not-contain [:knobs] (sut/config-paths index))
            (sut/reconcile! {:config {:knobs {:volume 3}} :module-index index})
            (should-be-nil (nexus/get-in [:knobs])))))))

  #_{:clj-kondo/ignore [:invalid-arity :unresolved-symbol]}
  (around [it]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (it)))

  (it "overlays berth config schemas onto the root schema"
    (let [effective   (sut/effective-root-schema schema-base/base-root module-index)
          signal-spec (get-in effective [:schema :signals])]
      (should= [[:signals]] (sut/config-paths module-index))
      (should= :map (:type signal-spec))
      (should= 'isaac.config.berths-spec/build-node
               (get-in signal-spec [:value-spec :factory]))))

  (it "composes dynamic schema fields into the effective root schema"
    (let [effective {:signals {:relay-1 {:type :longwave
                                         :station "captain"
                                         :relay/band "121.5"}}}
          schema    (sut/effective-root-schema schema-base/base-root module-index)]
      (binding [registered-in/*module-index* module-index]
        (should= effective (lexicon/conform! schema effective))
        (should-throw Exception
                      (lexicon/conform! schema
                                        {:signals {:relay-1 {:type :longwave
                                                             :station "captain"}}})))))

  (it "installs each built node into the nexus at the same path"
    (sut/install! {:config       {:signals {:relay-1 {:type :longwave
                                                      :station "captain"
                                                      :relay/band "121.5"}}}
                   :module-index module-index})
    (should= {:type       :longwave
              :path       [:signals :relay-1]
              :station    "captain"
              :relay/band "121.5"}
             (nexus/get-in [:signals :relay-1]))))
