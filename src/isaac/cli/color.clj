(ns isaac.cli.color)

(def bold   "[1m")
(def dim    "[2m")
(def red    "[31m")
(def yellow "[33m")
(def reset  "[0m")

(def codes {:bold bold :dim dim :red red :yellow yellow})

(defn env [name] (System/getenv name))

(defn- env-set? [name]
  (let [value (env name)]
    (and (some? value)
         (not= "" value))))

(defn force-color? []
  (or (env-set? "FORCE_COLOR")
      (env-set? "CLICOLOR_FORCE")))

(defn no-color? []
  (env-set? "NO_COLOR"))

(defn console? []
  (some? (System/console)))

(defn tty? []
  (cond
    (force-color?) true
    (no-color?)    false
    :else          (console?)))
