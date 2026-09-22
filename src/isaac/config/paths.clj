(ns isaac.config.paths
  "Filesystem layout knowledge for Isaac config. Pure path construction — no
   I/O. The root directory is the canonical home for config and runtime data:
   config lives at <root>/config and runtime data (crew, sessions, memory)
   under <root>. In production the root defaults to ~/.isaac, but any
   directory is valid (see isaac.config.root/default-root).
   Also: path-segment parsing that keeps namespace-qualified segments
   (`gchat/allow-from`) whole (isaac-cgxa)."
  (:require
    [clojure.string :as str]))

(def ^:private entity-file-pattern #"[^/]+/[^/]+\.edn")
(def ^:private markdown-file-pattern #"(berths|crew|cron|hooks)/[^/]+\.md")

(def root-filename "isaac.edn")

(defn config-root [root]
  (str root "/config"))

(defn config-path [root relative]
  (str (config-root root) "/" relative))

(defn root-config-file [root]
  (config-path root root-filename))

(defn entity-relative [kind id]
  (str (name kind) "/" id ".edn"))

(defn soul-relative [id]
  (str "crew/" id ".md"))

(defn ledger-relative [id]
  (str "berths/" id ".md"))

(defn cron-relative [id]
  (str "cron/" id ".md"))

(defn hook-relative [id]
  (str "hooks/" id ".md"))

(defn config-relative [root path]
  (let [root-prefix (str (config-root root) "/")]
    (when (str/starts-with? path root-prefix)
      (subs path (count root-prefix)))))

(defn config-file? [relative-path]
  (and (string? relative-path)
       (or (= root-filename relative-path)
           (boolean (re-matches entity-file-pattern relative-path))
           (boolean (re-matches markdown-file-pattern relative-path)))))

;; region ----- Path segments -----

(def ^:private segment-pattern
  "One `.`-separated segment of a config path, as a string: an optional
   namespace (`gchat/`), a name (`allow-from`), and the c3kit bracket forms
   (`[0]`, `[\"key\"]`, `[:kw]`) which are kept verbatim for the caller's
   grammar to classify. A `/` inside a segment is part of the name — the
   namespaced-keyword form — never a separator (isaac-cgxa)."
  #"[^\.\[\]]+|\[[^\]]*\]")

(defn split-path-segments
  "Split a config path into its `.`-separated segment strings, keeping
   namespace-qualified segments (`gchat/allow-from`) whole. c3kit's own
   tokenizer drops the `/` (its identifier regex excludes it), which turned
   one namespaced key into two nested maps — this is the isaac-side fix."
  [path-str]
  (vec (re-seq segment-pattern (str path-str))))

(defn parse-path-segments
  "Parse a config path into c3kit `path/parse`-shaped segments
   (`[:key :kw]`, `[:index n]`, `[:str s]`) while keeping a `/` inside a
   segment part of the keyword: `comms.gchat.gchat/allow-from` parses to
   `[[:key :comms] [:key :gchat] [:key :gchat/allow-from]]` (isaac-cgxa).
   Wildcards (`*`, `[*]`) throw exactly as c3kit's classifier does, so
   grammar-refusal callers see the same failure; unclassifiable segments
   are dropped, matching `path/parse`'s leniency."
  [path-str]
  (->> (split-path-segments path-str)
       (map (fn [token]
              (cond
                (or (= "*" token) (= "[*]" token))
                (throw (ex-info "wildcard (* or [*]) is not supported in config paths"
                                {:token token}))

                (str/starts-with? token "[")
                (let [inside (subs token 1 (dec (count token)))]
                  (cond
                    (re-matches #"-?\d+" inside)                [:index (parse-long inside)]
                    (and (str/starts-with? inside "\"")
                         (str/ends-with?   inside "\""))        [:str (subs inside 1 (dec (count inside)))]
                    (str/starts-with? inside ":")               [:key (keyword (subs inside 1))]
                    :else                                        nil))

                (re-matches #"[A-Za-z+!?_\-/][A-Za-z0-9+!?_\-/]*" token)
                [:key (keyword token)]

                :else nil)))
       (keep identity)
       (vec)))

;; endregion ^^^^^ Path segments ^^^^^
