(ns isaac.foundation-module-boundary-spec
  "Module→foundation-internal boundary gate: every production module namespace
   under modules/ may require documented foundation components or agent surfaces
   (isaac.comm, isaac.llm, isaac.slash, isaac.api). Foundation internals (loader,
   berths, cli.registry, compose pipeline, ...) are forbidden. Parses :require
   forms statically — no runtime requiring-resolve."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [speclj.core :refer [describe it should=]]))

(def tier-1-allowed
  "isaac.* namespaces module authors may require directly — keep in sync with
   FOUNDATION.md."
  '#{isaac.cli.api
     isaac.fs
     isaac.logger
     isaac.config.paths
     isaac.config.root
     isaac.schema.lexicon
     isaac.schema.meta
     isaac.reconfigurable
     isaac.module.protocol
     isaac.nexus})

(def agent-allowed-prefixes
  "Agent/platform module namespaces — allowed for modules that extend comm, llm,
   slash, or the agent API."
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
  "Returns isaac.* requires that violate the module boundary."
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

(describe "module→foundation boundary"

  (it "every module namespace under modules/ stays on allowed components or agent surfaces"
    (should= {} (module-violations)))

  (it "flags deliberate foundation-internal requires"
    (should= #{'isaac.config.loader 'isaac.cli.registry}
             (disallowed-module-requires '#{isaac.module.protocol
                                           isaac.config.loader
                                           isaac.cli.registry
                                           isaac.cli.api})))

  (it "allows documented foundation components and agent namespaces"
    (should= #{}
             (disallowed-module-requires '#{isaac.nexus
                                           isaac.module.protocol
                                           isaac.cli.api
                                           isaac.logger
                                           isaac.reconfigurable
                                           isaac.api
                                           isaac.comm.protocol
                                           isaac.llm.api.protocol}))))