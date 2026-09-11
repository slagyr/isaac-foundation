(ns isaac.component.protocol)

(defprotocol Component
  (start [this])
  (stop [this]))

(defprotocol BoundPort
  "Optional protocol for components that own a network listener."
  (bound-port [this]))

(defprotocol Supervised
  "Optional protocol a long-lived Component may implement so the supervisor
   watches its worker thread/loop and restarts it if it dies (isaac-royn).
   Components that do not implement it are started once and left unmonitored."
  (alive? [this]
    "True while the component's worker (thread, future, loop, scheduler task) is
     healthy. False once it has died — the supervisor then restarts the component
     via stop/start with backoff. Implementations decide how to detect liveness
     (a running flag, future-done?, a heartbeat timestamp, …)."))

(defn component?
  [value]
  (satisfies? Component value))

(defn supervised?
  [value]
  (satisfies? Supervised value))

(defn run-start! [this]
  (start this))

(defn run-stop! [this]
  (stop this))