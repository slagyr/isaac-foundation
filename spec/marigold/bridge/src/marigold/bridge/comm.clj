(ns marigold.bridge.comm)

(defmulti create-comm-node! (fn [_path slice] (:type slice)))
