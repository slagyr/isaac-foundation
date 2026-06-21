(ns isaac.module.modules-deps-steps
  "Steps for the `isaac modules deps` JVM-launch emitter. The boot step proves
   the documented launch pipe end to end: shell the packaged launcher to emit
   the -Sdeps map, then boot a fresh JVM with
   `clojure -Sdeps \"<map>\" -M -m isaac.main <args>` — exactly the command a
   `server --runtime jvm` trampoline runs."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defwhen helper!]]
    [babashka.process :as process]))

(helper! isaac.module.modules-deps-steps)

(defn- shell-capture [argv]
  (apply process/shell
         {:dir      (System/getProperty "user.dir")
          :out      :string
          :err      :string
          :continue true}
         argv))

(defn boot-via-launch-deps [args]
  (let [root-dir (g/get :root)
        script   (str (System/getProperty "user.dir") "/libexec/isaac")
        emit     (shell-capture (into [script]
                                      (concat (when root-dir ["--root" root-dir])
                                              ["modules" "deps" "--edn"])))
        deps-edn (str/trim (or (:out emit) ""))
        main-args (remove str/blank? (str/split (str/trim args) #"\s+"))
        boot     (shell-capture (concat ["clojure" "-Sdeps" deps-edn "-M" "-m"] main-args))]
    (g/assoc! :output (or (:out boot) ""))
    (g/assoc! :stderr (str (:err emit) (:err boot)))
    (g/assoc! :exit-code (long (:exit boot)))))

(defwhen "the emitted launch deps boot {args:string}" isaac.module.modules-deps-steps/boot-via-launch-deps
  "Shells the packaged launcher for `modules deps --edn`, then boots a fresh JVM
   with `clojure -Sdeps \"<emitted map>\" -M -m <args>`. Captures the JVM's
   :output / :exit-code so a scenario can assert isaac actually launches from the
   generated deps with no materialized deps.edn.")