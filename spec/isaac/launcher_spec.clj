(ns isaac.launcher-spec
  (:require
    [isaac.config.api :as config-api]
    [isaac.launcher :as sut]
    [isaac.log.output :as log-output]
    [isaac.main :as main]
    [speclj.core :refer :all]))

(describe "isaac.launcher"

  (it "installs the CLI log sink before its first config load, so a load-time warning never reaches the terminal (isaac-89q1)"
    (let [calls (atom [])]
      (with-redefs [log-output/provisional-cli-sink! (fn [_root & _] (swap! calls conj :sink))
                    config-api/load-resolved         (fn [_] (swap! calls conj :load) {:config {} :missing-config? true})
                    sut/compose-classpath!           (fn [& _] (swap! calls conj :classpath))
                    main/-main                       (fn [& _] (swap! calls conj :main) 0)]
        (sut/-main "--root" "/tmp/launcher-spec-root" "version")
        (should= [:sink :load :classpath :main] @calls))))

  (it "passes --log-file and --log-level from argv to the sink so an explicit flag is honoured from the first line"
    (let [seen (atom nil)]
      (with-redefs [log-output/provisional-cli-sink! (fn [_root & {:as opts}] (reset! seen opts))
                    config-api/load-resolved         (fn [_] {:config {} :missing-config? true})
                    sut/compose-classpath!           (fn [& _])
                    main/-main                       (fn [& _] 0)]
        (sut/-main "--root" "/tmp/launcher-spec-root" "--log-file" "logs/x.log" "--log-level" "warn" "version")
        (should= "logs/x.log" (:log-file-path @seen))
        (should= :warn (:log-level @seen))))))
