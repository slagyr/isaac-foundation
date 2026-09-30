(ns isaac.foundation.runner-watch-spec
  (:require
    [isaac.foundation.config.watch :as watch]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.runner :as runner]
    [speclj.core :refer :all]))

(describe "the runner owns the watcher (isaac-1pi2)"
  (it "starts config watching during boot"
    (let [started (atom nil)]
      (with-redefs [watch/start! (fn [opts] (reset! started opts) {:source ::s})
                    watch/stop!  (constantly nil)]
        (try
          (runner/start! {:config {:hot-reload true} :root "/test/root" :fs (fs/mem-fs)
                          :module-index {}})
          (finally (runner/stop!)))
        (should-not-be-nil @started)
        (should= "/test/root" (:root @started))))))
