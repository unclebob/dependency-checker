;; mutation-tested: 2026-03-07
(ns dependency-checker.core
  (:require [clojure.java.io :as io]
            [dependency-checker.cli :as cli]
            [dependency-checker.core.base.config :as cfg]
            [dependency-checker.core.base.dependencies :as deps]
            [dependency-checker.core.graph :as graph]
            [dependency-checker.core.infer :as infer]
            [dependency-checker.core.report :as report]))

;; Public API
(def analyze-project graph/analyze-project)
(def generate-starter-config infer/generate-starter-config)

;; Test-visible helper aliases
(def pattern->matcher cfg/pattern->matcher)
(def normalize-component-rule cfg/normalize-component-rule)
(def match-patterns cfg/match-patterns)
(def compile-normalized-rule cfg/compile-normalized-rule)
(def compile-exception cfg/compile-exception)
(def exception-matches? cfg/exception-matches?)
(def ns-target deps/ns-target)
(def dynamic-lookup-targets deps/dynamic-lookup-targets)
(def infer-abstract-prefixes infer/infer-abstract-prefixes)
(def infer-concrete-prefixes infer/infer-concrete-prefixes)
(def parent-prefix infer/parent-prefix)
(def best-abstract-prefix infer/best-abstract-prefix)
(def strongly-connected-components graph/strongly-connected-components)
(def write-config! infer/write-config!)
(def load-config report/load-config)
(def report-text report/report-text)
(def help! report/help!)
(def usage! report/usage!)
(def parse-args cli/parse-args)

(defn- config-action
  [exists? init? force-init?]
  (cond
    force-init? :recreate
    (not exists?) :create
    init? :noop-init
    :else :analyze))

(defn- create-config!
  [config-path reason]
  (write-config! config-path (generate-starter-config))
  (println (format "%s starter dependency config at %s" reason config-path))
  (println "Review the generated component rules and boundary restrictions, then rerun.")
  0)

(defn- apply-config-action!
  [action config-path]
  (case action
    :recreate (create-config! config-path "Recreated")
    :create (create-config! config-path "Created")
    :noop-init (do
                 (println (format "Config already exists at %s (not overwritten)." config-path))
                 0)
    nil))

(defn- ensure-config!
  [{:keys [config-path init? force-init?]}]
  (let [exists? (.exists (io/file config-path))]
    (if (and init? force-init?)
      (usage!)
      (apply-config-action! (config-action exists? init? force-init?) config-path))))

(defn- failure?
  [result]
  (or (and (seq (:violations result)) (get-in result [:config :fail-on-violations] true))
      (and (seq (:cycles result)) (get-in result [:config :fail-on-cycles] true))))

(defn- edn-output
  [result]
  (prn result)
  (if (failure? result) 1 0))

(defn- text-output
  [result opts]
  (report-text result opts)
  (if (failure? result) 1 0))

(defn- unsupported-format-output
  [fmt]
  (binding [*out* *err*]
    (println "Unsupported format:" fmt))
  2)

(defn- run-analysis!
  [{:keys [config-path fmt color? edges?]}]
  (let [result (analyze-project (load-config config-path))
        opts {:color? color? :edges? edges?}]
    (case fmt
      :edn (edn-output result)
      :text (text-output result opts)
      (unsupported-format-output fmt))))

(defn- run-cli
  [args]
  (let [{:keys [error help?] :as parsed} (parse-args args)]
    (cond
      help? (help!)
      (= error :usage) (usage!)
      :else (or (ensure-config! parsed)
                (run-analysis! parsed)))))

(defn -main
  [& args]
  (System/exit (run-cli args)))
