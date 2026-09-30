(ns isaac.foundation.foundation-boundary-spec
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
   config namespaces transitively required by isaac.foundation.config.loader
   (check-compose / schema-compose / validation), created by the config
   schema/check pre-work. Closed under isaac.* requires (asserted below)."
  '#{isaac.foundation.main isaac.foundation.startup.cache isaac.foundation.startup.classpath-cache isaac.foundation.cli.registry isaac.foundation.module isaac.foundation.module.protocol isaac.foundation.nexus
     isaac.foundation.fs isaac.foundation.logger isaac.foundation.log.file isaac.foundation.log.output isaac.foundation.config.root isaac.foundation.version isaac.foundation.reconfigurable
     isaac.foundation.naming isaac.foundation.scheduler.runtime
     isaac.foundation.spec-helper
     isaac.foundation.module.berths isaac.foundation.module.classpath isaac.foundation.module.coords isaac.foundation.module.discovery isaac.foundation.module.lifecycle isaac.foundation.module.loader isaac.foundation.module.manifest isaac.foundation.module.versions
     isaac.foundation.scheduler.cron
     isaac.foundation.schema.dynamic isaac.foundation.schema.lexicon isaac.foundation.schema.meta
     isaac.foundation.schema.registered-in
     isaac.foundation.config.paths isaac.foundation.config.nav isaac.foundation.config.companion isaac.foundation.config.loader
     isaac.foundation.config.env isaac.foundation.config.parse isaac.foundation.config.companions isaac.foundation.config.entities
     isaac.foundation.config.normalize isaac.foundation.config.warnings isaac.foundation.config.tree
     isaac.foundation.config.api isaac.foundation.config.berths isaac.foundation.config.schema-base
     isaac.foundation.config.check-compose isaac.foundation.config.schema-compose isaac.foundation.config.validation
     isaac.foundation.config.templating
     isaac.foundation.config.validation-lexicon
     isaac.foundation.cli.api isaac.foundation.cli.args isaac.foundation.cli.color isaac.foundation.cli.host isaac.foundation.cli.table})

(def forbidden-prefixes
  ["isaac.http" "isaac.session" "isaac.llm" "isaac.comm" "isaac.bridge"
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
