(ns isaac.foundation.modules.cli
  "isaac modules — inspect and manage configured extension modules."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.cli.color :as color]
    [isaac.foundation.cli.common :as cli-common]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.config.api :as config-api]
    [isaac.foundation.config.cli.common :as common]
    [isaac.foundation.config.loader :as config-loader]
    [isaac.foundation.config.mutate :as mutate]
    [isaac.foundation.config.paths :as paths]
    [isaac.foundation.cli.table :as table]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.berths :as berths]
    [isaac.foundation.module.classpath :as classpath]
    [isaac.foundation.module.coords :as coords]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.module.loader :as loader]
    [isaac.foundation.modules.pins :as pins]
    [isaac.foundation.modules.registry :as registry]
    [isaac.foundation.modules.setup :as setup]
    [isaac.foundation.shell :as shell]))

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
                          "  pins                Check sibling git pins: coherent as a set, and against the fleet\n"
                          "  show <name>         Full detail for one module (coordinate, source, required-by)\n"
                          "  remove <name>       Remove a module from config :modules\n"
                          "  setup <name> [--dry-run]  Run a module's setup (writes starter config)\n"
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
                       "               clojure -Sdeps \"$(isaac modules deps --edn)\" -M -m isaac.foundation.main server\n"
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

(defn- pins-help []
  (common/render-help
    {:command     "isaac modules pins"
     :description (str "Check this module repository's sibling git pins, from :deps and\n"
                       "from every alias. Pins that disagree with each other — with a\n"
                       "sibling's own deps.edn, or with themselves — fail. A coherent\n"
                       "set that is behind the fleet is noted with the set to move to,\n"
                       "but never fails.")
     :option-spec option-spec}))

(defn- show-help []
  (common/render-help
    {:command     "isaac modules show"
     :params      "<name> [options]"
     :description (str "Show full detail for one module: coordinate, source,\n"
                       "required-by, description, handbook doc, and what it\n"
                       "contributes to other modules' berths. Structured\n"
                       "output (also reporting declared berths) via --edn / --json.")
     :option-spec structured-option-spec}))

(defn- remove-help []
  (common/render-help
    {:command     "isaac modules remove"
     :params      "<name>"
     :description "Remove a module from config :modules."
     :option-spec option-spec}))

(def setup-option-spec
  (into option-spec
        [[nil "--dry-run" "Show the writes a setup would make, without making them"]]))

(defn- setup-help []
  (common/render-help
    {:command     "isaac modules setup"
     :params      "<name> [options]"
     :description (str "Run a module's setup: a fn of the current config that proposes\n"
                       "config writes plus optional hints, applied through the same\n"
                       "validated, atomic path `isaac config set` uses. A path that\n"
                       "already has a value is never overwritten. Runs automatically on\n"
                       "`modules install` and `modules upgrade`; run it again by hand\n"
                       "anytime, or pass --dry-run to preview the writes.")
     :option-spec setup-option-spec}))

(defn- upgrade-help []
  (common/render-help
    {:command     "isaac modules upgrade"
     :params      "[name] [<name> ...]"
     :description (str "Re-fetch the registry and rewrite registry-sourced :modules\n"
                       "coordinates to the latest catalog coords. Local paths and ids\n"
                       "not in the registry are left unchanged. New git pins are\n"
                       "materialized before the command reports success.")
     :option-spec option-spec}))

(defn- read-root-config [root]
  (when root
    (binding [classpath/*resolve-classpath?* false]
      (let [result (config-api/load-resolved {:root root :fs (fs/instance)})]
        (when-not (:missing-config? result)
          (:config result))))))

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

(defn- format-contribution-value [value]
  (if (sequential? value)
    (str/join " " (map module-id-str value))
    (str value)))

(defn- render-contributes-block [contributes]
  (when (seq contributes)
    (str "\nContributes:\n"
         (str/join "\n"
                   (map (fn [[berth-id value]]
                          (str (module-id-str berth-id) "  " (format-contribution-value value)))
                        (sort-by (comp module-id-str key) contributes))))))

(defn- render-module-detail [{:keys [id version status coord source required-by description handbook contributes]}]
  (let [coord-lines (format-full-coord-lines coord)
        indent      "            "]
    (str (module-id-str id) "\n"
         (when-not (str/blank? description) (str "Description: " description "\n"))
         (when version (str "Version:     " version "\n"))
         "Status:      " (name status) "\n"
         "Coordinate:  " (first coord-lines) "\n"
         (when (> (count coord-lines) 1)
           (str indent (str/join (str "\n" indent) (rest coord-lines)) "\n"))
         "Source:      " (name source) "\n"
         "Required by: " (format-required-by-detail required-by)
         (when-not (str/blank? handbook) (str "\nHandbook:    " handbook))
         (render-contributes-block contributes))))

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

(defn- divergence-table-rows [entries]
  (mapcat (fn [{:keys [id chosen requested]}]
            (cons {:module      id
                   :version     chosen
                   :required-by []
                   :loaded      "✓"}
                  (map (fn [{:keys [version required-by]}]
                         {:module      id
                          :version     version
                          :required-by (vec required-by)
                          :loaded      nil})
                       requested)))
          entries))

(defn- render-conflict-table [rows]
  (table/render
    {:columns [{:header "MODULE" :key :module :format module-id-str}
               {:header "VERSION" :key :version}
               {:header "REQUIRED BY" :key :required-by :format format-required-by}
               {:header "LOADED" :key :loaded}]
     :rows    rows
     :zebra?  true}))

(defn- render-warning-conflicts-block [conflicts]
  (when (seq conflicts)
    (str "\n"
         color/yellow "⚠  " color/reset
         (count conflicts) " version conflict"
         (when (> (count conflicts) 1) "s")
         " — requested newer than loaded\n"
         (render-conflict-table (divergence-table-rows conflicts)))))

(defn- render-drift-conflicts-block [drift]
  (when (seq drift)
    (str "\n"
         "ℹ  " (count drift) " version drift"
         (when (> (count drift) 1) "s")
         " — loaded version is newer than some requests\n"
         (render-conflict-table (divergence-table-rows drift)))))

(defn- render-installed-list [modules conflicts drift]
  (str (render-installed-table modules)
       (render-warning-conflicts-block conflicts)
       (render-drift-conflicts-block drift)))

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
            context {:cwd (host/cwd)}
            {:keys [modules conflicts drift]}
            (loader/list-configured-modules config context)]
        (cond
          (or edn json) (print-structured! edn json (cond-> {:modules modules}
                                                      (seq conflicts) (assoc :conflicts conflicts)
                                                      (seq drift)     (assoc :drift drift)))
          :else         (println (render-installed-list modules conflicts drift)))
        0))))

(defn- run-deps [opts _arguments options]
  (let [{:keys [classpath edn]} options]
    (if (and classpath edn)
      (common/print-cli-error! "choose one of --edn or --classpath")
      (let [config      (or (read-root-config (:root opts)) {})
            cwd         (host/cwd)
            launch-deps (loader/config->launch-deps config cwd)]
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

(defn- fresh-config
  "Re-reads config from disk, bypassing the process memo — used right after
   a write this same command made, so a module just added to :modules is
   visible to discovery immediately (isaac-82nx)."
  [root]
  (:config (config-loader/load-config-result {:root root :fs (fs/instance) :skip-cache? true})))

(defn- setup-module-index [config]
  (:index (loader/list-configured-modules config {:cwd (host/cwd)})))

(defn- try-setup-context
  "{:config :index} for setup lookups after install/upgrade, or nil when
   discovering the fresh module index blows up — e.g. a just-upgraded
   module's own schema can't validate standalone without a sibling module
   this environment doesn't have installed. install/upgrade already wrote
   :modules successfully by the time this runs, so a discovery failure here
   means only 'setup can't be checked right now', not 'the command failed'
   (isaac-82nx)."
  [root]
  (try
    (let [config' (fresh-config root)]
      {:config config' :index (setup-module-index config')})
    (catch Throwable _ nil)))

(defn- format-write-line [[path value]]
  (str "  " path " = " (pr-str value)))

(defn- run-one-setup!
  "Runs `id`'s :isaac/setup contribution (looked up in `module-index`)
   against `config` at `root`. `verbose?` reports the 'no setup' / 'already
   set up' cases — on for the explicit `modules setup` command, off for
   install/upgrade, where a module contributing no setup has nothing to
   report. `dry-run?` never writes."
  [root module-index config id {:keys [dry-run? verbose?]}]
  (if-let [descriptor (setup/find-setup module-index id)]
    (let [{:keys [writes hints]} (setup/proposed-writes descriptor config)
          missing                (setup/missing-writes config writes)]
      (cond
        (empty? missing)
        (do (when verbose? (println (str (module-id-str id) " is already set up"))) 0)

        dry-run?
        (do (println (str "Would set up " (module-id-str id) ":"))
            (run! println (map format-write-line missing))
            0)

        :else
        (let [result (setup/apply-writes! root missing)]
          (if (= :ok (:status result))
            (do (println (str "Set up " (module-id-str id) ":"))
                (run! println (map format-write-line missing))
                (run! println hints)
                0)
            (do (common/print-errors! (:errors result) "error") 1)))))
    (do (when verbose? (println (str (module-id-str id) " has no setup"))) 0)))

(defn- run-setup [opts arguments options]
  (let [module-name (first arguments)]
    (if (str/blank? module-name)
      (common/print-cli-error! "missing module name")
      (let [root   (:root opts)
            config (or (read-root-config root) {})]
        (try
          (run-one-setup! root (setup-module-index config) config (keyword module-name)
                          {:dry-run? (:dry-run options) :verbose? true})
          (catch Throwable t
            (common/print-cli-error! (str "could not resolve modules: " (ex-message t)))))))))

(defn- resolve-install-entries [registry names]
  (reduce
    (fn [result name]
      (if (:error result)
        result
        (let [{:keys [id coord error]} (registry/lookup-entry registry name)]
          (cond
            error
            {:error error}

            (not (coords/valid-module-coord? coord))
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
                (if-not (zero? exit)
                  exit
                  (let [ctx (try-setup-context root)]
                    (reduce (fn [acc {:keys [id]}]
                              (println (str "Installed " (module-id-str id)))
                              (if (and ctx
                                       (not (zero? (run-one-setup! root (:index ctx) (:config ctx) id
                                                                   {:dry-run? false :verbose? false}))))
                                1
                                acc))
                            0
                            entries)))))))))))

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
              (if-not (zero? exit)
                exit
                (do
                  (loader/warm-module-checkouts! (assoc config :modules merged))
                  (let [ctx (try-setup-context root)]
                    (reduce (fn [acc {:keys [id old new]}]
                              (println (str "Upgraded " (module-id-str id) ": "
                                            (coord-revision old) " -> " (coord-revision new)))
                              (if (and ctx
                                       (not (zero? (run-one-setup! root (:index ctx) (:config ctx) id
                                                                   {:dry-run? false :verbose? false}))))
                                1
                                acc))
                            0
                            upgrades)))))))))))

(defn- short-sha [sha]
  (subs sha 0 (min 7 (count sha))))

(defn- print-pin! [{:keys [id pinned-sha registry-sha status]}]
  (println
    (case status
      :current (str (module-id-str id) " current")
      :ahead   (str (module-id-str id) " pinned " (short-sha pinned-sha)
                    " registry " (short-sha registry-sha) " ahead")
      :older   (str (module-id-str id) " pinned " (short-sha pinned-sha)
                    " registry " (short-sha registry-sha) " older")
      (str (module-id-str id) " could not compare pin with registry"))))

(defn- pin-set-str [pins]
  (str/join ", " (map #(str (:repo %) " " (short-sha (:sha %))) pins)))

(defn- split-str [{:keys [repo pins]}]
  (str repo " pinned "
       (str/join " and "
                 (->> pins
                      (reduce (fn [acc pin]
                                (if (some #(= (:sha %) (:sha pin)) acc) acc (conj acc pin)))
                              [])
                      (map #(str (short-sha (:sha %)) " (" (:at %) ")"))))))

(defn- print-incoherent-pins! [{:keys [splits conflicts target]}]
  (binding [*out* *err*]
    (println (str color/red "✗  incoherent pins" color/reset
                  " — this repo's sibling pins do not agree with each other"))
    (doseq [split splits]
      (println (str "   " (split-str split))))
    (doseq [{:keys [repo ours theirs source source-sha]} conflicts]
      (println (str "   " source " " (short-sha source-sha)
                    " requires " repo " " (short-sha theirs)
                    ", this repo pins " (short-sha ours))))
    (println (str "   move to: " (pin-set-str target)))))

(defn- print-fleet-note! [{:keys [behind target]}]
  (println (str "ℹ  behind the fleet — " (str/join ", " (map :repo behind))))
  (println (str "   move to: " (pin-set-str target)))
  (println "   the set is coherent, so this is a note, not a failure"))

(defn- disagreement-str [subject fleet-requires]
  (str subject " — "
       (str/join ", " (for [{:keys [repo sha requires]} fleet-requires
                            :let [wanted (get requires subject)]
                            :when wanted]
                        (str repo " " (short-sha sha) " requires " (short-sha wanted))))))

(defn- print-fleet-disagreement! [disagreements fleet-requires]
  (doseq [repo (sort disagreements)]
    (println (str "ℹ  the fleet's own modules disagree about "
                  (disagreement-str repo fleet-requires)
                  " — a train is mid-flight, so there is no fleet sha to move to"))))

(defn- print-set-report! [{:keys [behind disagreements fleet-requires] :as report}]
  (cond
    (pins/incoherent? report) (print-incoherent-pins! report)
    (seq behind)              (print-fleet-note! report))
  (when (seq disagreements)
    (print-fleet-disagreement! disagreements fleet-requires)))

(defn- run-pins [opts _arguments _options]
  (let [root    (:root opts)
        cwd     (host/cwd)
        local   (pins/read-pin-set cwd)
        dir     (if (or (seq local) (= cwd root)) cwd root)
        pin-set (if (= dir cwd) local (pins/read-pin-set root))]
    (if (empty? pin-set)
      0
      (let [config (or (read-root-config root) {})
            result (registry/fetch-registry config root)]
        (if-let [error (:error result)]
          (common/print-cli-error! error)
          (let [registry (:registry result)
                checks   (pins/classify-pins (pins/read-sibling-pins dir) registry)
                report   (pins/check-set dir registry)]
            (doseq [check checks]
              (print-pin! check))
            (print-set-report! report)
            (if (pins/incoherent? report) 1 0)))))))

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
            context  {:cwd (host/cwd)}
            {:keys [modules index]}
            (loader/list-configured-modules config context)
            module   (find-module-by-name modules module-name)]
        (if-not module
          (common/print-cli-error! (str "Unknown module: " module-name))
          (let [{:keys [registry]} (registry/fetch-registry config root)
                explicit-ids       (set (keys (:modules config)))
                detail             (-> (enrich-module config registry explicit-ids module)
                                       (merge (berths/module-report index (:id module))))]
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
   "pins"      {:option-spec option-spec
                 :runner      run-pins
                 :help-text   pins-help}
   "show"      {:option-spec structured-option-spec
                :runner      run-show
                :help-text   show-help}
   "remove"    {:option-spec option-spec
                :runner      run-remove
                :help-text   remove-help}
   "setup"     {:option-spec setup-option-spec
                :runner      run-setup
                :help-text   setup-help}
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