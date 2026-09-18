(ns isaac.remote-routing-steps
  (:require
    [clojure.edn :as edn]
    [gherclj.core :as g :refer [defgiven defthen helper!]]
    [isaac.config.root :as root]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.main :as main]
    [isaac.nexus :as nexus]))

(helper! isaac.remote-routing-steps)

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (let [feature-home (g/get :user-home)
          path         (str feature-home "/.config/isaac.edn")
          fs*          (or (g/get :mem-fs) (nexus/get :fs))
          pointer      (or (g/get :remote-pointer)
                           (try (some-> (fs/slurp fs* "/tmp/user/.config/isaac.edn") edn/read-string)
                                (catch Exception _ nil)))]
      (binding [root/*user-home*      (or (g/get :user-home) root/*user-home*)
                root/*pointer-config* (when (or (g/get :remote-routing-scenario?)
                                                (= "/tmp/user" (g/get :user-home)))
                                        pointer)
                main/*remote-runner*  (when-let [runner (g/get :remote-runner)]
                                        (fn [request]
                                          (swap! (g/get :remote-runner-calls) conj request)
                                          (runner request)))]
        (thunk)))))

(defn- install-runner! [runner]
  (g/assoc! :remote-routing-scenario? true)
  (let [path "/tmp/user/.config/isaac.edn"
        fs*  (or (g/get :mem-fs) (nexus/get :fs))]
    (g/assoc! :remote-pointer (try (some-> (fs/slurp fs* path) edn/read-string) (catch Exception _ nil))))
  (g/assoc! :remote-runner-calls (atom []))
  (g/assoc! :remote-runner runner))

(defn stub-remote-runner []
  (install-runner! (constantly 0)))

(defn stub-remote-runner-exits [code]
  (install-runner! (constantly (parse-long code))))

(defn stub-remote-runner-fails [reason]
  (install-runner! (fn [_] (throw (ex-info reason {})))))

(defn stub-remote-runner-received [url argv]
  (let [request (last @(g/get :remote-runner-calls))]
    (g/should= url (:url request))
    (g/should= (read-string argv) (:argv request))))

(defn remote-runner-unavailable []
  (g/assoc! :remote-runner nil))

(defn stub-remote-runner-not-invoked []
  (g/should= [] @(or (g/get :remote-runner-calls) (atom []))))

(defgiven "a stub remote runner is installed" isaac.remote-routing-steps/stub-remote-runner)
(defgiven #"a stub remote runner is installed that exits with code (\d+)" isaac.remote-routing-steps/stub-remote-runner-exits)
(defgiven #"a stub remote runner is installed that fails with reason \"([^\"]+)\"" isaac.remote-routing-steps/stub-remote-runner-fails)
(defgiven "the remote runner module becomes unavailable" isaac.remote-routing-steps/remote-runner-unavailable)
(defthen #"the stub remote runner received url \"([^\"]+)\" and argv (.+)" isaac.remote-routing-steps/stub-remote-runner-received)
(defthen "the stub remote runner was not invoked" isaac.remote-routing-steps/stub-remote-runner-not-invoked)
