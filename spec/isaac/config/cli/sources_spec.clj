(ns isaac.config.cli.sources-spec
  (:require
     [c3kit.apron.env :as c3env]
     [isaac.config.cli.command :as sut]
     [isaac.config.cli.spec-support :as support]
     [isaac.config.marigold :as config-marigold]
     [isaac.fs :as fs]
     [isaac.marigold :as marigold]
     [isaac.nexus :as nexus]
     [speclj.core :refer :all]))

(def ^:private test-home "/test/config-sources")
(def ^:private test-root (str test-home "/.isaac"))

(defn- write-config! [path data]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path (pr-str data))))

(describe "CLI Config sources"

  (config-marigold/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (support/with-cli-env #(do (reset! c3env/-overrides {})
                               (example))))

  (it "lists root resolution precedence and contributing config files"
    (write-config! (str test-root "/config/isaac.edn") {:berths {(keyword marigold/captain) {}}})
    (write-config! (str test-root "/config/berths/" marigold/first-mate ".edn")
                   {:gauge (keyword marigold/helm-mark-iii)})
    (should= 0 (sut/run {:root test-root} ["sources"]))
    (should-contain "--root" (str *out*))
    (should-contain "ISAAC_ROOT" (str *out*))
    (should-contain "~/.config/isaac.edn" (str *out*))
    (should-contain "config/isaac.edn" (str *out*))
    (should-contain (str "config/berths/" marigold/first-mate ".edn") (str *out*))))