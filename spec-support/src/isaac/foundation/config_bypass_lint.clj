(ns isaac.foundation.config-bypass-lint
  "Static guard: production namespaces must not read Isaac config content via raw
   slurp + edn/read-string. Exposed through io.github.slagyr/isaac-foundation-test-support."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private allowed-ns-prefixes
  #{"isaac.config."})

(def ^:private allowed-exact-ns
  #{"isaac.cli.registry"})

(def ^:private suspicious-patterns
  [#"config/isaac\.edn"
   #"root-config-file"
   #"paths/config-path"
   #"\"/config/"
   #"\.env\""])

(defn- ns-from-file [content]
  (when-let [line (some #(when (str/starts-with? % "(ns ") %) (str/split-lines (str content)))]
    (-> line (subs 4) str/trim (str/split #" ") first)))

(defn- allowed-ns? [ns-name]
  (or (contains? allowed-exact-ns ns-name)
      (some #(str/starts-with? ns-name %) allowed-ns-prefixes)))

(defn- reads-config-content? [content]
  (and (re-find #"edn/read-string" content)
       (or (re-find #"fs/slurp" content)
           (re-find #"\(slurp " content))
       (some #(re-find % content) suspicious-patterns)))

(defn- collect-clj-files [dir]
  (let [root (io/file dir)]
    (when (.exists root)
      (->> (file-seq root)
           (filter #(.isFile %))
           (map str)
           (filter #(str/ends-with? % ".clj"))
           vec))))

(defn lint-targets
  "Scan dirs (default [\"src\"]) and return violation maps {:path :ns}."
  [dirs]
  (let [targets (if (seq dirs) dirs ["src"])
        files   (mapcat collect-clj-files targets)]
    (vec (keep (fn [path]
                 (let [content (slurp path)
                       ns-name (ns-from-file content)]
                   (when (and ns-name (not (allowed-ns? ns-name)) (reads-config-content? content))
                     {:path path :ns ns-name})))
               files))))

(defn lint!
  "Print violations and exit 1 when any are found; otherwise print ok."
  [& dirs]
  (let [hits (lint-targets dirs)]
    (doseq [{:keys [path ns]} hits]
      (println (str path ": " ns " reads config content outside isaac.config.*")))
    (if (seq hits)
      (do (println (str "\nconfig-bypass-lint: " (count hits) " violation(s)"))
          (System/exit 1))
      (println "config-bypass-lint: ok"))))