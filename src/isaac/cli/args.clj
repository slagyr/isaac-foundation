(ns isaac.cli.args
  "Pure CLI argument helpers (no registry or config dependencies)."
  (:require [clojure.string :as str]))

(def valid-log-levels #{:report :error :warn :info :debug})

(defn- parse-log-level [value]
  (let [level (some-> value keyword)]
    (when (valid-log-levels level) level)))

(defn extract-root-flag
  "Strips --root, --log-file, and --log-level from args."
  [args]
  (loop [remaining args
         stripped  []
         explicit-root nil
         explicit-log-file nil
         explicit-log-level nil]
    (if-let [arg (first remaining)]
      (cond
        (= "--root" arg)
        (if-let [value (second remaining)]
          (recur (nnext remaining) stripped value explicit-log-file explicit-log-level)
          {:args args :root explicit-root :log-file explicit-log-file :log-level explicit-log-level})

        (str/starts-with? arg "--root=")
        (recur (rest remaining) stripped (subs arg (count "--root=")) explicit-log-file explicit-log-level)

        (= "--log-file" arg)
        (if-let [value (second remaining)]
          (recur (nnext remaining) stripped explicit-root value explicit-log-level)
          {:args args :root explicit-root :log-file explicit-log-file :log-level explicit-log-level})

        (str/starts-with? arg "--log-file=")
        (recur (rest remaining) stripped explicit-root (subs arg (count "--log-file=")) explicit-log-level)

        (= "--log-level" arg)
        (if-let [value (second remaining)]
          (recur (nnext remaining) stripped explicit-root explicit-log-file (parse-log-level value))
          {:args args :root explicit-root :log-file explicit-log-file :log-level explicit-log-level})

        (str/starts-with? arg "--log-level=")
        (recur (rest remaining) stripped explicit-root explicit-log-file
               (parse-log-level (subs arg (count "--log-level="))))

        :else
        (recur (rest remaining) (conj stripped arg) explicit-root explicit-log-file explicit-log-level))
      {:args stripped :root explicit-root :log-file explicit-log-file :log-level explicit-log-level})))