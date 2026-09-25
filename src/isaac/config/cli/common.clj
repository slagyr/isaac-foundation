;; mutation-tested: 2026-05-06
(ns isaac.config.cli.common
  "Shared helpers for the isaac.config.cli.* subcommand namespaces."
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [clojure.walk :as walk]
    [isaac.cli.color :as color]
    [isaac.cli.host :as host]
    [isaac.config.env :as env]
    [isaac.config.loader :as loader]
    [isaac.config.schema.resolve :as schema-resolve]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.config.root :as root]
    [isaac.util.edn :as edn-pretty]))

;; region ----- Option parsing -----

(def help-option-spec
  [["-h" "--help" "Show help"]])

(defn parse-option-map [args option-spec & parse-args]
  (let [{:keys [arguments errors options]} (apply tools-cli/parse-opts args option-spec parse-args)]
    {:arguments arguments
     :errors    errors
     :options   (->> options
                     (remove (comp nil? val))
                     (into {}))}))

(defn- structured-flag? [arg]
  (#{"--edn" "--json" "--force"} arg))

(defn parse-in-order-with-structured-flags
  "Like parse-option-map with :in-order true, but --edn/--json may trail positional args."
  [args option-spec]
  (let [structured (into {}
                         (comp (filter structured-flag?)
                               (map (fn [f] [(keyword (subs f 2)) true])))
                         args)
        stripped   (remove structured-flag? args)
        parsed     (parse-option-map stripped option-spec :in-order true)]
    (update parsed :options merge structured)))

(defn pad-right [text width]
  (let [needed (- width (count text))]
    (if (pos? needed)
      (str text (apply str (repeat needed " ")))
      text)))

(defn- option-prefix [[short-name long-name & _]]
  (if short-name
    (str "  " short-name ", " long-name)
    (str "      " long-name)))

(defn option-help-section
  "Render an 'Options:' block from a tools.cli option-spec. Each row lines up
   at the description column using the widest option prefix as the anchor."
  [option-spec]
  (let [prefixes (mapv option-prefix option-spec)
        max-w    (apply max 0 (map count prefixes))]
    (str "Options:\n"
         (str/join "\n"
           (map (fn [prefix [_ _ desc]]
                  (str (pad-right prefix (+ max-w 2)) desc))
                prefixes
                option-spec)))))

(defn- titled-section [[title body]]
  (when body (str title ":\n" body)))

(defn render-help
  "Render a standard subcommand help page from parts. Keys:
     :command         command phrase, e.g. 'isaac config get'
     :params          positional-arg summary appended to command, e.g. '[config-path]'
     :description     paragraph body (no trailing newline)
     :arguments       optional pre-formatted Arguments body (no 'Arguments:' header)
     :option-spec     tools.cli option-spec (rendered as the Options block)
     :examples        optional pre-formatted Examples body
     :pre-sections    optional seq of [title body] pairs rendered BEFORE Options
     :post-sections   optional seq of [title body] pairs rendered AFTER Examples

   Order: Usage, description, pre-sections, Arguments, Options, Examples, post-sections."
  [{:keys [command params description arguments option-spec examples pre-sections post-sections]}]
  (let [usage-line (str "Usage: " command (when params (str " " params)))
        blocks     (concat
                     [usage-line
                      description]
                     (map titled-section pre-sections)
                     [(titled-section ["Arguments" arguments])
                      (when option-spec (option-help-section option-spec))
                      (titled-section ["Examples" examples])]
                     (map titled-section post-sections))]
    (str/join "\n\n" (remove nil? blocks))))

;; endregion ^^^^^ Option parsing ^^^^^

;; region ----- Paths -----

(defn resolve-root [{:keys [root]}]
  (or root
      (root/default-root)))

(defn- keyword-safe-segment? [s]
  (and (not (str/blank? s))
       (some? (re-matches #"[A-Za-z*+!_?-][A-Za-z0-9*+!_?-]*" s))))

(defn- segment->expr [first? seg]
  (cond
    (and first? (keyword-safe-segment? seg)) seg
    (keyword-safe-segment? seg)              (str "." seg)
    :else                                    (str "[\"" seg "\"]")))

(defn normalize-path
  "When path-str begins with '/', treat '/' as the only separator and '.' as a
   literal character inside each segment. Otherwise return path-str unchanged."
  [path-str]
  (if (and (string? path-str) (str/starts-with? path-str "/"))
    (let [segs (remove str/blank? (str/split (subs path-str 1) #"/"))]
      (apply str (map-indexed (fn [idx s] (segment->expr (zero? idx) s)) segs)))
    path-str))

(defn path-prefix [path-str]
  (when-not (or (nil? path-str) (str/blank? path-str))
    (vec (str/split path-str #"\."))))

;; endregion ^^^^^ Paths ^^^^^

;; region ----- Printing -----

(defn stdout-tty? []
  (or (color/force-color?)
      (and (color/console?)
           (not (instance? java.io.StringWriter *out*)))))

(defn print-lines! [lines]
  (doseq [line lines]
    (println line)))

(defn print-edn! [value]
  (println (edn-pretty/pretty value)))

(defn print-errors! [entries label]
  (binding [*out* *err*]
    (doseq [{:keys [bad-value file key valid-values value]} entries]
      (println (str label ": " key " - " value
                    (when file
                      (str " [file: " file "]"))
                    (when bad-value
                      (str " [bad value: " bad-value "]"))
                    (when (seq valid-values)
                      (str " [valid: " (str/join ", " valid-values) "]")))))))

(def ^:private shell-caveat
  "An unresolvable ${VAR} is reported from the CLI's own environment, which is
   not the one the service runs under — so the CLI says so and never refuses
   the write (isaac-rxun)."
  " (not set in this shell; the server's environment may differ)")

(defn- warning-line [{:keys [bad-value file key unresolved-ref valid-values value]}]
  (str key " - " value
       (when unresolved-ref shell-caveat)
       (when file
         (str " [file: " file "]"))
       (when bad-value
         (str " [bad value: " bad-value "]"))
       (when (seq valid-values)
         (str " [valid: " (str/join ", " valid-values) "]"))))

(defn print-warnings! [entries]
  (binding [*out* *err*]
    (doseq [entry entries]
      (println (str "warning: :" (warning-line entry))))))

(defn print-mutation-warnings! [entries]
  (when (seq entries)
    (println (str "Validation warnings (" (count entries) "):"))
    (doseq [entry entries]
      (println (warning-line entry)))))

(defn print-cli-errors! [errors]
  (binding [*out* *err*]
    (doseq [error errors]
      (println error)))
  1)

(defn print-cli-error! [message]
  (binding [*out* *err*]
    (println message))
  1)

;; endregion ^^^^^ Printing ^^^^^

;; region ----- Reveal / env substitution -----

(defn reveal-confirmed? []
  (binding [*out* *err*]
    (print "type REVEAL to confirm: ")
    (flush))
  (= "REVEAL" (some-> (binding [*in* (host/in)] (read-line)) str/trim)))

(defn print-reveal-refused! []
  (binding [*out* *err*]
    (println "Refusing to reveal config.")))

(defn- env-token [value]
  (when (and (string? value)
             (re-matches #"\$\{[^}]+\}" value))
    (second (re-matches #"\$\{([^}]+)\}" value))))

(defn redact-env-values [raw resolved]
  (cond
    (and (map? raw) (map? resolved))
    (into {} (map (fn [k] [k (redact-env-values (get raw k) (get resolved k))]) (set (concat (keys raw) (keys resolved)))))

    (and (sequential? raw) (sequential? resolved))
    (mapv redact-env-values raw resolved)

    :else
    (if-let [token (env-token raw)]
      (str "<" token ":" (if (= raw resolved) "UNRESOLVED" "redacted") ">")
      resolved)))

(defn resolve-env-values [value]
  (cond
    (map? value)        (into {} (map (fn [[k v]] [k (resolve-env-values v)]) value))
    (sequential? value) (mapv resolve-env-values value)
    :else               (if-let [token (env-token value)]
                          (or (env/env token) value)
                          value)))

;; endregion ^^^^^ Reveal / env substitution ^^^^^

;; region ----- Config access -----

(defn queryable-config [config]
  (walk/postwalk
    (fn [node]
      (if (map? node)
        (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v]) node))
        node))
    config))

(defn value-present? [value]
  (not (nil? value)))

(defn present-identifiers [value]
  (walk/postwalk
    (fn [node]
      (if (map? node)
        (cond-> node
          (string? (:berth node))    (assoc :berth (keyword (:berth node)))
          (string? (:crew node))     (assoc :crew (keyword (:crew node)))
          (string? (:foundry node))  (assoc :foundry (keyword (:foundry node)))
          (string? (:gauge node))    (assoc :gauge (keyword (:gauge node)))
          (string? (:model node))    (assoc :model (keyword (:model node)))
          (string? (:provider node)) (assoc :provider (keyword (:provider node))))
        node))
    value))

(defn- threaded-config [opts]
  (when-let [cfg (:config opts)]
    (when (seq cfg) cfg)))

(defn load-result [opts]
  (if-let [cfg (threaded-config opts)]
    (or (:load-result opts)
        {:config cfg :errors [] :warnings [] :sources []})
    (loader/load-config-result {:root (resolve-root opts)
                                :fs   (or (:fs opts) (nexus/get :fs) (fs/real-fs))})))

(defn schema-context
  "Resolved config plus module-index and composed root schema for CLI commands."
  [opts]
  (let [result (load-result opts)
        config (:config result)]
    {:config       config
     :module-index (schema-resolve/module-index-for-config config result)
     :root         (schema-resolve/root-schema-for config result)
     :result       result}))

(defn load-raw-result [opts]
  (loader/load-config-result {:root            (resolve-root opts)
                              :fs              (or (:fs opts) (nexus/get :fs) (fs/real-fs))
                              :substitute-env? false}))

(defn- source-path [root source]
  (let [path (if (str/starts-with? source "/") source (str root "/" source))]
    (if (and (str/ends-with? path "/") (> (count path) 1))
      (subs path 0 (dec (count path)))
      path)))

(defn- source-files
  "The files one source contributes. Since isaac-49zp an entity may be stored as
   `config/<key>/<id>/`, so a source can be a **directory** — which stands for
   the files inside it. Reading it as a file is what made `config get` throw on a
   tree `config validate` had just passed (isaac-63ei), and skipping it instead
   would silently un-redact every secret those files hold."
  [fs* path]
  (cond
    (fs/dir? fs* path)  (mapcat #(source-files fs* (str path "/" %))
                                (or (fs/children fs* path) []))
    (fs/file? fs* path) [path]
    :else               []))

(defn- source-env-tokens [fs* root source]
  (->> (source-files fs* (source-path root source))
       (mapcat #(re-seq #"\$\{([^}]+)\}" (or (fs/slurp fs* %) "")))
       (map second)))

(defn- threaded-env-redactions [opts result]
  (let [fs*  (or (:fs opts) (nexus/get :fs) (fs/real-fs))
        root (resolve-root opts)]
    (->> (:sources result)
         (mapcat #(source-env-tokens fs* root %))
         distinct
         sort
         (keep (fn [token]
                 (when-let [value (env/env token)]
                   (when-not (str/blank? value)
                     [value (str "<" token ":redacted>")])))))))

(defn- redact-threaded-config [opts result]
  (let [redactions (threaded-env-redactions opts result)]
    (update result :config
            #(walk/postwalk (fn [value]
                              (if (string? value)
                                (reduce (fn [text [secret marker]] (str/replace text secret marker))
                                        value
                                        redactions)
                                value))
                            %))))

(defn printable-config [opts reveal?]
  (if (and (not reveal?) (threaded-config opts))
    (redact-threaded-config opts (load-result opts))
    (let [raw      (load-raw-result opts)
          resolved (assoc raw :config (resolve-env-values (:config raw)))]
      (if reveal?
        resolved
        (assoc resolved :config (redact-env-values (:config raw) (:config resolved)))))))

;; endregion ^^^^^ Config access ^^^^^
