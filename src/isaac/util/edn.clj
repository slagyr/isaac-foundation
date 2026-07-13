(ns isaac.util.edn
  "Human-readable EDN pretty-printer (isaac-524u).

  Width-driven: collections render inline when they fit the remaining budget
  at the current indent; otherwise block (opening brace ends the line, entries
  at +2 spaces, closing brace alone). Maps are sorted and justified to the
  longest key; sequences keep original order and are not justified."
  (:require
    [clojure.string :as str]))

(def ^:private min-width 40)
(def ^:private max-width 80)
(def ^:private indent-step 2)

(defn clamp-width
  "Clamp terminal column count into the pretty-print budget [40, 80]."
  [n]
  (let [n (long (or n max-width))]
    (-> n (max min-width) (min max-width))))

(defn- sorted-map-entries [m]
  (sort-by (comp pr-str key) (seq m)))

(defn- key-str [k]
  (pr-str k))

(declare format*)

(defn- coll-value?
  "True for non-empty maps/vectors/seqs/sets (nested structure)."
  [v]
  (and (coll? v) (not (string? v)) (seq v)))

(defn- one-line-form
  "Single-line rendering of value, or nil when the value must block.
   Nested non-empty collections inside a map force the map to block so
   nested structure stays readable (Example 3: :tools breaks, :compaction stays inline)."
  [value]
  (cond
    (nil? value) "nil"
    (string? value) (pr-str value)
    (keyword? value) (pr-str value)
    (symbol? value) (pr-str value)
    (number? value) (pr-str value)
    (boolean? value) (pr-str value)
    (and (map? value) (empty? value)) "{}"
    (and (vector? value) (empty? value)) "[]"
    (and (set? value) (empty? value)) "#{}"
    (and (sequential? value) (empty? value) (not (vector? value)) (not (set? value))) "()"
    (map? value)
    (if (some (comp coll-value? val) value)
      nil
      (let [parts (map (fn [[k v]]
                         (str (key-str k) " " (or (one-line-form v) (pr-str v))))
                       (sorted-map-entries value))]
        (str "{" (str/join " " parts) "}")))
    (vector? value)
    (let [parts (map one-line-form value)]
      (when (every? some? parts)
        (str "[" (str/join " " parts) "]")))
    (set? value)
    (let [parts (map one-line-form (sort-by pr-str value))]
      (when (every? some? parts)
        (str "#{" (str/join " " parts) "}")))
    (sequential? value)
    (let [parts (map one-line-form value)]
      (when (every? some? parts)
        (str "(" (str/join " " parts) ")")))
    :else (pr-str value)))

(defn- fits? [s budget]
  (and (string? s)
       (not (str/includes? s "\n"))
       (<= (count s) budget)))

(defn- pad-key [k-str target-width]
  (let [pad (- target-width (count k-str))]
    (if (pos? pad)
      (str k-str (apply str (repeat pad \space)))
      k-str)))

(defn- format-map-block [m width indent]
  (let [entries     (sorted-map-entries m)
        key-strs    (map (comp key-str key) entries)
        key-width   (if (seq key-strs) (apply max (map count key-strs)) 0)
        body-indent (+ indent indent-step)
        pad         (apply str (repeat body-indent \space))
        close-pad   (apply str (repeat indent \space))
        lines
        (mapv (fn [[k v]]
                (let [ks     (pad-key (key-str k) key-width)
                      budget (- width body-indent (count ks) 1)
                      vs     (format* v width body-indent budget)
                      vlines (str/split-lines vs)]
                  (if (= 1 (count vlines))
                    (str pad ks " " (first vlines))
                    (str pad ks " " (first vlines) "\n"
                         (str/join "\n" (rest vlines))))))
              entries)]
    (str "{\n"
         (str/join "\n" lines)
         "\n" close-pad "}")))

(defn- format-seq-block [open close items width indent]
  (let [body-indent (+ indent indent-step)
        pad         (apply str (repeat body-indent \space))
        close-pad   (apply str (repeat indent \space))
        budget      (- width body-indent)
        lines       (mapv (fn [item]
                            (let [s (format* item width body-indent budget)]
                              (str pad s)))
                          items)]
    (str open "\n"
         (str/join "\n" lines)
         "\n" close-pad close)))

(defn- format*
  "Format value at indent. budget is remaining columns for an inline form
   (width - indent for top-level call sites)."
  ([value width indent]
   (format* value width indent (- width indent)))
  ([value width indent budget]
   (cond
     (nil? value) "nil"
     (string? value) (pr-str value)
     (keyword? value) (pr-str value)
     (symbol? value) (pr-str value)
     (number? value) (pr-str value)
     (boolean? value) (pr-str value)
     (and (map? value) (empty? value)) "{}"
     (and (vector? value) (empty? value)) "[]"
     (and (set? value) (empty? value)) "#{}"
     (and (sequential? value) (empty? value) (not (vector? value)) (not (set? value))) "()"

     (map? value)
     (let [inline (one-line-form value)]
       (if (and inline (fits? inline budget))
         inline
         (format-map-block value width indent)))

     (vector? value)
     (let [inline (one-line-form value)]
       (if (and inline (fits? inline budget))
         inline
         (format-seq-block "[" "]" value width indent)))

     (set? value)
     (let [inline (one-line-form value)]
       (if (and inline (fits? inline budget))
         inline
         (format-seq-block "#{" "}" (sort-by pr-str value) width indent)))

     (sequential? value)
     (let [inline (one-line-form value)]
       (if (and inline (fits? inline budget))
         inline
         (format-seq-block "(" ")" value width indent)))

     :else (pr-str value))))

(defn pretty
  "Pretty-print value as human-readable EDN.

  Arity-1 uses width 80 (non-TTY default).
  Arity-2 takes an explicit width (clamped to [40, 80]).
  Arity-3 is for recursive callers: (pretty value width indent) where indent is
  the current column offset for contextual fit."
  ([value]
   (pretty value max-width))
  ([value width]
   (let [w (clamp-width width)]
     (format* value w 0)))
  ([value width indent]
   (let [w (clamp-width width)]
     (format* value w indent))))
