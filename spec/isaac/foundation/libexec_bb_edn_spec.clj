(ns isaac.foundation.libexec-bb-edn-spec
  "Guards the installed launcher's libexec/bb.edn against drifting from
   deps.edn: missing :paths entries silently break resource loading
   (the handbook chapter, isaac-46ty) and a stale :deps pin runs a
   different dependency version than dev/CI/server (isaac-46ty)."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [speclj.core :refer :all]))

(defn- read-edn [path]
  (edn/read-string (slurp (io/file path))))

(describe "libexec/bb.edn"

  (it "pins the same :deps as deps.edn"
    (let [deps-edn   (read-edn "deps.edn")
          libexec-edn (read-edn "libexec/bb.edn")]
      (should= (:deps deps-edn) (:deps libexec-edn))))

  (it "includes every deps.edn :paths entry, rebased under ../"
    (let [deps-edn    (read-edn "deps.edn")
          libexec-edn (read-edn "libexec/bb.edn")
          expected    (set (map #(str "../" %) (:paths deps-edn)))
          actual      (set (:paths libexec-edn))]
      (should (set/subset? expected actual)))))
