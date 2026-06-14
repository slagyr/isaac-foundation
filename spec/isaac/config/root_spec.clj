(ns isaac.config.root-spec
  (:require
    [isaac.fs :as fs]
    [isaac.config.root :as sut]
    [isaac.logger :as log]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(def ^:dynamic *fs* nil)

(describe "config root"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (binding [*fs*            (fs/mem-fs)
              sut/*root*      nil
              sut/*user-home* "/tmp/user"]
      (sut/init-root! nil)
      (example)
      (sut/init-root! nil)))

  (it "default-root with no args is ~/.isaac"
    (should= "/tmp/user/.isaac" (sut/default-root)))

  (it "default-root with a home string derives <home>/.isaac"
    (should= "/home/me/.isaac" (sut/default-root "/home/me")))

  (it "default-root with opts prefers :root"
    (should= "/explicit" (sut/default-root {:root "/explicit" :home "/ignored"})))

  (it "default-root with opts derives from :home when :root absent"
    (should= "/home/me/.isaac" (sut/default-root {:home "/home/me"})))

  (it "uses the explicit --root before pointer files"
    (fs/mkdirs *fs* "/tmp/user/.config")
    (fs/spit   *fs* "/tmp/user/.config/isaac.edn" "{:root \"/tmp/pointer\"}")
    (should= "/tmp/explicit" (sut/resolve-root "/tmp/explicit" nil *fs*)))

  (it "uses the fallback before pointer files"
    (fs/mkdirs *fs* "/tmp/user/.config")
    (fs/spit   *fs* "/tmp/user/.config/isaac.edn" "{:root \"/tmp/pointer\"}")
    (should= "/tmp/fallback" (sut/resolve-root nil "/tmp/fallback" *fs*)))

  (it "reads :root from ~/.config/isaac.edn"
    (fs/mkdirs *fs* "/tmp/user/.config")
    (fs/spit   *fs* "/tmp/user/.config/isaac.edn" "{:root \"/tmp/pointer\"}")
    (should= "/tmp/pointer" (sut/resolve-root nil nil *fs*)))

  (it "falls back to ~/.isaac.edn when the XDG pointer is absent"
    (fs/spit *fs* "/tmp/user/.isaac.edn" "{:root \"/tmp/fallback-pointer\"}")
    (should= "/tmp/fallback-pointer" (sut/resolve-root nil nil *fs*)))

  (it "expands tildes in pointer values"
    (fs/mkdirs *fs* "/tmp/user/.config")
    (fs/spit   *fs* "/tmp/user/.config/isaac.edn" "{:root \"~/.elsewhere\"}")
    (should= "/tmp/user/.elsewhere" (sut/resolve-root nil nil *fs*)))

  (it "expands relative roots against the current working directory"
    (let [cwd (System/getProperty "user.dir")]
      (should= (str cwd "/target/test-state")
               (sut/resolve-root "target/test-state" nil *fs*))))

  (it "defaults to ~/.isaac when no source provides a root"
    (should= "/tmp/user/.isaac" (sut/resolve-root nil nil *fs*)))

  (it "logs a warning and falls through when a pointer file is malformed"
    (fs/mkdirs *fs* "/tmp/user/.config")
    (fs/spit   *fs* "/tmp/user/.config/isaac.edn" "{:root")
    (should= "/tmp/user/.isaac" (sut/resolve-root nil nil *fs*))
    (should= {:event :root/pointer-file-invalid :path "/tmp/user/.config/isaac.edn"}
             (select-keys (last @log/captured-logs) [:event :path])))

  (describe "current-root"

    (it "defaults to ~/.isaac when no binding or global is set"
      (should= "/tmp/user/.isaac" (sut/current-root)))

    (it "uses *root* binding when present"
      (binding [sut/*root* "/bound/root"]
        (should= "/bound/root" (sut/current-root))))

    (it "uses init-root! value when no per-thread binding"
      (sut/init-root! "/global/root")
      (should= "/global/root" (sut/current-root)))

    (it "binding takes priority over init-root!"
      (sut/init-root! "/global/root")
      (binding [sut/*root* "/bound/root"]
        (should= "/bound/root" (sut/current-root))))))