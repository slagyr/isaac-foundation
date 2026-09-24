(ns isaac.modules.pins-spec
  (:require
    [isaac.modules.pins :as pins]
    [speclj.core :refer :all]))

(def foundation-url "https://github.com/slagyr/isaac-foundation.git")
(def agent-url "https://github.com/slagyr/isaac-agent.git")
(def http-url "https://github.com/slagyr/isaac-http.git")

(def registry {:isaac.agent {:coord {:git/url agent-url :git/sha "b6eb475"}}
               :isaac.http  {:coord {:git/url http-url :git/sha "689d368"}}})

(defn- pin [repo sha at]
  {:repo repo :sha sha :at at :url (str "https://github.com/slagyr/" repo ".git")
   :lib (symbol "io.github.slagyr" repo)})

(describe "isaac.modules.pins"

  (context "repo-key"
    (it "names the repository, not the lib"
      (should= "isaac-foundation" (pins/repo-key foundation-url))
      (should= "isaac-agent" (pins/repo-key "https://github.com/slagyr/isaac-agent"))
      (should= "fixture-agent" (pins/repo-key "fixture-agent"))))

  (context "pin-set"
    (it "collects sibling git pins from :deps and from aliases"
      (let [deps-map {:deps    {'io.github.slagyr/isaac-foundation {:git/url foundation-url :git/sha "9ab2527"}
                                'babashka/process                  {:mvn/version "0.5.22"}}
                      :aliases {:spec      {:extra-deps {'io.github.slagyr/isaac-http {:git/url http-url :git/sha "689d368"}}}
                                :dev-local {:override-deps {'io.github.slagyr/isaac-http {:local/root "../isaac-http"}}}}}]
        (should= [{:repo "isaac-foundation" :sha "9ab2527" :at "deps"}
                  {:repo "isaac-http" :sha "689d368" :at "alias :spec"}]
                 (mapv #(select-keys % [:repo :sha :at]) (pins/pin-set deps-map)))))

    (it "counts a sibling repo's other artifacts — spec, test-support, bundled modules — as pins of that repo"
      (let [deps-map {:deps    {'io.github.slagyr/isaac-foundation {:git/url foundation-url :git/sha "9ab2527"}}
                      :aliases {:spec {:extra-deps {'io.github.slagyr/isaac-foundation-test-support
                                                    {:git/url foundation-url :git/sha "9ab2527" :deps/root "spec-support"}
                                                    'marigold.bridge/marigold.bridge
                                                    {:git/url foundation-url :git/sha "b644562" :deps/root "modules/marigold.bridge"}}}}}]
        (should= ["isaac-foundation" "isaac-foundation" "isaac-foundation"]
                 (mapv :repo (pins/pin-set deps-map)))))

    (it "ignores git deps that are not sibling repositories"
      (let [deps-map {:deps {'io.github.slagyr/gherclj {:git/url "https://github.com/slagyr/gherclj.git" :git/sha "85245b1"}}}]
        (should= [] (pins/pin-set deps-map)))))

  (context "split-pins"
    (it "is empty when every pin of a repo agrees"
      (should= [] (pins/split-pins [(pin "isaac-foundation" "9ab2527" "deps")
                                    (pin "isaac-foundation" "9ab2527" "alias :spec")])))

    (it "reports a repo pinned at two shas in the same deps.edn"
      (let [split (first (pins/split-pins [(pin "isaac-foundation" "9ab2527" "deps")
                                           (pin "isaac-foundation" "b644562" "alias :features")]))]
        (should= "isaac-foundation" (:repo split))
        (should= ["9ab2527" "b644562"] (mapv :sha (:pins split))))))

  (context "conflicts"
    (it "is empty when our pins agree with what each pinned sibling requires"
      (should= [] (pins/conflicts [(pin "isaac-agent" "b6eb475" "deps")
                                   (pin "isaac-foundation" "9ab2527" "deps")]
                                  [{:repo "isaac-agent" :sha "b6eb475"
                                    :requires {"isaac-foundation" "9ab2527"}}])))

    (it "reports the sibling that requires a foundation we do not pin"
      (should= [{:repo   "isaac-foundation"
                 :ours   "b644562"
                 :theirs "9ab2527"
                 :source "isaac-agent"
                 :source-sha "b6eb475"}]
               (pins/conflicts [(pin "isaac-agent" "b6eb475" "deps")
                                (pin "isaac-foundation" "b644562" "deps")]
                               [{:repo "isaac-agent" :sha "b6eb475"
                                 :requires {"isaac-foundation" "9ab2527"}}])))

    (it "says nothing about a repo we do not pin at all"
      (should= [] (pins/conflicts [(pin "isaac-agent" "b6eb475" "deps")]
                                  [{:repo "isaac-agent" :sha "b6eb475"
                                    :requires {"isaac-foundation" "9ab2527"}}]))))

  (context "fleet-shas"
    (it "takes a registry module's sha straight from the registry"
      (should= "b6eb475" (get-in (pins/fleet-shas registry []) [:fleet "isaac-agent"])))

    (it "takes foundation — which the registry does not list — from what the fleet's own modules require"
      (should= "9ab2527"
               (get-in (pins/fleet-shas registry [{:repo "isaac-agent" :sha "b6eb475"
                                                   :requires {"isaac-foundation" "9ab2527"}}
                                                  {:repo "isaac-http" :sha "689d368"
                                                   :requires {"isaac-foundation" "9ab2527"}}])
                       [:fleet "isaac-foundation"])))

    (it "names no fleet sha for a repo the fleet's own modules disagree about"
      (let [{:keys [fleet disagreements]}
            (pins/fleet-shas registry [{:repo "isaac-agent" :sha "b6eb475"
                                        :requires {"isaac-foundation" "9ab2527"}}
                                       {:repo "isaac-http" :sha "689d368"
                                        :requires {"isaac-foundation" "b644562"}}])]
        (should-not-contain "isaac-foundation" fleet)
        (should= #{"isaac-foundation"} disagreements)))

    (it "prefers the registry sha over what a module requires"
      (should= "b6eb475"
               (get-in (pins/fleet-shas registry [{:repo "isaac-http" :sha "689d368"
                                                   :requires {"isaac-agent" "aaaaaaa"}}])
                       [:fleet "isaac-agent"]))))

  (context "target-set"
    (it "names the coherent set to move to for every repo this one pins"
      (should= [{:repo "isaac-agent" :sha "b6eb475"}
                {:repo "isaac-foundation" :sha "9ab2527"}]
               (pins/target-set [(pin "isaac-agent" "8cfd44d" "deps")
                                 (pin "isaac-foundation" "b644562" "deps")]
                                {"isaac-agent"      "b6eb475"
                                 "isaac-foundation" "9ab2527"
                                 "isaac-http"       "689d368"}
                                []
                                #{})))

    (it "falls back to what a pinned sibling requires when the fleet set is silent"
      (should= [{:repo "isaac-agent" :sha "b6eb475"}
                {:repo "isaac-foundation" :sha "9ab2527"}]
               (pins/target-set [(pin "isaac-agent" "b6eb475" "deps")
                                 (pin "isaac-foundation" "b644562" "deps")]
                                {"isaac-agent" "b6eb475"}
                                [{:repo "isaac-agent" :sha "b6eb475"
                                  :requires {"isaac-foundation" "9ab2527"}}]
                                #{})))

    (it "leaves out a repo the fleet disagrees about rather than naming an incoherent set"
      (should= [{:repo "isaac-agent" :sha "b6eb475"}]
               (pins/target-set [(pin "isaac-agent" "8cfd44d" "deps")
                                 (pin "isaac-foundation" "b644562" "deps")]
                                {"isaac-agent" "b6eb475"}
                                []
                                #{"isaac-foundation"})))))
