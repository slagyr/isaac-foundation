(ns isaac.cli.host
  "Process boundary for CLI commands and in-process embedded dispatch."
  (:require
    [clojure.string :as str]
    [isaac.nexus :as nexus]))

(defprotocol Host
  (-exit! [host code])
  (-ensure-runtime! [host opts])
  (-in [host])
  (-out [host])
  (-err [host])
  (-cwd [host])
  (-env [host key])
  (-tty? [host])
  (-on-shutdown! [host f])
  (-block-until-cancelled! [host])
  (-cancelled? [host])
  (-cancel! [host]))

(deftype ProcessHost [installed]
  Host
  (-exit! [_ code] (throw (ex-info "process exit" {:isaac.cli/process-exit code})))
  (-ensure-runtime! [_ {:keys [install!]}]
    (when (and install! (not (contains? @installed install!)))
      (locking installed
        (when (not (contains? @installed install!))
          (install!)
          (swap! installed conj install!)))))
  (-in [_] *in*)
  (-out [_] *out*)
  (-err [_] *err*)
  (-cwd [_] (System/getProperty "user.dir"))
  (-env [_ key] (System/getenv key))
  (-tty? [_] (some? (System/console)))
  (-on-shutdown! [_ f]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (f)) "isaac-cli-shutdown")))
  (-block-until-cancelled! [_] @(promise))
  (-cancelled? [_] false)
  (-cancel! [_] nil))

(deftype EmbeddedHost [input output error environment working-dir tty shutdowns cancelled latch]
  Host
  (-exit! [_ code] (throw (ex-info "embedded exit" {:isaac.cli/exit code})))
  (-ensure-runtime! [_ _opts]
    (when-not (seq (nexus/necho))
      (throw (ex-info "embedded CLI requires the live runtime" {}))))
  (-in [_] input)
  (-out [_] output)
  (-err [_] error)
  (-cwd [_] working-dir)
  (-env [_ key] (get environment key))
  (-tty? [_] (boolean tty))
  (-on-shutdown! [_ f] (swap! shutdowns conj f))
  (-block-until-cancelled! [_] @latch)
  (-cancelled? [_] @cancelled)
  (-cancel! [_]
    (when (compare-and-set! cancelled false true)
      (doseq [f @shutdowns] (f))
      (deliver latch true))
    true))

(def process-host (ProcessHost. (atom #{})))
(def ^:dynamic *host* process-host)

(defn embedded-host [{:keys [in out err env cwd tty?]
                      :or   {in *in* out *out* err *err* env {} cwd "." tty? false}}]
  (let [input (if (instance? java.io.BufferedReader in)
                in
                (java.io.BufferedReader. in))]
    (EmbeddedHost. input out err env cwd tty? (atom []) (atom false) (promise))))

(defn exit! [code] (-exit! *host* code))
(defn ensure-runtime! [opts] (-ensure-runtime! *host* opts))
(defn in [] (-in *host*))
(defn out [] (-out *host*))
(defn err [] (-err *host*))
(defn cwd [] (-cwd *host*))
(defn env [key] (-env *host* key))
(defn tty? [] (-tty? *host*))
(defn on-shutdown! [f] (-on-shutdown! *host* f))
(defn block-until-cancelled! [] (-block-until-cancelled! *host*))
(defn cancelled?
  ([] (-cancelled? *host*))
  ([host] (-cancelled? host)))
(defn cancel! [host] (-cancel! host))

(defn- print-err! [message]
  (binding [*out* (err)]
    (println message)))

(defn- resolve-alias [args]
  (cond
    (and (= "models" (first args)) (= "auth" (second args))) (vec (rest args))
    (= "gateway" (first args)) (vec (cons "server" (rest args)))
    :else (vec args)))

(defn- dispatch-embedded [argv server-root]
  (let [extract-root (requiring-resolve 'isaac.cli.args/extract-root-flag)
        usage-text   (requiring-resolve 'isaac.cli.registry/usage-text)
        get-command  (requiring-resolve 'isaac.cli.registry/get-command)
        version-text (requiring-resolve 'isaac.foundation.version/version-string)
        root-var     (requiring-resolve 'isaac.config.root/*root*)
        {after-root :args requested-root :root} (extract-root argv)
        args         (resolve-alias after-root)
        cmd          (first args)]
    (cond
      (and requested-root (not= requested-root server-root))
      (do (print-err! (str "--root must match the server root " server-root)) 2)

      (or (nil? cmd) (str/blank? cmd) (#{"--help" "-h"} cmd))
      (do (println (usage-text)) 0)

      (#{"--version" "-V" "version"} cmd)
      (do (println (version-text)) 0)

      :else
      (if-let [command (get-command cmd)]
        (if (:local-only command)
          (do (print-err! (str cmd " is local-only; run this on the host")) 2)
          (with-bindings {root-var server-root}
            (or ((:run-fn command) {:root         server-root
                                    :display-root server-root
                                    :_raw-args    (vec (rest args))})
                0)))
        (do (print-err! (str "Unknown command: " cmd)) 1)))))

(defn run-embedded
  "Dispatch argv against the live command registry and nexus without process setup."
  [{:keys [argv root] :as opts}]
  (let [host (embedded-host opts)]
    (binding [*host* host
              *in*     (-in host)
              *out*    (-out host)
              *err*    (-err host)]
      (try
        (ensure-runtime! opts)
        (dispatch-embedded (vec argv) root)
        (catch clojure.lang.ExceptionInfo e
          (if-some [code (:isaac.cli/exit (ex-data e))]
            code
            (do (print-err! (.getMessage e)) 1)))
        (catch Throwable t
          (print-err! (or (.getMessage t) (str t)))
          1)))))
