(ns isaac.module.loader-fixtures
  "Shared fixture builders for the isaac.module.loader spec suite
   (loader-discovery-spec, loader-compose-spec)."
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(def ctx {:root "/state/.isaac" :cwd "/workspace"})

(defn mod-dir! [path]
  (fs/mkdirs (nexus/get :fs) path))

(defn mod-manifest! [path content]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path content)))

(defn mod-deps! [path]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path "{:paths [\"src\" \"resources\"]}")))

(defn mod-root [id]
  (str "/state/.isaac/modules/" (name id)))

(defn mod-coord [id]
  {:local/root (mod-root id)})

(defn write-local-module! [id manifest]
  (let [root (mod-root id)]
    (mod-dir! root)
    (mod-deps! (str root "/deps.edn"))
    (mod-manifest! (str root "/resources/isaac-manifest.edn") (pr-str manifest))))

(def valid-comm-manifest
  ;; The pigeon declares its own berth and contributes to it — discovery
  ;; must accept a self-declared berth without any other module installed.
  {:id           :isaac.comm.pigeon
   :version      "0.1.0"
   :berths       {:pigeon/comm {:description "test comm berth"
                                :schema      {:type       :map
                                              :key-spec   {:type :keyword}
                                              :value-spec {:type   :map
                                                           :schema {:factory {:type :symbol}}}}}}
   :pigeon/comm  {:pigeon {:factory 'isaac.comm.pigeon/make}}})
