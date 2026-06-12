(ns isaac.schema.registered-in-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.schema.registered-in :as sut]
    [speclj.core :refer :all]))

(defn- index-with
  "Builds a synthetic module-index. `provider-id` declares `berth-id`;
   `contributions` is a map of {consumer-id contribution-map} so each
   consumer registers its own contribution-keys under `berth-id`."
  [provider-id berth-id contributions]
  (-> (reduce-kv (fn [acc consumer-id contrib]
                   (assoc acc consumer-id
                          {:manifest {berth-id contrib}}))
                 {}
                 contributions)
      (assoc provider-id {:manifest {:berths {berth-id {:description "test berth"}}}})))

(defn- error-message-of
  "Validates `value` against `[:registered-in? berth-id]` and returns
   the apron error message (or nil if validation passed)."
  [berth-id value]
  (let [result (schema/conform {:value {:type :keyword
                                        :validations [[:registered-in? berth-id]]}}
                               {:value value})]
    (when (schema/error? result)
      (-> result :value schema/error-message))))

(describe ":registered-in? validation primitive"

  (it "accepts the lone registered id and rejects others"
    (binding [sut/*module-index* (index-with :marigold.bridge
                                              :marigold.bridge/comm
                                              {:marigold.longwave {:longwave {:label "lw"}}})]
      (should-be-nil (error-message-of :marigold.bridge/comm :longwave))
      (should= "must be one of [\"longwave\"]"
               (error-message-of :marigold.bridge/comm :unknown))))

  (it "accepts any of multiple registered ids"
    (binding [sut/*module-index* (index-with :marigold.bridge
                                              :marigold.bridge/comm
                                              {:marigold.longwave {:longwave {}}
                                               :marigold.skybeam  {:skybeam {}}
                                               :marigold.logbook  {:logbook {}}})]
      (should-be-nil (error-message-of :marigold.bridge/comm :longwave))
      (should-be-nil (error-message-of :marigold.bridge/comm :skybeam))
      (should-be-nil (error-message-of :marigold.bridge/comm :logbook))))

  (it "lists accepted ids in the error when the set is small"
    (binding [sut/*module-index* (index-with :marigold.bridge
                                              :marigold.bridge/comm
                                              {:marigold.longwave {:longwave {}}
                                               :marigold.skybeam  {:skybeam {}}})]
      (let [msg (error-message-of :marigold.bridge/comm :unknown)]
        ;; Sorted for stability.
        (should= "must be one of [\"longwave\" \"skybeam\"]" msg))))

  (it "omits the list and names the berth when the set is large"
    (let [many (into {} (for [n (range 10)]
                          [(keyword (str "consumer-" n))
                           {(keyword (str "impl-" n)) {}}]))]
      (binding [sut/*module-index* (index-with :marigold.bridge
                                                :marigold.bridge/comm
                                                many)]
        (let [msg (error-message-of :marigold.bridge/comm :nope)]
          (should= "must be a registered contribution to :marigold.bridge/comm" msg)))))

  (it "fails with 'no registered impls' when the berth is declared but empty"
    (binding [sut/*module-index* {:marigold.bridge
                                  {:manifest {:berths {:marigold.bridge/comm {:description "x"}}}}}]
      (should= "no registered impls for berth :marigold.bridge/comm"
               (error-message-of :marigold.bridge/comm :anything))))

  (it "fails with 'unknown berth' when no manifest declares the berth"
    (binding [sut/*module-index* {:marigold.bridge
                                  {:manifest {:berths {:something-else {:description "x"}}}}}]
      (should= "unknown berth: :marigold.bridge/comm"
               (error-message-of :marigold.bridge/comm :anything))))

  (it "treats a nil module-index as empty"
    (binding [sut/*module-index* nil]
      (should= "unknown berth: :marigold.bridge/comm"
               (error-message-of :marigold.bridge/comm :anything))))

  (it "composes with :present? in a single :validations chain"
    (binding [sut/*module-index* (index-with :marigold.bridge
                                              :marigold.bridge/comm
                                              {:marigold.longwave {:longwave {}}})]
      (let [schema-spec {:value {:type :keyword
                                 :validations [:present?
                                               [:registered-in? :marigold.bridge/comm]]}}]
        (should= "is required"
                 (-> (schema/conform schema-spec {:value nil})
                     :value schema/error-message))
        (should= "must be one of [\"longwave\"]"
                 (-> (schema/conform schema-spec {:value :other})
                     :value schema/error-message))
        (should-be-nil
          (-> (schema/conform schema-spec {:value :longwave})
              :value schema/error-message))))))
