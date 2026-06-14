(ns isaac.cli.args
  "Pure CLI argument helpers (no registry or config dependencies)."
  (:require [clojure.string :as str]))

(defn extract-root-flag
  "Strips --root <dir> (or --root=<dir>) from args, returning
   {:args <stripped> :root <explicit-or-nil>}."
  [args]
  (loop [remaining args
         stripped  []
         explicit  nil]
    (if-let [arg (first remaining)]
      (cond
        (= "--root" arg)
        (if-let [value (second remaining)]
          (recur (nnext remaining) stripped value)
          {:args args :root explicit})

        (str/starts-with? arg "--root=")
        (recur (rest remaining) stripped (subs arg (count "--root=")))

        :else
        (recur (rest remaining) (conj stripped arg) explicit))
      {:args stripped :root explicit})))