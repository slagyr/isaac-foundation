(ns isaac.foundation.log-steps
  "Foundation-grade log assertions: match captured log entries against a
   table. Depends only on foundation namespaces (logger, step-tables,
   spec-helper); moves to the foundation repo at cut time."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defthen helper!]]
    [isaac.logger :as log]
    [isaac.spec-helper :as helper]
    [isaac.step-tables :as match]))

(helper! isaac.foundation.log-steps)

(defn- log-match-result [table entries]
  (let [headers         (:headers table)
        message-idx     (.indexOf headers "message")
        table           (if (neg? message-idx)
                          table
                          (update table :rows
                                  (fn [rows]
                                    (mapv (fn [row]
                                            (let [cell (nth row message-idx nil)]
                                              (if (or (nil? cell) (str/starts-with? cell "#\""))
                                                row
                                                (assoc row message-idx (str "#\"" cell "\"")))))
                                          rows))))
        expected-count (count (:rows table))
        direct         (match/match-entries table entries)]
    (if (empty? (:failures direct))
      direct
      (or (some (fn [start]
                  (let [window (subvec (vec entries) start (min (count entries) (+ start expected-count)))
                        result (match/match-entries table window)]
                    (when (empty? (:failures result)) result)))
                (range (count entries)))
          direct))))

(defn log-entries-match [table]
  (let [turn-future (g/get :turn-future)
        result*     (atom nil)
        matched?    (fn []
                      (let [result (log-match-result table (log/get-entries))]
                        (reset! result* result)
                        (empty? (:failures result))))]
    (helper/await-condition
      #(or (matched?)
           (some-> turn-future realized?))
      100)
    (when (and (seq (:failures @result*)) turn-future (not (realized? turn-future)))
      (deref turn-future 30000 nil))
    (when (seq (:failures @result*))
      (helper/await-condition matched? 2000))
    (g/should= [] (:failures @result*))))

(defn log-entries-dont-match [table]
  (let [entries (log/get-entries)
        headers (:headers table)]
    (doseq [row (:rows table)]
      (let [result (match/match-entries {:headers headers :rows [row]} entries)]
        (g/should-not (:pass? result))))))

;; region ----- Routing -----

(defthen "the log has entries matching:" isaac.foundation.log-steps/log-entries-match
  "Polls captured log entries (in-memory logger) for the table rows as an
   ordered subsequence, awaiting :turn-future if one is pending.")

(defthen "the log has no entries matching:" isaac.foundation.log-steps/log-entries-dont-match)

;; endregion ^^^^^ Routing ^^^^^
