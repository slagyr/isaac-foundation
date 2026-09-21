(ns isaac.config.warnings-spec
  (:require
    [isaac.config.warnings :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(describe "isaac.config.warnings"
  (it "collect-unknown-key-warnings flags keys absent from schema fields"
    (let [schema {:type :map :schema {:known {:type :string}}}
          ws (sut/collect-unknown-key-warnings [] "crew" "main" {:known "a" :extra 1} schema)]
      (should= 1 (count ws))
      (should= "crew.main.extra" (:key (first ws)))
      (should= "unknown key" (:value (first ws)))))

  (it "ignores non-map entities"
    (should= [] (sut/collect-unknown-key-warnings [] "crew" "main" ["not-a-map"] {:schema {}})))

  (describe "slice-unknown-key-warnings"

    (it "warns on an unknown field in an open-map slot, shallow (existing behaviour)"
      (let [spec {:type    :map
                  :value-spec {:type :map :schema {:command {:type :string}}}}]
        (should= [{:key "comms.bigbird.bogus" :value "unknown key"}]
                 (sut/slice-unknown-key-warnings [:comms] spec {:bigbird {:command "x" :bogus 1}}))))

    (it "warns on every key inside a bare :map field whose contents conform prunes (the isaac-12fo :env shape)"
      (let [spec {:type       :map
                  :value-spec {:type   :map
                               :schema {:env   {:type  :map}}}}]
        (should= [{:key "providers.claude-a.env.CLAUDE_CONFIG_DIR" :value "unknown key"}
                  {:key "providers.claude-a.env.bogus" :value "unknown key"}]
                 (sut/slice-unknown-key-warnings [:providers] spec
                                                 {:claude-a {:env {:CLAUDE_CONFIG_DIR "/x" :bogus "y"}}}))))


    (it "descends into closed :map fields without a key-spec, warning on pruned contents"
      (let [spec {:type       :map
                  :value-spec {:type   :map
                               :schema {:headers {:type   :map
                                                  :schema {:token {:type :string}}}}}}]
        (should= [{:key "comms.bigbird.headers.bogus" :value "unknown key"}]
                 (sut/slice-unknown-key-warnings [:comms] spec
                                                 {:bigbird {:headers {:token "t" :bogus 1}}}))))

    (it "descends into :seq entries"
      (let [spec {:type       :map
                  :value-spec {:type   :map
                               :schema {:args {:type :seq :spec {:type :map :schema {:name {:type :string}}}}}}}]
        (should= [{:key "comms.bigbird.args[1].bogus" :value "unknown key"}]
                 (sut/slice-unknown-key-warnings [:comms] spec
                                                 {:bigbird {:args [{:name "a"} {:name "b" :bogus 1}]}}))))

    (it "never warns on known fields at any depth"
      (let [spec {:type       :map
                  :value-spec {:type   :map
                               :schema {:command {:type :string}
                                        :env     {:type  :map
                                                  :key-spec   {:type :keyword}
                                                  :value-spec {:type :string}}}}}]
        (should= []
                 (sut/slice-unknown-key-warnings [:comms] spec
                                                 {:bigbird {:command "x"
                                                            :env {:CLAUDE_CONFIG_DIR "/x"}}}))))

    (it "stays quiet for slots and values that are not maps"
      (let [spec {:type :map :value-spec {:type :map :schema {:command {:type :string}}}}]
        (should= [] (sut/slice-unknown-key-warnings [:comms] spec {:bigbird "not-a-map"})))))

  (describe "log-unknown-keys!"

    (it "warns once per unknown-key row, naming the slice and the key"
      (let [entries (atom [])]
        (with-redefs [log/log* (fn [level event _file _line & kvs]
                                 (swap! entries conj (assoc (apply hash-map kvs) :level level :event event)))]
          (sut/log-unknown-keys! [{:key "comms.bigbird.bogus" :value "unknown key"}
                                  {:key "providers.claude-a.env.CLAUDE_CONFIG_DIR" :value "unknown key"}]))
        (should= [{:level :warn :event :config/unknown-key
                   :slice "comms.bigbird" :key "bogus" :path "comms.bigbird.bogus"}
                  {:level :warn :event :config/unknown-key
                   :slice "providers.claude-a.env" :key "CLAUDE_CONFIG_DIR"
                   :path "providers.claude-a.env.CLAUDE_CONFIG_DIR"}]
                 @entries)))

    (it "says nothing about warnings that are not unknown keys"
      (let [entries (atom [])]
        (with-redefs [log/log* (fn [& args] (swap! entries conj args))]
          (sut/log-unknown-keys! [{:key "crew/nobody.md" :value "no matching crew entry"}]))
        (should= [] @entries)))

    (it "names a bare top-level key with no slice"
      (let [entries (atom [])]
        (with-redefs [log/log* (fn [level event _file _line & kvs]
                                 (swap! entries conj (assoc (apply hash-map kvs) :level level :event event)))]
          (sut/log-unknown-keys! [{:key "bogus" :value "unknown key"}]))
        (should= [{:level :warn :event :config/unknown-key :slice nil :key "bogus" :path "bogus"}]
                 @entries)))

    (it "returns the warnings it was given so it can sit in the load pipeline"
      (let [rows [{:key "comms.bigbird.bogus" :value "unknown key"}]]
        (with-redefs [log/log* (fn [& _] nil)]
          (should= rows (sut/log-unknown-keys! rows)))))))
