(ns isaac.reconfigurable
  "Foundation-public protocol for config-driven components. Module
   implementors extend Reconfigurable here — not via config.berths or
   config.runtime.")

(defprotocol Reconfigurable
  (on-load           [this slice])
  (on-config-change! [this old-slice new-slice])
  (on-unload         [this slice]))