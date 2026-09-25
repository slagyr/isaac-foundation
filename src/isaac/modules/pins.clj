(ns isaac.modules.pins
  "Compare sibling git pins in a module repository with the module registry.

   Two questions, both answered from what `isaac modules pins` already has —
   the registry and the gitlib clones tools.deps keeps:

   - coherence: every sibling this repo pins declares, in its own deps.edn,
     which siblings it was built against. Pinning isaac-agent b6eb475 next to
     a foundation other than the 9ab2527 it requires is a broken build, and
     so is pinning one repo at two shas in one deps.edn.
   - fleet drift: the registry names the sha the fleet installs per module,
     and those modules' own deps.edn name the sha for isaac-foundation, which
     the registry does not list. Behind the fleet is a note, never a failure."
  (:require
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.gitlibs :as gitlibs]
    [clojure.tools.gitlibs.impl :as git-impl]
    [isaac.cli.host :as host]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.coords :as coords]))

(defn- sibling-id [lib]
  (when (and (symbol? lib) (= "io.github.slagyr" (namespace lib)))
    (let [repository (name lib)]
      (when (str/starts-with? repository "isaac-")
        (keyword (str "isaac." (str/replace (subs repository (count "isaac-")) "-" ".")))))))

(defn read-deps-map [dir]
  (some-> (coords/read-text-file (fs/instance) (str dir "/deps.edn")) edn/read-string))

(defn read-sibling-pins [cwd]
  (let [deps (:deps (read-deps-map cwd))]
    (->> deps
         (keep (fn [[lib coord]]
                 (when-let [id (and (:git/url coord) (:git/sha coord) (sibling-id lib))]
                   {:coord coord :id id})))
         (sort-by :id)
         vec)))

(defn local-git-url [url]
  (if (or (str/starts-with? url "/") (str/includes? url ":")
          (str/starts-with? url "~"))
    url
    (.getCanonicalPath (io/file (host/cwd) url))))

(defn stale-cache? [url error]
  (let [cache  (git-impl/git-dir url)
        remote (process/shell {:out :string :err :string :continue true}
                              "git" "--git-dir" (.getPath cache) "remote" "get-url" "origin")
        path   (str/trim (or (:out remote) ""))]
    (and (= 128 (:exit (ex-data error)))
         (zero? (:exit remote))
         (or (str/starts-with? path "/") (str/starts-with? path "file://"))
         (not (.exists (io/file (str/replace path #"^file://" "")))))))

(defn discard-stale-cache! [url]
  (let [cache (git-impl/git-dir url)]
    (doseq [file (reverse (file-seq cache))]
      (when-not (.delete file)
        (throw (ex-info "Cannot discard stale gitlibs cache" {:path (.getPath file)}))))))

(defn- with-recloned-cache [url operation]
  (try
    (operation)
    (catch Exception error
      (if (stale-cache? url error)
        (do
          (discard-stale-cache! url)
          (log/info :modules.pins/cache-recloned :url url)
          (operation))
        (throw error)))))

(defn- ancestry [url pinned-sha registry-sha]
  (if (= pinned-sha registry-sha)
    :current
    (let [url        (local-git-url url)
          descendant (with-recloned-cache url #(gitlibs/descendant url [pinned-sha registry-sha]))]
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

;; region ----- The pin set: every sibling pin in one deps.edn -----

(defn repo-key
  "Repository identity for a git coord: the last url segment, sans .git. Pins
   are a set per *repository*, not per lib — isaac-agent, isaac-agent-spec and
   a bundled marigold module all move with the repo they come from."
  [url]
  (-> url (str/replace #"\.git$" "") (str/split #"/") last))

(defn- git-pins [deps at]
  (keep (fn [[lib coord]]
          (when (and (map? coord) (:git/url coord) (:git/sha coord))
            {:lib      lib
             :url      (:git/url coord)
             :sha      (:git/sha coord)
             :at       at
             :repo     (repo-key (:git/url coord))
             :sibling? (some? (sibling-id lib))}))
        deps))

(defn- sibling-pins
  "Keep the pins whose repository a sibling lib claims — that is how a bundled
   module (marigold.bridge at foundation's url) joins its repo's set."
  [all]
  (let [repos (set (map :repo (filter :sibling? all)))]
    (->> all
         (filter #(contains? repos (:repo %)))
         (map #(dissoc % :sibling?)))))

(defn- deps-rank [{:keys [at]}] (if (= "deps" at) 0 1))

(defn pin-set
  "Every sibling git pin in a deps.edn map: :deps first, then each alias's
   :deps / :extra-deps / :override-deps. Aliases count — isaac-google pins
   isaac-http only under :spec and :features, and that is the build its suite
   actually runs."
  [deps-map]
  (->> (concat (git-pins (:deps deps-map) "deps")
               (mapcat (fn [[alias-key alias-map]]
                         (git-pins (merge (:deps alias-map)
                                          (:extra-deps alias-map)
                                          (:override-deps alias-map))
                                   (str "alias " alias-key)))
                       (:aliases deps-map)))
       sibling-pins
       (sort-by (juxt :repo deps-rank :at))
       vec))

(defn read-pin-set [dir]
  (pin-set (read-deps-map dir)))

(defn- by-repo
  "One pin per repo — the :deps one when there is one."
  [pin-set*]
  (->> pin-set* (group-by :repo) (map (fn [[_ pins]] (first pins))) (sort-by :repo) vec))

;; endregion ^^^^^ The pin set ^^^^^

;; region ----- Coherence -----

(defn split-pins
  "Repos this deps.edn pins at more than one sha — bumped in one place, missed
   in another."
  [pin-set*]
  (->> (group-by :repo pin-set*)
       (keep (fn [[repo pins]]
               (when (> (count (distinct (map :sha pins))) 1)
                 {:repo repo :pins (vec pins)})))
       (sort-by :repo)
       vec))

(defn declarations
  "What each pinned sibling itself requires, read from its own deps.edn at the
   pinned sha in the gitlib clone: [{:repo :sha :requires {repo sha}}]. A repo
   that cannot be procured contributes nothing rather than a failure."
  [pin-set*]
  (->> (by-repo pin-set*)
       (map (fn [{:keys [repo sha lib url]}]
              {:repo repo
               :sha  sha
               :requires
               (or (try
                     (let [url (local-git-url url)
                           dir (with-recloned-cache url #(gitlibs/procure url (or lib (symbol "io.github.slagyr" repo)) sha))]
                       (->> (git-pins (:deps (read-deps-map dir)) "deps")
                            sibling-pins
                            (map (juxt :repo :sha))
                            (into {})))
                     (catch Exception _ nil))
                   {})}))
       vec))

(defn conflicts
  "Pins that disagree with what a pinned sibling requires."
  [pin-set* declarations*]
  (let [ours (group-by :repo pin-set*)]
    (vec (for [{:keys [repo sha requires]} declarations*
               [required-repo required-sha] (sort-by key requires)
               :let [mine (first (remove #(= required-sha (:sha %)) (get ours required-repo)))]
               :when mine]
           {:repo       required-repo
            :ours       (:sha mine)
            :theirs     required-sha
            :source     repo
            :source-sha sha}))))

;; endregion ^^^^^ Coherence ^^^^^

;; region ----- The fleet set -----

(defn registry-pins
  "Pins for the registry's own coords, limited to the repos this one pins —
   the fleet set is only interesting where the two overlap."
  [pin-set* registry]
  (let [mine (set (map :repo pin-set*))]
    (->> registry
         (keep (fn [[_ {:keys [coord]}]]
                 (when (and (:git/url coord) (:git/sha coord))
                   (let [repo (repo-key (:git/url coord))]
                     (when (contains? mine repo)
                       {:repo repo
                        :sha  (:git/sha coord)
                        :url  (:git/url coord)
                        :lib  (symbol "io.github.slagyr" repo)})))))
         (sort-by :repo)
         vec)))

(defn fleet-shas
  "The set the fleet runs: {repo sha} plus the repos its modules disagree
   about. Registry coords are authoritative; isaac-foundation — absent from
   the registry — comes from what the registry's own modules require."
  [registry declarations*]
  (let [direct   (into {} (for [[_ {:keys [coord]}] registry
                                :when (and (:git/url coord) (:git/sha coord))]
                            [(repo-key (:git/url coord)) (:git/sha coord)]))
        required (apply merge-with
                        (fn [a b] (if (= a b) a ::disagreement))
                        {}
                        (map :requires declarations*))
        disputed (set (for [[repo sha] required :when (= ::disagreement sha)] repo))]
    {:fleet          (merge (apply dissoc required disputed) direct)
     :disagreements  disputed}))

(defn fleet-drift
  "Pins that sit behind the fleet set. Ahead is normal for the hours of a
   train (isaac-yrxx) and says nothing."
  [pin-set* fleet]
  (->> (by-repo pin-set*)
       (keep (fn [{:keys [repo sha url]}]
               (let [fleet-sha (get fleet repo)]
                 (when (and fleet-sha
                            (not= fleet-sha sha)
                            (= :older (try (ancestry url sha fleet-sha) (catch Exception _ nil))))
                   {:repo repo :pinned sha :fleet fleet-sha}))))
       vec))

(defn target-set
  "The coherent set to move to, for every repo this one pins: the fleet sha
   when the fleet names one, else what a pinned sibling requires, else the
   sha already pinned. A repo the fleet's own modules disagree about is left
   out — naming either sha would name an incoherent set."
  [pin-set* fleet declarations* disputed]
  (->> (by-repo pin-set*)
       (remove (comp (set disputed) :repo))
       (map (fn [{:keys [repo sha]}]
              {:repo repo
               :sha  (or (get fleet repo)
                         (some #(get-in % [:requires repo]) declarations*)
                         sha)}))
       vec))

;; endregion ^^^^^ The fleet set ^^^^^

(defn check-set
  "The whole set check for the repository at `dir` against `registry`."
  [dir registry]
  (let [pins          (read-pin-set dir)
        ours          (declarations pins)
        fleet-decls   (declarations (registry-pins pins registry))
        {:keys [fleet disagreements]} (fleet-shas registry fleet-decls)]
    {:pins           pins
     :splits         (split-pins pins)
     :conflicts      (conflicts pins ours)
     :behind         (fleet-drift pins fleet)
     :fleet          fleet
     :disagreements  disagreements
     :fleet-requires fleet-decls
     :target         (target-set pins fleet (concat ours fleet-decls) disagreements)}))

(defn incoherent? [{:keys [splits conflicts]}]
  (boolean (or (seq splits) (seq conflicts))))
