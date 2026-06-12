(ns isaac.schema.dynamic-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.schema.dynamic :as sut]
    [isaac.schema.lexicon :as lexicon]
    [speclj.core :refer :all]))

(def berth-key :isaac.server/comm)

(defn- module-entry [contributions]
  {:manifest {berth-key contributions}})

(defn- compose [spec module-index]
  (sut/compose spec berth-key module-index))

(describe "dynamic schema composition"

  (it "ignores a contribution that redeclares a base field — base wins, checks report it"
    (let [decl  {:type :map :dynamic-schema [:extra-schema] :schema {:type {:type :id}}}
          index {:mod.x {:manifest {:probe/berth {:badmod {:extra-schema {:type {:type :string}}}}}}}
          composed (sut/compose decl :probe/berth index)]
      (should= :id (get-in composed [:schema :type :type]))))

  (it "annotates gathered fields with the contributing entry id as :isaac/variant"
    (let [decl  {:type :map :dynamic-schema [:extra-schema] :schema {:crew {:type :string}}}
          index {:mod.x {:manifest {:probe/berth {:longwave {:extra-schema {:helm/freq {:type :string}}}}}}}
          composed (sut/compose decl :probe/berth index)]
      (should= "longwave" (get-in composed [:schema :helm/freq :isaac/variant]))
      (should-be-nil (get-in composed [:schema :crew :isaac/variant]))))

  (it "validates against the base schema only when no contributions exist"
    (let [spec     {:type           :map
                    :schema         {:base {:type :int}}
                    :dynamic-schema [:value :foo]}
          composed (compose spec {})]
      (should= {:base 7} (lexicon/conform! composed {:base 7}))
      (let [result (lexicon/conform composed {:base "steady"})]
        (should (schema/error? result))
        (should= "can't coerce \"steady\" to int" (get-in (schema/message-map result) [:base])))))

  (it "validates a gathered field from one contribution"
    (let [spec       {:type           :map
                      :schema         {}
                      :dynamic-schema [:value :foo]}
          module-idx {:marigold.longwave (module-entry {:longwave {:value {:foo {:bar {:type :int}}}}})}
          composed   (compose spec module-idx)]
      (should= {:bar 9} (lexicon/conform! composed {:bar 9}))
      (let [result (lexicon/conform composed {:bar "hi"})]
        (should (schema/error? result))
        (should= "can't coerce \"hi\" to int" (get-in (schema/message-map result) [:bar])))))

  (it "composes disjoint gathered fields from multiple contributions"
    (let [spec       {:type           :map
                      :schema         {}
                      :dynamic-schema [:value :foo]}
          module-idx {:marigold.longwave (module-entry {:longwave {:value {:foo {:bar {:type :string}}}}})
                      :marigold.skybeam  (module-entry {:skybeam  {:value {:foo {:baz {:type :int}}}}})}
          composed   (compose spec module-idx)]
      (should= {:bar "hi" :baz 7} (lexicon/conform! composed {:bar "hi" :baz 7}))
      (let [result (lexicon/conform composed {:bar "hi" :baz "nope"})]
        (should (schema/error? result))
        (should= "can't coerce \"nope\" to int" (get-in (schema/message-map result) [:baz])))))

  (it "errors when two contributions define the same gathered field"
    (let [spec       {:type           :map
                      :schema         {}
                      :dynamic-schema [:value :foo]}
          module-idx {:marigold.longwave (module-entry {:longwave {:value {:foo {:bar {:type :string}}}}})
                      :marigold.skybeam  (module-entry {:skybeam  {:value {:foo {:bar {:type :int}}}}})}
          error      (try
                       (compose spec module-idx)
                       (catch clojure.lang.ExceptionInfo e
                         e))]
      (should= :dynamic-schema/collision (:type (ex-data error)))
      (should= berth-key (:berth-key (ex-data error)))
      (should= :bar (:field (ex-data error)))
      (should= [{:module-id :marigold.longwave :entry-id :longwave}
                {:module-id :marigold.skybeam  :entry-id :skybeam}]
               (:contributors (ex-data error)))))

  (it "fires nested dynamic-schema markers recursively"
    (let [spec       {:type           :map
                      :schema         {:base   {:type :string}
                                       :nested {:type           :map
                                                :schema         {}
                                                :dynamic-schema [:value :nested-extra]}}
                      :dynamic-schema [:value :top-extra]}
          module-idx {:marigold.longwave (module-entry {:longwave {:value {:top-extra    {:channel {:type :string}}
                                                                           :nested-extra {:frequency {:type :int}}}}})}
          composed   (compose spec module-idx)]
      (should= {:base "ok" :channel "bridge" :nested {:frequency 7}}
               (lexicon/conform! composed {:base "ok" :channel "bridge" :nested {:frequency 7}}))
      (let [result (lexicon/conform composed {:base "ok" :channel "bridge" :nested {:frequency "daily"}})]
        (should (schema/error? result))
        (should= "can't coerce \"daily\" to int" (get-in (schema/message-map result) [:nested :frequency]))))))
