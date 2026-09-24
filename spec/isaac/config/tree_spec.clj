(ns isaac.config.tree-spec
  (:require
    [isaac.config.tree :as sut]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private config-root (str marigold/root "/config"))

(defn- write! [relative content]
  (let [fs*  (nexus/get :fs)
        path (str config-root "/" relative)]
    #_{:clj-kondo/ignore [:invalid-arity]}
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path content)))

(defn- mkdir! [relative]
  #_{:clj-kondo/ignore [:invalid-arity]}
  (fs/mkdirs (nexus/get :fs) (str config-root "/" relative)))

(defn- aboard []
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (mkdir! "")
      (example))))

(describe "isaac.config.tree"

  (aboard)

  (describe "scan"

    (it "finds nothing in an empty config directory"
      (should= {:slices {} :dirs {} :errors []} (sut/scan config-root)))

    (it "ignores isaac.edn — the root file is not a key"
      (write! "isaac.edn" "{}")
      (should= {} (:slices (sut/scan config-root))))

    (it "reads a top-level .edn file as a key"
      (write! "modules.edn" "{}")
      (should= {:modules "modules.edn"} (:slices (sut/scan config-root)))
      (should= [] (:errors (sut/scan config-root))))

    (it "reads a top-level directory as a key"
      (write! (str "berths/" marigold/first-mate ".edn") "{}")
      (should= {:berths "berths"} (:dirs (sut/scan config-root)))
      (should= {} (:slices (sut/scan config-root))))

    (it "keeps a dotted filename whole — filenames are literal names, never paths"
      (write! "isaac.agent.edn" "{}")
      (should= {:isaac.agent "isaac.agent.edn"} (:slices (sut/scan config-root))))

    (it "refuses a key that is both a file and a directory"
      (write! "berths.edn" "{}")
      (write! (str "berths/" marigold/first-mate ".edn") "{}")
      (let [{:keys [slices dirs errors]} (sut/scan config-root)]
        (should= {} slices)
        (should= {} dirs)
        (should= 1 (count errors))
        (should= "berths" (:key (first errors)))
        (should-contain "config/berths.edn" (:value (first errors)))
        (should-contain "config/berths/" (:value (first errors)))))

    (it "does not refuse an .edn and .md pair — that is an entity and its companion"
      (write! (str "berths/" marigold/first-mate ".edn") "{}")
      (write! (str "berths/" marigold/first-mate ".md") "You keep the log.")
      (should= [] (:errors (sut/scan config-root))))

    (it "ignores a dot-file at the config root — a dot-entry is not config (isaac-63ei)"
      (write! ".modules.edn" "{}")
      (write! ".notes.md" "scratch")
      (should= {:slices {} :dirs {} :errors []} (sut/scan config-root)))

    (it "ignores a dot-directory at the config root"
      (write! ".removed-20260915/crew.edn" "{}")
      (should= {:slices {} :dirs {} :errors []} (sut/scan config-root)))

    (it "does not let a dot-entry collide with the key it shadows"
      (write! "berths.edn" "{}")
      (mkdir! ".berths")
      (should= {:berths "berths.edn"} (:slices (sut/scan config-root)))
      (should= [] (:errors (sut/scan config-root)))))

  (describe "dir-errors"

    (it "reports a file-versus-directory conflict nested inside a key"
      (write! (str "berths/" marigold/first-mate ".edn") "{}")
      (write! (str "berths/" marigold/first-mate "/_.edn") "{}")
      (let [errors (sut/dir-errors (str config-root "/berths") "berths/")]
        (should= 1 (count errors))
        (should= (str "berths/" marigold/first-mate) (:key (first errors)))
        (should-contain (str "config/berths/" marigold/first-mate ".edn") (:value (first errors)))))

    (it "reports nothing for a dot-entry inside a key's directory (isaac-63ei)"
      (write! (str "berths/." marigold/first-mate ".edn") "{}")
      (write! (str "berths/." marigold/first-mate "/_.edn") "{}")
      (should= [] (sut/dir-errors (str config-root "/berths") "berths/"))))

  (describe "read-slices"

    (it "folds a slice file into the root data as the whole value of its key"
      (write! "modules.edn" "{:marigold.longwave {:local/root \"/tmp/longwave\"}}")
      (let [{:keys [data errors sources]} (sut/read-slices config-root {:modules "modules.edn"} {} false)]
        (should= [] errors)
        (should= {:local/root "/tmp/longwave"} (get-in data [:modules :marigold.longwave]))
        (should= ["config/modules.edn"] sources)))

    (it "refuses a key present both inline and in its own file"
      (write! "defaults.edn" "{:crew \"atticus\"}")
      (let [{:keys [errors]} (sut/read-slices config-root {:defaults "defaults.edn"}
                                              {:defaults {:crew "main"}} false)]
        (should= 1 (count errors))
        (should= "defaults" (:key (first errors)))
        (should-contain "config/isaac.edn" (:value (first errors)))
        (should-contain "config/defaults.edn" (:value (first errors)))))

    (it "reports a malformed slice instead of throwing"
      (write! "defaults.edn" "{:crew")
      (let [{:keys [data errors]} (sut/read-slices config-root {:defaults "defaults.edn"} {} false)]
        (should= 1 (count errors))
        (should= "defaults" (:key (first errors)))
        (should-not-contain :defaults data))))

  (describe "read-markdown"

    (it "is the whole file when there is no frontmatter"
      (write! "note.md" "You keep the longwave log.")
      (should= "You keep the longwave log."
               (sut/read-markdown (str config-root "/note.md") false)))

    (it "fills the `_`-valued field with the body"
      (write! "captain.md" "---\ngauge: helm-mark-iii\nledger: _\n---\nYou keep the log.\n")
      (should= {:gauge "helm-mark-iii" :ledger "You keep the log.\n"}
               (sut/read-markdown (str config-root "/captain.md") false)))

    (it "leaves frontmatter alone when no field claims the body"
      (write! "captain.md" "---\ngauge: helm-mark-iii\n---\nYou keep the log.\n")
      (should= {:gauge "helm-mark-iii"}
               (sut/read-markdown (str config-root "/captain.md") false))))

  (describe "read-map-dir"

    (it "names each field by its filename"
      (write! (str "berths/" marigold/first-mate "/gauge.edn") "\"helm-mark-iii\"")
      (should= {:gauge "helm-mark-iii"}
               (sut/read-map-dir (str config-root "/berths/" marigold/first-mate)
                                 (str "berths/" marigold/first-mate "/") false)))

    (it "takes `_` as the map's own values"
      (write! (str "berths/" marigold/first-mate "/_.edn") "{:gauge \"helm-mark-iii\"}")
      (write! (str "berths/" marigold/first-mate "/ledger.md") "You keep the log.")
      (should= {:gauge "helm-mark-iii" :ledger "You keep the log."}
               (sut/read-map-dir (str config-root "/berths/" marigold/first-mate)
                                 (str "berths/" marigold/first-mate "/") false)))

    (it "lets a field's own file win over the `_` map"
      (write! (str "berths/" marigold/first-mate "/_.edn") "{:gauge \"helm-mark-iii\"}")
      (write! (str "berths/" marigold/first-mate "/gauge.edn") "\"starcore-one\"")
      (should= {:gauge "starcore-one"}
               (sut/read-map-dir (str config-root "/berths/" marigold/first-mate)
                                 (str "berths/" marigold/first-mate "/") false)))

    (it "descends into a subdirectory as a nested map"
      (write! (str "berths/" marigold/first-mate "/limits/ceiling.edn") "3")
      (should= {:limits {:ceiling 3}}
               (sut/read-map-dir (str config-root "/berths/" marigold/first-mate)
                                 (str "berths/" marigold/first-mate "/") false)))

    (it "keeps a dotted filename whole inside a directory too"
      (write! "modules/isaac.agent.edn" "{:git/sha \"abc\"}")
      (should= {:isaac.agent {:git/sha "abc"}}
               (sut/read-map-dir (str config-root "/modules") "modules/" false)))

    (it "skips a dot-file and a dot-directory inside the map (isaac-63ei)"
      (write! (str "berths/" marigold/first-mate "/gauge.edn") "\"helm-mark-iii\"")
      (write! (str "berths/" marigold/first-mate "/.gauge.edn") "\"starcore-one\"")
      (write! (str "berths/" marigold/first-mate "/.removed-20260915/gauge.edn") "\"starcore-one\"")
      (should= {:gauge "helm-mark-iii"}
               (sut/read-map-dir (str config-root "/berths/" marigold/first-mate)
                                 (str "berths/" marigold/first-mate "/") false))))

  (describe "read-dir-own"

    (it "is the `_` entry of each directory"
      (write! "berths/_.edn" (str "{\"" marigold/first-mate "\" {:gauge \"helm-mark-iii\"}}"))
      (should= {:berths {marigold/first-mate {:gauge "helm-mark-iii"}}}
               (sut/read-dir-own config-root {:berths "berths"} false)))

    (it "is empty when a directory has no `_` entry"
      (write! (str "berths/" marigold/first-mate ".edn") "{}")
      (should= {} (sut/read-dir-own config-root {:berths "berths"} false)))))
