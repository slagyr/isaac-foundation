(ns isaac.fs
  (:refer-clojure :exclude [slurp spit])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.nexus :as nexus])
  (:import
    (java.nio ByteBuffer)
    (java.nio.channels FileChannel)
    (java.nio.charset StandardCharsets)
    (java.nio.file Files OpenOption StandardCopyOption StandardOpenOption)))

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
  (-modified     [fs path])
  (-size         [fs path])
  (-mkdirs       [fs path])
  (-delete       [fs path])
  (-move         [fs source destination])
  (-copy         [fs source destination])
  (-read-bytes   [fs path offset length]))

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
  (-modified     [_ path]         (let [f (io/file path)] (when (.exists f) (.lastModified f))))
  (-size         [_ path]         (let [f (io/file path)] (if (.isFile f) (.length f) 0)))
  (-mkdirs       [_ path]         (.mkdirs (io/file path)))
  (-delete       [_ path]         (.delete (io/file path)))
  (-move         [_ source destination]
    (some-> destination parent-path io/file .mkdirs)
    (let [source-file      (io/file source)
          destination-file (io/file destination)]
      (when (.exists destination-file)
        (.delete destination-file))
      (.renameTo source-file destination-file)))
  (-copy [_ source destination]
    (let [src (io/file source)]
      (when (.isFile src)
        (some-> destination parent-path io/file .mkdirs)
        (Files/copy (.toPath src)
                    (.toPath (io/file destination))
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))))
  (-read-bytes [_ path offset length]
    (let [f (io/file path)]
      (when (.isFile f)
        (with-open [ch (FileChannel/open (.toPath f) (into-array OpenOption [StandardOpenOption/READ]))]
          (let [size   (.size ch)
                off    (max 0 (min (long offset) size))
                n      (max 0 (min (long length) (- size off)))
                buf    (ByteBuffer/allocate (int n))
                _      (.position ch off)
                read-n (.read ch buf)]
            (if (neg? read-n)
              (byte-array 0)
              (let [arr (byte-array read-n)]
                (.flip buf)
                (.get buf arr)
                arr))))))))

;; endregion

;; region ----- MemFs -----

(deftype MemFs [store revision]
  Fs
  (-slurp        [_ path _]       (get @store path))
  (-spit         [_ path content options]
    (let [new-rev (swap! revision inc)]
      (if (:append (apply hash-map options))
        (swap! store #(cond-> (update % path (fn [existing] (str (or existing "") content)))
                              true               (assoc [::mtime path] new-rev)
                              (parent-path path) (assoc [::dir (parent-path path)] true)))
        (swap! store #(cond-> (assoc % path content)
                              true               (assoc [::mtime path] new-rev)
                              (parent-path path) (assoc [::dir (parent-path path)] true))))
      nil))
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
  (-modified     [_ path]         (get @store [::mtime path]))
  (-size         [_ path]
    (if-let [content (get @store path)]
      (alength (.getBytes ^String content StandardCharsets/UTF_8))
      0))
  (-mkdirs       [_ path]
    (swap! store assoc [::dir path] true)
    (swap! revision inc)
    nil)
  (-delete       [_ path]
    (swap! store #(dissoc % path [::mtime path]))
    (swap! revision inc)
    nil)
  (-move         [_ source destination]
    (let [value   (get @store source)
          new-rev (swap! revision inc)]
      (swap! store #(cond-> (dissoc % source [::mtime source])
                      (some? value)               (assoc destination value [::mtime destination] new-rev)
                      (parent-path destination)   (assoc [::dir (parent-path destination)] true)))
      nil))
  (-copy [_ source destination]
    (when-let [content (get @store source)]
      (let [new-rev (swap! revision inc)]
        (swap! store #(cond-> (assoc % destination content [::mtime destination] new-rev)
                        (parent-path destination) (assoc [::dir (parent-path destination)] true)))
        nil)))
  (-read-bytes [_ path offset length]
    (when-let [content (get @store path)]
      (let [bytes (.getBytes ^String content StandardCharsets/UTF_8)
            size  (alength bytes)
            off   (max 0 (min (long offset) size))
            n     (max 0 (min (long length) (- size off)))
            out   (byte-array n)]
        (when (pos? n)
          (System/arraycopy bytes off out 0 n))
        out))))

;; endregion

(defn real-fs [] (->RealFs))

(defn instance
  "Returns the active Fs instance: the source map's :fs when provided, otherwise
   the live nexus :fs. Throws when neither is set — runtime dependencies must be
   passed explicitly or installed in the nexus, never via thread-local fallback."
  ([] (instance nil))
  ([source]
   (or (:fs source)
       (nexus/get :fs)
       (throw (ex-info "isaac.fs/instance: no filesystem available — install one via the nexus (nexus/init!, nexus/-with-nexus) or pass an explicit {:fs ...} source"
                       {:source source})))))

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

(defn modified
  "Returns a monotonic modification stamp for path (real mtime millis on disk,
   a per-path write revision in-memory), or nil when the path has never been
   written. Stamps are comparable within one filesystem: a larger stamp means a
   later write. Used for cheap staleness checks (isaac-clic startup cache)."
  [fs path] (assert-absolute! path) (-modified fs path))

(defn exists?
  "Returns truthy when the path exists in the given filesystem."
  [fs path] (assert-absolute! path) (-exists? fs path))

(defn file?
  "Returns truthy when the path refers to a file in the given filesystem."
  [fs path] (assert-absolute! path) (-file? fs path))

(defn dir?
  "Returns truthy when the path refers to a directory in the given filesystem."
  [fs path] (assert-absolute! path) (-dir? fs path))

(defn size
  "Returns the on-disk byte length of the file at path, or 0 when the path
   is missing or is not a file. RealFs uses File.length (no content I/O);
   MemFs uses the UTF-8 byte length of the stored string."
  [fs path] (assert-absolute! path) (-size fs path))

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

(defn copy
  "Copies a file to an absolute destination. No-op when the source is missing."
  [fs source destination]
  (assert-absolute! source)
  (assert-absolute! destination)
  (-copy fs source destination))

(defn read-bytes
  "Reads `length` UTF-8 bytes starting at `offset`. Missing file → nil.
   Offset past EOF or non-positive length → empty array."
  [fs path offset length]
  (assert-absolute! path)
  (-read-bytes fs path offset length))

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
