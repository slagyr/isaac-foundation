(ns isaac.cli.common-spec
  (:require
    [clojure.string :as str]
    [isaac.cli.common :as sut]
    [speclj.core :refer :all]))

(describe "cli common"

  (describe "render-json"

    (it "renders sets as sorted JSON arrays"
      (should= "{\"tags\":[\"project/chess\",\"role/worker\"]}"
               (sut/render-json {:tags #{:role/worker :project/chess}})))

    (it "prints rendered JSON to stdout"
      (should= "{\"tags\":[\"project/chess\",\"role/worker\"]}"
               (str/trim (with-out-str (sut/print-json! {:tags #{:role/worker :project/chess}})))))))