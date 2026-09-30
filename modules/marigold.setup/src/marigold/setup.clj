(ns marigold.setup
  "Marigold fixture module: contributes an :isaac/setup — a fn of the
   current config that proposes starter writes plus a hint.")

(defn setup [_config]
  {:writes [["marigold.greeting" "ahoy"]
            ["marigold.chimes" 3]]
   :hints  ["Polish the bell before first use."]})
