(ns isaac.module.git-steps
  (:require
    [gherclj.core :refer [defgiven helper!]]
    [isaac.foundation.git-helpers]))

(helper! isaac.foundation.git-helpers)

(defgiven "a git repository {name:string} with commits:"
  isaac.foundation.git-helpers/repository-with-commits!
  "Creates a real scenario-local git repository under the project working directory.")

(defgiven "the git repository {name:string} gains a commit {msg:string}"
  isaac.foundation.git-helpers/repository-gains-commit!)
