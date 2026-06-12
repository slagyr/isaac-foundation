(ns isaac.foundation.cli-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.foundation.cli-steps :as sut]
    [speclj.core :refer :all]))

(describe "foundation cli feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (it)
    (g/reset!))

  (describe "stdout JSON contains"

    (it "walks dotted paths through parsed JSON"
      (g/assoc! :output "[{\"name\":\"joe\",\"tags\":[\"project/chess\",\"role/worker\"]}]")
      (sut/stdout-json-contains {:headers ["path" "value"]
                                 :rows    [["0.name" "\"joe\""]
                                           ["0.tags" "[\"project/chess\",\"role/worker\"]"]]}))

    (it "fails with a clear message for a missing path"
      (g/assoc! :output "[{\"name\":\"joe\"}]")
      (let [error (try
                    (sut/stdout-json-contains {:headers ["path" "value"]
                                               :rows    [["0.tags" "[]"]]})
                    (catch clojure.lang.ExceptionInfo e e))]
        (should-contain "stdout JSON missing path: 0.tags" (.getMessage error))))

    (it "fails with a clear message for a mismatched value"
      (g/assoc! :output "[{\"name\":\"joe\"}]")
      (let [error (try
                    (sut/stdout-json-contains {:headers ["path" "value"]
                                               :rows    [["0.name" "\"sue\""]]})
                    (catch clojure.lang.ExceptionInfo e e))]
        (should-contain "stdout JSON path 0.name expected \"sue\" but was \"joe\""
                        (.getMessage error))))

    (it "fails with a clear message when stdout is not valid JSON"
      (g/assoc! :output "not json")
      (let [error (try
                    (sut/stdout-json-contains {:headers ["path" "value"]
                                               :rows    [["0.name" "\"joe\""]]})
                    (catch clojure.lang.ExceptionInfo e e))]
        (should-contain "stdout was not valid JSON:" (.getMessage error))
        (should-contain "stdout head: not json" (.getMessage error)))))

  (describe "stdout EDN contains"

    (it "walks dotted paths through parsed EDN"
      (g/assoc! :output "[{:name \"joe\" :tags #{:project/chess :role/worker}}]")
      (sut/stdout-edn-contains {:headers ["path" "value"]
                                :rows    [["0.name" "\"joe\""]
                                          ["0.tags" "#{:role/worker :project/chess}"]]}))

    (it "treats unparseable scalar literals as strings"
      (g/assoc! :output "{:sent-at \"2026-05-23T12:00:00Z\"}")
      (sut/stdout-edn-contains {:headers ["path" "value"]
                                :rows    [["sent-at" "2026-05-23T12:00:00Z"]]}))

    (it "fails with a clear message for a mismatched EDN value"
      (g/assoc! :output "[{:name \"joe\"}]")
      (let [error (try
                    (sut/stdout-edn-contains {:headers ["path" "value"]
                                              :rows    [["0.name" "\"sue\""]]})
                    (catch clojure.lang.ExceptionInfo e e))]
        (should-contain "stdout EDN path 0.name expected \"sue\" but was \"joe\""
                        (.getMessage error))))

    (it "fails with a clear message when stdout is not valid EDN"
      (g/assoc! :output "{")
      (let [error (try
                    (sut/stdout-edn-contains {:headers ["path" "value"]
                                              :rows    [["0.name" "\"joe\""]]})
                    (catch clojure.lang.ExceptionInfo e e))]
        (should-contain "stdout was not valid EDN:" (.getMessage error))
        (should-contain "stdout head: {" (.getMessage error))))))
