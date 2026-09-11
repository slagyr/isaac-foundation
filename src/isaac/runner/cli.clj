(ns isaac.runner.cli
  (:require
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.api :as cli-api]
    [isaac.cli.common :as cli-common]
    [isaac.component.protocol :as component]
    [isaac.component.registry :as component-registry]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.log.file :as log-file]
    [isaac.log.output :as log-output]
    [isaac.log-viewer :as viewer]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.runner :as runner]
    [isaac.runner.runtime :as runtime]))

(def option-spec
  [["-p" "--port N" "Port to listen on (default: 6674)"]
   ["-H" "--host H" "Host to bind to (default: 127.0.0.1)"]
   ["-d" "--dev" "Enable development reload mode"]
   [nil "--runtime RUNTIME" "Server runtime: bb (default) or jvm" :default "bb"]
   [nil "--logs" "Tail and print the log file while the server runs"]
   [nil "--no-color" "Disable color output for --logs"]
   [nil "--zebra" "Enable zebra striping for --logs"]
   ["-h" "--help" "Show help"]])

(defonce ^:private shutdown-hook-registered? (atom false))

(defn- register-shutdown-hook! []
  (when (compare-and-set! shutdown-hook-registered? false true)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(runner/stop!) "isaac-runner-shutdown"))))

(defn block! []
  @(promise))

(defn- start-log-tail! [log-path root-dir {:keys [no-color zebra]}]
  (when log-path
    (let [path (if (.isAbsolute (java.io.File. log-path))
                 log-path
                 (str root-dir "/" log-path))]
      (fs/mkdirs (fs/instance) (fs/parent path))
      (when-not (fs/exists? (fs/instance) path)
        (fs/spit (fs/instance) path ""))
      (future (viewer/tail! path {:color?  (not no-color)
                                  :zebra?  (boolean zebra)
                                  :follow? true
                                  :limit   10}))
      path)))

(defn run [{:keys [config host logs port] :as opts}]
  (let [root-dir (root/default-root opts)
        fs*      (or (:fs opts) (nexus/get :fs) (fs/real-fs))
        dev?     (boolean (:dev opts))]
    (log-output/apply-server! root-dir config)
    (when logs
      (start-log-tail! (log-file/server-log-path root-dir) root-dir opts))
    (let [started (runner/start! {:config config
                                  :fs fs*
                                  :host host
                                  :port (some-> port str parse-long)
                                  :root root-dir
                                  :dev dev?})
          started-port (or (some-> (component-registry/instance-for :http)
                                   component/bound-port)
                           (some-> port str parse-long))
          started-host (or host "127.0.0.1")]
      (when dev?
        (log/info :server/dev-mode-enabled :host started-host :port started-port))
      (log/info :server/started :host started-host :port started-port)
      (println (str "Isaac server running on " started-host ":" started-port))
      (register-shutdown-hook!)
      (block!)
      started)))

(defn- parse-options [raw-args]
  (tools-cli/parse-opts raw-args option-spec))

(defn run-fn [opts]
  (let [raw-args (or (:_raw-args opts) [])]
    (cli-common/standard-run-fn
      "server"
      parse-options
      (fn [merged]
        (if-let [exit (runtime/maybe-trampoline! merged raw-args)]
          exit
          (run merged)))
      opts)))

(defmethod cli-api/run :server [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :server [_id]
  option-spec)
