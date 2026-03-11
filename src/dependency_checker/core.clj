;; mutation-tested: 2026-03-11
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
(def compile-exception cfg/compile-exception)
(def exception-matches? cfg/exception-matches?)
(def namespace-component cfg/namespace-component)
(def validate-config! cfg/validate-config!)
(def ns-target deps/ns-target)
(def dynamic-lookup-targets deps/dynamic-lookup-targets)
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
  [config-path source-path reason]
  (write-config! config-path (generate-starter-config source-path))
  (println (format "%s starter dependency config at %s" reason config-path))
  (println (format "Review the inferred allowed dependencies for namespaces under %s, then rerun." source-path))
  0)

(defn- apply-config-action!
  [action config-path source-path]
  (case action
    :recreate (create-config! config-path source-path "Recreated")
    :create (create-config! config-path source-path "Created")
    :noop-init (do
                 (println (format "Config already exists at %s (not overwritten)." config-path))
                 0)
    nil))

(defn- ensure-config!
  [{:keys [config-path source-path init? force-init?]}]
  (let [exists? (.exists (io/file config-path))]
    (if (and init? force-init?)
      (usage!)
      (apply-config-action! (config-action exists? init? force-init?) config-path source-path))))

(defn- failure?
  [result]
  (let [violations? (seq (:violations result))
        cycles? (seq (:cycles result))
        fail-on-violations? (get-in result [:config :fail-on-violations] true)
        fail-on-cycles? (get-in result [:config :fail-on-cycles] true)]
    (or (and violations? fail-on-violations?)
        (and cycles? fail-on-cycles?))))

(defn- edn-output
  [result]
  (prn result)
  (if (failure? result) 1 0))

(defn- text-output
  [result]
  (report-text result)
  (if (failure? result) 1 0))

(defn- unsupported-format-output
  [fmt]
  (binding [*out* *err*]
    (println "Unsupported format:" fmt))
  2)

(defn- legacy-config-message
  [{:keys [top-level legacy-rules?]}]
  (str "Legacy dependency-checker config syntax is no longer supported."
       (when (seq top-level)
         (str " Found legacy keys: " (pr-str top-level) "."))
       (when legacy-rules?
         " Found legacy component matching rules.")))

(defn- legacy-config-error?
  [ex]
  (= "Legacy dependency-checker config syntax is no longer supported."
     (.getMessage ex)))

(defn- legacy-config-output
  [config-path source-path ex]
  (let [details (ex-data ex)]
    (binding [*out* *err*]
      (println (legacy-config-message details))
      (println (format "Regenerate the config with: clj -M:check-dependencies %s --source-path %s --force-init"
                       config-path source-path)))
    2))

(defn- handle-analysis-exception
  [config-path source-path ex]
  (if (legacy-config-error? ex)
    (legacy-config-output config-path source-path ex)
    (throw ex)))

;; Test-visible helper aliases
(def legacy-config-error-pred legacy-config-error?)
(def handle-analysis-exception* handle-analysis-exception)

(defn- run-analysis!
  [{:keys [config-path source-path fmt]}]
  (try
    (let [result (analyze-project (load-config config-path) source-path)
          format-handler ({:edn edn-output
                           :text text-output}
                          fmt)]
      (if format-handler
        (format-handler result)
        (unsupported-format-output fmt)))
    (catch clojure.lang.ExceptionInfo ex
      (handle-analysis-exception config-path source-path ex))))

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
