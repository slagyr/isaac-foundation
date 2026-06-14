(ns isaac.foundation-module-boundary-spec
  "Module→foundation-internal boundary gate (isaac-fkxv step 9): every
   production module namespace under modules/ may require isaac.* only from
   Tier 1 (isaac.foundation facade + documented carve-outs) or from agent
   surfaces (isaac.comm, isaac.llm, isaac.slash, isaac.api). Foundation
   internals (loader, berths, cli.registry, compose pipeline, ...) are
   forbidden. Parses :require forms statically — no runtime requiring-resolve."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [speclj.core :refer [describe it should=]]))

(def tier-1-direct-imports
  "Documented carve-outs — keep in sync with FOUNDATION.md and foundation_spec."
  '#{isaac.cli.api
     isaac.fs
     isaac.logger
     isaac.config.paths
     isaac.config.root
     isaac.schema.lexicon
     isaac.schema.meta
     isaac.reconfigurable})

(def tier-1-allowed
  "isaac.* namespaces module authors may require (facade + carve-outs + the
   small protocol/nexus surfaces re-exported by isaac.foundation)."
  (conj tier-1-direct-imports
        'isaac.foundation
        'isaac.module.protocol
        'isaac.nexus))

(def agent-allowed-prefixes
  "Agent/platform module namespaces — not foundation Tier 1, but allowed for
   modules that extend comm, llm, slash, or the agent API."
  ["isaac.comm" "isaac.llm" "isaac.slash" "isaac.api"])

(defn- read-ns-form [file]
  (when (.exists file)
    (with-open [r (java.io.PushbackReader. (io/reader file))]
      (loop []
        (let [form (read {:eof ::eof} r)]
          (cond
            (= ::eof form)                         nil
            (and (seq? form) (= 'ns (first form))) form
            :else                                  (recur)))))))

(defn- entry->nses [entry]
  (cond
    (symbol? entry) [entry]
    (vector? entry) [(first entry)]
    (seq? entry)    (let [prefix (first entry)]
                      (map (fn [sub]
                             (symbol (str prefix "." (if (sequential? sub) (first sub) sub))))
                           (rest entry)))
    :else           []))

(defn- isaac-requires-of-file [file]
  (->> (rest (read-ns-form file))
       (filter #(and (sequential? %) (= :require (first %))))
       (mapcat rest)
       (mapcat entry->nses)
       (filter #(str/starts-with? (name %) "isaac."))
       set))

(defn- agent-allowed? [ns-sym]
  (boolean (some (fn [p]
                   (or (= (name ns-sym) p)
                       (str/starts-with? (name ns-sym) (str p "."))))
                 agent-allowed-prefixes)))

(defn allowed-module-isaac-ns?
  [ns-sym]
  (or (tier-1-allowed ns-sym)
      (agent-allowed? ns-sym)))

(defn disallowed-module-requires
  "Returns isaac.* requires that violate the module→Tier-1 boundary."
  [requires]
  (set (remove allowed-module-isaac-ns? requires)))

(defn- module-src-files []
  (->> (file-seq (io/file "modules"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (filter #(str/includes? (.getPath %) "/src/"))
       vec))

(defn- module-namespace->file []
  (into {}
        (keep (fn [file]
                (when-let [ns-form (read-ns-form file)]
                  [(second ns-form) file]))
              (module-src-files))))

(defn- module-violations []
  (into (sorted-map)
        (for [[ns-sym file] (module-namespace->file)
              :let          [bad (sort (disallowed-module-requires (isaac-requires-of-file file)))]
              :when         (seq bad)]
          [ns-sym (vec bad)])))

(describe "module→foundation Tier-1 boundary"

  (it "every module namespace under modules/ stays on Tier 1 or agent surfaces"
    (should= {} (module-violations)))

  (it "flags deliberate foundation-internal requires"
    (should= #{'isaac.config.loader 'isaac.cli.registry}
             (disallowed-module-requires '#{isaac.foundation
                                           isaac.config.loader
                                           isaac.cli.registry
                                           isaac.cli.api})))

  (it "allows Tier-1 facade, carve-outs, and agent namespaces"
    (should= #{}
             (disallowed-module-requires '#{isaac.foundation
                                           isaac.cli.api
                                           isaac.logger
                                           isaac.reconfigurable
                                           isaac.api
                                           isaac.comm.protocol
                                           isaac.llm.api.protocol}))))