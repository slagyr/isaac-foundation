(ns isaac.config.cli.common-spec
  (:require
    [isaac.cli.color :as color]
    [isaac.config.cli.common :as sut]
    [isaac.config.env :as env]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
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

  (describe "print-warnings!"

    (defn- warning-output [entries]
      (let [err (java.io.StringWriter.)]
        (binding [*err* err] (sut/print-warnings! entries))
        (str err)))

    (it "prints the field path and the reason"
      (should-contain "warning: :foundries.helm.api-key - RXUN_MISSING is not set"
                      (warning-output [{:key            "foundries.helm.api-key"
                                        :value          "RXUN_MISSING is not set"
                                        :unresolved-ref "RXUN_MISSING"}])))

    (it "caveats an unresolvable reference — the CLI's shell is not the server's (isaac-rxun)"
      (should-contain "not set in this shell; the server's environment may differ"
                      (warning-output [{:key            "foundries.helm.api-key"
                                        :value          "RXUN_MISSING is not set"
                                        :unresolved-ref "RXUN_MISSING"}])))

    (it "leaves other warnings uncaveated"
      (should-not-contain "the server's environment may differ"
                          (warning-output [{:key "foundries.helm.bogus" :value "unknown key"}]))))

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

    (it "load-result preserves errors from the threaded load result"
      (let [load-result {:config {:embedding {:source :warp-drive}}
                         :errors [{:key "embedding.source" :value "must be one of provider"}]
                         :warnings []
                         :sources []}]
        (should= (:errors load-result)
                 (:errors (sut/load-result {:config (:config load-result)
                                           :load-result load-result})))))

    (it "printable-config redacts threaded secrets without resolving config again"
      (let [mem      (fs/mem-fs)
            root     "/x"
            source   "config/isaac.edn"
            threaded {:providers {:anthropic {:api-key "sk-test-123"}}}
            result   {:config threaded :errors [] :warnings [] :sources [source]}]
        (fs/mkdirs mem (str root "/config"))
        (fs/spit mem (str root "/" source)
                 "{:providers {:anthropic {:api-key \"${CONFIG_TEST_API_KEY}\"}}}")
        (with-redefs [env/env (fn [token] (when (= "CONFIG_TEST_API_KEY" token) "sk-test-123"))
                      loader/load-config-result (fn [_] (throw (ex-info "unexpected reload" {})))]
          (should= "<CONFIG_TEST_API_KEY:redacted>"
                   (get-in (sut/printable-config {:config      threaded
                                                  :fs          mem
                                                  :load-result result
                                                  :root        root}
                                                 false)
                           [:config :providers :anthropic :api-key])))))

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