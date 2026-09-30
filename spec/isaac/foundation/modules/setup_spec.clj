(ns isaac.foundation.modules.setup-spec
  (:require
    [isaac.foundation.marigold :as marigold]
    [isaac.foundation.modules.setup :as sut]
    [speclj.core :refer :all]))

(def ^:private module-index
  {:marigold.setup
   {:manifest {:isaac/setup {:fn          'isaac.foundation.modules.setup-spec/fixture-setup-fn
                             :description "Marigold starter config"}}}
   :marigold.cli.greeter
   {:manifest {}}})

(defn fixture-setup-fn [_config]
  {:writes [["marigold.greeting" "ahoy"]
            ["marigold.chimes" 3]]
   :hints  ["Polish the bell before first use."]})

(describe "isaac.foundation.modules.setup"

  (describe "find-setup"
    (it "returns the :isaac/setup descriptor a module contributes"
      (should= {:fn 'isaac.foundation.modules.setup-spec/fixture-setup-fn
                :description "Marigold starter config"}
               (sut/find-setup module-index :marigold.setup)))

    (it "returns nil for a module that contributes no setup"
      (should-be-nil (sut/find-setup module-index :marigold.cli.greeter)))

    (it "returns nil for an unknown module id"
      (should-be-nil (sut/find-setup module-index :nope))))

  (describe "proposed-writes"
    (it "resolves and calls the descriptor's :fn with the current config"
      (let [received (atom nil)]
        (with-redefs [fixture-setup-fn (fn [config] (reset! received config) {:writes [] :hints []})]
          (sut/proposed-writes (sut/find-setup module-index :marigold.setup) {:some :config})
          (should= {:some :config} @received)))))

  (describe "missing-writes"
    (it "keeps every write when the config has none of them yet"
      (should= [["marigold.greeting" "ahoy"] ["marigold.chimes" 3]]
               (sut/missing-writes {} [["marigold.greeting" "ahoy"] ["marigold.chimes" 3]])))

    (it "drops a write whose path already has a value"
      (should= [["marigold.chimes" 3]]
               (sut/missing-writes {:marigold {:greeting "hello"}}
                                    [["marigold.greeting" "ahoy"] ["marigold.chimes" 3]])))

    (it "returns empty when every path already has a value"
      (should= []
               (sut/missing-writes {:marigold {:greeting "ahoy" :chimes 3}}
                                    [["marigold.greeting" "ahoy"] ["marigold.chimes" 3]]))))

  (describe "apply-writes!"
    (marigold/aboard)

    (it "writes every pair through the atomic set-many! path"
      (marigold/write-baseline!)
      (let [result (sut/apply-writes! marigold/root [["tz" "America/New_York"]])]
        (should= :ok (:status result))
        (should= "America/New_York" (:tz (:config (marigold/load-config))))))))
