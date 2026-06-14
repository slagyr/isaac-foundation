(ns isaac.foundation.version
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]))

(defn- manifest-version []
  (try
    (some (fn [url]
            (let [manifest (edn/read-string (slurp url))]
              (when (= :isaac.foundation (:id manifest))
                (:version manifest))))
          (enumeration-seq
            (.getResources (or (.getContextClassLoader (Thread/currentThread))
                               (clojure.lang.RT/baseLoader))
                           "isaac-manifest.edn")))
    (catch Exception _ nil)))

(def ^:private manifest-version* (delay (manifest-version)))

(defn read-git-sha []
  (try
    (let [head (str/trim (slurp ".git/HEAD"))]
      (if (str/starts-with? head "ref: ")
        (let [ref-path (str ".git/" (str/trim (subs head 5)))
              sha      (str/trim (slurp ref-path))]
          (when (>= (count sha) 7) (subs sha 0 7)))
        (when (>= (count head) 7) (subs head 0 7))))
    (catch Exception _ nil)))

(defn version-string []
  (let [v   (or @manifest-version* "unknown")
        sha (read-git-sha)]
    (if sha
      (str "isaac " v " (" sha ")")
      (str "isaac " v))))