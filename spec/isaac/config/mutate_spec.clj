(ns isaac.config.mutate-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.marigold :as config-marigold]
    [isaac.config.mutate :as sut]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [isaac.util.edn :as edn-pretty]
    [speclj.core :refer :all]))

(def ^:private config-root (str marigold/root "/config"))
(def ^:private test-berth-id (keyword marigold/first-mate))
(def ^:private test-berth-path marigold/first-mate)

(defn- read-edn [relative]
  (let [fs* (nexus/get :fs)]
    (when (fs/exists? fs* (str config-root "/" relative))
      (edn/read-string (fs/slurp fs* (str config-root "/" relative))))))

(defn- slurp-file [relative]
  (fs/slurp (nexus/get :fs) (str config-root "/" relative)))

(defn- file-exists? [relative]
  (fs/exists? (nexus/get :fs) (str config-root "/" relative)))

(defn- write-slice! [relative data]
  (fs/spit (nexus/get :fs) (str config-root "/" relative) (pr-str data)))

(def ^:private parlor-module-root
  (str (config-marigold/fixture-modules-root) "/marigold.comm.parlor"))

(describe "isaac.config.mutate"

  (config-marigold/aboard)

  (describe "set-config"

    (it "writes a new entity to isaac.edn by default"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= "isaac.edn" (:file result))
        (should= :helm-mark-iii (get-in (read-edn "isaac.edn") [:berths test-berth-id :gauge]))))

    (it "writes to an existing entity file when the entity lives there"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii})
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= (str "berths/" test-berth-path ".edn") (:file result))
        (should-not-contain test-berth-id (:berths (read-edn "isaac.edn")))
        (should= :helm-mark-iii (:gauge (read-edn (str "berths/" test-berth-path ".edn"))))))

    (it "writes to isaac.edn when the entity is already defined inline"
      (config-marigold/write-config! (assoc-in config-marigold/baseline-config [:berths test-berth-id] {:gauge :helm-mark-iii}))
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= "isaac.edn" (:file result))
        (should-not (file-exists? (str "berths/" test-berth-path ".edn")))))

    (it "routes new entities to entity files when :prefer-entity-files is true"
      (config-marigold/write-config! (assoc config-marigold/baseline-config :prefer-entity-files true))
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= (str "berths/" test-berth-path ".edn") (:file result))
        (should-not-contain test-berth-id (:berths (read-edn "isaac.edn")))))

    (it "writes ledger to the companion .md when one already exists"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii} :ledger "Old ledger.")
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".ledger") "New ledger.")]
        (should= :ok (:status result))
        (should= (str "berths/" test-berth-path ".md") (:file result))
        (should= "New ledger." (slurp-file (str "berths/" test-berth-path ".md")))
        (should-not (contains? (read-edn (str "berths/" test-berth-path ".edn")) :ledger))))

    (it "creates a companion .md when a new ledger exceeds 64 characters"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii})
      (let [long-ledger "You are Cordelia, first mate of the Marigold. Calm command is your default."
            result      (sut/set-config marigold/root (str "berths." test-berth-path ".ledger") long-ledger)]
        (should= :ok (:status result))
        (should= (str "berths/" test-berth-path ".md") (:file result))
        (should= long-ledger (slurp-file (str "berths/" test-berth-path ".md")))
        (should-not (contains? (read-edn (str "berths/" test-berth-path ".edn")) :ledger))))

    (it "writes a short new ledger inline"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii})
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".ledger") "Steady.")]
        (should= :ok (:status result))
        (should= (str "berths/" test-berth-path ".edn") (:file result))
        (should= "Steady." (:ledger (read-edn (str "berths/" test-berth-path ".edn"))))
        (should-not (file-exists? (str "berths/" test-berth-path ".md")))))

    (it "rewrites the affected EDN file with isaac.util.edn/pretty and one trailing newline"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii})
      (let [relative (str "berths/" test-berth-path ".edn")
            result   (sut/set-config marigold/root (str "berths." test-berth-path ".ledger") "Steady.")]
        (should= :ok (:status result))
        (let [body (slurp-file relative)]
          (should= (str (edn-pretty/pretty (edn/read-string body)) "\n") body)
          (should (str/ends-with? body "\n"))
          (should-not (str/ends-with? body "\n\n")))))

    (it "refuses to write a value that fails schema validation"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge") :nonexistent)]
        (should= :invalid (:status result))
        (should (seq (:errors result)))
        (should-not-contain test-berth-id (:berths (read-edn "isaac.edn")))))

    (it "validates staged changes against the installed runtime fs"
      (config-marigold/write-baseline!)
      (let [result (nexus/-with-nested-nexus {:fs (nexus/get :fs)}
                     (sut/set-config marigold/root (str "berths." test-berth-path ".gauge") :nonexistent))]
        (should= :invalid (:status result))
        (should (seq (:errors result)))
        (should-not-contain test-berth-id (:berths (read-edn "isaac.edn")))))

    (it "compares staged errors against a cold current load instead of a warm cache hit"
      (config-marigold/write-baseline!)
      (let [staged-error {:key "signals[:discord].kind" :value "must be a registered contribution"}
            calls        (atom [])]
        (with-redefs [loader/load-config-result
                      (fn [opts]
                        (swap! calls conj opts)
                        (if (or (:skip-cache? opts) (:fs opts))
                          {:config {} :errors [staged-error] :warnings []}
                          {:config {} :errors [] :warnings []}))]
          (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge") :helm-mark-iii)]
            (should= :ok (:status result))
            (should (:skip-cache? (first @calls)))
            (should-contain (assoc staged-error :value "pre-existing: must be a registered contribution")
                            (:warnings result))))))

    (it "refuses an unknown key under a schema'd map by default (isaac-a5dx, was: warns but still writes)"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "berths." marigold/captain ".experimental") true)]
        (should= :invalid (:status result))
        (should-not-contain :experimental (get-in (read-edn "isaac.edn") [:berths (keyword marigold/captain)]))))

    (it "--force writes the unknown key and the load-time warning still fires (isaac-a5dx)"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "berths." marigold/captain ".experimental") true :force? true)]
        (should= :ok (:status result))
        (should (seq (:warnings result)))
        (should= true (get-in (read-edn "isaac.edn") [:berths (keyword marigold/captain) :experimental]))))

    (it "does not warn on a module-provided signal field"
      (config-marigold/install-fixture-module! "marigold.comm.parlor")
      (config-marigold/write-config! (merge config-marigold/baseline-config
                                            {:modules {:marigold.comm.parlor {:local/root parlor-module-root}}
                                             :signals {:bert {:kind :parlor :berth :atticus}}}))
      (let [result (sut/set-config marigold/root "signals.bert.loft" "rooftop")]
        (should= :ok (:status result))
        (should-not (some #(= {:key "signals.bert.loft" :value "unknown key"}
                              (select-keys % [:key :value]))
                          (:warnings result)))
        (should= "rooftop" (get-in (read-edn "isaac.edn") [:signals :bert :loft]))))

    (it "refuses an unknown signal field by default (isaac-a5dx)"
      (config-marigold/install-fixture-module! "marigold.comm.parlor")
      (config-marigold/write-config! (merge config-marigold/baseline-config
                                            {:modules {:marigold.comm.parlor {:local/root parlor-module-root}}
                                             :signals {:bert {:kind :parlor :berth :atticus}}}))
      (let [result (sut/set-config marigold/root "signals.bert.bogus" 42)]
        (should= :invalid (:status result))
        (should-not-contain :bogus (get-in (read-edn "isaac.edn") [:signals :bert]))))

    (it "still warns on an unknown signal field via the loader when forced (isaac-a5dx, was: isaac-nq4c)"
      (config-marigold/install-fixture-module! "marigold.comm.parlor")
      (config-marigold/write-config! (merge config-marigold/baseline-config
                                            {:modules {:marigold.comm.parlor {:local/root parlor-module-root}}
                                             :signals {:bert {:kind :parlor :berth :atticus}}}))
      (let [result (sut/set-config marigold/root "signals.bert.bogus" 42 :force? true)]
        (should= :ok (:status result))
        (should-contain {:key "signals[:bert].bogus" :value "unknown key"}
                        (mapv #(select-keys % [:key :value]) (:warnings result)))))

    (it "accepts a whole-entity value and replaces the target"
      (config-marigold/write-baseline!)
      (config-marigold/write-foundry! :starcore {:base-url "https://old" :api-key "${OLD}"})
      (let [result (sut/set-config marigold/root "foundries.starcore"
                                    {:base-url "https://api.starcore.test/v1" :api-key "${STARCORE_API_KEY}"})]
        (should= :ok (:status result))
        (should= {:base-url "https://api.starcore.test/v1" :api-key "${STARCORE_API_KEY}"}
                 (read-edn "foundries/starcore.edn"))))

    (it "rejects paths the grammar refuses to parse"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root "berths.*.gauge" :helm-mark-iii)]
        (should= :invalid-path (:status result))))

    (it "applies a mutation that fixes an existing error without being blocked by unrelated pre-existing errors"
      (config-marigold/write-config! {:watch     {:berth :main :gauge :sparky}
                                      :berths    {:main {}}
                                      :gauges    {:sparky {:reading "spark-1" :foundry :bogus}
                                                  :embery {:reading "embers" :foundry :bogus}}
                                      :foundries {:helm-systems {}}})
      (let [result (sut/set-config marigold/root "gauges.sparky.foundry" :helm-systems)]
        (should= :ok (:status result))
        (should= :helm-systems (get-in (read-edn "isaac.edn") [:gauges :sparky :foundry]))
        (should-contain {:key "gauges.embery.foundry" :value "pre-existing: must be one of [\"helm-systems\"]"}
                        (mapv #(select-keys % [:key :value]) (:warnings result)))))

    (it "rejects a mutation that introduces a new error even when other errors already exist"
      (config-marigold/write-config! {:watch     {:berth :main :gauge :sparky}
                                      :berths    {:main {}}
                                      :gauges    {:sparky {:reading "spark-1" :foundry :helm-systems}
                                                  :embery {:reading "embers" :foundry :bogus}}
                                      :foundries {:helm-systems {}}})
      (let [result (sut/set-config marigold/root "gauges.sparky.foundry" :nonexistent)]
        (should= :invalid (:status result))
        (should= [{:key "gauges.sparky.foundry" :value "must be one of [\"helm-systems\"]" :bad-value "nonexistent" :valid-values ["helm-systems"]}]
                 (mapv #(select-keys % [:key :value :bad-value :valid-values]) (:errors result)))))

    (it "still blocks a value-validator error when skip-ref-validation? is true"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root "server.port" "not-a-number"
                                   :skip-ref-validation? true)]
        (should= :invalid (:status result))
        (should (seq (:errors result)))))

    (it "still accepts a missing-entity reference when skip-ref-validation? is true"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge")
                                   :not-yet-defined
                                   :skip-ref-validation? true)]
        (should= :ok (:status result))
        (should= :not-yet-defined (get-in (read-edn "isaac.edn") [:berths test-berth-id :gauge]))))

    (it "still accepts a missing-entity reference when the error is an untagged check contribution"
      (config-marigold/write-baseline!)
      (let [ghost {:key "crew.joe.model" :value "references undefined model" :bad-value "not-yet-defined"}]
        (with-redefs [loader/load-config-result
                      (fn [opts]
                        (if (:fs opts)
                          {:config {} :errors [ghost] :warnings []}
                          {:config {} :errors [] :warnings []}))]
          (let [result (sut/set-config marigold/root (str "berths." test-berth-path ".gauge")
                                       :helm-mark-iii
                                       :skip-ref-validation? true)]
            (should= :ok (:status result))))))

    (it "still blocks a required-sibling error when skip-ref-validation? is true"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root "gauges.echo.reading" "echo-v1"
                                   :skip-ref-validation? true)]
        (should= :invalid (:status result))
        (should-contain "gauges.echo.foundry"
                        (map :key (:errors result)))))

    (it "writes a required-sibling error when force? is true and surfaces it as a warning"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root "gauges.echo.reading" "echo-v1"
                                   :skip-ref-validation? true
                                   :force? true)]
        (should= :ok (:status result))
        (should= "echo-v1" (get-in (read-edn "isaac.edn") [:gauges :echo :reading]))
        (should= [] (:errors result))
        (should-contain "gauges.echo.foundry" (map :key (:warnings result)))))

    (it "force? does not bypass a coercion error"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root "relay.alpha.gain" "not-a-number"
                                   :skip-ref-validation? true
                                   :force? true)]
        (should= :invalid (:status result))
        (should-contain "relay.alpha.gain" (map :key (:errors result)))
        (should-be-nil (get-in (read-edn "isaac.edn") [:relay "alpha" :gain]))))

    (describe "an unresolvable ${VAR} reference (isaac-rxun)"

      ;; The writer's shell is not the server's, so a variable missing here
      ;; proves nothing: warn, never refuse.

      (it "writes an optional field and warns instead of refusing"
        (config-marigold/write-baseline!)
        (let [result (sut/set-config marigold/root
                                     (str "foundries." marigold/helm-systems ".api-key")
                                     "${RXUN_MISSING}")]
          (should= :ok (:status result))
          (should-contain {:key            (str "foundries." marigold/helm-systems ".api-key")
                           :value          "RXUN_MISSING is not set"
                           :unresolved-ref "RXUN_MISSING"}
                          (:warnings result))))

      (it "keeps the literal in the file — raw reads are unaffected by substitution"
        (config-marigold/write-baseline!)
        (sut/set-config marigold/root (str "foundries." marigold/helm-systems ".api-key") "${RXUN_MISSING}")
        (should= "${RXUN_MISSING}"
                 (get-in (read-edn "isaac.edn") [:foundries (keyword marigold/helm-systems) :api-key])))

      (it "writes a required field and warns instead of refusing"
        (config-marigold/write-baseline!)
        (let [path   (str "gauges." marigold/helm-mark-iii ".reading")
              result (sut/set-config marigold/root path "${RXUN_MISSING}")]
          (should= :ok (:status result))
          (should= [] (:errors result))
          (should-contain {:key            path
                           :value          "RXUN_MISSING is not set"
                           :unresolved-ref "RXUN_MISSING"}
                          (:warnings result))))

      (it "still refuses a value that cannot become the declared type"
        (config-marigold/write-baseline!)
        (let [result (sut/set-config marigold/root (str "relay." marigold/first-mate ".gain") "not-an-int")]
          (should= :invalid (:status result)))))

    (it "writes a namespaced-keyword segment as one key, not two nested maps (isaac-cgxa, forced — isaac-a5dx: parlor/mood isn't the declared bare :mood key)"
      (config-marigold/install-fixture-module! "marigold.comm.parlor")
      (config-marigold/write-config! (merge config-marigold/baseline-config
                                            {:modules {:marigold.comm.parlor {:local/root parlor-module-root}}
                                             :signals {:bert {:kind :parlor :berth :atticus}}}))
      (let [result (sut/set-config marigold/root "signals.bert.parlor/mood" "happy" :force? true)]
        (should= :ok (:status result))
        (should= "happy" (get-in (read-edn "isaac.edn") [:signals :bert :parlor/mood]))
        (should-be-nil (get-in (read-edn "isaac.edn") [:signals :bert :parlor]))))

    (it "writes nested namespaced keys under a namespaced map (isaac-cgxa, forced — isaac-a5dx: not a declared key)"
      (config-marigold/install-fixture-module! "marigold.comm.parlor")
      (config-marigold/write-config! (merge config-marigold/baseline-config
                                            {:modules {:marigold.comm.parlor {:local/root parlor-module-root}}
                                             :signals {:bert {:kind :parlor :berth :atticus}}}))
      (let [result (sut/set-config marigold/root "signals.bert.parlor/outer.inner/key" "x" :force? true)]
        (should= :ok (:status result))
        (should= "x" (get-in (read-edn "isaac.edn") [:signals :bert :parlor/outer :inner/key]))
        (should-be-nil (get-in (read-edn "isaac.edn") [:signals :bert :parlor/outer :inner]))))

    (it "unset removes a namespaced-keyword segment (isaac-cgxa, forced — isaac-a5dx: not a declared key)"
      (config-marigold/install-fixture-module! "marigold.comm.parlor")
      (config-marigold/write-config! (merge config-marigold/baseline-config
                                            {:modules {:marigold.comm.parlor {:local/root parlor-module-root}}
                                             :signals {:bert {:kind      :parlor
                                                              :berth     :atticus
                                                              :parlor/mood "happy"}}}))
      (let [result (sut/unset-config marigold/root "signals.bert.parlor/mood" :force? true)]
        (should= :ok (:status result))
        (should-be-nil (get-in (read-edn "isaac.edn") [:signals :bert :parlor/mood]))
        (should= :parlor (get-in (read-edn "isaac.edn") [:signals :bert :kind]))))

    (it "refuses a set that creates an unknown key inside a schema'd map (isaac-a5dx, split from isaac-cgxa)"
      (config-marigold/write-baseline!)
      (let [path   (str "berths." marigold/captain ".gchat/allow-from")
            result (sut/set-config marigold/root path ["*@tonotop.com"])]
        (should= :invalid (:status result))
        (should-contain path (map :key (:errors result)))
        (should-not-contain :gchat/allow-from (get-in (read-edn "isaac.edn") [:berths (keyword marigold/captain)]))))

    (it "names the parent path and known keys in the refusal message (isaac-a5dx)"
      (config-marigold/write-baseline!)
      (let [path   (str "berths." marigold/captain ".gchat/allow-from")
            result (sut/set-config marigold/root path ["*@tonotop.com"])
            value  (:value (first (:errors result)))]
        (should (str/includes? value (str "berths." marigold/captain)))
        (should (str/includes? value "gauge"))
        (should (str/includes? value "ledger"))))

    (it "--force writes an undeclared key and the load-time warning still fires (isaac-a5dx)"
      (config-marigold/write-baseline!)
      (let [path   (str "berths." marigold/captain ".gchat/allow-from")
            result (sut/set-config marigold/root path ["*@tonotop.com"] :force? true)]
        (should= :ok (:status result))
        (should= ["*@tonotop.com"] (get-in (read-edn "isaac.edn") [:berths (keyword marigold/captain) :gchat/allow-from]))
        (should-contain path (map :key (:warnings result)))))

    (it "still writes a new key under an open entity table (key-spec) without force (isaac-a5dx)"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "berths.newcomer.gauge") :helm-mark-iii)]
        (should= :ok (:status result))
        (should= :helm-mark-iii (get-in (read-edn "isaac.edn") [:berths :newcomer :gauge]))))

  (describe "a key in its own file (isaac-49zp)"

    (it "writes to config/<key>.edn when the key already lives there"
      (config-marigold/write-config! config-marigold/baseline-config)
      (write-slice! "station.edn" {:primary marigold/captain})
      (let [result (sut/set-config marigold/root "station.backup" marigold/first-mate)]
        (should= :ok (:status result))
        (should= "station.edn" (:file result))
        (should= {:primary marigold/captain :backup marigold/first-mate} (read-edn "station.edn"))
        (should-not-contain :station (read-edn "isaac.edn"))))

    (it "writes a key with no home to its own file when :prefer-entity-files is true"
      (config-marigold/write-config! (assoc config-marigold/baseline-config :prefer-entity-files true))
      (let [result (sut/set-config marigold/root "station.primary" marigold/captain)]
        (should= :ok (:status result))
        (should= "station.edn" (:file result))
        (should= {:primary marigold/captain} (read-edn "station.edn"))
        (should-not-contain :station (read-edn "isaac.edn"))))

    (it "writes to isaac.edn when the key already lives inline"
      (config-marigold/write-config! (assoc config-marigold/baseline-config
                                            :prefer-entity-files true
                                            :station {:primary marigold/captain}))
      (let [result (sut/set-config marigold/root "station.backup" marigold/first-mate)]
        (should= :ok (:status result))
        (should= "isaac.edn" (:file result))
        (should-not (file-exists? "station.edn"))))

    (it "unsets from the file that holds the key"
      (config-marigold/write-config! config-marigold/baseline-config)
      (write-slice! "station.edn" {:primary marigold/captain :backup marigold/first-mate})
      (let [result (sut/unset-config marigold/root "station.backup")]
        (should= :ok (:status result))
        (should= "station.edn" (:file result))
        (should= {:primary marigold/captain} (read-edn "station.edn")))))

  (describe "unset-config"

    (config-marigold/aboard)

    (it "removes a key from the file where it lives"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii :ledger "Steady."})
      (let [result (sut/unset-config marigold/root (str "berths." test-berth-path ".ledger"))]
        (should= :ok (:status result))
        (should= (str "berths/" test-berth-path ".edn") (:file result))
        (should= {:gauge :helm-mark-iii} (read-edn (str "berths/" test-berth-path ".edn")))))

    (it "rewrites the remaining EDN with isaac.util.edn/pretty and one trailing newline"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii :ledger "Steady."})
      (let [relative (str "berths/" test-berth-path ".edn")
            result   (sut/unset-config marigold/root (str "berths." test-berth-path ".ledger"))]
        (should= :ok (:status result))
        (let [body (slurp-file relative)]
          (should= (str (edn-pretty/pretty (edn/read-string body)) "\n") body)
          (should (str/ends-with? body "\n"))
          (should-not (str/ends-with? body "\n\n")))))

    (it "deletes the entity file when the removal empties it"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii})
      (let [result (sut/unset-config marigold/root (str "berths." test-berth-path ".gauge"))]
        (should= :ok (:status result))
        (should-not (file-exists? (str "berths/" test-berth-path ".edn")))))

    (it "rejects paths the grammar refuses to parse"
      (let [result (sut/unset-config marigold/root "berths.*.gauge")]
        (should= :invalid-path (:status result))))

    (it "refuses an unset that targets an unknown key inside a schema'd map (isaac-a5dx)"
      (config-marigold/write-baseline!)
      (let [path (str "berths." marigold/captain ".gchat/allow-from")]
        (sut/set-config marigold/root path ["*@tonotop.com"] :force? true)
        (let [result (sut/unset-config marigold/root path)]
          (should= :invalid (:status result))
          (should-contain path (map :key (:errors result)))
          (should= ["*@tonotop.com"]
                   (get-in (read-edn "isaac.edn") [:berths (keyword marigold/captain) :gchat/allow-from])))))

    (it "--force unsets an undeclared key (isaac-a5dx)"
      (config-marigold/write-baseline!)
      (let [path (str "berths." marigold/captain ".gchat/allow-from")]
        (sut/set-config marigold/root path ["*@tonotop.com"] :force? true)
        (let [result (sut/unset-config marigold/root path :force? true)]
          (should= :ok (:status result))
          (should-not-contain :gchat/allow-from
                              (get-in (read-edn "isaac.edn") [:berths (keyword marigold/captain)]))))))))