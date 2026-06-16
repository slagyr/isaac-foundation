(ns marigold.longwave
  "Fixture consumer module shared by manifest-only and config-berth tests."
  (:require
    [isaac.cli.api :as cli-api]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.module.protocol :as module]
    [marigold.bridge.comm :as bridge.comm]))

(defn create-module []
  (module/module))

(defmethod bridge.comm/create-comm-node! :longwave [path slice]
  {:type      :longwave
   :path      path
   :crew      (:crew slice)
   :helm/freq (:helm/freq slice)})

;; A Reconfigurable node: demonstrates that the berth engine delivers
;; lifecycle calls to nodes that opt in (and recreates the ones that
;; don't — see the plain :longwave map above).
(defrecord RelayStation [state*]
  reconfigurable/Reconfigurable
  (on-load [_ slice]
    (reset! state* {:slice slice :last-event :started}))
  (on-config-change! [_ _old new]
    (reset! state* {:slice new :last-event :changed}))
  (on-unload [_ _old]
    (reset! state* {:slice nil :last-event :stopped})))

(defmethod bridge.comm/create-comm-node! :relay-station [_path _slice]
  (->RelayStation (atom {})))

(defn ping-handler [_request]
  {:status 200
   :body   "pong"})

(defmethod cli-api/run :longwave-ping
  [_id _opts]
  (println "pong")
  0)
