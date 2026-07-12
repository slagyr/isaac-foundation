(ns isaac.startup.classpath-cache-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.startup.cache :as cache]
    [isaac.startup.classpath-cache :as sut]
    [speclj.core :refer :all]))

(defn- write-v2-cache! [fs* root basis data]
  (cache/write-cache! fs* root
                      {:version cache/cache-version
                       :basis   basis
                       :data    data}))

(describe "classpath cache (isaac-tki3)"

  (describe "identity basis"

    (it "records foundation version and normalized module coords from config"
      (let [config {:modules {"my-mod" {:git/sha "abc123def456"
                                        :git/url "https://example.com/x.git"}}}
            basis  (sut/identity-basis config)]
        (should (contains? basis :foundation))
        (should= {"my-mod" {:git/sha "abc123def456"}} (:module-coords basis))))

    (it "treats identity-fresh? false when module SHA pin changes"
      (let [config-a {:modules {"m" {:git/sha "aaa"}}}
            config-b {:modules {"m" {:git/sha "bbb"}}}
            cached   {:basis (sut/identity-basis config-a)}]
        (should (sut/identity-fresh? cached config-a))
        (should-not (sut/identity-fresh? cached config-b)))))

  (describe "compose-with-cache!"

    (around [example]
      (nexus/-with-nexus {:fs (fs/mem-fs)}
        (example)))

    (it "skips plan-module-classpath-pairs on a warm timestamp-fresh cache hit"
      (let [fs*    (nexus/get :fs)
            root   "/tki3/warm"
            config {:modules {}}
            watched {:config [(str root "/config/isaac.edn")]}
            pairs  [[:cached-mod {:local/root "/tmp/mod"}]]
            t0     1000000000000]
        (fs/mkdirs fs* (str root "/cache"))
        (fs/mkdirs fs* (str root "/config"))
        (fs/spit fs* (str root "/config/isaac.edn") "{}")
        (write-v2-cache! fs* root
                         (merge {:config t0} (sut/identity-basis config))
                         {:classpath-pairs pairs :commands []})
        (with-redefs [fs/modified (fn [_ path]
                                    (cond
                                      (= path (str root "/cache/cli.edn")) t0
                                      (= path (str root "/config/isaac.edn")) t0
                                      :else nil))
                      module-loader/plan-module-classpath-pairs
                      (fn [& _] (throw (ex-info "plan should not run" {})))
                      module-loader/compose-config-modules!
                      (fn [& _] (throw (ex-info "compose should not run" {})))
                      module-loader/apply-module-classpath-pairs!
                      (fn [p] (should= pairs p))]
          (let [result (sut/compose-with-cache! fs* root config "/cwd" watched)]
            (should (:from-cache? result))
            (should= pairs (:pairs result))))))

    (it "fail-opens to full replan when cached apply throws"
      (let [fs*     (nexus/get :fs)
            root    "/tki3/failopen"
            config  {:modules {"m" {:local/root "/mod"}}}
            watched {:config [(str root "/config/isaac.edn")]}
            t0      2000000000000
            planned (atom nil)]
        (fs/mkdirs fs* (str root "/cache"))
        (fs/mkdirs fs* (str root "/config"))
        (fs/spit fs* (str root "/config/isaac.edn") (pr-str {:modules {"m" {:local/root "/mod"}}}))
        (write-v2-cache! fs* root
                         (merge {:config t0} (sut/identity-basis config))
                         {:classpath-pairs [[:m {:local/root "/stale"}]]
                          :commands        []})
        (with-redefs [fs/modified (fn [_ path]
                                    (cond
                                      (str/ends-with? path "cli.edn") t0
                                      (str/includes? path "isaac.edn") t0
                                      :else nil))
                      module-loader/apply-module-classpath-pairs!
                      (fn [_] (throw (ex-info "missing artifact" {})))
                      module-loader/compose-config-modules! (fn [& _] nil)
                      module-loader/plan-module-classpath-pairs
                      (fn [_ _] (reset! planned :ran) [[:m {:local/root "/mod"}]])]
          (let [result (sut/compose-with-cache! fs* root config "/cwd" watched)]
            (should= :ran @planned)
            (should-not (:from-cache? result))
            (should= [[:m {:local/root "/mod"}]] (:pairs result))))))

    (it "records phase timing samples when *timing-samples* is bound"
      (let [fs*     (nexus/get :fs)
            root    "/tki3/timing"
            config  {}
            watched {:config [(str root "/config/isaac.edn")]}
            samples (atom [])]
        (fs/mkdirs fs* (str root "/config"))
        (fs/spit fs* (str root "/config/isaac.edn") "{}")
        (binding [sut/*timing-samples* samples]
          (with-redefs [module-loader/compose-config-modules! (fn [& _] nil)
                        module-loader/plan-module-classpath-pairs (constantly [])]
            (sut/compose-with-cache! fs* root config "/cwd" watched)))
        (should (pos? (count @samples)))
        (should (some #(= :cold-plan (:phase %)) @samples))))))
