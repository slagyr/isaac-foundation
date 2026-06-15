(ns isaac.config.mutate-spec
  (:require
    [clojure.edn :as edn]
    [isaac.config.marigold :as config-marigold]
    [isaac.config.mutate :as sut]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
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

    (it "warns on an unknown key but still writes"
      (config-marigold/write-baseline!)
      (let [result (sut/set-config marigold/root (str "berths." marigold/captain ".experimental") true)]
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

    (it "still warns on an unknown signal field via the loader"
      (config-marigold/install-fixture-module! "marigold.comm.parlor")
      (config-marigold/write-config! (merge config-marigold/baseline-config
                                            {:modules {:marigold.comm.parlor {:local/root parlor-module-root}}
                                             :signals {:bert {:kind :parlor :berth :atticus}}}))
      (let [result (sut/set-config marigold/root "signals.bert.bogus" 42)]
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

  (describe "unset-config"

    (config-marigold/aboard)

    (it "removes a key from the file where it lives"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii :ledger "Steady."})
      (let [result (sut/unset-config marigold/root (str "berths." test-berth-path ".ledger"))]
        (should= :ok (:status result))
        (should= (str "berths/" test-berth-path ".edn") (:file result))
        (should= {:gauge :helm-mark-iii} (read-edn (str "berths/" test-berth-path ".edn")))))

    (it "deletes the entity file when the removal empties it"
      (config-marigold/write-baseline!)
      (config-marigold/write-berth! test-berth-id {:gauge :helm-mark-iii})
      (let [result (sut/unset-config marigold/root (str "berths." test-berth-path ".gauge"))]
        (should= :ok (:status result))
        (should-not (file-exists? (str "berths/" test-berth-path ".edn"))))))

    (it "rejects paths the grammar refuses to parse"
      (let [result (sut/unset-config marigold/root "berths.*.gauge")]
        (should= :invalid-path (:status result))))))