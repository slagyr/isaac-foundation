(ns isaac.cli.help
  "Top-level `isaac help` command: command help first, then topics."
  (:require
    [clojure.string :as str]
    [isaac.cli.api :as api]
    [isaac.cli.registry :as registry]
    [isaac.config.root :as root]))

(def ^:private topics
  "Name → help text (or 0-arg fn producing text). Lookup: command first, then topic."
  {"root" (fn []
            (str "Root resolution (first hit wins):\n"
                 (str/join "\n" root/root-lookup-precedence)))})

(defn topic-names []
  (sort (keys topics)))

(defn topic-help [name]
  (when-let [text (get topics name)]
    (if (fn? text) (text) text)))

(defn help-text []
  (str "Usage: isaac help [command-or-topic]\n\n"
       "Show help for a command or topic.\n\n"
       "With no argument, prints the top-level command menu.\n"
       "With a command name, prints that command's help page.\n"
       "With a topic name, prints the topic.\n\n"
       "Topics:\n"
       (str/join "\n" (map #(str "  " %) (topic-names)))))

(defn run
  "Run help. Target is the first element of :_raw-args."
  [{:keys [_raw-args]}]
  (let [target (first _raw-args)]
    (cond
      (or (nil? target) (str/blank? target))
      (do (println (registry/usage-text)) 0)

      :else
      (if-let [command (registry/get-command target)]
        (do (println (registry/command-help command)) 0)
        (if-let [text (topic-help target)]
          (do (println text) 0)
          (do (println (str "Unknown command or topic: " target)) 1))))))

(defmethod api/run :help [_id opts]
  (run opts))

(defmethod api/help :help [_id]
  (help-text))
