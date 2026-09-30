(ns isaac.foundation.config.cli.spec-support
  (:require
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus])
  (:import (java.io BufferedReader StringReader StringWriter)))

(defn with-cli-env [f]
  (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
    (binding [*out*  (StringWriter.)
              *err*  (StringWriter.)
              *in*   (BufferedReader. (StringReader. ""))]
      (f))))
