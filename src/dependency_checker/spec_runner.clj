(ns dependency-checker.spec-runner
  (:require [speclj-structure-check.core :as structure-check]
            [speclj.main :as speclj]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- path-results [path]
  (let [file (io/file path)]
    (cond
      (.isFile file)
      [{:file path
        :result (structure-check/check-file path)}]

      (.isDirectory file)
      (structure-check/check-directory path)

      :else
      [{:file path
        :result "not found"}])))

(defn- print-structure-results! [results]
  (doseq [{:keys [file result]} results]
    (println (str file ": " result))))

(defn- structure-errors? [results]
  (boolean
   (some (fn [{:keys [result]}]
           (not (str/starts-with? result "OK")))
         results)))

(defn -main [& args]
  (let [spec-path "spec"
        has-args? (seq args)
        structure-args (if has-args? args [spec-path])
        results (mapcat path-results structure-args)]
    (print-structure-results! results)
    (when (structure-errors? results)
      (System/exit 1))
    (if has-args?
      (apply speclj/-main args)
      (speclj/-main "-c"))))
