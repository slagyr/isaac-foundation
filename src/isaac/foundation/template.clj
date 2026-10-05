(ns isaac.foundation.template
  "Mustache-lite interpolation over keyword- or string-keyed bindings."
  (:require
    [clojure.string :as str]))

(def ^:private placeholder-re #"\{\{([\w.-]+)\}\}")

(defn- lookup [bindings path]
  (reduce (fn [[present? value] segment]
            (if (and present? (map? value))
              (cond
                (contains? value (keyword segment)) [true (get value (keyword segment))]
                (contains? value segment) [true (get value segment)]
                :else [false nil])
              [false nil]))
          [true bindings]
          (str/split path #"\.")))

(defn render
  "Render {{name}} and {{a.b}}. Missing values default to :keep; :empty
   replaces them with empty text, :marker with (missing). Present nil renders
   as empty text, as with ordinary str conversion."
  [template bindings & {:keys [on-missing] :or {on-missing :keep}}]
  (str/replace (or template "") placeholder-re
               (fn [[placeholder path]]
                 (let [[present? value] (lookup bindings path)]
                   (if present?
                     (str value)
                     (case on-missing
                       :empty ""
                       :marker "(missing)"
                       placeholder))))))

(defn render-all
  "Render every string in a nested map, vector or sequence; preserve scalars."
  ([value bindings] (render-all value bindings {}))
  ([value bindings opts]
   (cond
     (string? value) (render value bindings opts)
     (map? value) (into (empty value) (map (fn [[k v]] [k (render-all v bindings opts)])) value)
     (vector? value) (mapv #(render-all % bindings opts) value)
     (seq? value) (map #(render-all % bindings opts) value)
     :else value)))

(defn placeholders
  "Names occurring in strings anywhere inside a nested value."
  [value]
  (cond
    (string? value) (set (map second (re-seq placeholder-re value)))
    (map? value) (into #{} (mapcat placeholders) (vals value))
    (sequential? value) (into #{} (mapcat placeholders) value)
    :else #{}))
