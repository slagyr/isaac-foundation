(ns isaac.config.cli.common-spec
  (:require
    [isaac.config.cli.common :as sut]
    [isaac.marigold :as marigold]
    [speclj.core :refer :all]))

(describe "config cli common"

  (describe "normalize-path"

    (it "preserves dotted paths without a leading slash"
      (should= (str "berths." marigold/captain ".ledger") (sut/normalize-path (str "berths." marigold/captain ".ledger"))))

    (it "splits on '/' when the path starts with '/'"
      (should= (str "berths." marigold/captain ".ledger") (sut/normalize-path (str "/berths/" marigold/captain "/ledger"))))

    (it "escapes segments with dots as bracket-strings in slash-mode"
      (should= "berths[\"john.doe\"].gauge" (sut/normalize-path "/berths/john.doe/gauge")))

    (it "escapes segments with spaces as bracket-strings in slash-mode"
      (should= "berths[\"my berth\"].ledger" (sut/normalize-path "/berths/my berth/ledger")))))