  (ns isaac.config.cli.mutate-common-spec
    (:require
      [clojure.string :as str]
      [isaac.config.cli.common :as common]
      [isaac.config.cli.inspect :as inspect]
      [isaac.config.cli.mutate-common :as sut]
      [isaac.marigold :as marigold]
      [speclj.core :refer :all]))

 (def path-str (str "berths." marigold/captain ".ledger"))
 (def slash-path (str "/berths/" marigold/captain "/ledger"))

 (describe "config cli mutate common"

   (describe "target-root+path!"

     (it "returns explicit root and normalized path for a valid argument"
       (should= {:root "/tmp/home/.isaac" :path-str path-str}
                (sut/target-root+path! {:root "/tmp/home/.isaac"} slash-path)))

     (it "prints missing path and returns nil for a blank argument"
       (let [err (java.io.StringWriter.)]
         (binding [*err* (java.io.PrintWriter. err)]
           (should= nil (sut/target-root+path! {:root "/tmp/home/.isaac"} nil)))
         (should (str/includes? (str err) "missing path")))))

   (describe "parse-set-value"

    (it "keywordizes a bare word when the target field is an id"
      (should= :gpt (sut/parse-set-value {:type :id} "gpt")))

    (it "leaves a bare word as a string when the target field is a plain string"
      (should= "gpt" (sut/parse-set-value {:type :string} "gpt"))))

  (describe "handle-mutate-result!"

     (it "logs successful set and unset operations"
        (let [logged (atom [])]
          (with-redefs [common/print-warnings! (fn [_] nil)
                        sut/log-mutation!                        (fn [& args] (swap! logged conj args))]
            (with-out-str
              (should= 0 (sut/handle-mutate-result! :set path-str {:status :ok :file "config"} "hi"))
              (should= 0 (sut/handle-mutate-result! :unset path-str {:status :ok :file "config"} nil))))
          (should= [[:info :config/set "config" path-str :value "hi"]
                    [:info :config/unset "config" path-str]]
                   @logged)))

     (it "prints a one-line confirmation on successful set"
       (let [out (with-out-str
                   (with-redefs [common/print-warnings! (fn [_] nil)
                                 sut/log-mutation! (fn [& _] nil)]
                     (should= 0 (sut/handle-mutate-result! :set path-str {:status :ok :file "crew/joe.edn"} :echo))))]
         (should-contain "set " out)
         (should-contain path-str out)
         (should-contain "crew/joe.edn" out)))

     (it "prints a one-line confirmation on successful unset"
       (let [out (with-out-str
                   (with-redefs [common/print-warnings! (fn [_] nil)
                                 sut/log-mutation! (fn [& _] nil)]
                     (should= 0 (sut/handle-mutate-result! :unset path-str {:status :ok :file "crew/joe.edn"} nil))))]
         (should-contain "unset " out)
         (should-contain path-str out)
         (should-contain "crew/joe.edn" out)))

     (it "suppresses the confirmation when --edn is requested"
       (let [out (with-out-str
                   (with-redefs [common/print-warnings! (fn [_] nil)
                                 sut/log-mutation! (fn [& _] nil)
                                 inspect/print-structured! (fn [& _] nil)
                                 inspect/structured-requested? (constantly true)]
                     (should= 0 (sut/handle-mutate-result! :set path-str {:status :ok :file "crew/joe.edn"} :echo {:edn true}))))]
         (should-not (str/includes? out "set "))))

     (it "prints validation errors and logs set failures"
        (let [printed (atom nil)
              logged  (atom nil)]
          (with-redefs [common/print-warnings! (fn [_] nil)
                        common/print-errors!   (fn [errors level] (reset! printed [errors level]))
                        sut/log-mutation!                        (fn [& args] (reset! logged args))]
            (should= 1 (sut/handle-mutate-result! :set path-str {:status :invalid :errors [{:key path-str :value "bad"}]} "hi")))
          (should= [[{:key path-str :value "bad"}] "error"] @printed)
          (should= [:error :config/set-failed "config" path-str :error (str path-str " - bad")] @logged)))

     (it "prints config errors and status errors without logging mutation success"
        (let [printed (atom nil)
              status  (java.io.StringWriter.)]
          (with-redefs [common/print-warnings! (fn [_] nil)
                        common/print-errors!   (fn [errors level] (reset! printed [errors level]))]
            (should= 1 (sut/handle-mutate-result! :unset path-str {:status :invalid-config :errors [{:key "config" :value "bad"}]} nil))
            (binding [*err* (java.io.PrintWriter. status)]
              (should= 1 (sut/handle-mutate-result! :unset path-str {:status :not-found} nil))))
          (should= [[{:key "config" :value "bad"}] "error"] @printed)
          (should (str/includes? (str status) (str "not found: " path-str))))))

   (describe "print-status-error!"

      (it "prints missing path to stderr"
        (let [err (java.io.StringWriter.)]
          (binding [*err* (java.io.PrintWriter. err)]
            (#'sut/print-status-error! :missing-path path-str))
          (should (str/includes? (str err) "missing path"))))

      (it "prints invalid path with the path string"
        (let [err (java.io.StringWriter.)]
          (binding [*err* (java.io.PrintWriter. err)]
            (#'sut/print-status-error! :invalid-path path-str))
          (should (str/includes? (str err) (str "invalid path: " path-str)))))

      (it "prints unknown statuses as generic config errors"
        (let [err (java.io.StringWriter.)]
          (binding [*err* (java.io.PrintWriter. err)]
            (#'sut/print-status-error! :kaboom path-str))
          (should (str/includes? (str err) "config error: kaboom"))))))
