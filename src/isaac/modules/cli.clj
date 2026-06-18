(ns isaac.modules.cli
  "isaac modules — inspect and manage configured extension modules."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli.api :as cli-api]
    [isaac.cli.color :as color]
    [isaac.cli.common :as cli-common]
    [isaac.config.cli.common :as common]
    [isaac.config.paths :as paths]
    [isaac.cli.table :as table]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]))

(def option-spec
  [["-h" "--help" "Show help"]])

(def list-option-spec
  (into option-spec
        [[nil "--edn" "Print structured EDN output"]
         [nil "--json" "Print structured JSON output"]]))

(defn- modules-help []
  (common/render-help
    {:command     "isaac modules"
     :params      "[subcommand] [options]"
     :description "Inspect and manage Isaac extension modules declared in config."
     :pre-sections [["Subcommands"
                     (str "  list   List configured modules (id, coordinate, status)\n"
                          "  help   Print usage for a subcommand")]]
     :option-spec option-spec}))

(defn- list-help []
  (common/render-help
    {:command     "isaac modules list"
     :description (str "List every module in config :modules with its source coordinate\n"
                       "and resolution status. Matches the set the packaged launcher loads.")
     :option-spec list-option-spec}))

(defn- read-root-config [root]
  (when root
    (let [fs*         (fs/instance)
          config-file (paths/root-config-file root)]
      (when (fs/exists? fs* config-file)
        (try
          (edn/read-string (fs/slurp fs* config-file))
          (catch Exception _ nil))))))

(defn- status-color [status]
  (when (= :invalid status)
    color/red))

(defn- render-table [modules]
  (table/render
    {:columns [{:header "ID" :key :id}
               {:header "STATUS" :key :status
                :format #(name %)
                :color-fn status-color}
               {:header "COORD" :key :coord
                :format #(if % (pr-str %) "")}]
     :rows    modules
     :zebra?  true}))

(defn- run-list [_opts _arguments options]
  (let [{:keys [edn json]} options]
    (if (and edn json)
      (common/print-cli-error! "choose one of --edn or --json")
      (let [config  (or (read-root-config (:root _opts)) {})
            context {:cwd (System/getProperty "user.dir")}
            {:keys [modules]} (module-loader/list-configured-modules config context)]
        (cond
          json (cli-common/print-json! {:modules modules})
          edn  (cli-common/print-edn! {:modules modules})
          :else (println (render-table modules)))
        0))))

(def ^:private subcommands
  {"list" {:option-spec list-option-spec
           :runner      run-list
           :help-text   list-help}})

(defn- print-help! []
  (println (modules-help))
  0)

(defn- print-subcommand-help! [help-fn]
  (println (if help-fn (help-fn) (modules-help)))
  0)

(defn- run-parsed-subcommand [opts sub-args {:keys [option-spec parse-args runner help-text]}]
  (let [{:keys [arguments errors options]} (apply common/parse-option-map sub-args option-spec parse-args)]
    (cond
      (:help options) (print-subcommand-help! help-text)
      (seq errors)    (common/print-cli-errors! errors)
      :else           (runner opts arguments options))))

(defn run [opts args]
  (cond
    (and (= "help" (first args)) (get subcommands (second args)))
    (print-subcommand-help! (:help-text (get subcommands (second args))))

    (get subcommands (first args))
    (run-parsed-subcommand opts (rest args) (get subcommands (first args)))

    (and (first args) (not (str/starts-with? (first args) "-")))
    (common/print-cli-error! (str "Unknown modules subcommand: " (first args)))

    :else
    (let [{:keys [errors]} (common/parse-option-map args option-spec :in-order true)]
      (if (seq errors)
        (common/print-cli-errors! errors)
        (print-help!)))))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (run opts (or _raw-args [])))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :modules [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :modules [_id]
  option-spec)

(defmethod cli-api/help :modules [_id]
  (modules-help))