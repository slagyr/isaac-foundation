(ns isaac.config.cli.mutate-common
  "Shared helpers for 'config set' and 'config unset'."
  (:require
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
    [isaac.logger :as log]))

(defn- root-schema [opts]
  (:root (common/schema-context opts)))

(defn target-spec-for [opts path-str]
  (schema-resolve/schema-for-data-path (root-schema opts) path-str))

(defn parse-set-value [spec raw-value]
  (cond
    (re-matches #"-?\d+" raw-value)
    (parse-long raw-value)

    (#{"false" "nil" "true"} raw-value)
    (edn/read-string raw-value)

    (str/starts-with? raw-value ":")
    (edn/read-string raw-value)

    (and spec (= :id (:type spec)) (re-matches #"[A-Za-z_][A-Za-z0-9_-]*" raw-value))
    (keyword raw-value)

    :else
    raw-value))

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

(defn handle-mutate-result!
  ([operation path-str result value]
   (handle-mutate-result! operation path-str result value nil))
  ([operation path-str result value options]
   (common/print-warnings! (:warnings result))
   (case (:status result)
     :ok
     (do
       (let [file (or (:file result) "config")]
         (case operation
           :set   (log-mutation! :info :config/set   file path-str :value value)
           :unset (log-mutation! :info :config/unset file path-str))
         (print-confirmation! operation path-str file value options)
         (when (seq (:warnings result))
           (println (str "wrote " path-str " to " file " with "
                         (count (:warnings result))
                         " validation error(s) outstanding — run: isaac config validate"))))
       (when (inspect/structured-requested? options)
         (let [{:keys [edn json]} options]
           (inspect/print-structured! edn json (inspect/mutation-result-record path-str result))))
       0)

     :invalid
     (do
       (common/print-errors! (:errors result) "error")
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
    (let [root        (common/resolve-root opts)
          root-schema (root-schema opts)
          path-result (nav/path->spec root-schema path-str)]
      (if-not (:ok? path-result)
        (do
          (binding [*out* *err*]
            (println (:error path-result)))
          (log-mutation! :error :config/set-failed "config" path-str :error (:error path-result))
          1)
        (if-let [member (:member path-result)]
          (set-member! root path-str member options)
          (if (nil? raw-value)
            (common/print-cli-error! "missing value")
            (let [value-result (if (= "-" raw-value)
                                 (read-stdin-value)
                                 {:value (parse-set-value (target-spec-for opts path-str) raw-value)})]
              (if (:error value-result)
                (do
                  (binding [*out* *err*]
                    (println (:error value-result)))
                  (log-mutation! :error :config/set-failed "config" path-str :error (:error value-result))
                  1)
                (let [value  (:value value-result)
                      result (mutate/set-config root path-str value :skip-ref-validation? true :force? (boolean (:force options)))]
                  (handle-mutate-result! :set path-str result value options))))))))))

(defn unset-config! [opts path-str options]
  (if-let [format-error (inspect/structured-format-conflict? options)]
    format-error
    (let [root        (common/resolve-root opts)
          root-schema (root-schema opts)
          path-result (nav/path->spec root-schema path-str)]
      (if-let [member (:member path-result)]
        (unset-member! root path-str member options)
        (handle-mutate-result! :unset path-str (mutate/unset-config root path-str :force? (boolean (:force options))) nil options)))))
