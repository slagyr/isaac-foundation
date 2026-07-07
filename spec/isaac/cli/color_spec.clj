(ns isaac.cli.color-spec
  (:require
    [isaac.cli.color :as sut]
    [speclj.core :refer :all]))

(describe "cli color detection"

  (it "treats FORCE_COLOR=1 as color-capable even without a console"
    (with-redefs [sut/env (fn [name] (when (= "FORCE_COLOR" name) "1"))]
      (should (sut/tty?))))

  (it "treats CLICOLOR_FORCE=1 as color-capable even without a console"
    (with-redefs [sut/env (fn [name] (when (= "CLICOLOR_FORCE" name) "1"))]
      (should (sut/tty?))))

  (it "treats NO_COLOR as disabling auto color even when a console is present"
    (with-redefs [sut/env (fn [name] (when (= "NO_COLOR" name) "1"))]
      (should-not (sut/tty?)))))
