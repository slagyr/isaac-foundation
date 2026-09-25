(ns isaac.modules.pins-helpers
  (:require
    [babashka.process :as process]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.gitlibs :as gitlibs]
    [clojure.tools.gitlibs.impl :as git-impl]
    [gherclj.core :as g]))

(defn seed-stale-cache! [url]
  (let [source (str (System/getProperty "user.dir") "/" url)
        cache  (git-impl/git-dir source)]
    (when-not (.exists cache)
      (let [result (process/shell {:out :string :err :string :continue true}
                                  "git" "clone" "--quiet" "--mirror" source (.getPath cache))]
        (when-not (zero? (:exit result))
          (throw (ex-info "Failed to seed gitlibs cache" result)))))
    (let [result (process/shell {:out :string :err :string :continue true}
                                "git" "--git-dir" (.getPath cache) "remote" "set-url" "origin"
                                (str source "-deleted"))]
      (when-not (zero? (:exit result))
        (throw (ex-info "Failed to poison gitlibs remote" result))))))

(defn cache-under-checkout [url dir]
  (let [root     (.getCanonicalPath (io/file (System/getProperty "user.dir") dir))
        cache    (.getCanonicalPath (git-impl/git-dir (str (System/getProperty "user.dir") "/" url)))
        remote   (process/shell {:out :string :err :string :continue true}
                                "git" "--git-dir" cache "remote" "get-url" "origin")]
    (g/should (str/starts-with? (gitlibs/cache-dir) (str root "/")))
    (g/should (str/starts-with? cache (str root "/")))
    (g/should= 0 (:exit remote))
    (g/should= (str (System/getProperty "user.dir") "/" url) (str/trim (:out remote)))))
