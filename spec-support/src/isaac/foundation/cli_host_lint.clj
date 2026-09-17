(ns isaac.foundation.cli-host-lint
  "Lint command-facing production code for process-global CLI operations."
  (:require
    [babashka.fs :as bfs]
    [clojure.string :as str]))

(def ^:private forbidden
  [#"System/(exit|getenv|console|setProperty)"
   #"System/getProperty\s+\"user\.dir\""
   #"addShutdownHook"])

(def ^:private allowed
  ["src/isaac/cli/host.clj"
   "src/isaac/main.clj"
   "src/isaac/launcher.clj"])

(defn findings [root]
  (for [path (bfs/glob root "src/**/*.clj")
        :let [relative (str (bfs/relativize root path))]
        :when (not (some #{relative} allowed))
        [line-number line] (map-indexed vector (str/split-lines (slurp (str path))))
        pattern forbidden
        :when (re-find pattern line)]
    {:file relative :line (inc line-number) :text (str/trim line)}))

(defn lint!
  ([] (lint! ["."]))
  ([args]
   (let [root   (or (first args) ".")
         errors (vec (findings root))]
     (doseq [{:keys [file line text]} errors]
       (println (str file ":" line ": forbidden CLI host bypass: " text)))
     (if (seq errors)
       (throw (ex-info "CLI host lint failed" {:findings errors}))
       (println "lint-cli-host: ok")))))
