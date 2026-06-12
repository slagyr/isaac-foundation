(ns isaac.schema.lexicon-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.schema.lexicon :as sut]
    [speclj.core :refer :all]))

(describe "schema lexicon"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (sut/clear!)
    (example)
    (sut/clear!))

  (it "registering a new type makes it discoverable by the conform and coerce surfaces"
    (sut/register-type! {:name     :bang
                         :validate #(= :bang %)
                         :coerce   (constantly :bang)})
    (should= :bang (sut/coerce! {:type :bang} "ignored"))
    (should= :bang (sut/conform! {:type :bang} "ignored")))

  (it "conforms a symbol schema when the value is a symbol"
    (should= 'marigold.bridge/create-module
             (sut/conform! {:type :symbol} 'marigold.bridge/create-module)))

  (it "returns a clear validation error when a symbol schema gets a non-symbol value"
    (let [result (sut/conform {:type :symbol} "marigold.bridge/create-module")]
      (should (schema/error? result))
      (should= "must be a symbol" (schema/error-message result))))

  (it "returns a clear error naming an unregistered type"
    (let [error (should-throw clojure.lang.ExceptionInfo
                              (sut/conform! {:type :mystery} :anything))]
      (should= "unknown schema type: :mystery" (.getMessage error))))

  (it "clear! returns custom registrations to baseline"
    (sut/register-type! {:name :bang :validate #(= :bang %)})
    (sut/clear!)
    (should= 'bridge/factory (sut/conform! {:type :symbol} 'bridge/factory))
    (let [error (should-throw clojure.lang.ExceptionInfo
                              (sut/conform! {:type :bang} :bang))]
      (should= "unknown schema type: :bang" (.getMessage error))))

  (it "conforms an id schema by coercing a keyword to its name"
    (should= "atticus" (sut/conform! {:type :id} :atticus)))

  (it "conforms an id schema passing a string through untouched"
    (should= "atticus" (sut/conform! {:type :id} "atticus")))

  (it "conforms a nil id to nil"
    (should= nil (sut/conform! {:type :id} nil)))

  (it "conforms a non-keyword non-string id via str"
    (should= "42" (sut/conform! {:type :id} 42)))

  (it "reports whether a type is known"
    (should (sut/known-type? :string))
    (should (sut/known-type? :symbol))
    (should-not (sut/known-type? :bang))
    (sut/register-type! {:name :bang :validate #(= :bang %)})
    (should (sut/known-type? :bang))))
