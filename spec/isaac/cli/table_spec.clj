(ns isaac.cli.table-spec
  (:require
    [clojure.string :as str]
    [isaac.cli.color :as color]
    [isaac.cli.table :as sut]
    [speclj.core :refer :all]))

(def ^:private simple-cols
  [{:key :name  :header "NAME"  :align :left}
   {:key :count :header "COUNT" :align :right}])

(describe "Table renderer"

  (it "renders headers and rows aligned to column widths"
    (let [output (sut/render {:columns simple-cols
                              :rows    [{:name "alice" :count 10}
                                        {:name "bob"   :count 200}]
                              :color?  false})]
      ; NAME width = max(4, 5) = 5; COUNT width = max(5, 3) = 5
      ; header: "NAME   COUNT"  (NAME=5 left, sep=2, COUNT=5 right)
      ; row 1:  "alice     10"
      ; row 2:  "bob      200"
      (should= "NAME   COUNT" (first (str/split-lines output)))
      (should= "alice     10" (second (str/split-lines output)))
      (should= "bob      200" (nth (str/split-lines output) 2))))

  (it "comma-formats numbers via a :format fn"
    (let [output (sut/render {:columns [{:key :n :header "N" :align :right
                                          :format #(format "%,d" %)}]
                              :rows    [{:n 5000} {:n 1000000}]
                              :color?  false})]
      ; N width = max(1, 5, 9) = 9
      (should= "        N" (first (str/split-lines output)))
      (should= "    5,000" (second (str/split-lines output)))
      (should= "1,000,000" (nth (str/split-lines output) 2))))

  (it "applies bold to the header row when :color? is true"
    (let [output (sut/render {:columns simple-cols
                              :rows    [{:name "x" :count 1}]
                              :color?  true})]
      (should (str/starts-with? output color/bold))
      (should (str/includes? (first (str/split-lines output)) color/reset))))

  (it "dims every other row when :zebra? is true"
    (let [output (sut/render {:columns simple-cols
                              :rows    [{:name "a" :count 1}
                                        {:name "b" :count 2}
                                        {:name "c" :count 3}]
                              :zebra?  true
                              :color?  true})
          lines  (str/split-lines output)]
      ; row 0 (a) → no dim; row 1 (b) → dim; row 2 (c) → no dim
      (should-not (str/includes? (nth lines 1) color/dim))
      (should     (str/starts-with? (nth lines 2) color/dim))
      (should-not (str/includes? (nth lines 3) color/dim))))

  (it "emits no ANSI escapes when :color? is false"
    (let [output (sut/render {:columns simple-cols
                              :rows    [{:name "x" :count 1}]
                              :zebra?  true
                              :color?  false})]
      (should-not (str/includes? output ""))))

  (it "applies :color-fn result to a cell"
    (let [cols   [{:key :v :header "V" :align :right
                   :format str
                   :color-fn (fn [v] (when (> v 5) :red))}]
          output (sut/render {:columns cols
                              :rows    [{:v 3} {:v 9}]
                              :color?  true})
          lines  (str/split-lines output)]
      (should-not (str/includes? (nth lines 1) color/red))
      (should     (str/includes? (nth lines 2) color/red))))

  (it "prints just the header row when there are no data rows"
    (let [output (sut/render {:columns simple-cols
                              :rows    []
                              :color?  false})]
      (should= 1 (count (str/split-lines output)))
      (should (str/includes? output "NAME"))))

  (it "auto-detects color from TTY when :color? is not set"
    (with-redefs [color/tty? (fn [] true)
                  color/env  (fn [_] nil)]
      (let [output (sut/render {:columns simple-cols
                                :rows    [{:name "x" :count 1}]})]
        (should (str/includes? output ""))))
    (with-redefs [color/tty? (fn [] false)]
      (let [output (sut/render {:columns simple-cols
                                :rows    [{:name "x" :count 1}]})]
        (should-not (str/includes? output "")))))

  (it "respects NO_COLOR env var in auto-detect mode"
    (with-redefs [color/env (fn [_] "1")]
      (let [output (sut/render {:columns simple-cols
                                :rows    [{:name "x" :count 1}]})]
        (should-not (str/includes? output ""))))))
