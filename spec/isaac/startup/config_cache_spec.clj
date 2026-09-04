(ns isaac.startup.config-cache-spec
  (:require
    [clojure.string :as str]
    [isaac.config.parse :as parse]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.startup.cache :as cache]
    [isaac.startup.classpath-cache :as classpath-cache]
    [isaac.startup.config-cache :as sut]
    [speclj.core :refer :all]))

(def ^:private root "/v1la/root")
(def ^:private config-path (str root "/config/isaac.edn"))
(def ^:private t0 1000000000000)

(defn- seed-config! [fs* content]
  (fs/mkdirs fs* (str root "/config"))
  (fs/mkdirs fs* (str root "/cache"))
  (fs/spit fs* config-path content))

(defn- write-cache! [fs* config-data]
  (cache/write-cache! fs* root
                      {:version cache/cache-version
                       :basis   (merge {:config      t0
                                        :config-hash (cache/content-hash fs* config-path)}
                                       (classpath-cache/identity-basis config-data))
                       :data    {:classpath-pairs []
                                 :commands        []
                                 :config          (sut/cacheable-config config-data)}}))

(describe "config cache (isaac-v1la)"

  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (context "read-pre-sub"

    (it "returns nil when the cache is missing"
      (should-be-nil (sut/read-pre-sub (nexus/get :fs) root)))

    (it "returns the cached pre-substitution config when the cache is warm"
      (let [fs*    (nexus/get :fs)
            config {:defaults {:crew "main"}}]
        (seed-config! fs* "{:defaults {:crew \"main\"}}")
        (write-cache! fs* config)
        (with-redefs [fs/modified (fn [_ path]
                                    (cond
                                      (str/ends-with? path "cli.edn") t0
                                      (str/includes? path "isaac.edn") t0
                                      :else nil))]
          (let [hit (sut/read-pre-sub fs* root)]
            (should= "main" (get-in hit [:config :defaults :crew]))))))

    (it "returns nil when a watched config file is newer than the cache"
      (let [fs*    (nexus/get :fs)
            config {:defaults {:crew "main"}}]
        (seed-config! fs* "{:defaults {:crew \"main\"}}")
        (write-cache! fs* config)
        (with-redefs [fs/modified (fn [_ path]
                                    (cond
                                      (str/ends-with? path "cli.edn") t0
                                      (str/includes? path "isaac.edn") (inc t0)
                                      :else nil))]
          (should-be-nil (sut/read-pre-sub fs* root)))))

    (it "returns nil when the cached config blob is not a map"
      (let [fs* (nexus/get :fs)]
        (seed-config! fs* "{}")
        (cache/write-cache! fs* root
                            {:version cache/cache-version
                             :basis   {:config t0}
                             :data    {:classpath-pairs []
                                       :commands        []
                                       :config          "corrupt"}})
        (with-redefs [fs/modified (constantly t0)]
          (should-be-nil (sut/read-pre-sub fs* root)))))

    (it "returns nil when the cached config blob is empty (fail-open)"
      (let [fs* (nexus/get :fs)]
        (seed-config! fs* "{:defaults {:crew \"main\"}}")
        (cache/write-cache! fs* root
                            {:version cache/cache-version
                             :basis   {:config t0}
                             :data    {:classpath-pairs []
                                       :commands        []
                                       :config          {}}})
        (with-redefs [fs/modified (constantly t0)]
          (should-be-nil (sut/read-pre-sub fs* root)))))
    )

  (context "hydrate"

    (it "re-applies env substitution on a warm read"
      (let [cached {:config {:token "${V1LA_TOKEN}"} :errors [] :warnings [] :sources []}
            opts   {:root root :substitute-env? true}]
        (with-redefs [parse/substitute-env-recursive
                      (fn [value]
                        (if (= value {:token "${V1LA_TOKEN}"})
                          {:token "from-env"}
                          value))]
          (should= "from-env" (get-in (sut/hydrate cached opts) [:config :token])))))

    (it "leaves placeholders intact when substitute-env? is false"
      (let [cached {:config {:token "${V1LA_TOKEN}"} :errors [] :warnings [] :sources []}
            opts   {:root root :substitute-env? false}]
        (should= "${V1LA_TOKEN}" (get-in (sut/hydrate cached opts) [:config :token]))))
    )

  (context "write payload never contains substituted secrets"

    (it "keeps ${VAR} placeholders in the cache file"
      (let [fs*    (nexus/get :fs)
            config {:foundries {"helm" {:api-key "${HELM_API_KEY}"}}}]
        (seed-config! fs* (pr-str config))
        (write-cache! fs* config)
        (let [raw (fs/slurp fs* (cache/cache-path root))]
          (should-not (str/includes? raw "sk-super-secret"))
          (should (str/includes? raw "${HELM_API_KEY}")))))
    )

  (context "cacheable-config"

    (it "strips process-local keys so they never land in the cache file"
      (should= {:defaults {:crew "main"}}
               (sut/cacheable-config {:defaults     {:crew "main"}
                                      :module-index {:isaac.foundation {}}
                                      :root         root})))
    )
  )
