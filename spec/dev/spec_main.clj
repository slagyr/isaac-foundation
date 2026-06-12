(ns dev.spec-main
  (:require
    [clojure.java.io :as io]
    [speclj.main :as speclj]))

(defn- module-spec-dirs []
  (->> (some-> (io/file "modules") .listFiles seq)
       (filter (fn [f] (.isDirectory f)))
       (map (fn [f] (io/file f "spec")))
       (filter (fn [f] (.isDirectory f)))
       (mapcat (fn [d] ["-D" (.getAbsolutePath d)]))))

(defn -main [& args]
  (if (seq args)
    (apply speclj/-main "-c" "-D" "spec" args)
    (apply speclj/-main (concat ["-c" "-D" "spec"] (module-spec-dirs)))))
