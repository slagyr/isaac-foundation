(ns isaac.version-spec
  (:require
    [clojure.string :as str]
    [isaac.version :as sut]
    [speclj.core :refer :all]))

(describe "isaac.version"

  (it "version-string starts with 'isaac '"
    (should (str/starts-with? (sut/version-string) "isaac ")))

  (it "version-string contains the manifest version pattern"
    (should (re-find #"^isaac \d+\.\d+\.\d+" (sut/version-string))))

  (describe "read-git-sha"

    (it "returns nil when .git/HEAD is absent"
      (with-redefs [slurp (fn [_] (throw (java.io.FileNotFoundException. ".git/HEAD")))]
        (should-be-nil (sut/read-git-sha))))

    (it "returns 7-char SHA for a symbolic ref"
      (with-redefs [slurp (fn [path]
                            (cond
                              (str/ends-with? path "HEAD") "ref: refs/heads/main\n"
                              (str/ends-with? path "main") "abc1234def567890\n"
                              :else (throw (java.io.FileNotFoundException. path))))]
        (should= "abc1234" (sut/read-git-sha))))

    (it "returns 7-char SHA for a detached HEAD"
      (with-redefs [slurp (fn [path]
                            (if (str/ends-with? path "HEAD")
                              "abc1234def567890\n"
                              (throw (java.io.FileNotFoundException. path))))]
        (should= "abc1234" (sut/read-git-sha))))

    (it "returns nil when ref file is missing"
      (with-redefs [slurp (fn [path]
                            (if (str/ends-with? path "HEAD")
                              "ref: refs/heads/main\n"
                              (throw (java.io.FileNotFoundException. path))))]
        (should-be-nil (sut/read-git-sha)))))

  (describe "version-string with mocked SHA"

    (it "includes SHA in parens when git is available"
      (with-redefs [sut/read-git-sha (constantly "abc1234")]
        (should (str/includes? (sut/version-string) "(abc1234)"))))

    (it "omits parens when no git SHA"
      (with-redefs [sut/read-git-sha (constantly nil)]
        (should-not (str/includes? (sut/version-string) "("))))))
