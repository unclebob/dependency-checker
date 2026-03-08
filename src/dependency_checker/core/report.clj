;; mutation-tested: 2026-03-08
(ns dependency-checker.core.report
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn fmt-double [d]
  (format "%.3f" (double d)))

(def ^:private ansi-reset "\u001b[0m")



(defn- ansi-color
  [r g b]
  (format "\u001b[38;2;%d;%d;%dm" r g b))

(defn colorize-zone
  [z distance threshold]
  (let [intensity (case z
                    (:pain :useless) (min 1.0 (/ (max 0.0 (- (double distance) threshold))
                                                 (- 1.0 threshold)))
                    :healthy (min 1.0 (- 1.0 (/ (double distance) threshold))))
        label (name z)
        color (case z
                :pain (let [r (int (+ 140 (* 115 intensity)))
                            g (int (* 80 (- 1.0 intensity)))
                            b (int (* 80 (- 1.0 intensity)))]
                        (ansi-color r g b))
                :useless (let [r (int (* 80 (- 1.0 intensity)))
                               g (int (* 80 (- 1.0 intensity)))
                               b (int (+ 140 (* 115 intensity)))]
                           (ansi-color r g b))
                :healthy (let [r (int (* 80 (- 1.0 intensity)))
                               g (int (+ 140 (* 115 intensity)))
                               b (int (* 80 (- 1.0 intensity)))]
                           (ansi-color r g b)))]
    (str color label ansi-reset)))

(defn print-labeled-section
  [title underline lines]
  (when (seq lines)
    (println)
    (println title)
    (println underline)
    (doseq [line lines]
      (println line))))

(defn- format-zone
  [z distance threshold color?]
  (if color?
    (colorize-zone z distance threshold)
    (name z)))

(defn metric-lines
  [component-stats {:keys [color? threshold] :or {color? true threshold 0.3}}]
  (cons
   (format "%-18s %6s %7s %11s %11s %9s  %s" "Component" "FanIn" "FanOut" "Instability" "Abstract" "Distance" "Zone")
   (for [[component {:keys [fan-in fan-out instability abstractness distance zone]}] component-stats]
     (format "%-18s %6d %7d %11s %11s %9s  %s"
             (str component)
             fan-in
             fan-out
             (fmt-double instability)
             (fmt-double abstractness)
             (fmt-double distance)
             (format-zone zone distance threshold color?)))))

(defn edge-lines
  [component-edges]
  (map (fn [[from to]] (format "%s -> %s" from to)) component-edges))

(defn warning-lines
  [warnings]
  (map (fn [{:keys [namespace callee targets]}]
         (format "%s uses %s%s"
                 namespace
                 callee
                 (if (seq targets)
                   (str " -> " (str/join ", " targets))
                   "")))
       warnings))

(defn violation-lines
  [violations]
  (map (fn [{:keys [from-component to-component from-ns to-ns]}]
         (format "%s -> %s  (%s -> %s)"
                 from-component to-component from-ns to-ns))
       violations))

(defn cycle-lines
  [cycles]
  (map (fn [cycle] (str/join " -> " (map str cycle))) cycles))

(defn report-text
  ([result] (report-text result {}))
  ([{:keys [config component-stats component-edges warnings violations cycles]} opts]
   (let [components (keys component-stats)
         threshold (or (:healthy-threshold config) 0.3)
         opts (merge {:color? true :edges? true :threshold threshold} opts)]
     (println "Dependency Analysis")
     (println "===================")
     (println)
     (println (format "Components: %d" (count components)))
     (println (format "Component edges: %d" (count component-edges)))
     (println (format "Warnings: %d" (count warnings)))
     (println (format "Violations: %d" (count violations)))
     (println (format "Cycles: %d" (count cycles)))
     (print-labeled-section "Component Metrics" "-----------------" (metric-lines component-stats opts))
     (when (:edges? opts)
       (print-labeled-section "Component Dependencies" "----------------------" (edge-lines component-edges)))
     (print-labeled-section "Warnings" "--------" (warning-lines warnings))
     (print-labeled-section "Boundary Violations" "-------------------" (violation-lines violations))
     (print-labeled-section "Cycles" "------" (cycle-lines cycles)))))

(defn load-config
  [path]
  (if (and path (.exists (io/file path)))
    (edn/read-string (slurp path))
    {}))

(def usage-summary
  "Usage: clj -M:check-dependencies [config.edn] [--format text|edn] [--no-color] [--no-edges] [--init|--force-init] [--help]")

(defn help!
  []
  (println usage-summary)
  0)

(defn usage!
  []
  (binding [*out* *err*]
    (println usage-summary))
  2)
