(ns isaac.config.process-memo-spec
  (:require
    [isaac.config.api :as api]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.main :as main]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "CLI config is resolved once per run (isaac-v1la)"

  (around [example]
    (binding [*out* (java.io.StringWriter.)]
      (nexus/-with-nexus {:fs (fs/mem-fs)}
        (example))))

  (it "run invokes load-config-result once for --version"
    (let [calls (atom 0)]
      (with-redefs [loader/load-config-result
                    (fn [opts]
                      (swap! calls inc)
                      {:config          {:root (:root opts)}
                       :errors          []
                       :missing-config? true
                       :warnings        []
                       :sources         []})]
        (binding [main/*extra-opts* {:fs (nexus/get :fs) :root "/v1la"}]
          (should= 0 (main/run ["--version"])))
        (should= 1 @calls))))

  (it "run reuses the extra-opts load-result instead of loading again"
    (let [calls  (atom 0)
          result {:config {:defaults {:crew "main"}} :errors [] :warnings [] :sources []}]
      (with-redefs [loader/load-config-result
                    (fn [_]
                      (swap! calls inc)
                      result)
                    api/load-resolved
                    (fn [opts] (loader/load-config-result opts))]
        (binding [main/*extra-opts* {:fs          (nexus/get :fs)
                                     :root        "/v1la"
                                     :load-result result
                                     :config      (:config result)}]
          (should= 0 (main/run ["--version"])))
        (should= 0 @calls))))
  )
