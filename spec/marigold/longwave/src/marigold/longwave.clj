(ns marigold.longwave
  "Fixture consumer module shared by manifest-only and config-berth tests."
  (:require
    [isaac.module :as module]
    [marigold.bridge.comm :as bridge.comm]))

(defn create-module []
  (module/module))

(defmethod bridge.comm/create-comm-node! :longwave [path slice]
  {:type      :longwave
   :path      path
   :crew      (:crew slice)
   :helm/freq (:helm/freq slice)})

(defn ping-handler [_request]
  {:status 200
   :body   "pong"})

(defn longwave-ping-run-fn
  "Run-fn for the longwave-ping CLI command (contributed via the
   :marigold.bridge/cli berth). Prints \"pong\" and returns exit 0."
  [_opts]
  (println "pong")
  0)
