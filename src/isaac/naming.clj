(ns isaac.naming
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]))

(def adjectives
  ["Calm" "Quiet" "Gentle" "Mellow" "Peaceful" "Tranquil" "Restful" "Serene"
   "Still" "Hushed" "Placid" "Soft" "Soothing" "Tender" "Mild" "Patient"
   "Composed" "Smooth" "Even" "Steady"
   "Sunny" "Bright" "Glowing" "Radiant" "Lambent" "Luminous" "Beaming" "Gilded"
   "Shining" "Vivid" "Glimmering" "Twinkling" "Lustrous" "Cheery" "Sparkling"
   "Merry" "Jolly" "Glad" "Gleeful" "Buoyant" "Mirthful" "Chipper" "Sprightly"
   "Spry" "Lively" "Bouncy" "Frisky" "Perky" "Zippy" "Joyful"
   "Bold" "Daring" "Plucky" "Steadfast" "Stout" "Stalwart" "Resolute" "Valiant"
   "Doughty" "Sturdy" "Faithful" "Loyal" "Hearty" "Gallant" "Brave"
   "Clever" "Curious" "Keen" "Sage" "Witty" "Wise" "Astute" "Inquiring"
   "Bookish" "Pondering"
   "Tidy" "Neat" "Trim" "Crisp" "Polite" "Mannerly" "Prim" "Smart"
   "Polished" "Refined"
   "Brisk" "Swift" "Nimble" "Quick" "Fleet" "Lithe" "Agile" "Prompt"
   "Speedy" "Snappy"
   "Cozy" "Snug" "Toasty" "Warm" "Cordial" "Genial" "Gracious" "Welcoming"
   "Homey" "Heartfelt"
   "Hopeful" "Trusty" "Earnest" "Sincere" "Honest" "Forthright" "Reliable"
   "Devoted" "Genuine" "Candid"
   "Stellar" "Lunar" "Astral" "Orbital" "Cosmic" "Solar" "Celestial" "Dawning"
   "Misty" "Hazy" "Drifting" "Wandering" "Roving"])

(def nouns
  ["Otter" "Badger" "Beaver" "Marten" "Quokka" "Hare" "Hedgehog" "Wombat"
   "Capybara" "Tapir" "Sloth" "Possum" "Lemur" "Vole" "Pika" "Shrew"
   "Ferret" "Mole" "Rabbit" "Squirrel" "Chipmunk" "Raccoon" "Bison" "Donkey"
   "Llama" "Alpaca" "Goat" "Sheep" "Camel" "Reindeer"
   "Falcon" "Heron" "Wren" "Sparrow" "Robin" "Finch" "Plover" "Dove"
   "Lark" "Thrush" "Egret" "Avocet" "Ibis" "Jay" "Magpie" "Tern"
   "Puffin" "Pelican" "Cardinal" "Swallow"
   "Tortoise" "Newt" "Toad" "Lizard" "Anole" "Skink" "Salamander" "Gecko"
   "Iguana" "Chameleon"
   "Dolphin" "Seal" "Manatee" "Narwhal" "Octopus" "Cuttle" "Walrus" "Beluga"
   "Porpoise" "Manta"
   "Bee" "Firefly" "Cricket" "Mantis" "Dragonfly"
   "Spruce" "Cedar" "Maple" "Willow" "Birch" "Aspen" "Oak" "Linden"
   "Ash" "Elm" "Beech" "Hawthorn" "Hazel" "Yew" "Rowan" "Fern"
   "Reed" "Moss" "Lichen" "Clover" "Holly" "Ivy" "Cypress" "Thyme" "Mint"
   "Harbor" "Cove" "Glade" "Meadow" "Marsh" "Ridge" "Vale" "Glen"
   "Dell" "Brook" "Rivulet" "Tarn" "Lagoon" "Bay" "Spring"
   "Comet" "Voyage" "Signal" "Beacon" "Orbit" "Lantern" "Compass" "Sextant"
   "Tiller" "Anchor" "Mast" "Aurora" "Pulsar"])

(defprotocol NamedDomain
  "A namespace where generated names might collide."
  (name-taken? [this name]))

(defprotocol NameStrategy
  "An algorithm for producing fresh names."
  (generate [this]))

(defrecord SequentialStrategy [root counter-key prefix fs]
  NameStrategy
  (generate [_]
    (let [counter-file (str root "/" counter-key "/.counter")
          n            (inc (or (when (fs/exists? fs counter-file)
                                  (some-> (fs/slurp fs counter-file) str/trim parse-long))
                                0))]
      (fs/mkdirs fs (fs/parent counter-file))
      (fs/spit fs counter-file (str n))
      (str prefix n))))

(defrecord AdjectiveNounStrategy [domain adjectives nouns]
  NameStrategy
  (generate [_]
    (loop [attempt 0]
      (when (>= attempt 1000)
        (throw (ex-info "AdjectiveNounStrategy: failed to generate a unique name after 1000 attempts" {})))
      (let [candidate (str (rand-nth adjectives) " " (rand-nth nouns))]
        (if (name-taken? domain candidate)
          (recur (inc attempt))
          candidate)))))
