(ns isaac.logger
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

;; region ----- Configuration -----

(def ^:private levels {:report 0 :error 1 :warn 2 :info 3 :debug 4})

(defonce ^:private state
         (atom {:level    :debug
                :output   :file
                :log-file "/tmp/isaac.log"
                :entries  []}))

(defn level [] (get @state :level :debug))

(defn level-rank
  ([] (level-rank (level)))
  ([level] (get levels level 4)))

(defn set-level! [level]
  (swap! state assoc :level level))

(defn log-file []
  (:log-file @state))

(defn set-log-file! [path]
  (swap! state assoc :log-file path))

(defn set-output! [output]
  (swap! state assoc :output output))

(defn get-entries []
  (:entries @state))

(defn clear-entries! []
  (swap! state assoc :entries []))

;; endregion ^^^^^ Configuration ^^^^^

;; region ----- Core -----

(defn enabled? [level]
  (<= (level-rank level) (level-rank)))

(defn- iso-now []
  (str (java.time.Instant/now)))

(defn- normalize-file-path [file]
  (let [workspace  (System/getProperty "user.dir")
        normalized (str/replace file "\\" "/")
        workspace* (str/replace workspace "\\" "/")
        relative   (if (str/starts-with? normalized (str workspace* "/"))
                     (subs normalized (inc (count workspace*)))
                     normalized)]
    (or (when (re-matches #"(src|spec|features|test)/.*" relative)
          relative)
        (some (fn [dir]
                (let [candidate (str dir "/" relative)]
                  (when (.exists (io/file workspace candidate))
                    candidate)))
              ["src" "spec" "features" "test"])
        relative)))

(defn normalize-context [kvs]
  (cond
    (empty? kvs) {}
    (and (= 1 (count kvs)) (map? (first kvs))) (first kvs)
    :else (apply hash-map kvs)))

(defn- build-entry [level event context file line]
  (let [ts    (iso-now)
        extra (dissoc context :event)]
    (apply array-map
           (concat [:ts ts :level level :event event]
                   (apply concat extra)
                   [:file (normalize-file-path file) :line line]))))

(defn- save-entry [entry]
  (case (:output @state)
    :memory (swap! state update :entries conj entry)
    (let [fs* (or (nexus/get :fs) (fs/real-fs))]
      (fs/spit fs* (:log-file @state) (str (pr-str entry) "\n") :append true))))

(defn log* [level event file line & kvs]
  (when (enabled? level)
    (let [context (normalize-context kvs)
          entry   (build-entry level event context file line)]
      (save-entry entry))))

(def captured-logs (atom nil))
(defmacro capture-logs [& body]
  `(let [original-level# (level)]
     (reset! captured-logs [])
     (try
       (set-level! :debug)
       (with-redefs [save-entry (fn [entry#] (swap! captured-logs conj entry#))]
         ~@body)
       (finally
         (set-level! original-level#)))))

;; endregion ^^^^^ Core ^^^^^

;; region ----- Macros -----

(defmacro log [level event & kvs]
  `(log* ~level ~event ~*file* ~(:line (meta &form)) ~@kvs))

(defmacro error [event & kvs]
  `(log* :error ~event ~*file* ~(:line (meta &form)) ~@kvs))

(defmacro warn [event & kvs]
  `(log* :warn ~event ~*file* ~(:line (meta &form)) ~@kvs))

(defmacro report [event & kvs]
  `(log* :report ~event ~*file* ~(:line (meta &form)) ~@kvs))

(defmacro info [event & kvs]
  `(log* :info ~event ~*file* ~(:line (meta &form)) ~@kvs))

(defmacro debug [event & kvs]
  `(log* :debug ~event ~*file* ~(:line (meta &form)) ~@kvs))

(defn ex-context
  "Build a context map from an exception merged with additional kvs.
   kvs can be a single map or key-value pairs."
  [e & kvs]
  (merge {:ex-class      (.getSimpleName (class e))
          :error-message (.getMessage e)}
         (normalize-context kvs)))

(defmacro ex
  "Log an exception at :error level. Merges :ex-class and :error-message
   into the context. Usage:
     (log/ex :event/name e :key val ...)
     (log/ex :event/name e {:key val ...})"
  [event e & kvs]
  `(log* :error ~event ~*file* ~(:line (meta &form))
         (ex-context ~e ~@kvs)))

;; endregion ^^^^^ Macros ^^^^^
