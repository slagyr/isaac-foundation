(ns isaac.cli.host-spec
  (:require
    [clojure.string :as str]
    [isaac.cli.host :as sut]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config-api]
    [isaac.module.lifecycle :as lifecycle]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around describe it should should=]]))

(defn- embedded-options [argv]
  {:argv argv
   :in   (java.io.StringReader. "voyage\n")
   :out  (java.io.StringWriter.)
   :err  (java.io.StringWriter.)
   :env  {"TIDE" "high"}
   :cwd  "/srv/isaac"
   :root "/srv/isaac"
   :tty? true})

(describe "isaac.cli.host"
  (around [example]
    (nexus/-with-nested-nexus {:root "/srv/isaac"}
      (example)))

  (it "returns an embedded exit code without running code after exit!"
    (let [after? (atom false)]
      (registry/register! {:name "leave"
                           :run-fn (fn [_]
                                     (sut/exit! 7)
                                     (reset! after? true))})
      (should= 7 (sut/run-embedded (embedded-options ["leave"])))
      (should= false @after?)))

  (it "captures future output and embedded stdin"
    (let [printed (promise)]
      (registry/register! {:name "streams"
                           :run-fn (fn [_]
                                     (let [line (read-line)]
                                       (future
                                         (println (str line " tide"))
                                         (deliver printed true))
                                       0))})
      (let [{:keys [out] :as opts} (embedded-options ["streams"])]
        (should= 0 (sut/run-embedded opts))
        (should= true (deref printed 5000 ::timeout))
        (should (str/includes? (str out) "voyage tide")))))

  (it "supplies cwd, environment, and tty state"
    (let [seen (atom nil)]
      (registry/register! {:name "context"
                           :run-fn (fn [_]
                                     (reset! seen [(sut/cwd) (sut/env "TIDE") (sut/tty?)])
                                     0)})
      (should= 0 (sut/run-embedded (embedded-options ["context"])))
      (should= ["/srv/isaac" "high" true] @seen)))

  (it "leaves process registries and runtime state untouched"
    (let [before-commands  (registry/snapshot)
          before-logger    ((requiring-resolve 'isaac.logger/snapshot))
          before-memo      (config-api/process-memo-snapshot)
          before-nexus     (nexus/necho)
          before-activated (lifecycle/activated-modules)]
      (should= 0 (sut/run-embedded (embedded-options ["--help"])))
      (should= before-commands (registry/snapshot))
      (should= before-logger ((requiring-resolve 'isaac.logger/snapshot)))
      (should= before-memo (config-api/process-memo-snapshot))
      (should= before-nexus (nexus/necho))
      (should= before-activated (lifecycle/activated-modules))))

  (it "refuses local-only commands and mismatched roots"
    (registry/register! {:name "local" :local-only true :run-fn (constantly 0)})
    (let [{local-err :err :as local-opts} (embedded-options ["local"])
          {root-err :err :as root-opts}   (embedded-options ["--root" "/elsewhere" "local"])]
      (should= 2 (sut/run-embedded local-opts))
      (should (str/includes? (str local-err) "run this on the host"))
      (should= 2 (sut/run-embedded root-opts))
      (should (str/includes? (str root-err) "server root"))))

  (it "reports unknown and throwing commands"
    (registry/register! {:name "explode" :run-fn (fn [_] (throw (ex-info "boom" {})))})
    (let [{unknown-err :err :as unknown} (embedded-options ["missing"])
          {throw-err :err :as throwing}  (embedded-options ["explode"])]
      (should= 1 (sut/run-embedded unknown))
      (should (str/includes? (str unknown-err) "Unknown command"))
      (should= 1 (sut/run-embedded throwing))
      (should (str/includes? (str throw-err) "boom"))))

  (it "unblocks cancellation and runs shutdown callbacks once"
    (let [host    (sut/embedded-host {:env {} :cwd "/srv" :tty? false})
          stopped (promise)
          calls   (atom 0)
          registered (promise)
          _blocked   (future
                       (binding [sut/*host* host]
                         (sut/on-shutdown! #(swap! calls inc))
                         (deliver registered true)
                         (sut/block-until-cancelled!)
                         (deliver stopped true)))]
      (deref registered 1000 false)
      (sut/cancel! host)
      (sut/cancel! host)
      (should= true (deref stopped 1000 false))
      (should= 1 @calls)
      (should (sut/cancelled? host))))
  )
