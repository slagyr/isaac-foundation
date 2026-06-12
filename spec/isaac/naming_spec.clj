(ns isaac.naming-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.naming :as sut]
    [speclj.core :refer :all]))

(def ^:dynamic *fs* nil)

(describe "isaac.naming"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (binding [*fs* (fs/mem-fs)]
      (example)))

  (describe "NamedDomain / NameStrategy protocols"

    (it "SequentialStrategy satisfies NameStrategy"
      (should (satisfies? sut/NameStrategy (sut/->SequentialStrategy "/s" "items" "item-" *fs*))))

    (it "AdjectiveNounStrategy satisfies NameStrategy"
      (let [domain (reify sut/NamedDomain (name-taken? [_ _] false))]
        (should (satisfies? sut/NameStrategy (sut/->AdjectiveNounStrategy domain ["Red"] ["Fox"])))))

    (it "a reified NamedDomain satisfies the protocol"
      (let [domain (reify sut/NamedDomain (name-taken? [_ _] false))]
        (should (satisfies? sut/NamedDomain domain)))))

  (describe "SequentialStrategy"

    (it "generates the first name as prefix+1 when no counter exists"
      (let [s (sut/->SequentialStrategy "/state" "items" "item-" *fs*)]
        (should= "item-1" (sut/generate s))))

    (it "persists the counter after generation"
      (let [s (sut/->SequentialStrategy "/state" "items" "item-" *fs*)]
        (sut/generate s)
        (should= "1" (str/trim (fs/slurp *fs* "/state/items/.counter")))))

    (it "increments the counter on subsequent calls"
      (let [s (sut/->SequentialStrategy "/state" "items" "item-" *fs*)]
        (should= "item-1" (sut/generate s))
        (should= "item-2" (sut/generate s))
        (should= "item-3" (sut/generate s))))

    (it "reads an existing counter and continues from it"
      (fs/mkdirs *fs* "/state/items")
      (fs/spit *fs* "/state/items/.counter" "5")
      (let [s (sut/->SequentialStrategy "/state" "items" "item-" *fs*)]
        (should= "item-6" (sut/generate s))))

    (it "uses the counter-key as the subdirectory"
      (let [s (sut/->SequentialStrategy "/state" "sessions" "session-" *fs*)]
        (sut/generate s)
        (should= "1" (str/trim (fs/slurp *fs* "/state/sessions/.counter")))))

    (it "uses the prefix in the generated name"
      (let [s (sut/->SequentialStrategy "/state" "items" "hail-" *fs*)]
        (should= "hail-1" (sut/generate s)))))

  (describe "AdjectiveNounStrategy"

    (it "generates a name from the provided word lists"
      (let [domain   (reify sut/NamedDomain (name-taken? [_ _] false))
            strategy (sut/->AdjectiveNounStrategy domain ["Red"] ["Fox"])]
        (should= "Red Fox" (sut/generate strategy))))

    (it "retries when the first candidate is taken"
      (let [calls*   (atom 0)
            domain   (reify sut/NamedDomain
                       (name-taken? [_ _]
                         (< (swap! calls* inc) 3)))
            strategy (sut/->AdjectiveNounStrategy domain ["Red"] ["Fox"])]
        (should= "Red Fox" (sut/generate strategy))
        (should= 3 @calls*)))

    (it "throws after 1000 failed attempts"
      (let [domain   (reify sut/NamedDomain (name-taken? [_ _] true))
            strategy (sut/->AdjectiveNounStrategy domain ["Red"] ["Fox"])]
        (should-throw clojure.lang.ExceptionInfo (sut/generate strategy))))

    (it "returns the first non-taken candidate without retry when domain is empty"
      (let [taken*   (atom #{"Red Fox"})
            domain   (reify sut/NamedDomain
                       (name-taken? [_ name] (contains? @taken* name)))
            strategy (sut/->AdjectiveNounStrategy domain ["Red" "Blue"] ["Fox"])]
        (should= "Blue Fox" (sut/generate strategy))))))
