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

(defn- normalize-message-table [table]
  (let [message-idx (.indexOf (:headers table) "message")]
    (if (neg? message-idx)
      table
      (update table :rows
                (fn [rows]
                  (mapv (fn [row]
                          (let [cell (nth row message-idx nil)]
                            (if (or (nil? cell) (str/starts-with? cell "#\""))
                              row
                              (assoc row message-idx (str "#\"" cell "\"")))))
                        rows))))))

(defn- first-row-match-idx [table entries start]
  (let [headers (:headers table)
        row     (first (:rows table))]
    (some (fn [i]
            (let [result (match/match-entries {:headers headers :rows [row]}
                                               [(nth entries i)])]
              (when (empty? (:failures result)) i)))
          (range start (count entries)))))

(defn- log-match-result [table entries]
  (let [table (normalize-message-table table)]
    (loop [remaining (:rows table)
           row-num   0
           idx       0
           captures  {}
           failures  []]
      (if (empty? remaining)
        {:pass? (empty? failures) :failures failures :captures captures}
        (let [single    {:headers (:headers table) :rows [(first remaining)]}
              match-idx (first-row-match-idx single entries idx)]
          (if (some? match-idx)
            (let [result (match/match-entries single [(nth entries match-idx)])]
              (recur (rest remaining)
                     (inc row-num)
                     (inc match-idx)
                     (merge captures (:captures result))
                     failures))
            (let [result (match/match-entries single (subvec (vec entries) idx))]
              (recur []
                     row-num
                     idx
                     captures
                     (into failures (map #(str "Row " row-num ": " %) (:failures result)))))))))))

(defn log-entries-match [table]
  (let [turn-future (g/get :turn-future)
        result*     (atom nil)
        matched?    (fn []
                      (let [result (log-match-result table (log/get-entries))]
                        (reset! result* result)
                        (empty? (:failures result))))]
    ;; Wait for async work only when the log is not already satisfied — a
    ;; realized :turn-future must not short-circuit polling for entries that
    ;; arrive later in the same synchronous boot (e.g. comm on-load after
    ;; :server/boot-phase).
    (when (and turn-future (not (realized? turn-future)) (not (matched?)))
      (deref turn-future 30000 nil))
    (helper/await-condition matched? 5000)
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