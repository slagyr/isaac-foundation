(ns isaac.foundation.pin-lint
  "Fail-fast reachability lint for isaac-* :git/sha pins in deps.edn / bb.edn.

   Fetch is a seam (`*fetch-sha*`) so specs never touch the network. GitHub
   serves only ref-reachable shas; a failed fetch IS the reachability test."
  (:require
    [babashka.fs :as bfs]
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.string :as str]))

(def ^:dynamic *fetch-sha*
  "Seam: (fn [url sha] boolean). True when GitHub (or the remote) serves the sha."
  nil)

(defn env
  "Read an environment variable. Seam for ISAAC_LINT_PINS=0."
  [k]
  (System/getenv k))

(defn- isaac-lib? [lib]
  (and (symbol? lib)
       (= "io.github.slagyr" (namespace lib))
       (str/starts-with? (name lib) "isaac-")))

(defn- git-pin? [coord]
  (and (map? coord)
       (string? (:git/url coord))
       (string? (:git/sha coord))))

(defn- alias-deps [edn]
  (mapcat (fn [[_ alias]]
            (concat (seq (:extra-deps alias))
                    (seq (:override-deps alias))))
          (:aliases edn)))

(defn- collect-pins [edn]
  (->> (concat (seq (:deps edn)) (alias-deps edn))
       (keep (fn [[lib coord]]
               (when (and (isaac-lib? lib) (git-pin? coord))
                 {:dep lib :url (:git/url coord) :sha (:git/sha coord)})))
       distinct
       vec))

(defn- default-fetch-sha [url sha]
  (let [scratch (str (bfs/create-temp-dir {:prefix "isaac-pin-lint-"}))
        result  (try
                  (process/shell {:dir     scratch
                                  :out     :string
                                  :err     :string
                                  :continue true}
                                 "git" "init" "-q")
                  (process/shell {:dir     scratch
                                  :out     :string
                                  :err     :string
                                  :continue true}
                                 "git" "fetch" "--depth" "1" url sha)
                  (catch Exception _ {:exit 1}))]
    (try (bfs/delete-tree scratch) (catch Exception _))
    (zero? (or (:exit result) 1))))

(defn- fetch-sha [url sha]
  (if *fetch-sha*
    (*fetch-sha* url sha)
    (default-fetch-sha url sha)))

(defn findings
  "Return unreachable-pin maps {:file :dep :sha} for isaac-* git pins in `edn`."
  [file edn]
  (vec (keep (fn [{:keys [dep url sha]}]
               (when-not (fetch-sha url sha)
                 {:file file :dep dep :sha sha}))
             (collect-pins edn))))

(defn- read-edn [path]
  (when (bfs/exists? path)
    (try
      (edn/read-string {:readers *data-readers*} (slurp (str path)))
      (catch Exception _ nil))))

(defn- lint-file [path]
  (if-let [edn (read-edn path)]
    (findings (str path) edn)
    []))

(defn- print-hits! [hits]
  (doseq [{:keys [file dep sha]} hits]
    (println (str file ": unreachable pin " dep " @ " sha))))

(defn lint!
  "Scan deps.edn and bb.edn under root (first arg, default \".\").
   ISAAC_LINT_PINS=0 warns and returns :skipped. Unreachable pins throw."
  ([] (lint! ["."]))
  ([args]
   (if (= "0" (env "ISAAC_LINT_PINS"))
     (do (println "lint-pins: skipped (ISAAC_LINT_PINS=0)")
         :skipped)
     (let [root (or (first args) ".")
           hits (into [] (mapcat lint-file) [(str root "/deps.edn") (str root "/bb.edn")])]
       (print-hits! hits)
       (if (seq hits)
         (throw (ex-info "pin lint failed" {:findings hits}))
         (do (println "lint-pins: ok")
             :ok))))))
