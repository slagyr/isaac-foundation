(ns isaac.foundation-spec
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.config.api :as config-api]
    [isaac.foundation :as sut]
    [isaac.foundation-smoke-module :as smoke]
    [isaac.fs :as fs]
    [isaac.module.protocol :as module-protocol]
    [isaac.nexus :as nexus]
    [isaac.reconfigurable :as reconfigurable]
    [speclj.core :refer [describe it should should=]]))

(def tier-1-direct-imports
  "Documented carve-outs modules may require in addition to isaac.foundation."
  '#{isaac.cli.api
     isaac.fs
     isaac.logger
     isaac.config.paths
     isaac.config.root
     isaac.schema.lexicon
     isaac.schema.meta
     isaac.reconfigurable})

(defn- ns->file [ns-sym]
  (io/file "spec" (str (-> (name ns-sym)
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

(defn- entry->nses [entry]
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

(describe "isaac.foundation"

  (describe "module.protocol re-exports"

    (it "re-exports the same named protocol"
      (should= (:name module-protocol/Module) (:name sut/Module))
      (should= (set (keys (:sigs module-protocol/Module)))
               (set (keys (:sigs sut/Module)))))

    (it "re-exports module fns from module.protocol"
      (let [m (module-protocol/module {:on-startup (fn [_] nil)})]
        (should (sut/module? m))
        (with-redefs [module-protocol/module (fn [& _] ::stub)]
          (should= ::stub (sut/module))))))

  (describe "reconfigurable re-exports"

    (it "re-exports the public protocol"
      (should= (:name reconfigurable/Reconfigurable) (:name sut/Reconfigurable))
      (should= (set (keys (:sigs reconfigurable/Reconfigurable)))
               (set (keys (:sigs sut/Reconfigurable)))))

    (it "a type implementing foundation/Reconfigurable satisfies foundation/Reconfigurable"
      (let [r (reify sut/Reconfigurable
                (on-startup! [_ _] :started)
                (on-config-change! [_ _ _] :changed))]
        (should (satisfies? sut/Reconfigurable r)))))

  (describe "config.api re-exports"

    (it "re-exports load-config-result and siblings from config.api"
      (should (identical? config-api/load-config-result sut/load-config-result))
      (should (identical? config-api/snapshot sut/snapshot))
      (should (identical? config-api/root sut/root))
      (should (identical? config-api/normalize-config sut/normalize-config))
      (should (identical? config-api/env sut/env))
      (should (identical? config-api/load-config! sut/load-config!)))

    (it "does not re-export default-root"
      (should= nil (resolve 'isaac.foundation/default-root))))

  (describe "nexus re-exports"

    (it "re-exports nexus get, get-in, and register!"
      (should (identical? nexus/get sut/get))
      (should (identical? nexus/get-in sut/get-in))
      (should (identical? nexus/register! sut/register!))
      (nexus/-with-nexus {}
        (sut/register! [:smoke] :registered)
        (should= :registered (sut/get :smoke))
        (should= :registered (sut/get-in [:smoke])))))

  (describe "create-module"

    (it "returns a module"
      (should (sut/module? (sut/create-module)))))

  (describe "module-author smoke"

    (it "smoke module requires only facade plus documented carve-outs"
      (let [requires (isaac-requires 'isaac.foundation-smoke-module)
            allowed  (conj tier-1-direct-imports 'isaac.foundation)]
        (should= #{} (set/difference requires allowed))))

    (it "smoke module uses facade exports for module, nexus, and Reconfigurable"
      (should (sut/module? (smoke/create-module)))
      (nexus/-with-nexus {:fs (fs/mem-fs)}
        (should (smoke/smoke-ready?)))
      (let [relay (smoke/relay)]
        (sut/on-startup! relay {:crew "main"})
        (should (satisfies? sut/Reconfigurable relay))))))