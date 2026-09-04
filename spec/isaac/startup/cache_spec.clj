(ns isaac.startup.cache-spec
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.startup.cache :as sut]
    [speclj.core :refer :all]))

(def ^:private root "/v1la/fresh")
(def ^:private cfg (str root "/config/isaac.edn"))
(def ^:private cache-p (str root "/cache/cli.edn"))
(def ^:private t0 1000000000000)
(def ^:private watched {:config [cfg]})

(defn- seed-and-cache! [fs* content]
  (fs/mkdirs fs* (str root "/config"))
  (fs/mkdirs fs* (str root "/cache"))
  (fs/spit fs* cfg content)
  (sut/write-cache! fs* root
                    {:version sut/cache-version
                     :basis   (sut/compute-basis fs* watched)
                     :data    {:classpath-pairs [] :commands []}}))

(describe "startup cache freshness (isaac-v1la)"

  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (context "same-tick config rewrite (real-fs millisecond tie)"

    (it "treats equal mtime as stale when the root config content changed"
      (let [fs* (nexus/get :fs)]
        (seed-and-cache! fs* "{:defaults {:crew \"main\"}}")
        (fs/spit fs* cfg "{:defaults {:crew \"marvin\"}}")
        (with-redefs [fs/modified (fn [_ path]
                                    (when (or (= path cache-p) (= path cfg)) t0))]
          (should-not (sut/fresh? fs* root watched)))))

    (it "treats equal mtime as fresh when the root config content is unchanged"
      (let [fs* (nexus/get :fs)]
        (seed-and-cache! fs* "{:defaults {:crew \"main\"}}")
        (with-redefs [fs/modified (fn [_ path]
                                    (when (or (= path cache-p) (= path cfg)) t0))]
          (should (sut/fresh? fs* root watched)))))

    (it "treats equal mtime as stale when the cache has no config-hash witness"
      (let [fs* (nexus/get :fs)]
        (fs/mkdirs fs* (str root "/config"))
        (fs/mkdirs fs* (str root "/cache"))
        (fs/spit fs* cfg "{:defaults {:crew \"main\"}}")
        (sut/write-cache! fs* root
                          {:version sut/cache-version
                           :basis   {:config t0}
                           :data    {:classpath-pairs [] :commands []}})
        (with-redefs [fs/modified (fn [_ path]
                                    (when (or (= path cache-p) (= path cfg)) t0))]
          (should-not (sut/fresh? fs* root watched)))))
    )

  (it "compute-basis records a config-hash of the root config file"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* (str root "/config"))
      (fs/spit fs* cfg "{:defaults {:crew \"main\"}}")
      (let [basis (sut/compute-basis fs* watched)]
        (should= (sut/content-hash fs* cfg) (:config-hash basis)))))
  )
