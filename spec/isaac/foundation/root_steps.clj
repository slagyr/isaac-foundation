(ns isaac.foundation.root-steps
  "Foundation-grade test root/state setup: 'an (empty) Isaac root at', 'an
   empty Isaac state directory', and module-manifest fixtures. initialize-root!
   does the foundation-grade reset (gherclj/env/nexus/module-loader/log + fs +
   nexus :fs/:root) and then runs registered root-setup hooks — the server step
   layer registers one that tears down its runtime state (comms, tools, session
   store, ...). This namespace depends only on foundation namespaces and moves
   to the foundation repo at cut time."
  (:require
    [c3kit.apron.env :as c3env]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]))

(helper! isaac.foundation.root-steps)

;; Root-setup hooks run (with abs-dir) at the end of initialize-root!, after
;; the foundation reset and fs/nexus setup. The server step layer registers
;; one that resets its runtime state and installs the session store, so this
;; namespace needn't depend on comm/tool/session/store namespaces.
(defonce ^:private root-setup-hooks* (atom []))

(defn register-root-setup-hook!
  "Register (fn [abs-dir]) to run at the end of initialize-root!. Used by the
   server layer to reset runtime state and install the session store."
  [f]
  (swap! root-setup-hooks* conj f))

;; Cleanup of real (on-disk) module fixture roots written by
;; module-manifest-exists, deleted after each scenario.
(defonce ^:private real-module-roots* (atom #{}))

(g/after-scenario
  (fn []
    (doseq [path @real-module-roots*]
      (let [dir (io/file path)]
        (when (.exists dir)
          (doseq [f (reverse (file-seq dir))]
            (.delete f)))))
    (reset! real-module-roots* #{})))

;; region ----- fs helpers -----

(defn- clean-dir! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (-> dir file-seq reverse butlast)]
        (.delete f)))))

(defn- root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- invalidate-feature-config! []
  (g/dissoc! :feature-config))

(defn- seed-cwd-files! [mem]
  (let [cwd (System/getProperty "user.dir")
        agents-md (str cwd "/AGENTS.md")]
    (when (.exists (io/file agents-md))
      (fs/spit mem agents-md (slurp agents-md)))))

(defn- ->root [dir virtual?]
  (if (str/starts-with? dir "/")
    dir
    (if (or virtual? (not (str/includes? dir "/")))
      (str "/" dir)
      (str (System/getProperty "user.dir") "/" dir))))

(def ^:private minimal-config
  {:defaults  {:crew "main"
               :model "llama"}
   :crew      {"main" {}}
   :models    {"llama" {:model          "llama3.3:1b"
                         :provider       "ollama"
                          :context-window 32768}}
   :providers {"ollama" {:api      "ollama"
                          :base-url "http://localhost:11434"}}})

(defn- seed-minimal-config! [root]
  (let [config-path (str root "/config/isaac.edn")
        fs*         (mem-fs)]
    (fs/mkdirs fs* (fs/parent config-path))
    (fs/spit   fs* config-path (pr-str minimal-config))
    (invalidate-feature-config!)))

;; endregion ^^^^^ fs helpers ^^^^^

;; region ----- Root setup -----

(defn initialize-root! [path virtual?]
  (let [dir (if (and (str/starts-with? path "\"") (str/ends-with? path "\""))
              (subs path 1 (dec (count path)))
              path)
        abs-dir  (->root dir virtual?)
        mem      (when virtual? (fs/mem-fs))]
    (g/reset!)
    (reset! c3env/-overrides {})
    (config/clear-env-overrides!)
    (nexus/reset!)
    (module-loader/clear-activations!)
    (log/set-output! :memory)
    (log/clear-entries!)
    (if virtual?
      (do
        (seed-cwd-files! mem)
        (fs/mkdirs mem abs-dir)
        (g/assoc! :mem-fs mem))
      (do
        (clean-dir! abs-dir)
        (g/dissoc! :mem-fs)))
    (nexus/register! [:fs] (or mem (fs/real-fs)))
    (nexus/register! [:root] abs-dir)
    (g/assoc! :root abs-dir)
    (doseq [hook @root-setup-hooks*] (hook abs-dir))))

(defn empty-state [path]
  (let [dir (if (and (str/starts-with? path "\"") (str/ends-with? path "\""))
              (subs path 1 (dec (count path)))
              path)]
    (initialize-root! path (or (not (or (str/starts-with? dir "/")
                                             (str/includes? dir "/")))
                                    (= "/test" dir)
                                    (str/starts-with? dir "/test/")))))

(defn empty-state-directory
  "Like empty-state but always virtual / mem-fs. Useful when the scenario
   writes module fixtures at sibling paths (e.g. /tmp/modules/...) that
   wouldn't trigger the /test heuristic but still need to live in the
   in-memory fs."
  [path]
  (initialize-root! path true))

(defn in-memory-state [path]
  (initialize-root! path true)
  (with-feature-fs #(seed-minimal-config! (root-dir))))

(defn module-manifest-exists [path content]
  (with-feature-fs
    (fn []
      (let [abs-path (if (str/starts-with? path "/")
                       path
                       (str (System/getProperty "user.dir") "/" path))
            module-root (some-> abs-path io/file .getParentFile .getParentFile .getPath)
            fs*         (mem-fs)]
        (when module-root
          (swap! real-module-roots* conj module-root))
        (fs/mkdirs fs* (fs/parent abs-path))
        (fs/spit   fs* abs-path content)))))

;; endregion ^^^^^ Root setup ^^^^^

;; region ----- Routing -----

(defgiven "an empty Isaac root at {string}" isaac.foundation.root-steps/empty-state
  "Real-fs root when path is absolute or contains '/'; in-memory
   otherwise. Clean slate — deletes any existing content first. No
   config files are seeded. Use 'an Isaac root at' if the scenario
   needs a seeded minimal config.")

(defgiven "an empty Isaac state directory {string}" isaac.foundation.root-steps/empty-state-directory
  "Always virtual / mem-fs at the given path. Same intent as
   'an empty Isaac root at' but bypasses the real-fs heuristic so a
   scenario can put fixtures at arbitrary paths (e.g. /tmp/modules/...)
   without touching disk.")

(defgiven "an Isaac root at {string}" isaac.foundation.root-steps/in-memory-state
  "Virtual fs (mem-fs) rooted at the given path. Seeds a minimal
   isaac.edn at <path>/.isaac/config/isaac.edn so config loaders have
   something to parse. For a bare root without the seed, use
   'an empty Isaac root at'.")

(defgiven #"a module manifest \"([^\"]+)\":$" isaac.foundation.root-steps/module-manifest-exists)

(defgiven #"a module manifest at \"([^\"]+)\":$" isaac.foundation.root-steps/module-manifest-exists)

;; endregion ^^^^^ Routing ^^^^^
