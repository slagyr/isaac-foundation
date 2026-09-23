(ns isaac.config.tree
  "The config directory read as a tree of keys, with no kind-specific
   knowledge (isaac-49zp).

   Every top-level key may be expressed three ways, all equally valid:

     1. inline in `isaac.edn`
     2. as `config/<key>.edn` — the file's contents are the value of `:<key>`
     3. as `config/<key>/` — each file inside is one entry of `:<key>`

   Two rules hold at every level:

   - **Filenames are literal names, never paths.** `config/isaac.agent.edn` is
     the key `:isaac.agent`, not `:isaac` → `:agent`. Nesting is expressed by a
     file's contents, never by its name.
   - **A key is a file or a directory, never both.** Two storage forms for one
     key is a structure disagreement, so it is refused at load rather than
     resolved by precedence.

   `_` names a map's own values at every level (`crew/_.edn` holds the `:crew`
   table's own entries; `crew/marvin/_.edn` holds marvin's own fields), and as
   a *value* in markdown frontmatter it means \"this field is the body below\"."
  (:require
    [isaac.config.parse :as parse]
    [isaac.config.paths :as paths]))

(def ^:private form-ext
  "Storage form → the extension that selects it. `:dir` has none."
  {:edn ".edn" :md ".md"})

(defn- strip-ext [filename ext]
  (subs filename 0 (- (count filename) (count ext))))

(defn- classify-child
  "The `[name form]` a child of a config directory stands for, or nil when it is
   neither an `.edn` file, a `.md` file, nor a directory."
  [dir child]
  (cond
    (parse/has-ext? child ".edn")     [(strip-ext child ".edn") :edn]
    (parse/has-ext? child ".md")      [(strip-ext child ".md") :md]
    (parse/dir?* (str dir "/" child)) [child :dir]))

(defn relative-for
  "The config-relative path a name in storage `form` occupies, below `prefix`
   (\"\" at the config root)."
  [prefix name form]
  (str prefix name (if (= :dir form) "/" (get form-ext form))))

(defn- conflict-error [prefix name a b]
  {:key   (str prefix name)
   :value (str "defined as both config/" (relative-for prefix name a)
               " and config/" (relative-for prefix name b))})

(defn- add-entry
  "Fold one classified child in. A key is a **file or a directory, never both**,
   so a directory meeting a file of the same name is refused and neither form is
   read. Two *files* are not a conflict: `<id>.edn` plus `<id>.md` is the
   long-standing entity-and-companion pair, and `.edn` names the entry."
  [acc prefix name form]
  (let [existing (get-in acc [:entries name])]
    (cond
      (contains? (:refused acc) name) acc

      (or (and existing (= :dir form))
          (= :dir existing))
      (-> acc
          (update :entries dissoc name)
          (update :refused conj name)
          (update :errors conj (conflict-error prefix name existing form)))

      (= :md existing) (assoc-in acc [:entries name] form)
      existing         acc
      :else            (assoc-in acc [:entries name] form))))

(defn child-entries
  "Classify the immediate children of `dir` (config-relative `prefix`, \"\" at
   the config root) by literal name. Returns `{:entries {name form} :errors []}`
   where `form` is `:edn`, `:md` or `:dir`."
  [dir prefix & {:keys [skip]}]
  (-> (reduce (fn [acc child]
                (if (and skip (skip child))
                  acc
                  (if-let [[name form] (classify-child dir child)]
                    (add-entry acc prefix name form)
                    acc)))
              {:entries {} :errors [] :refused #{}}
              (sort (or (parse/children* dir) [])))
      (dissoc :refused)))

;; region ----- Reading values -----

(defn- body-field
  "The frontmatter field whose value is the `_` sentinel — \"this field's value
   is the markdown body\". Exactly `_`; `_<name>` is something else."
  [data]
  (when (map? data)
    (some (fn [[k v]] (when (= paths/default-entry-name v) k)) data)))

(defn read-markdown
  "The value a `.md` file stands for. With frontmatter it is the frontmatter
   map, with the `_`-valued field replaced by the body; without frontmatter the
   whole file is the value."
  [path substitute-env?]
  (let [content (parse/slurp* path)]
    (if-let [{:keys [frontmatter body]} (parse/split-frontmatter content)]
      (let [data  (parse/read-yaml-string frontmatter substitute-env?)
            field (body-field data)]
        (cond-> data field (assoc field body)))
      content)))

(declare read-map-dir)

(defn- read-entry [dir prefix name form substitute-env?]
  (let [path (str dir "/" name (get form-ext form ""))]
    (case form
      :edn (:data (parse/read-edn-file path substitute-env? false))
      :md  (read-markdown path substitute-env?)
      :dir (read-map-dir path (str prefix name "/") substitute-env?))))

(defn read-map-dir
  "Read a directory as one map: `_` supplies the map's own values and every
   other entry contributes one field named by its filename. Field names are
   keywords — nesting comes from a file's contents or from a subdirectory,
   never from a dotted filename. Returns the map; structure conflicts inside
   it are reported by `dir-errors`."
  [dir prefix substitute-env?]
  (let [{:keys [entries]} (child-entries dir prefix)
        own               (when-let [form (get entries paths/default-entry-name)]
                            (read-entry dir prefix paths/default-entry-name form substitute-env?))]
    (reduce (fn [acc [name form]]
              (if (= paths/default-entry-name name)
                acc
                (assoc acc (keyword name) (read-entry dir prefix name form substitute-env?))))
            (if (map? own) own {})
            entries)))

(defn read-dir-own
  "The `_` entry of each `config/<key>/` directory — the table's own values,
   which for a table of entities is a map of names to entries."
  [config-root dirs substitute-env?]
  (reduce (fn [acc [key dir-name]]
            (let [dir  (str config-root "/" dir-name)
                  form (get (:entries (child-entries dir (str dir-name "/")))
                            paths/default-entry-name)
                  own  (when form
                         (read-entry dir (str dir-name "/") paths/default-entry-name form substitute-env?))]
              (cond-> acc (map? own) (assoc key own))))
          {}
          dirs))

(defn dir-errors
  "Every structure conflict inside `dir` and below it."
  [dir prefix]
  (let [{:keys [entries errors]} (child-entries dir prefix)]
    (into (vec errors)
          (mapcat (fn [[name form]]
                    (when (= :dir form)
                      (dir-errors (str dir "/" name) (str prefix name "/"))))
                  entries))))

;; endregion ^^^^^ Reading values ^^^^^

;; region ----- The config root -----

(defn scan
  "Classify `config-root` (excluding `isaac.edn` itself) into the storage form
   each top-level key uses. Returns
   `{:slices {key relative} :dirs {key dir-name} :errors [...]}`."
  [config-root]
  (let [{:keys [entries errors]} (child-entries config-root "" :skip #{paths/root-filename})]
    (reduce (fn [acc [name form]]
              (if (= :dir form)
                (assoc-in acc [:dirs (keyword name)] name)
                (assoc-in acc [:slices (keyword name)] (str name (get form-ext form)))))
            {:slices {} :dirs {} :errors (vec errors)}
            entries)))

(defn read-slices
  "Fold every `config/<key>.edn` / `config/<key>.md` into `root-data`. A key
   present both inline and in its own file is refused — two storage forms for
   one key is a structure disagreement, not a precedence."
  [config-root slices root-data substitute-env?]
  (reduce (fn [acc [key relative]]
            (if (contains? (:data acc) key)
              (update acc :errors conj
                      {:key   (name key)
                       :value (str "defined in both config/" paths/root-filename
                                   " and config/" relative)})
              (let [path (str config-root "/" relative)]
                (if (parse/has-ext? relative ".md")
                  (-> acc
                      (assoc-in [:data key] (read-markdown path substitute-env?))
                      (update :sources conj (parse/source-path relative)))
                  (let [{:keys [data error]} (parse/read-edn-file path substitute-env? false)]
                    (if error
                      (update acc :errors conj {:key (name key) :value error})
                      (-> acc
                          (assoc-in [:data key] data)
                          (update :sources conj (parse/source-path relative)))))))))
          {:data (or root-data {}) :errors [] :sources []}
          (sort-by key slices)))

;; endregion ^^^^^ The config root ^^^^^
