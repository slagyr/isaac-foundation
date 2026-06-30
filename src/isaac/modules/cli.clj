(ns isaac.modules.cli
  "isaac modules — inspect and manage configured extension modules."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [isaac.cli.api :as cli-api]
    [isaac.cli.color :as color]
    [isaac.cli.common :as cli-common]
    [isaac.config.api :as config-api]
    [isaac.config.cli.common :as common]
    [isaac.config.mutate :as mutate]
    [isaac.config.paths :as paths]
    [isaac.cli.table :as table]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.modules.registry :as registry]
    [isaac.shell :as shell]))

(def option-spec
  [["-h" "--help" "Show help"]])

(def structured-option-spec
  (into option-spec
        [[nil "--edn" "Print structured EDN output"]
         [nil "--json" "Print structured JSON output"]]))

(def deps-option-spec
  (into option-spec
        [[nil "--edn" "Print the -Sdeps map (default)"]
         [nil "--classpath" "Print the fully-resolved classpath (shells clojure -Spath)"]]))

(defn- modules-help []
  (common/render-help
    {:command     "isaac modules"
     :params      "[subcommand] [options]"
     :description "Inspect and manage Isaac extension modules declared in config."
     :pre-sections [["Subcommands"
                     (str "  available [search]  Browse the installable module catalog\n"
                          "  deps [--edn|--classpath]  Emit JVM launch deps/classpath from config\n"
                          "  install <name> ...  Add module coordinates to config :modules\n"
                          "  list                List configured modules (id, coordinate, status)\n"
                          "  show <name>         Full detail for one module (coordinate, source, required-by)\n"
                          "  remove <name>       Remove a module from config :modules\n"
                          "  upgrade [name] ...  Refresh registry-sourced modules to latest coords\n"
                          "  help <subcommand>   Print usage for a subcommand")]]
     :option-spec option-spec}))

(defn- list-help []
  (common/render-help
    {:command     "isaac modules list"
     :description (str "List every module in config :modules with its source coordinate\n"
                       "and resolution status. Matches the set the packaged launcher loads.")
     :option-spec structured-option-spec}))

(defn- deps-help []
  (common/render-help
    {:command     "isaac modules deps"
     :description (str "Emit the dependency set needed to launch isaac on the JVM, derived\n"
                       "from this root's config :modules (foundation seed :paths + each\n"
                       "module coord with seed-authoritative exclusions). No deps.edn is\n"
                       "materialized — the set is regenerated from config each call.\n\n"
                       "  --edn        (default) print the -Sdeps map; launch with\n"
                       "               clojure -Sdeps \"$(isaac modules deps --edn)\" -M -m isaac.main server\n"
                       "  --classpath  print the flattened classpath (shells clojure -Spath);\n"
                       "               for debugging / java -cp. Requires the clojure CLI.")
     :option-spec deps-option-spec}))

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

(defn- show-help []
  (common/render-help
    {:command     "isaac modules show"
     :params      "<name> [options]"
     :description (str "Show full detail for one module: coordinate, source,\n"
                       "and required-by. Structured output via --edn / --json.")
     :option-spec structured-option-spec}))

(defn- remove-help []
  (common/render-help
    {:command     "isaac modules remove"
     :params      "<name>"
     :description "Remove a module from config :modules."
     :option-spec option-spec}))

(defn- upgrade-help []
  (common/render-help
    {:command     "isaac modules upgrade"
     :params      "[name] [<name> ...]"
     :description (str "Re-fetch the registry and rewrite registry-sourced :modules\n"
                       "coordinates to the latest catalog coords. Local paths and ids\n"
                       "not in the registry are left unchanged.")
     :option-spec option-spec}))

(defn- read-root-config [root]
  (when root
    (let [result (config-api/load-resolved {:root root :fs (fs/instance)})]
      (when-not (:missing-config? result)
        (:config result)))))

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

(defn- format-full-coord-lines [coord]
  (cond
    (nil? coord) ["(none)"]

    (:local/root coord)
    [(str ":local/root " (:local/root coord))]

    (:mvn/version coord)
    [(str ":mvn/version " (:mvn/version coord))]

    (or (:git/url coord) (:git/tag coord) (:git/sha coord))
    (remove nil?
            [(when (:git/url coord) (str ":git/url " (:git/url coord)))
             (when (:git/sha coord) (str ":git/sha " (:git/sha coord)))
             (when (:git/tag coord) (str ":git/tag " (:git/tag coord)))
             (when (:deps/root coord) (str ":deps/root " (:deps/root coord)))])

    :else [(pr-str coord)]))

(defn- format-required-by-detail [required-by]
  (let [rb (cond
             (vector? required-by) required-by
             (map? required-by)    (or (:required-by required-by) [])
             :else                 [])]
    (if (empty? rb)
      "—"
      (str/join ", " (map module-id-str rb)))))

(defn- infer-module-source [config registry explicit-ids {:keys [id coord]}]
  (cond
    (not (contains? explicit-ids id)) :transitive
    (:local/root coord)                 :local
    (and registry (= coord (get-in registry [id :coord]))) :registry
    :else                               :hand-pinned))

(defn- find-module-by-name [modules name]
  (let [id (keyword name)]
    (first (filter #(= (:id %) id) modules))))

(defn- enrich-module [config registry explicit-ids module]
  (assoc module :source (infer-module-source config registry explicit-ids module)))

(defn- render-module-detail [{:keys [id version status coord source required-by]}]
  (let [coord-lines (format-full-coord-lines coord)
        indent      "            "]
    (str (module-id-str id) "\n"
         (when version (str "Version:     " version "\n"))
         "Status:      " (name status) "\n"
         "Coordinate:  " (first coord-lines) "\n"
         (when (> (count coord-lines) 1)
           (str indent (str/join (str "\n" indent) (rest coord-lines)) "\n"))
         "Source:      " (name source) "\n"
         "Required by: " (format-required-by-detail required-by))))

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

(defn- run-deps [opts _arguments options]
  (let [{:keys [classpath edn]} options]
    (if (and classpath edn)
      (common/print-cli-error! "choose one of --edn or --classpath")
      (let [config      (or (read-root-config (:root opts)) {})
            cwd         (System/getProperty "user.dir")
            launch-deps (module-loader/config->launch-deps config cwd)]
        (if classpath
          (if-not (shell/cmd-available? "clojure")
            (common/print-cli-error!
              "modules deps --classpath requires the clojure CLI on PATH")
            (let [{:keys [out err exit]} (shell/sh! "clojure" "-Spath" "-Sdeps" (pr-str launch-deps))]
              (if (zero? exit)
                (do (println (str/trim out)) 0)
                (common/print-cli-error!
                  (str "clojure -Spath failed (exit " exit "): " (str/trim (or err "")))))))
          (do (pp/pprint launch-deps) 0))))))

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

(defn- coord-revision [coord]
  (let [rev (or (:git/sha coord) (:git/tag coord) (:mvn/version coord))]
    (when rev (subs rev 0 (min 7 (count rev))))))

(defn- registry-upgradeable? [id coord registry]
  (and (map? coord)
       (not (:local/root coord))
       (contains? registry id)))

(defn- plan-upgrades [modules registry names]
  (let [selective? (seq names)
        ids        (if selective?
                     (mapv keyword names)
                     (vec (keys modules)))]
    (reduce
      (fn [result id]
        (if (:error result)
          result
          (cond
            (not (contains? modules id))
            (if selective?
              (assoc result :error (str "Unknown module: " (module-id-str id)))
              result)

            :else
            (let [coord (get modules id)]
              (cond
                (not (registry-upgradeable? id coord registry))
                result

                :else
                (let [new-coord (:coord (get registry id))]
                  (if (= coord new-coord)
                    result
                    (update result :upgrades conj {:id id :old coord :new new-coord}))))))))
      {:upgrades []}
      ids)))

(defn- run-upgrade [opts arguments _options]
  (let [names (vec (remove str/blank? arguments))
        root  (:root opts)
        config (or (read-root-config root) {})]
    (let [{:keys [registry error]} (registry/fetch-registry config root true)]
      (cond
        error
        (common/print-cli-error! error)

        :else
        (let [{:keys [upgrades error]} (plan-upgrades (get config :modules {}) registry names)]
          (cond
            error
            (common/print-cli-error! error)

            (empty? upgrades)
            (do (println "up to date") 0)

            :else
            (let [merged (reduce (fn [m {:keys [id new]}] (assoc m id new))
                                 (get config :modules {})
                                 upgrades)
                  exit   (mutate-modules! root "modules" merged)]
              (when (zero? exit)
                (doseq [{:keys [id old new]} upgrades]
                  (println (str "Upgraded " (module-id-str id) ": "
                                  (coord-revision old) " -> " (coord-revision new)))))
              exit)))))))

(defn- run-show [opts arguments options]
  (let [{:keys [edn json]} options
        module-name (first arguments)]
    (cond
      (and edn json)
      (common/print-cli-error! "choose one of --edn or --json")

      (str/blank? module-name)
      (common/print-cli-error! "missing module name")

      :else
      (let [root     (:root opts)
            config   (or (read-root-config root) {})
            context  {:cwd (System/getProperty "user.dir")}
            {:keys [modules]}
            (module-loader/list-configured-modules config context)
            module   (find-module-by-name modules module-name)]
        (if-not module
          (common/print-cli-error! (str "Unknown module: " module-name))
          (let [{:keys [registry]} (registry/fetch-registry config root)
                explicit-ids       (set (keys (:modules config)))
                detail             (enrich-module config registry explicit-ids module)]
            (cond
              (or edn json) (print-structured! edn json detail)
              :else         (println (render-module-detail detail)))
            0))))))

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
   "deps"      {:option-spec deps-option-spec
                :runner      run-deps
                :help-text   deps-help}
   "install"   {:option-spec option-spec
                :runner      run-install
                :help-text   install-help}
   "list"      {:option-spec structured-option-spec
                :runner      run-list
                :help-text   list-help}
   "show"      {:option-spec structured-option-spec
                :runner      run-show
                :help-text   show-help}
   "remove"    {:option-spec option-spec
                :runner      run-remove
                :help-text   remove-help}
   "upgrade"   {:option-spec option-spec
                :runner      run-upgrade
                :help-text   upgrade-help}})

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