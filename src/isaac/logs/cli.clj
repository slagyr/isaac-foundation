(ns isaac.logs.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.common :as cli-common]
    [isaac.logs.streams :as streams]
    [isaac.log-viewer :as viewer]))

(def ^:private default-limit 20)

(def option-spec
  [[nil  "--file PATH" "Log file path (view a file directly, bypassing the stream registry)"]
   [nil  "--list" "List the registered log streams"]
   ["-f" "--follow" "Follow the file for new entries (default: read and exit)"]
   ["-n" "--limit N" (str "Show last N entries; 0 = all (default: " default-limit ")")
    :default default-limit
    :parse-fn #(Long/parseLong %)]
   [nil  "--no-color" "Disable color output"]
   [nil  "--zebra" "Enable alternating row background"]
   [nil  "--plain" "Raw passthrough — no parsing, color, or zebra"]
   ["-h" "--help" "Show help"]])

(defn- resolve-path [file root]
  (cond
    (nil? file)                 nil
    (str/starts-with? file "/") file
    (and root (seq root))       (str root "/" file)
    :else                       file))

(defn- tail-path! [path {:keys [follow limit no-color zebra plain]}]
  (viewer/tail! path
                {:color?  (not no-color)
                 :zebra?  (boolean zebra)
                 :follow? (boolean follow)
                 :plain?  (boolean plain)
                 :limit   limit}))

(defn- list-streams! [registry]
  (if (empty? registry)
    (println "No log streams are registered.")
    (do
      (println "Registered log streams:")
      (doseq [[id {:keys [file description]}] (sort-by (comp name key) registry)]
        (println (format "  %-12s %-22s %s" (name id) (or file "") (or description "")))))))

(defn run
  "View Isaac log streams. With --file, view that path directly. With a stream
   name, tail that stream's file. With --list or no name, list the registered
   streams — there is no default stream (the user picks from the list)."
  [{:keys [arguments file root] :as opts}]
  (let [stream-name (first arguments)
        registry    (streams/streams)]
    (cond
      file
      (do (tail-path! (resolve-path file root) opts) 0)

      stream-name
      (if-let [decl (get registry (keyword stream-name))]
        (do (tail-path! (resolve-path (:file decl) root) opts) 0)
        (do (println (str "Unknown log stream: " stream-name))
            (list-streams! registry)
            0))

      :else
      (do (list-streams! registry) 0))))

(defn run-fn [opts]
  (cli-common/standard-run-fn "logs"
                               #(tools-cli/parse-opts % option-spec)
                               run
                               opts))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :logs [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :logs [_id]
  option-spec)
