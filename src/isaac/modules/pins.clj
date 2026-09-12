(ns isaac.modules.pins
  "Compare sibling git pins in a module repository with the module registry."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.gitlibs :as gitlibs]
    [isaac.fs :as fs]
    [isaac.module.coords :as coords]))

(defn- sibling-id [lib]
  (when (and (symbol? lib) (= "io.github.slagyr" (namespace lib)))
    (let [repository (name lib)]
      (when (str/starts-with? repository "isaac-")
        (keyword (str "isaac." (str/replace (subs repository (count "isaac-")) "-" ".")))))))

(defn read-sibling-pins [cwd]
  (let [fs*  (fs/instance)
        path (str cwd "/deps.edn")
        deps (some-> (coords/read-text-file fs* path) edn/read-string :deps)]
    (->> deps
         (keep (fn [[lib coord]]
                 (when-let [id (and (:git/url coord) (:git/sha coord) (sibling-id lib))]
                   {:coord coord :id id})))
         (sort-by :id)
         vec)))

(defn- ancestry [url pinned-sha registry-sha]
  (if (= pinned-sha registry-sha)
    :current
    (let [descendant (gitlibs/descendant url [pinned-sha registry-sha])]
      (cond
        (= registry-sha descendant) :older
        (= pinned-sha descendant)   :ahead
        :else                       :unrelated))))

(defn classify-pins [pins registry]
  (->> pins
       (keep (fn [{:keys [coord id]}]
               (when-let [registry-coord (get-in registry [id :coord])]
                 (let [pinned-sha   (:git/sha coord)
                       registry-sha (:git/sha registry-coord)
                       url          (:git/url registry-coord)]
                   {:id           id
                    :pinned-sha   pinned-sha
                    :registry-sha registry-sha
                    :status       (when (and (= (:git/url coord) url) pinned-sha registry-sha)
                                    (ancestry url pinned-sha registry-sha))}))))
       vec))
