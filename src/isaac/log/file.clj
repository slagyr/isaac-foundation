(ns isaac.log.file
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(def ^:dynamic *now* nil)

(def default-max-bytes (* 100 1024 1024))
(def default-max-days 30)

(def ^:private archive-re #"^server-(\d{8})\.log(?:\.(\d+))?$")

(defonce ^:private sink-state
  (atom {:root     nil
         :settings nil}))

(defn instant-now []
  (or *now* (java.time.Instant/now)))

(defn server-log-path [root]
  (str root "/logs/server.log"))

(defn cli-log-path [root]
  (str root "/logs/cli.log"))

(def cli-log-rel-path "logs/cli.log")

(defn logs-dir [root]
  (str root "/logs"))

(defn- zone-id [tz]
  (try (java.time.ZoneId/of (or tz "UTC"))
       (catch Exception _ (java.time.ZoneId/of "UTC"))))

(defn- date-stamp [instant tz]
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd")
           (.atZone instant (zone-id tz))))

(defn- parse-instant [ts]
  (try (java.time.Instant/parse ts)
       (catch Exception _ nil)))

(defn- entry-day [entry tz]
  (when-let [inst (parse-instant (:ts entry))]
    (date-stamp inst tz)))

(defn- read-entries [fs* path]
  (when (fs/exists? fs* path)
    (->> (str/split-lines (fs/slurp fs* path))
         (remove str/blank?)
         (mapv #(try (edn/read-string %) (catch Exception _ nil)))
         (remove nil?))))

(defn- file-day [fs* path tz]
  (some #(entry-day % tz) (read-entries fs* path)))

(defn- byte-size [fs* path]
  (if (fs/exists? fs* path)
    (count (fs/slurp fs* path))
    0))

(defn- archive-filename [date suffix]
  (str "server-" date ".log" (when suffix (str "." suffix))))

(defn- list-archives [fs* dir]
  (when-let [children (fs/children fs* dir)]
    (->> children
         (keep (fn [name]
                 (when-let [[_ date suffix] (re-matches archive-re name)]
                   {:name   name
                    :path   (str dir "/" name)
                    :date   date
                    :suffix (when suffix (parse-long suffix))})))
         vec)))

(defn- next-same-day-suffix [archives date]
  (let [same-day (filter #(= date (:date %)) archives)]
    (when (seq same-day)
      (inc (apply max (map #(or (:suffix %) 0) same-day))))))

(defn- unique-archive-path [fs* dir date]
  (let [base     (archive-filename date nil)
        base-path (str dir "/" base)]
    (if-not (fs/exists? fs* base-path)
      base-path
      (let [archives (list-archives fs* dir)
            suffix   (or (next-same-day-suffix archives date) 0)]
        (str dir "/" (archive-filename date suffix))))))

(defn- move-file! [fs* source dest]
  (fs/mkdirs fs* (fs/parent dest))
  (fs/move fs* source dest))

(defn- prune-retention! [fs* dir {:keys [max-days tz] :as settings}]
  (let [cutoff-date (date-stamp (.minus (instant-now) (java.time.Period/ofDays (or max-days default-max-days)))
                                (zone-id tz))]
    (doseq [{:keys [path date]} (list-archives fs* dir)]
      (when (< (compare date cutoff-date) 0)
        (fs/delete fs* path)))))

(defn- rotate-active! [fs* root settings]
  (let [active (server-log-path root)
        dir    (logs-dir root)]
    (when (and (fs/exists? fs* active)
               (pos? (byte-size fs* active)))
      (let [tz          (:tz settings)
            today       (date-stamp (instant-now) tz)
            active-day  (or (file-day fs* active tz) today)
            oversize?   (> (byte-size fs* active) (:max-bytes settings))
            new-day?    (not= active-day today)
            archive-day (if new-day? active-day today)]
        (when (or new-day? oversize?)
          (let [dest (unique-archive-path fs* dir archive-day)]
            (move-file! fs* active dest)
            (fs/spit fs* active "")))))))

(defn- resolve-settings [config]
  (let [logging (or (:logging config) {})]
    {:max-bytes (or (:max-bytes logging) default-max-bytes)
     :max-days  (or (:max-days logging) default-max-days)
     :tz        (or (:tz config) "UTC")}))

(defn prepare-active-log!
  "Ensures logs/ exists, rotates the active file when day or size thresholds
   are exceeded, and prunes archives past retention."
  ([root config]
   (prepare-active-log! (or (nexus/get :fs) (fs/real-fs)) root config))
  ([fs* root config]
   (let [settings (resolve-settings config)
         dir      (logs-dir root)]
     (fs/mkdirs fs* dir)
     (rotate-active! fs* root settings)
     (prune-retention! fs* dir settings))))

(defn configure-server-sink!
  "Binds the process-wide server file sink at <root>/logs/server.log."
  [root config]
  (swap! sink-state assoc :root root :settings (resolve-settings config) :cli-path nil)
  (prepare-active-log! root config))

(defn configure-cli-sink!
  "Opt-in CLI file sink; no rotation."
  [root rel-path]
  (when rel-path
    (let [fs*  (or (nexus/get :fs) (fs/real-fs))
          path (if (str/starts-with? rel-path "/")
                 rel-path
                 (str root "/" rel-path))]
      (fs/mkdirs fs* (fs/parent path))
      (swap! sink-state assoc :root nil :settings nil :cli-path path)
      path)))

(defn clear-sink-config! []
  (reset! sink-state {:root nil :settings nil}))

(defn active-log-path []
  (or (:cli-path @sink-state)
      (when-let [root (:root @sink-state)]
        (server-log-path root))))

(defn server-sink? []
  (some? (:root @sink-state)))

(defn write-entry!
  "Appends one EDN log line, rotating first when a server sink is active."
  [entry]
  (when-let [path (active-log-path)]
    (let [fs* (or (nexus/get :fs) (fs/real-fs))]
      (when-let [root (:root @sink-state)]
        (let [{:keys [max-bytes max-days tz]} (:settings @sink-state)]
          (prepare-active-log! fs* root {:logging {:max-bytes max-bytes
                                                   :max-days  max-days}
                                         :tz      tz})))
      (when-let [parent (fs/parent path)]
        (fs/mkdirs fs* parent))
      (fs/spit fs* path (str (pr-str entry) "\n") :append true))))