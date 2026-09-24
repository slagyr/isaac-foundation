(ns isaac.config.cli.get-spec
  (:require
     [cheshire.core :as json]
     [c3kit.apron.env :as c3env]
     [clojure.edn :as edn]
     [clojure.string :as str]
     [isaac.config.cli.common :as common]
     [isaac.config.loader :as loader]
     [isaac.config.cli.command :as sut]
     [isaac.config.cli.spec-support :as support]
     [isaac.config.marigold :as config-marigold]
     [isaac.fs :as fs]
     [isaac.marigold :as marigold]
     [isaac.nexus :as nexus]
     [speclj.core :refer :all])
  (:import (java.io BufferedReader StringReader)))

(def ^:private test-home "/test/config-get")
(def ^:private test-root (str test-home "/.isaac"))
(def ^:private test-foundry (keyword marigold/helm-systems))
(def ^:private test-gauge (keyword marigold/helm-mark-iii))
(def ^:private test-berth (keyword marigold/first-mate))

(defn- api-key-load-result []
  {:config {:foundries {test-foundry {:api-key "${CONFIG_TEST_API_KEY}"}}}})

(defn- write-config! [path data]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path (pr-str data))))

(defn- write-berth-with-ledger! [berth-id cfg ledger]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (str test-root "/config/berths"))
    (fs/spit fs* (str test-root "/config/berths/" (name berth-id) ".edn") (pr-str cfg))
    (fs/spit fs* (str test-root "/config/berths/" (name berth-id) ".md") ledger)))

(defn- gauge-cfg [foundry reading & {:as overrides}]
  (merge {:reading reading :foundry foundry} overrides))

(describe "CLI Config get"

  (config-marigold/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env #(do (reset! c3env/-overrides {})
                               (example))))

  (describe "whole config"

    (redefs-around [common/load-raw-result (fn [_] (api-key-load-result))])

    (it "prints the resolved config when no path is given, redacting env values"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (should= 0 (sut/run {:root test-root} ["get"]))
      (should-contain "<CONFIG_TEST_API_KEY:redacted>" (str *out*))
      (should-not-contain "sk-test-123" (str *out*)))

    (it "prints raw config without substitution when --raw is set"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (should= 0 (sut/run {:root test-root} ["get" "--raw"]))
      (should-contain "${CONFIG_TEST_API_KEY}" (str *out*))
      (should-not-contain "redacted" (str *out*)))

    (it "reveals actual values only after typed confirmation"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (binding [*in* (BufferedReader. (StringReader. "REVEAL\n"))]
        (should= 0 (sut/run {:root test-root} ["get" "--reveal"])))
      (should-contain "type REVEAL to confirm:" (str *err*))
      (should-contain "sk-test-123" (str *out*)))

    (it "refuses reveal without typed confirmation"
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (should= 1 (sut/run {:root test-root} ["get" "--reveal"]))
      (should-contain "Refusing to reveal config." (str *err*))
      (should-not-contain "sk-test-123" (str *out*))))

  (describe "subtree"

    (it "prints scalar values by dotted path"
      (write-berth-with-ledger! test-berth {} "You are Cordelia.")
      (should= 0 (sut/run {:root test-root} ["get" (str "berths." marigold/first-mate ".ledger")]))
      (should-contain "You are Cordelia." (str *out*)))

    (it "prints scalar values by bracket keyword path"
      (write-berth-with-ledger! test-berth {} "You are Cordelia.")
      (should= 0 (sut/run {:root test-root} ["get" (str "berths[:" marigold/first-mate "].ledger")]))
      (should-contain "You are Cordelia." (str *out*)))

    (it "returns 1 for a missing key"
      (write-berth-with-ledger! test-berth {} "You are Cordelia.")
      (should= 1 (sut/run {:root test-root} ["get" (str "berths." marigold/first-mate ".nope")]))
      (should-contain (str "not found: berths." marigold/first-mate ".nope") (str *err*)))

    (it "prints nested map values via pretty (inline when they fit)"
      (write-config! (str test-root "/config/isaac.edn")
                     {:watch  {:berth (keyword marigold/captain) :gauge test-gauge}
                      :berths {(keyword marigold/captain) {}
                               test-berth (config-marigold/berth-cfg marigold/first-mate :gauge marigold/helm-mark-iii)}
                      :gauges {test-gauge (gauge-cfg test-foundry "helm-mk-3-1.0")}
                      :foundries {test-foundry {}}})
      (should= 0 (sut/run {:root test-root} ["get" (str "berths." marigold/first-mate)]))
      (let [out (str/trim (str *out*))]
        (should-contain ":gauge" out)
        (should-contain ":ledger" out)))

    (it "renders map values via isaac.util.edn/pretty block form (isaac-524u)"
      (write-config! (str test-root "/config/isaac.edn")
                     {:foundries {(keyword marigold/quantum-anvil)
                                  {:api      "responses"
                                   :base-url "https://api.x.ai/v1"
                                   :auth     "api-key"
                                   :api-key  "${XAI_API_KEY}"}}})
      (should= 0 (sut/run {:root test-root} ["get" (str "foundries." marigold/quantum-anvil)]))
      (let [out (str/trim (str *out*))]
        (should-contain "{\n" out)
        (should-contain ":api" out)
        (should-contain ":base-url" out)
        (should (str/includes? out "  :"))))

    (it "prints foundry auth when configured"
      (write-config! (str test-root "/config/isaac.edn")
                     {:foundries {(keyword marigold/quantum-anvil) {:auth "oauth-device"}}})
      (should= 0 (sut/run {:root test-root} ["get" (str "foundries." marigold/quantum-anvil ".auth")]))
      (should-contain "oauth-device" (str *out*)))

    (it "reveals get values after typed confirmation and prompts first"
      (write-config! (str test-root "/config/isaac.edn")
                     {:foundries {test-foundry {:api-key "${CONFIG_TEST_API_KEY}"}}})
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (binding [*in* (BufferedReader. (StringReader. "REVEAL\n"))]
        (should= 0 (sut/run {:root test-root} ["get" (str "foundries." marigold/helm-systems ".api-key") "--reveal"])))
      (should-contain "type REVEAL to confirm:" (str *err*))
      (should-contain "sk-test-123" (str *out*))))

  (describe "structured output (isaac-0jse)"

    (it "get --json emits parseable JSON for a map subtree"
      (write-berth-with-ledger! test-berth {} "You are Cordelia.")
      (should= 0 (sut/run {:root test-root} ["get" (str "berths." marigold/first-mate) "--json"]))
      (let [parsed (json/parse-string (str/trim (str *out*)))]
        (should (map? parsed))))

    (it "get --edn round-trips the subtree"
      (write-berth-with-ledger! test-berth {} "You are Cordelia.")
      (should= 0 (sut/run {:root test-root} ["get" (str "berths." marigold/first-mate) "--edn"]))
      (let [parsed (edn/read-string (str/trim (str *out*)))]
        (should (map? parsed))))

    (it "rejects unknown flags cleanly"
      (should= 1 (sut/run {:root test-root} ["get" "models" "--nope"]))
      (should-contain "Unknown option" (str *err*))))

  (describe "agrees with validate on the same tree (isaac-63ei)"

    ;; The CLI resolves config once and threads the load result into every
    ;; subcommand (isaac-v1la). Only `get` then re-reads the contributing source
    ;; files to redact ${VAR} secrets — and since isaac-49zp a source may be a
    ;; directory (`config/berths/<id>/`), which is not slurpable. `validate`
    ;; never re-reads, so it certified a tree `get` could not read. Every example
    ;; here threads opts exactly as isaac.main does; without that the divergent
    ;; branch is never taken, which is why the suite could not see the bug.

    (defn- threaded-opts []
      (let [result (loader/load-config-result {:root test-root :fs (nexus/get :fs)})]
        {:root test-root :config (:config result) :load-result result}))

    (defn- write-tree! []
      (write-config! (str test-root "/config/isaac.edn")
                     {:watch     {:berth test-berth :gauge :llama}
                      :gauges    {:llama {:reading "llama3.3:1b" :foundry test-foundry}}
                      :foundries {test-foundry {}}})
      (write-config! (str test-root "/config/berths/" marigold/first-mate "/_.edn")
                     {:ledger "You keep the log."}))

    (it "reads an entity stored as a directory, which validate already accepts"
      (write-tree!)
      (let [opts (threaded-opts)]
        (should= 0 (sut/run opts ["validate"]))
        (should= 0 (sut/run opts ["get"]))
        (should-contain "You keep the log." (str *out*))))

    (it "reads a subtree path off such a tree"
      (write-tree!)
      (should= 0 (sut/run (threaded-opts) ["get" (str "berths." marigold/first-mate ".ledger")]))
      (should-contain "You keep the log." (str *out*)))

    (it "still redacts a secret held in a file inside an entity directory"
      (write-config! (str test-root "/config/isaac.edn") {})
      (fs/spit (nexus/get :fs)
               (str test-root "/config/foundries/" marigold/helm-systems "/_.edn")
               (pr-str {:api-key "${CONFIG_TEST_API_KEY}"}))
      (c3env/override! "CONFIG_TEST_API_KEY" "sk-test-123")
      (should= 0 (sut/run (threaded-opts) ["get"]))
      (should-contain "<CONFIG_TEST_API_KEY:redacted>" (str *out*))
      (should-not-contain "sk-test-123" (str *out*)))))