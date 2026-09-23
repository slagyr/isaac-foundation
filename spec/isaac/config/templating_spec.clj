(ns isaac.config.templating-spec
  (:require
    [isaac.config.templating :as sut]
    [speclj.core :refer :all]))

(describe "config templating"

  (context "template-name?"

    (it "recognizes a leading underscore"
      (should (sut/template-name? :_worker))
      (should (sut/template-name? "_worker"))
      (should (sut/template-name? :_parlour-base)))

    (it "rejects ordinary names"
      (should-not (sut/template-name? :marvin))
      (should-not (sut/template-name? "marvin")))

    (it "rejects `_` exactly — that is isaac-49zp's own-values sentinel"
      (should-not (sut/template-name? :_))
      (should-not (sut/template-name? "_")))

    (it "rejects unnameable keys"
      (should-not (sut/template-name? nil))
      (should-not (sut/template-name? 7))))

  (context "merge-entries"

    (it "child keys win"
      (should= {:model "sonnet"}
               (sut/merge-entries {:model "grover"} {:model "sonnet"})))

    (it "keys absent from the child are inherited"
      (should= {:model "grover" :soul "hi"}
               (sut/merge-entries {:model "grover"} {:soul "hi"})))

    (it "map-valued keys merge key-wise, one level deep"
      (should= {:tools {:allow [:fs/read] :directories {:allow ["/tmp"]}}}
               (sut/merge-entries {:tools {:allow [:fs/read :exec/run] :directories {:allow ["/tmp"]}}}
                                  {:tools {:allow [:fs/read]}})))

    (it "nested maps inside a merged key replace rather than deep-merge"
      (should= {:tools {:directories {:deny ["/etc"]}}}
               (sut/merge-entries {:tools {:directories {:allow ["/tmp"]}}}
                                  {:tools {:directories {:deny ["/etc"]}}})))

    (it "vectors replace"
      (should= {:tags [:b]}
               (sut/merge-entries {:tags [:a]} {:tags [:b]})))

    (it "scalars replace"
      (should= {:n 2} (sut/merge-entries {:n 1} {:n 2}))))

  (context "resolve-table"

    (it "leaves a table with no templating markers untouched"
      (let [table {:bert {:kind "parlor"}}]
        (should= {:table table :errors []} (sut/resolve-table :signals table))))

    (it "an entry inherits its template's fields"
      (should= {:table  {:marvin {:model "grover" :soul "You are Marvin."}}
                :errors []}
               (sut/resolve-table :crew {:_worker {:model "grover"}
                                         :marvin  {:_base "_worker" :soul "You are Marvin."}})))

    (it "the entry's own keys win"
      (should= {:table  {:marvin {:model "sonnet"}}
                :errors []}
               (sut/resolve-table :crew {:_worker {:model "grover"}
                                         :marvin  {:_base "_worker" :model "sonnet"}})))

    (it "drops templates from the resolved table"
      (should= [:marvin]
               (keys (:table (sut/resolve-table :crew {:_worker {:model "grover"}
                                                       :marvin  {:_base "_worker"}})))))

    (it "keeps `_` exactly — it is not a template"
      (should= #{:_ :marvin}
               (set (keys (:table (sut/resolve-table :crew {:_      {:model "grover"}
                                                            :marvin {:model "sonnet"}
                                                            :_base- {:model "x"}}))))))

    (it "matches a :_base string against a keyword entry key"
      (should= {:model "grover"}
               (get-in (sut/resolve-table :signals {:_parlour-base {:model "grover"}
                                                    :parlour       {:_base "_parlour-base"}})
                       [:table :parlour])))

    (it "matches a :_base keyword against a string entry key"
      (should= {"marvin" {:model "grover"}}
               (:table (sut/resolve-table :crew {"_worker" {:model "grover"}
                                                 "marvin"  {:_base :_worker}}))))

    (it "follows a chain of templates"
      (should= {:model "grover" :loft "upper" :mood "happy"}
               (get-in (sut/resolve-table :signals {:_a      {:model "grover"}
                                                    :_b      {:_base "_a" :loft "upper"}
                                                    :parlour {:_base "_b" :mood "happy"}})
                       [:table :parlour])))

    (it "strips :_base from the resolved entry"
      (should-not-contain :_base
                          (get-in (sut/resolve-table :crew {:_worker {:model "grover"}
                                                            :marvin  {:_base "_worker"}})
                                  [:table :marvin])))

    (it "leaves non-map values in the table alone"
      (should= {:enabled? true :marvin {:model "grover"}}
               (:table (sut/resolve-table :hail {:enabled? true
                                                 :_worker  {:model "grover"}
                                                 :marvin   {:_base "_worker"}}))))

    (it "a :_base naming no template is an error naming the entry and the template"
      (let [{:keys [table errors]} (sut/resolve-table :signals {:parlour {:_base "_missing"}})]
        (should= {} table)
        (should= 1 (count errors))
        (should= "signals.parlour" (:key (first errors)))
        (should-contain "_missing" (:value (first errors)))))

    (it "a cycle is an error naming the cycle"
      (let [{:keys [errors]} (sut/resolve-table :signals {:_a      {:_base "_b"}
                                                          :_b      {:_base "_a"}
                                                          :parlour {:_base "_a"}})
            entry-error      (first (filter #(= "signals.parlour" (:key %)) errors))]
        (should-not-be-nil entry-error)
        (should-contain "_a" (:value entry-error))
        (should-contain "_b" (:value entry-error))))

    (it "a self-referencing :_base is a cycle"
      (let [{:keys [errors]} (sut/resolve-table :signals {:parlour {:_base "_parlour"}
                                                          :_parlour {:_base "_parlour"}})]
        (should-contain "signals.parlour" (map :key errors))))

    (it "a :_base that is not a name is an error"
      (let [{:keys [errors]} (sut/resolve-table :signals {:parlour {:_base 7}})]
        (should= "signals.parlour" (:key (first errors)))))

    (it "an entry whose resolution failed is dropped from the table"
      (should= {:keaton {:model "grover"}}
               (:table (sut/resolve-table :crew {:keaton  {:model "grover"}
                                                 :marvin  {:_base "_gone"}})))))

  (context "resolve-config"

    (it "resolves every entity table in the config"
      (let [{:keys [config errors]}
            (sut/resolve-config {:crew    {:_worker {:model "grover"}
                                           :marvin  {:_base "_worker"}}
                                 :signals {:_sig    {:kind "parlor"}
                                           :parlour {:_base "_sig"}}})]
        (should= [] errors)
        (should= {:marvin {:model "grover"}} (:crew config))
        (should= {:parlour {:kind "parlor"}} (:signals config))))

    (it "works for a key no module declares"
      (should= {:gizmo {:color "red"}}
               (:widgets (:config (sut/resolve-config {:widgets {:_w    {:color "red"}
                                                                 :gizmo {:_base "_w"}}})))))

    (it "leaves tables with no templating markers identical"
      (let [config {:modules  {:isaac.agent {:local/root "../isaac-agent"}}
                    :defaults {:crew "main"}}]
        (should-be-same config (:config (sut/resolve-config config)))))

    (it "leaves non-table values alone"
      (let [{:keys [config]} (sut/resolve-config {:tz      "UTC"
                                                  :signals {:_s {:kind "parlor"}
                                                            :p  {:_base "_s"}}})]
        (should= "UTC" (:tz config))))

    (it "collects errors from every table"
      (let [{:keys [errors]} (sut/resolve-config {:crew    {:marvin  {:_base "_gone"}}
                                                  :signals {:parlour {:_base "_missing"}}})]
        (should= ["crew.marvin" "signals.parlour"] (sort (map :key errors)))))))
