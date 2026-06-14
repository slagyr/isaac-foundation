(ns isaac.foundation-boundary-spec
  "Foundation boundary gate (isaac-youm): the permanent guard that the
   foundation file set — the namespaces that move to isaac-foundation at cut
   time — never requires a server-side namespace. Parses each foundation ns
   form and asserts every isaac.* require stays inside the set, and that none
   match a forbidden (server/session/llm/...) prefix. If this spec goes red,
   some namespace has leaked a server dependency into the foundation."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [speclj.core :refer :all]))

(def foundation-namespaces
  "The foundation file set. The bean's enumerated set plus the foundation
   config namespaces transitively required by isaac.config.loader
   (check-compose / schema-compose / validation), created by the config
   schema/check pre-work. Closed under isaac.* requires (asserted below)."
  '#{isaac.main isaac.cli.registry isaac.foundation.module isaac.module.protocol isaac.nexus
     isaac.fs isaac.logger isaac.config.root isaac.foundation.version isaac.reconfigurable
     isaac.naming isaac.scheduler.runtime
     isaac.spec-helper
     isaac.module.loader isaac.module.manifest
     isaac.scheduler.cron
     isaac.schema.dynamic isaac.schema.lexicon isaac.schema.meta
     isaac.schema.registered-in
     isaac.config.paths isaac.config.nav isaac.config.companion isaac.config.loader
     isaac.config.api isaac.config.berths isaac.config.schema-base
     isaac.config.check-compose isaac.config.schema-compose isaac.config.validation
     isaac.cli.api isaac.cli.args isaac.cli.color isaac.cli.table})

(def forbidden-prefixes
  ["isaac.server" "isaac.session" "isaac.llm" "isaac.comm" "isaac.bridge"
   "isaac.hail" "isaac.tool" "isaac.slash" "isaac.drive" "isaac.cron"
   "isaac.crew" "isaac.hooks" "isaac.prompt" "isaac.service" "isaac.charge"
   "isaac.api" "isaac.util"])

(defn- ns->file [ns-sym]
  (io/file "src" (str (-> (name ns-sym)
                          (str/replace "." "/")
                          (str/replace "-" "_"))
                      ".clj")))

(defn- read-ns-form [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (loop []
      (let [form (read {:eof ::eof} r)]
        (cond
          (= ::eof form)                         nil
          (and (seq? form) (= 'ns (first form))) form
          :else                                  (recur))))))

(defn- entry->nses
  "An entry in a :require clause is a symbol, a [ns ...] vector, or a prefix
   list (prefix [sub ...] sub ...). Returns the namespace symbols it names."
  [entry]
  (cond
    (symbol? entry) [entry]
    (vector? entry) [(first entry)]
    (seq? entry)    (let [prefix (first entry)]
                      (map (fn [sub]
                             (symbol (str prefix "." (if (sequential? sub) (first sub) sub))))
                           (rest entry)))
    :else           []))

(defn- isaac-requires [ns-sym]
  (->> (rest (read-ns-form (ns->file ns-sym)))
       (filter #(and (sequential? %) (= :require (first %))))
       (mapcat rest)
       (mapcat entry->nses)
       (filter #(str/starts-with? (name %) "isaac."))
       set))

(defn- forbidden? [ns-sym]
  (boolean (some (fn [p] (or (= (name ns-sym) p)
                             (str/starts-with? (name ns-sym) (str p "."))))
                 forbidden-prefixes)))

(describe "foundation boundary gate"

  (it "every foundation namespace has a source file"
    (should= []
             (vec (sort (remove #(.exists (ns->file %)) foundation-namespaces)))))

  (it "every isaac.* require of a foundation namespace stays inside the foundation set"
    (let [violations (into (sorted-map)
                           (for [ns-sym foundation-namespaces
                                 :let   [outside (sort (remove foundation-namespaces (isaac-requires ns-sym)))]
                                 :when  (seq outside)]
                             [ns-sym (vec outside)]))]
      (should= {} violations)))

  (it "no foundation namespace requires a server-side (forbidden) namespace"
    (let [violations (into (sorted-map)
                           (for [ns-sym foundation-namespaces
                                 :let   [hits (sort (filter forbidden? (isaac-requires ns-sym)))]
                                 :when  (seq hits)]
                             [ns-sym (vec hits)]))]
      (should= {} violations))))
