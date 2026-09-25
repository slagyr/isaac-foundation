(ns isaac.config.cli.mutate-common
  "Shared helpers for 'config set' and 'config unset'."
  (:require
    [c3kit.apron.schema :as schema]
    [c3kit.apron.schema.path :as path]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli.host :as host]
    [isaac.config.cli.common :as common]
    [isaac.config.cli.inspect :as inspect]
    [isaac.config.loader :as loader]
    [isaac.config.mutate :as mutate]
    [isaac.config.nav :as nav]
    [isaac.config.schema.resolve :as schema-resolve]
    [isaac.logger :as log]
    [isaac.schema.lexicon :as lexicon]))

(defn- root-schema [opts]
  (:root (common/schema-context opts)))

(defn target-spec-for [opts path-str]
  (schema-resolve/schema-for-data-path (root-schema opts) path-str))

(defn- guessed-value [raw-value]
  (cond
    (re-matches #"-?\d+" raw-value) (parse-long raw-value)
    (#{"false" "nil" "true"} raw-value) (edn/read-string raw-value)
    (str/starts-with? raw-value ":") (edn/read-string raw-value)
    :else raw-value))

(defn- cli-value [spec raw-value]
  (case (:type spec)
    :keyword (keyword (str/replace-first raw-value #"^:" ""))
    :id      (keyword (str/replace-first raw-value #"^:" ""))
    raw-value))

(defn- conform-cli-value [spec raw-value]
  (let [value (cli-value spec raw-value)]
    (if (= :id (:type spec))
      {:value value}
      (let [result (lexicon/conform spec value)]
        (if (schema/error? result)
          {:error (schema/error-message result)}
          {:value result})))))

(defn- set-member-spec [spec]
  (or (:member-spec spec)
      {:type (or (:member-type spec) :keyword)}))

(defn parse-set-value [spec raw-value]
  (try
    (cond
      (nil? spec) {:value (guessed-value raw-value)}
      (:set-type? spec) (let [values (map #(conform-cli-value (set-member-spec spec) %)
                                         (str/split raw-value #","))]
                          (if-let [error (:error (first (filter :error values)))]
                            {:error error}
                            {:value (set (map :value values))}))
      :else (conform-cli-value spec raw-value))
    (catch Exception e
      {:error (or (.getMessage e) "invalid value")})))

(defn read-stdin-value []
  (try
    {:value (edn/read-string (slurp (host/in)))}
    (catch Exception _
      {:error "stdin must contain valid EDN"})))

(defn- format-errors [errors]
  (str/join "; " (map (fn [{:keys [key value]}] (str key " - " value)) errors)))

(defn log-mutation! [level event file path-str & kvs]
  (apply log/log* level event file 0 :path path-str kvs))

(defn- print-status-error! [status path-str]
  (binding [*out* *err*]
    (println (case status
               :missing-path      "missing path"
               :missing-entity-id "missing entity id"
               :invalid-path      (str "invalid path: " path-str)
               :not-found         (str "not found: " path-str)
               (str "config error: " (name status))))))

(defn target-root+path! [opts path-arg]
  (if (str/blank? path-arg)
    (do
      (print-status-error! :missing-path nil)
      nil)
    {:root (common/resolve-root opts)
     :path-str  (common/normalize-path path-arg)}))

(defn- print-confirmation! [operation path-str file value options]
  (when-not (inspect/structured-requested? options)
    (println (case operation
               :set   (if (and (map? options) (:member options))
                        (str "set " (or (:parent options) path-str) " += " (pr-str value) " (" file ")")
                        (str "set " path-str " = " (pr-str value) " (" file ")"))
               :unset (if (and (map? options) (:member options))
                        (str "unset " (or (:parent options) path-str) " -= " (pr-str (:member options)) " (" file ")")
                        (str "unset " path-str " (" file ")"))))))

(defn- parent-path [path-str]
  (str/join "." (butlast (str/split path-str #"\."))))

(defn- warning-scope [path-str]
  (let [[kind id] (str/split path-str #"\." 3)]
    (str kind "." id ".")))

(defn- scoped-warnings [path-str warnings]
  (group-by #(str/starts-with? (:key %) (warning-scope path-str)) warnings))

(defn- print-other-warning-count! [warnings]
  (when (seq warnings)
    (println (str (count warnings) " other validation warning"
                  (when-not (= 1 (count warnings)) "s")
                  " — run: isaac config validate"))))

(defn print-mutation-warnings! [path-str warnings]
  (let [{local true other false} (scoped-warnings path-str warnings)]
    (common/print-mutation-warnings! local)
    (print-other-warning-count! other)))

(defn handle-mutate-result!
  ([operation path-str result value]
   (handle-mutate-result! operation path-str result value nil))
  ([operation path-str result value options]
   (case (:status result)
     :ok
     (do
       (let [file (or (:file result) "config")]
         (case operation
           :set   (log-mutation! :info :config/set   file path-str :value value)
           :unset (log-mutation! :info :config/unset file path-str))
         (print-confirmation! operation path-str file value options)
         (when-not (inspect/structured-requested? options)
           (print-mutation-warnings! path-str (:warnings result))))
       (when (inspect/structured-requested? options)
         (let [{:keys [edn json]} options]
           (inspect/print-structured! edn json (inspect/mutation-result-record path-str result))))
       0)

     :invalid
     (do
       (common/print-errors! (:errors result) "error")
       (binding [*out* *err*]
         (print-mutation-warnings! path-str (:warnings result)))
       (when (= :set operation)
         (log-mutation! :error :config/set-failed "config" path-str :error (format-errors (:errors result)))
         (when-not (:force options)
           (binding [*out* *err*]
             (println (str "(use --force to write anyway, or set the whole map: echo '{…}' | isaac config set "
                           (parent-path path-str) " -)")))))
       1)

     :invalid-config
     (do
       (common/print-errors! (:errors result) "error")
       (binding [*out* *err*]
         (print-mutation-warnings! path-str (:warnings result)))
       1)

     (do
       (print-status-error! (:status result) path-str)
       1))))

;; region ----- Set-typed helpers -----

(defn- current-config-value [root path-str]
  (let [result (loader/load-config-result {:root root})
        config (common/queryable-config (:config result))]
    (path/data-at config path-str)))

(defn- set-member! [root path-str member options]
  (let [pp          (parent-path path-str)
        current-set (or (current-config-value root pp) #{})
        new-set     (conj current-set member)
        result      (mutate/set-config root pp new-set :skip-ref-validation? true :force? (boolean (:force options)))
        options     (assoc (or options {}) :member member :parent pp)]
    (handle-mutate-result! :set path-str result member options)))

(defn- member-keyword [raw-member]
  (keyword (str/replace-first raw-member #"^:" "")))

(defn- unset-member! [root path-str member options]
  (let [pp          (parent-path path-str)
        current-set (or (current-config-value root pp) #{})
        new-set     (disj current-set member)
        result      (if (empty? new-set)
                      (mutate/unset-config root pp :force? (boolean (:force options)))
                      (mutate/set-config root pp new-set :skip-ref-validation? true :force? (boolean (:force options))))
        options     (assoc (or options {}) :member member :parent pp)]
    (handle-mutate-result! :unset path-str result nil options)))

;; endregion ^^^^^ Set-typed helpers ^^^^^

(defn set-config! [opts path-str raw-value options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [root           (common/resolve-root opts)
          schema-context (common/schema-context opts)
          root-schema    (:root schema-context)
          ;; A path the composed schema doesn't recognize (a typo'd or
          ;; undeclared segment under a schema'd map) is not refused here —
          ;; mutate/set-config makes that call itself (isaac-a5dx), in the
          ;; same fun8-shaped :invalid result, so `--force` can bypass it.
          ;; This walk is only for set-typed member detection.
          path-result (nav/path->spec root-schema path-str)]
      (if-let [member (:member path-result)]
        (set-member! root path-str member options)
        (if (nil? raw-value)
          (common/print-cli-error! "missing value")
          (let [value-result (if (= "-" raw-value)
                               (read-stdin-value)
                               (parse-set-value (target-spec-for opts path-str) raw-value))]
            (if (:error value-result)
              (do
                (binding [*out* *err*]
                  (println (str "error: " path-str " - " (:error value-result)))
                  (print-mutation-warnings! path-str (get-in schema-context [:result :warnings])))
                (log-mutation! :error :config/set-failed "config" path-str :error (:error value-result))
                1)
              (let [value  (:value value-result)
                    result (mutate/set-config root path-str value :skip-ref-validation? true :force? (boolean (:force options)))]
                (handle-mutate-result! :set path-str result value options)))))))))

(defn unset-config!
  ([opts path-str options]
   (unset-config! opts path-str options nil))
  ([opts path-str options raw-member]
   (if-let [format-error (inspect/structured-format-conflict? options)]
     format-error
     (let [root        (common/resolve-root opts)
           root-schema (root-schema opts)
           path-result (nav/path->spec root-schema path-str)]
       (cond
         (and raw-member (:set-type? (:spec path-result)))
         (unset-member! root (str path-str "." raw-member) (member-keyword raw-member) options)

         raw-member
         (common/print-cli-error! (str path-str " takes no value"))

         (:member path-result)
         (unset-member! root path-str (:member path-result) options)

         :else
         (handle-mutate-result! :unset path-str (mutate/unset-config root path-str :force? (boolean (:force options))) nil options))))))
