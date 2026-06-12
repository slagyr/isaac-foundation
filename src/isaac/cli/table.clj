(ns isaac.cli.table
  (:require
    [clojure.string :as str]
    [isaac.cli.color :as color]))

(defn- effective-color? [color?]
  (cond
    (boolean? color?)             color?
    (seq (color/env "NO_COLOR")) false
    :else                         (color/tty?)))

(defn- cell-str [col value]
  (if-let [fmt (:format col)]
    (fmt value)
    (str (or value ""))))

(defn- pad [s width align]
  (let [pad (max 0 (- width (count s)))]
    (if (= :right align)
      (str (apply str (repeat pad \space)) s)
      (str s (apply str (repeat pad \space))))))

(defn- col-width [col rows]
  (let [header-w (count (:header col))
        cell-ws  (map #(count (cell-str col (get % (:key col)))) rows)]
    (apply max header-w cell-ws)))

(defn- colorize [code s]
  (str code s color/reset))

(defn render [{:keys [columns rows zebra? color?]}]
  (let [use-color? (effective-color? color?)
        widths     (mapv #(col-width % rows) columns)
        align      #(or (:align %1) :left)

        header-cells (mapv (fn [col w]
                             (pad (:header col) w (align col)))
                           columns widths)
        header-line  (let [s (str/join "  " header-cells)]
                       (if use-color? (colorize color/bold s) s))

        data-lines   (map-indexed
                       (fn [i row]
                         (let [cells (mapv (fn [col w]
                                            (let [v    (get row (:key col))
                                                  s    (pad (cell-str col v) w (align col))
                                                  code (when (and use-color? (:color-fn col))
                                                         (some-> ((:color-fn col) v)
                                                                 color/codes))]
                                              (if code (colorize code s) s)))
                                          columns widths)
                               line  (str/join "  " cells)]
                           (if (and use-color? zebra? (odd? i))
                             (colorize color/dim line)
                             line)))
                       rows)]
    (str/join "\n" (cons header-line data-lines))))
