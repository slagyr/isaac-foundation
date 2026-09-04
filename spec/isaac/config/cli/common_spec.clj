(ns isaac.config.cli.common-spec
  (:require
    [isaac.cli.color :as color]
    [isaac.config.cli.common :as sut]
    [isaac.config.loader :as loader]
    [isaac.marigold :as marigold]
    [speclj.core :refer :all]))

(describe "config cli common"

  (describe "stdout-tty?"

    (it "reports stdout as color-capable when FORCE_COLOR=1 even without a console"
      (with-redefs [color/force-color? (fn [] true)
                    color/console?     (fn [] false)]
        (binding [*out* (java.io.PrintWriter. (java.io.StringWriter.))]
          (should (sut/stdout-tty?)))))

    (it "reports stdout as not color-capable when writing to a StringWriter without force"
      (with-redefs [color/force-color? (fn [] false)
                    color/console?     (fn [] false)]
        (binding [*out* (java.io.PrintWriter. (java.io.StringWriter.))]
          (should-not (sut/stdout-tty?))))))

  (describe "normalize-path"

    (it "preserves dotted paths without a leading slash"
      (should= (str "berths." marigold/captain ".ledger") (sut/normalize-path (str "berths." marigold/captain ".ledger"))))

    (it "splits on '/' when the path starts with '/'"
      (should= (str "berths." marigold/captain ".ledger") (sut/normalize-path (str "/berths/" marigold/captain "/ledger"))))

    (it "escapes segments with dots as bracket-strings in slash-mode"
      (should= "berths[\"john.doe\"].gauge" (sut/normalize-path "/berths/john.doe/gauge")))

    (it "escapes segments with spaces as bracket-strings in slash-mode"
      (should= "berths[\"my berth\"].ledger" (sut/normalize-path "/berths/my berth/ledger"))))

  (describe "threaded config reuse (isaac-v1la)"

    (it "printable-config reuses a non-empty :config from opts"
      (let [cfg {:defaults {:crew :main}}]
        (should= cfg (:config (sut/printable-config {:config cfg} false)))))

    (it "printable-config loads when :config is empty so a missing-config launch still reads disk"
      (let [calls (atom 0)
            loaded {:config {:defaults {:crew :marvin}} :errors [] :warnings [] :sources []}]
        (with-redefs [loader/load-config-result
                      (fn [_]
                        (swap! calls inc)
                        loaded)]
          (should= {:crew :marvin}
                   (:defaults (:config (sut/printable-config {:config {} :root "/x"} false))))
          (should= 1 @calls))))
    )
  )