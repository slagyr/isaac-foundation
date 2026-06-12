(ns isaac.cli.color)

(def bold   "[1m")
(def dim    "[2m")
(def red    "[31m")
(def yellow "[33m")
(def reset  "[0m")

(def codes {:bold bold :dim dim :red red :yellow yellow})

(defn tty? [] (some? (System/console)))
(defn env [name] (System/getenv name))
