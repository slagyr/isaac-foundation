(ns isaac.modules.cli
  "isaac modules — inspect and manage configured extension modules."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli.api :as cli-api]
    [isaac.cli.color :as color]
    [isaac.cli.common :as cli-common]
    [isaac.config.cli.common :as common]
    [isaac.config.mutate :as mutate]
    [isaac.config.paths :as paths]
    [isaac.cli.table :as table]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.modules.registry :as registry]))

(def option-spec
  [["-h" "--help" "Show help"]])

(def structured-option-spec
  (into option-spec
        [[nil "--edn" "Print structured EDN output"]
         [nil "--json" "Print structured JSON output"]]))

(defn- modules-help []
  (common/render-help
    {:command     "isaac modules"
     :params      "[subcommand] [options]"
     :description "Inspect and manage Isaac extension modules declared in config."
     :pre-sections [["Subcommands"
                     (str "  available [search]  Browse the installable module catalog\n"
                          "  install <name> ...  Add module coordinates to config :modules\n"
                          "  list                List configured modules (id, coordinate, status)\n"
                          "  remove <name>       Remove a module from config :modules\n"
                          "  help <subcommand>   Print usage for a subcommand")]]
     :option-spec option-spec}))

(defn- list-help []
  (common/render-help
    {:command     "isaac modules list"
     :description (str "List every module in config :modules with its source coordinate\n"
                       "and resolution status. Matches the set the packaged launcher loads.")
     :option-spec structured-option-spec}))

(defn- available-help []
  (common/render-help
    {:command     "isaac modules available"
     :params      "[search] [options]"
     :description "List installable modules from the registry catalog."
     :option-spec structured-option-spec}))

(defn- install-help []
  (common/render-help
    {:command     "isaac modules install"
     :params      "<name> [<name> ...]"
     :description "Resolve registry module names to coordinates and add them to config :modules."
     :option-spec option-spec}))

(defn- remove-help []
  (common/render-help
    {:command     "isaac modules remove"
     :params      "<name>"
     :description "Remove a module from config :modules."
     :option-spec option-spec}))

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

(defn- module-id-str [id]
  (cond
    (keyword? id) (subs (str id) 1)
    (symbol? id)  (str id)
    (string? id)  id
    :else         (str id)))

(defn- format-coord [coord]
  (cond
    (nil? coord) ""
    (:local/root coord)
    (str "local " (:local/root coord))

    (:mvn/version coord)
    (str "mvn " (:mvn/version coord))

    (or (:git/url coord) (:git/tag coord) (:git/sha coord))
    (let [url  (:git/url coord)
          slug (when (string? url)
                 (some-> url
                         (str/replace #"\.git$" "")
                         (str/split #"/")
                         last))
          rev  (or (:git/sha coord) (:git/tag coord))]
      (str "git "
           (or slug url)
           (when rev (str "@" (subs rev 0 (min 7 (count rev)))))))

    :else (pr-str coord)))

(defn- format-required-by [required-by]
  (let [rb (cond
             (vector? required-by) required-by
             (map? required-by)      (or (:required-by required-by) [])
             :else                   [])]
    (case (count rb)
      0 ""
      1 (module-id-str (first rb))
      (str (module-id-str (first rb)) " +" (dec (count rb))))))

(defn- render-installed-table [modules]
  (table/render
    {:columns [{:header "ID" :key :id :format module-id-str}
               {:header "VERSION" :key :version}
               {:header "STATUS" :key :status
                :format #(name %)
                :color-fn status-color}
               {:header "COORD" :key :coord :format format-coord}
               {:header "REQUIRED BY" :key :required-by :format format-required-by}]
     :rows    modules
     :zebra?  true}))

(defn- conflict-table-rows [conflicts]
  (mapcat (fn [{:keys [id chosen requested]}]
            (map (fn [{:keys [version required-by]}]
                   {:module      id
                    :version     version
                    :required-by (vec required-by)
                    :loaded      (when (= version chosen) "✓")})
                 requested))
          conflicts))

(defn- render-conflicts-block [conflicts]
  (when (seq conflicts)
    (let [n    (count conflicts)
          rows (conflict-table-rows conflicts)]
      (str "\n"
           color/yellow "⚠  " color/reset
           n " version conflict"
           (when (> n 1) "s")
           " — one version loaded; the rest dropped\n"
           (table/render
             {:columns [{:header "MODULE" :key :module :format module-id-str}
                        {:header "VERSION" :key :version}
                        {:header "REQUIRED BY" :key :required-by :format format-required-by}
                        {:header "LOADED" :key :loaded}]
              :rows    rows
              :zebra?  true})))))

(defn- render-installed-list [modules conflicts]
  (str (render-installed-table modules) (render-conflicts-block conflicts)))

(defn- render-catalog-table [modules]
  (table/render
    {:columns [{:header "ID" :key :id :format module-id-str}
               {:header "DESCRIPTION" :key :desc}]
     :rows    modules
     :zebra?  true}))

(defn- matches-search? [search {:keys [id desc]}]
  (let [needle (str/lower-case search)
        hay    (str/lower-case (str id " " (or desc "")))]
    (str/includes? hay needle)))

(defn- print-structured! [edn? json? value]
  (cond
    json? (cli-common/print-json! value)
    edn?  (cli-common/print-edn! value)
    :else (throw (ex-info "structured output requires --edn or --json" {}))))

(defn- run-list [opts _arguments options]
  (let [{:keys [edn json]} options]
    (if (and edn json)
      (common/print-cli-error! "choose one of --edn or --json")
      (let [config  (or (read-root-config (:root opts)) {})
            context {:cwd (System/getProperty "user.dir")}
            {:keys [modules conflicts]}
            (module-loader/list-configured-modules config context)]
        (cond
          (or edn json) (print-structured! edn json {:modules modules :conflicts conflicts})
          :else         (println (render-installed-list modules conflicts)))
        0))))

(defn- run-available [opts arguments options]
  (let [{:keys [edn json]} options
        search   (first arguments)
        root     (:root opts)
        config   (or (read-root-config root) {})]
    (if (and edn json)
      (common/print-cli-error! "choose one of --edn or --json")
      (let [{:keys [registry error]} (registry/fetch-registry config root)]
        (if error
          (common/print-cli-error! error)
          (let [modules (->> (registry/catalog-entries registry)
                             (filter #(or (str/blank? search)
                                          (matches-search? search %)))
                             vec)]
            (cond
              (or edn json) (print-structured! edn json {:modules modules})
              :else         (println (render-catalog-table modules)))
            0))))))

(defn- mutate-modules! [root path value]
  (let [result (if (some? value)
                 (mutate/set-config root path value
                                    :skip-ref-validation? true
                                    :skip-module-validation? true)
                 (mutate/unset-config root path :skip-module-validation? true))]
    (case (:status result)
      :ok 0
      (do (common/print-errors! (:errors result) "error")
          1))))

(defn- resolve-install-entries [registry names]
  (reduce
    (fn [result name]
      (if (:error result)
        result
        (let [{:keys [id coord error]} (registry/lookup-entry registry name)]
          (cond
            error
            {:error error}

            (not (module-loader/valid-module-coord? coord))
            {:error (str "Registry entry for " name " has invalid coordinate")}

            :else
            (update result :entries conj {:id id :coord coord :name name})))))
    {:entries []}
    names))

(defn- run-install [opts arguments _options]
  (let [names (vec (remove str/blank? arguments))
        root  (:root opts)]
    (cond
      (empty? names)
      (common/print-cli-error! "missing module name")

      :else
      (let [config                   (or (read-root-config root) {})
            {:keys [registry error]} (registry/fetch-registry config root)]
        (cond
          error
          (common/print-cli-error! error)

          :else
          (let [{:keys [entries error]} (resolve-install-entries registry names)]
            (cond
              error
              (common/print-cli-error! error)

              :else
              (let [modules (get config :modules {})
                    merged  (reduce (fn [m {:keys [id coord]}] (assoc m id coord))
                                    modules
                                    entries)
                    exit    (mutate-modules! root "modules" merged)]
                (when (zero? exit)
                  (doseq [{:keys [id]} entries]
                    (println (str "Installed " (module-id-str id)))))
                exit))))))))

(defn- run-remove [opts arguments _options]
  (let [module-name (first arguments)
        root        (:root opts)]
    (cond
      (str/blank? module-name)
      (common/print-cli-error! "missing module name")

      :else
      (let [config  (or (read-root-config root) {})
            modules (get config :modules {})
            id      (keyword module-name)]
        (if-not (contains? modules id)
          (common/print-cli-error! (str "Unknown module: " module-name))
          (let [exit (mutate-modules! root "modules" (dissoc modules id))]
            (when (zero? exit)
              (println (str "Removed " module-name)))
            exit))))))

(def ^:private subcommands
  {"available" {:option-spec structured-option-spec
                :runner      run-available
                :help-text   available-help}
   "install"   {:option-spec option-spec
                :runner      run-install
                :help-text   install-help}
   "list"      {:option-spec structured-option-spec
                :runner      run-list
                :help-text   list-help}
   "remove"    {:option-spec option-spec
                :runner      run-remove
                :help-text   remove-help}})

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
    (let [{:keys [errors options]} (common/parse-option-map args structured-option-spec :in-order true)]
      (cond
        (seq errors)    (common/print-cli-errors! errors)
        (:help options) (print-help!)
        :else           (run-list opts [] options)))))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (run opts (or _raw-args [])))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :modules [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :modules [_id]
  option-spec)

(defmethod cli-api/help :modules [_id]
  (modules-help))