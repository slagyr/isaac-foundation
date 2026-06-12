(ns isaac.foundation.fs-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.foundation.fs-steps :as sut]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def test-root "/target/test-state")
(def test-crew-id marigold/first-mate)
(def test-model-id (keyword marigold/helm-mark-iii))
(def updated-model-id (keyword marigold/helm-spark))

(describe "foundation fs steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (it))
    (g/reset!))

  (it "writes isaac EDN files relative to root"
    (g/assoc! :mem-fs (nexus/get :fs))
    (g/assoc! :root test-root)
    (sut/isaac-edn-file-exists (str "config/crew/" test-crew-id ".edn")
                               {:headers ["path" "value"]
                                :rows    [["model" marigold/helm-mark-iii]
                                          ["soul" "You are Cordelia."]]})
    (should= (marigold/crew-cfg test-crew-id :model test-model-id)
             (read-string (fs/slurp (nexus/get :fs) (str test-root "/config/crew/" test-crew-id ".edn")))))

  (it "invalidates cached feature config when writing isaac EDN files"
    (g/assoc! :mem-fs (nexus/get :fs))
    (g/assoc! :root test-root)
    (g/assoc! :feature-config {:crew {"stale" {}}})
    (sut/isaac-edn-file-exists (str "config/crew/" test-crew-id ".edn")
                               {:headers ["path" "value"]
                                :rows    [["model" marigold/helm-mark-iii]]})
    (should-be-nil (g/get :feature-config)))

  (it "writes bare isaac.edn under the config directory"
    (g/assoc! :mem-fs (nexus/get :fs))
    (g/assoc! :root test-root)
    (sut/isaac-file-exists-with-content "isaac.edn" "{:crew {}}")
    (should= "{:crew {}}"
             (fs/slurp (nexus/get :fs) (str test-root "/config/isaac.edn"))))

  (it "deletes isaac EDN file keys with #delete"
    (g/assoc! :mem-fs (nexus/get :fs))
    (g/assoc! :root test-root)
    (fs/mkdirs (nexus/get :fs) (str test-root "/config/crew"))
    (fs/spit (nexus/get :fs) (str test-root "/config/crew/" test-crew-id ".edn")
             (pr-str (assoc (marigold/crew-cfg test-crew-id :model test-model-id)
                            :tools {:allow [(keyword marigold/spyglass-tool)]})))
    (sut/isaac-edn-file-exists (str "config/crew/" test-crew-id ".edn")
                               {:headers ["path" "value"]
                                :rows    [["soul" "#delete"]
                                          ["model" marigold/helm-spark]]})
    (should= {:model updated-model-id
              :tools {:allow [(keyword marigold/spyglass-tool)]}}
             (read-string (fs/slurp (nexus/get :fs) (str test-root "/config/crew/" test-crew-id ".edn")))))

  (it "deletes EDN isaac file keys with #delete in write mode"
    (g/assoc! :mem-fs (nexus/get :fs))
    (g/assoc! :root test-root)
    (fs/mkdirs (nexus/get :fs) (str test-root "/delivery/pending"))
    (fs/spit (nexus/get :fs) (str test-root "/delivery/pending/7f3a.edn")
             (pr-str {"status" "pending"
                      "attempt" 1}))
    (sut/edn-isaac-file-contains "delivery/pending/7f3a.edn"
                                 {:headers ["path" "value"]
                                  :rows    [["status" "#delete"]
                                            ["attempt" "2"]]})
    (should= {"attempt" 2}
             (read-string (fs/slurp (nexus/get :fs) (str test-root "/delivery/pending/7f3a.edn"))))))
