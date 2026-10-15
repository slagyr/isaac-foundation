(ns isaac.foundation.module.modules-deps-steps
  "Steps for the `isaac modules deps` JVM-launch emitter. The boot step proves
   the documented launch pipe end to end: shell the packaged launcher to emit
   the -Sdeps map, then boot a fresh JVM with
   `clojure -Sdeps \"<map>\" -M -m isaac.foundation.main <args>` — exactly the command a
   `server --runtime jvm` trampoline runs."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defwhen helper!]]
    [babashka.process :as process]))

(helper! isaac.foundation.module.modules-deps-steps)

(defn- shell-capture [dir argv]
  (apply process/shell
         {:dir      dir
          :out      :string
          :err      :string
          :continue true}
         argv))

(defn- boot! [args boot-dir]
  (let [root-dir  (g/get :root)
        checkout  (System/getProperty "user.dir")
        script    (str checkout "/libexec/isaac")
        emit      (shell-capture checkout (into [script]
                                               (concat (when root-dir ["--root" root-dir])
                                                       ["modules" "deps" "--edn"])))
        deps-edn  (str/trim (or (:out emit) ""))
        main-args (remove str/blank? (str/split (str/trim args) #"\s+"))
        boot      (shell-capture boot-dir (concat ["clojure" "-Sdeps" deps-edn "-M" "-m"] main-args))]
    (g/assoc! :output (or (:out boot) ""))
    (g/assoc! :stderr (str (:err emit) (:err boot)))
    (g/assoc! :exit-code (long (:exit boot)))))

(defn boot-via-launch-deps [args]
  (boot! args (System/getProperty "user.dir")))

(defn boot-from-isaac-root [args]
  (boot! args (g/get :root)))

(defwhen "a JVM in the Isaac root boots {args:string} from the emitted launch deps"
  isaac.foundation.module.modules-deps-steps/boot-from-isaac-root)

(defwhen "the emitted launch deps boot {args:string}" isaac.foundation.module.modules-deps-steps/boot-via-launch-deps
  "Shells the packaged launcher for `modules deps --edn`, then boots a fresh JVM
   with `clojure -Sdeps \"<emitted map>\" -M -m <args>`. Captures the JVM's
   :output / :exit-code so a scenario can assert isaac actually launches from the
   generated deps with no materialized deps.edn.")