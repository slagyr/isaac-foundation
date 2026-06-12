(ns isaac.fs-spec
  (:require
    [clojure.java.io :as io]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(def test-path (str (System/getProperty "user.dir") "/target/test-fs-real"))

(def ^:dynamic *fs* nil)

(defn- delete-test-path! []
  (let [root (io/file test-path)]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (.delete file)))))

(defn- test-path* [path]
  (str test-path "/" path))

(describe "path validation"

  (it "spit rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: foo.txt"
      (fs/spit (fs/mem-fs) "foo.txt" "content")))

  (it "slurp rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: foo.txt"
      (fs/slurp (fs/mem-fs) "foo.txt")))

  (it "exists? rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: foo.txt"
      (fs/exists? (fs/mem-fs) "foo.txt")))

  (it "mkdirs rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: foo.txt"
      (fs/mkdirs (fs/mem-fs) "foo.txt")))

  (it "delete rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: foo.txt"
      (fs/delete (fs/mem-fs) "foo.txt")))

  (it "move rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: foo.txt"
      (fs/move (fs/mem-fs) "foo.txt" "/tmp/bar.txt")))

  (it "children rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: dir"
      (fs/children (fs/mem-fs) "dir")))

  (it "file? rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: foo.txt"
      (fs/file? (fs/mem-fs) "foo.txt")))

  (it "dir? rejects relative paths"
    (should-throw IllegalArgumentException "Relative path not allowed: dir"
      (fs/dir? (fs/mem-fs) "dir")))

  (it "rejects tilde paths"
    (should-throw IllegalArgumentException "Relative path not allowed: ~/documents/file.txt"
      (fs/spit (fs/mem-fs) "~/documents/file.txt" "content")))

  (it "allows absolute paths"
    (let [mem (fs/mem-fs)]
      (fs/spit mem "/tmp/test.txt" "ok")
      (should= "ok" (fs/slurp mem "/tmp/test.txt")))))

(describe "memory fs"

  (around [example] (binding [*fs* (fs/mem-fs)] (example)))

  (it "exists? is false for missing paths and true for existing files"
    (should-not (fs/exists? *fs* "/mem/found.txt"))
    (fs/spit *fs* "/mem/found.txt" "yep")
    (should (fs/exists? *fs* "/mem/found.txt")))

  (it "file? is false for missing paths and directories and true for existing files"
    (should-not (fs/file? *fs* "/mem/found.txt"))
    (fs/mkdirs *fs* "/mem/dir")
    (should-not (fs/file? *fs* "/mem/dir"))
    (fs/spit *fs* "/mem/found.txt" "yep")
    (should (fs/file? *fs* "/mem/found.txt")))

  (it "dir? is false for missing paths and files and true for directories"
    (should-not (fs/dir? *fs* "/mem/dir"))
    (fs/spit *fs* "/mem/found.txt" "yep")
    (should-not (fs/dir? *fs* "/mem/found.txt"))
    (fs/mkdirs *fs* "/mem/dir")
    (should (fs/dir? *fs* "/mem/dir")))

  (it "parent returns nil for root"
    (should-be-nil (fs/parent "/")))

  (it "parent returns the lexical parent for directory paths ending with a slash"
    (should= "/mem/dir" (fs/parent "/mem/dir/subdir/")))

  (it "parent returns the lexical parent directory for nested paths"
    (should= "/mem/dir/subdir" (fs/parent "/mem/dir/subdir/found.txt")))

  (it "slurp returns nil for missing files"
    (should-be-nil (fs/slurp *fs* "/mem/missing.txt")))

  (it "slurp ignores the :encoding option"
    (fs/spit *fs* "/mem/found.txt" "yep")
    (should= "yep" (fs/slurp *fs* "/mem/found.txt" :encoding :utf-8)))

  (it "spit ignores the :encoding option"
    (fs/spit *fs* "/mem/found.txt" "yep" :encoding "ISO-8859-1")
    (should= "yep" (fs/slurp *fs* "/mem/found.txt")))

  (it "spit appends when :append is true"
    (fs/spit *fs* "/mem/log.txt" "line1\n")
    (fs/spit *fs* "/mem/log.txt" "line2\n" :append true)
    (should= "line1\nline2\n" (fs/slurp *fs* "/mem/log.txt")))

  (it "children returns nil for missing paths"
    (should-be-nil (fs/children *fs* "/mem/missing")))

  (it "children returns nil for files"
    (fs/spit *fs* "/mem/found.txt" "yep")
    (should-be-nil (fs/children *fs* "/mem/found.txt")))

  (it "children returns sorted child names for directories"
    (fs/spit *fs* "/mem/dir/b.txt" "b")
    (fs/spit *fs* "/mem/dir/a.txt" "a")
    (fs/spit *fs* "/mem/other/c.txt" "c")
    (should= ["a.txt" "b.txt"] (fs/children *fs* "/mem/dir")))

  (it "children includes child directories"
    (fs/mkdirs *fs* "/mem/dir/subdir")
    (fs/spit *fs* "/mem/dir/a.txt" "a")
    (should= ["a.txt" "subdir"] (fs/children *fs* "/mem/dir")))

  (it "mkdirs creates directories"
    (should-be-nil (fs/mkdirs *fs* "/mem/any/path/here"))
    (should (fs/dir? *fs* "/mem/any/path/here")))

  (it "cache-token advances on writes"
    (let [before (fs/cache-token *fs*)]
      (fs/spit *fs* "/mem/log.txt" "line1")
      (should (< before (fs/cache-token *fs*)))
      (let [after-write (fs/cache-token *fs*)]
        (fs/delete *fs* "/mem/log.txt")
        (should (< after-write (fs/cache-token *fs*))))))

  (it "delete removes files"
    (fs/spit *fs* "/mem/gone.txt" "bye")
    (should (fs/exists? *fs* "/mem/gone.txt"))
    (fs/delete *fs* "/mem/gone.txt")
    (should-not (fs/exists? *fs* "/mem/gone.txt")))

  (it "move relocates files"
    (fs/spit *fs* "/mem/old.txt" "bye")
    (fs/move *fs* "/mem/old.txt" "/mem/new.txt")
    (should-not (fs/exists? *fs* "/mem/old.txt"))
    (should= "bye" (fs/slurp *fs* "/mem/new.txt"))))

(describe "real fs"

  (before (delete-test-path!))
  (before (io/make-parents (test-path* "keep")))
  (around [example] (binding [*fs* (fs/real-fs)] (example)))

  (it "exists? is false for missing paths and true for existing files"
    (should-not (fs/exists? *fs* (test-path* "found.txt")))
    (fs/spit *fs* (test-path* "found.txt") "yep")
    (should (fs/exists? *fs* (test-path* "found.txt"))))

  (it "file? is false for missing paths and directories and true for existing files"
    (should-not (fs/file? *fs* (test-path* "found.txt")))
    (fs/mkdirs *fs* (test-path* "dir"))
    (should-not (fs/file? *fs* (test-path* "dir")))
    (fs/spit *fs* (test-path* "found.txt") "yep")
    (should (fs/file? *fs* (test-path* "found.txt"))))

  (it "dir? is false for missing paths and files and true for directories"
    (should-not (fs/dir? *fs* (test-path* "dir")))
    (fs/spit *fs* (test-path* "found.txt") "yep")
    (should-not (fs/dir? *fs* (test-path* "found.txt")))
    (.mkdirs (io/file (test-path* "dir")))
    (should (fs/dir? *fs* (test-path* "dir"))))

  (it "parent returns the lexical parent for single-segment paths under a test root"
    (should= test-path (fs/parent (test-path* "found.txt"))))

  (it "parent returns the lexical parent for directory paths ending with a slash"
    (should= (test-path* "dir")
             (fs/parent (str (test-path* "dir/subdir") "/"))))

  (it "parent returns the lexical parent directory for nested paths"
    (should= (test-path* "dir/subdir")
             (fs/parent (test-path* "dir/subdir/found.txt"))))

  (it "slurp returns nil for missing files"
    (should-be-nil (fs/slurp *fs* (test-path* "missing.txt"))))

  (it "slurp honors the :encoding option"
    (spit (test-path* "latin1.txt") "café" :encoding "ISO-8859-1")
    (should= "café" (fs/slurp *fs* (test-path* "latin1.txt") :encoding "ISO-8859-1")))

  (it "spit honors the :encoding option"
    (fs/spit *fs* (test-path* "latin1.txt") "café" :encoding "ISO-8859-1")
    (should= "café" (clojure.core/slurp (test-path* "latin1.txt") :encoding "ISO-8859-1")))

  (it "spit appends when :append is true"
    (fs/spit *fs* (test-path* "log.txt") "line1\n")
    (fs/spit *fs* (test-path* "log.txt") "line2\n" :append true)
    (should= "line1\nline2\n" (fs/slurp *fs* (test-path* "log.txt"))))


  (it "children returns nil for missing paths"
    (should-be-nil (fs/children *fs* (test-path* "missing"))))

  (it "children returns nil for files"
    (fs/spit *fs* (test-path* "found.txt") "yep")
    (should-be-nil (fs/children *fs* (test-path* "found.txt"))))

  (it "children returns sorted child names for directories"
    (fs/mkdirs *fs* (test-path* "dir"))
    (fs/mkdirs *fs* (test-path* "other"))
    (fs/spit *fs* (test-path* "dir/b.txt") "b")
    (fs/spit *fs* (test-path* "dir/a.txt") "a")
    (fs/spit *fs* (test-path* "other/c.txt") "c")
    (should= ["a.txt" "b.txt"] (fs/children *fs* (test-path* "dir"))))

  (it "children includes child directories"
    (fs/mkdirs *fs* (test-path* "dir/subdir"))
    (fs/spit *fs* (test-path* "dir/a.txt") "a")
    (should= ["a.txt" "subdir"] (fs/children *fs* (test-path* "dir"))))

  (it "mkdirs creates directories"
    (should= true (fs/mkdirs *fs* (test-path* "any/path/here/file.txt")))
    (should (fs/dir? *fs* (test-path* "any/path/here"))))

  (it "delete removes files"
    (fs/spit *fs* (test-path* "gone.txt") "bye")
    (should (fs/exists? *fs* (test-path* "gone.txt")))
    (fs/delete *fs* (test-path* "gone.txt"))
    (should-not (fs/exists? *fs* (test-path* "gone.txt"))))

  (it "move relocates files"
    (fs/spit *fs* (test-path* "old.txt") "bye")
    (fs/move *fs* (test-path* "old.txt") (test-path* "new.txt"))
    (should-not (fs/exists? *fs* (test-path* "old.txt")))
    (should= "bye" (fs/slurp *fs* (test-path* "new.txt")))))
