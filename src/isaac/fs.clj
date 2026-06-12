(ns isaac.fs
  (:refer-clojure :exclude [slurp spit])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.nexus :as nexus]))

(defn- parent-path [path]
  (let [trimmed-path (if (and (str/ends-with? path "/") (> (count path) 1))
                       (subs path 0 (dec (count path)))
                       path)
        parts (str/split trimmed-path #"/")]
    (some->> (butlast parts)
             seq
             (str/join "/"))))

(defn- mem-dir? [store path]
  (let [prefix (str path "/")]
    (or (contains? store [::dir path])
        (some #(str/starts-with? % prefix)
              (keys store)))))

(defprotocol Fs
  (-slurp        [fs path options])
  (-spit         [fs path content options])
  (-exists?      [fs path])
  (-file?        [fs path])
  (-dir?         [fs path])
  (-children     [fs path])
  (-cache-token  [fs])
  (-mkdirs       [fs path])
  (-delete       [fs path])
  (-move         [fs source destination]))

;; region ----- RealFs -----

(deftype RealFs []
  Fs
  (-slurp        [_ path options]
    (when (.exists (io/file path))
      (if (seq options)
        (apply clojure.core/slurp path options)
        (clojure.core/slurp path))))
  (-spit         [_ path content options]
    (if (seq options)
      (apply clojure.core/spit path content options)
      (clojure.core/spit path content)))
  (-exists?      [_ path]         (.exists (io/file path)))
  (-file?        [_ path]         (.isFile (io/file path)))
  (-dir?         [_ path]         (.isDirectory (io/file path)))
  (-children     [_ path]
    (let [f (io/file path)]
      (when (.isDirectory f)
        (some->> (.list f)
                 seq
                 sort
                 vec))))
  (-cache-token  [_] nil)
  (-mkdirs       [_ path]         (.mkdirs (io/file path)))
  (-delete       [_ path]         (.delete (io/file path)))
  (-move         [_ source destination]
    (some-> destination parent-path io/file .mkdirs)
    (let [source-file      (io/file source)
          destination-file (io/file destination)]
      (when (.exists destination-file)
        (.delete destination-file))
      (.renameTo source-file destination-file))))

;; endregion

;; region ----- MemFs -----

(deftype MemFs [store revision]
  Fs
  (-slurp        [_ path _]       (get @store path))
  (-spit         [_ path content options]
    (if (:append (apply hash-map options))
      (do
        (swap! store #(cond-> (update % path (fn [existing] (str (or existing "") content)))
                              (parent-path path) (assoc [::dir (parent-path path)] true)))
        (swap! revision inc)
        nil)
      (do
        (swap! store #(cond-> (assoc % path content)
                              (parent-path path) (assoc [::dir (parent-path path)] true)))
        (swap! revision inc)
        nil)))
  (-exists?      [_ path]         (or (contains? @store path) (mem-dir? @store path)))
  (-file?        [_ path]         (contains? @store path))
  (-dir?         [_ path]         (mem-dir? @store path))
  (-children     [_ path]
    (when (mem-dir? @store path)
      (let [prefix (if (str/ends-with? path "/") path (str path "/"))]
        (->> (keys @store)
             (map #(cond
                     (string? %) %
                     (and (vector? %) (= ::dir (first %))) (second %)
                     :else nil))
             (keep identity)
             (filter #(str/starts-with? % prefix))
             (map #(subs % (count prefix)))
             (remove str/blank?)
              (map #(first (str/split % #"/")))
              distinct
              sort
              vec))))
  (-cache-token  [_]              @revision)
  (-mkdirs       [_ path]
    (swap! store assoc [::dir path] true)
    (swap! revision inc)
    nil)
  (-delete       [_ path]
    (swap! store dissoc path)
    (swap! revision inc)
    nil)
  (-move         [_ source destination]
    (let [value (get @store source)]
      (swap! store #(cond-> (dissoc % source)
                      (some? value)               (assoc destination value)
                      (parent-path destination)   (assoc [::dir (parent-path destination)] true)))
      (swap! revision inc)
      nil)))

;; endregion

(defn real-fs [] (->RealFs))

(def ^:dynamic *fs* nil)

(defn instance
  "Returns the active Fs instance. Reads from source map when provided, otherwise reads from the live nexus."
  ([]       (or *fs* (nexus/get :fs)))
  ([source] (or *fs* (:fs source) (nexus/get :fs))))

(defn- assert-absolute! [path]
  (when-not (str/starts-with? path "/")
    (throw (IllegalArgumentException. (str "Relative path not allowed: " path)))))

;; region ----- Public API -----

(defn mem-fs
  "Creates an in-memory filesystem implementation for tests and isolated workflows."
  []
  (->MemFs (atom {}) (atom 0)))

(defn cache-token
  "Returns a cache token for the given filesystem when it supports cheap
   invalidation-aware caching, otherwise nil."
  [fs] (-cache-token fs))

(defn exists?
  "Returns truthy when the path exists in the given filesystem."
  [fs path] (assert-absolute! path) (-exists? fs path))

(defn file?
  "Returns truthy when the path refers to a file in the given filesystem."
  [fs path] (assert-absolute! path) (-file? fs path))

(defn dir?
  "Returns truthy when the path refers to a directory in the given filesystem."
  [fs path] (assert-absolute! path) (-dir? fs path))

(defn parent
  "Returns the parent path string for the given path, or nil when there is no parent."
  [path]
  (parent-path path))

(defn filename
  "Returns the final segment of a path string."
  [path]
  (let [trimmed (if (and (str/ends-with? path "/") (> (count path) 1))
                  (subs path 0 (dec (count path)))
                  path)]
    (last (str/split trimmed #"/"))))

(defn children
  "Returns a sorted vector of immediate child names for a directory in the given
   filesystem, or nil when the path is not a directory."
  [fs path] (assert-absolute! path) (-children fs path))

(defn slurp
  "Reads and returns file content from the given filesystem.

  Options:
  - :encoding  character encoding name to use when reading."
  ([fs path] (assert-absolute! path) (-slurp fs path nil))
  ([fs path & options] (assert-absolute! path) (-slurp fs path options)))

(defn spit
  "Writes content to a file in the given filesystem.

  Options:
  - :append    when truthy, appends instead of overwriting
  - :encoding  character encoding name to use when writing"
  ([fs path content] (assert-absolute! path) (-spit fs path content nil))
  ([fs path content & options] (assert-absolute! path) (-spit fs path content options)))

(defn mkdirs
  "Creates the directory path in the given filesystem."
  [fs path] (assert-absolute! path) (-mkdirs fs path))

(defn delete
  "Deletes the path from the given filesystem."
  [fs path] (assert-absolute! path) (-delete fs path))

(defn move
  "Moves a path to a new absolute destination in the given filesystem."
  [fs source destination]
  (assert-absolute! source)
  (assert-absolute! destination)
  (-move fs source destination))

(defn copy-tree!
  "Recursively copies `path` from `source-fs` to `target-fs`. Useful
  for staging a dry-run of filesystem changes (e.g. copy real fs into
  a mem-fs, apply edits, validate before committing)."
  [source-fs target-fs path]
  (when (exists? source-fs path)
    (if (file? source-fs path)
      (let [content (slurp source-fs path)
            p      (parent-path path)]
        (when p (mkdirs target-fs p))
        (spit target-fs path content))
      (do
        (mkdirs target-fs path)
        (doseq [child (or (children source-fs path) [])]
          (copy-tree! source-fs target-fs (str path "/" child)))))))

;; endregion
