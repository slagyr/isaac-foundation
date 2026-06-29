(ns isaac.cli.args
  "Pure CLI argument helpers (no registry or config dependencies)."
  (:require [clojure.string :as str]))

(defn extract-root-flag
  "Strips --root and --log-file from args, returning
   {:args <stripped> :root <explicit-or-nil> :log-file <explicit-or-nil>}."
  [args]
  (loop [remaining args
         stripped  []
         explicit-root nil
         explicit-log-file nil]
    (if-let [arg (first remaining)]
      (cond
        (= "--root" arg)
        (if-let [value (second remaining)]
          (recur (nnext remaining) stripped value explicit-log-file)
          {:args args :root explicit-root :log-file explicit-log-file})

        (str/starts-with? arg "--root=")
        (recur (rest remaining) stripped (subs arg (count "--root=")) explicit-log-file)

        (= "--log-file" arg)
        (if-let [value (second remaining)]
          (recur (nnext remaining) stripped explicit-root value)
          {:args args :root explicit-root :log-file explicit-log-file})

        (str/starts-with? arg "--log-file=")
        (recur (rest remaining) stripped explicit-root (subs arg (count "--log-file=")))

        :else
        (recur (rest remaining) (conj stripped arg) explicit-root explicit-log-file))
      {:args stripped :root explicit-root :log-file explicit-log-file})))