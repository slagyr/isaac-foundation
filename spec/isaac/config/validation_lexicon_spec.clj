(ns isaac.config.validation-lexicon-spec
  (:require
    [isaac.config.validation-lexicon :as sut]
    [speclj.core :refer :all]))

;; Fixture "module" for the :isaac.config/validation-ref berth (isaac-h2oo):
;; a module owning its own entity concept (e.g. isaac-agent's crew/model)
;; contributes an existence-ref by manifest data alone — a ref keyword, a
;; :known symbol, and a :message — never by foundation naming the concept.

(defn known-test-ids
  [config]
  (:test-ids config))

(def ^:private module-index-with-contribution
  {:marigold.fixture {:manifest {:isaac.config/validation-ref
                                 {:foo-exists? {:known   'isaac.config.validation-lexicon-spec/known-test-ids
                                                :message "references undefined foo"}}}}})

(describe "contributed-existence-refs"

  (it "returns {} for a module-index with no contributions"
    (should= {} (sut/contributed-existence-refs {})))

  (it "returns {} when modules contribute to other berths only"
    (should= {} (sut/contributed-existence-refs {:marigold.other {:manifest {:isaac.config/schema {}}}})))

  (it "builds an exists-ref shaped entry for a contributed ref"
    (let [ref (:foo-exists? (sut/contributed-existence-refs module-index-with-contribution))]
      (should= "references undefined foo" (:message ref))
      (should= true (:reference? ref))
      (should (fn? (:validate ref)))
      (should (fn? (:known ref)))))

  (it "the contributed ref's :known thunk calls the module's :known fn against *config*'s :raw"
    (let [ref (:foo-exists? (sut/contributed-existence-refs module-index-with-contribution))]
      (binding [sut/*config* {:raw {:test-ids ["atticus" "cordelia"]}}]
        (should= ["atticus" "cordelia"] ((:known ref))))))

  (it "the contributed ref's :known thunk prefers a cached :known-values entry over recomputing"
    (let [ref (:foo-exists? (sut/contributed-existence-refs module-index-with-contribution))]
      (binding [sut/*config* {:raw {:test-ids ["ignored"]} :known-values {:foo-exists? ["cached"]}}]
        (should= ["cached"] ((:known ref))))))

  (it "the contributed ref's :validate accepts a known id and rejects a ghost"
    (let [ref (:foo-exists? (sut/contributed-existence-refs module-index-with-contribution))]
      (binding [sut/*config* {:raw {:test-ids ["atticus"]}}]
        (should ((:validate ref) "atticus"))
        (should-not ((:validate ref) "ghost")))))

  (it "throws when a contributed :known symbol does not resolve"
    (let [module-index {:marigold.fixture {:manifest {:isaac.config/validation-ref
                                                       {:bad-exists? {:known   'isaac.config.validation-lexicon-spec/no-such-fn
                                                                      :message "nope"}}}}}]
      (should-throw clojure.lang.ExceptionInfo (sut/contributed-existence-refs module-index)))))
