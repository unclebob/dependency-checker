;; mutation-tested: 2026-03-11
(ns dependency-checker.core.base.config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-config
  {:include-exts #{".clj" ".cljc" ".cljs"}
   :allowed-dependencies {}
   :allowed-exceptions []
   :ignored-components #{}
   :fail-on-cycles true
   :fail-on-violations true})

(defn ignored-components-set
  [config]
  (set (:ignored-components config)))

(def legacy-config-keys
  #{:source-paths :component-rules})

(def legacy-rule-keys
  #{:match :matches :pattern})

(defn namespace-segments
  [ns-sym]
  (str/split (str ns-sym) #"\."))

(defn namespace-root
  [ns-sym]
  (first (namespace-segments ns-sym)))

(defn namespace-component
  ([ns-sym]
   (let [parts (namespace-segments ns-sym)]
     (when (>= (count parts) 2)
       (keyword (second parts)))))
  ([project-roots ns-sym]
   (when (contains? project-roots (namespace-root ns-sym))
     (namespace-component ns-sym))))

(defn- legacy-rule?
  [value]
  (cond
    (map? value) (boolean (some legacy-rule-keys (keys value)))
    (vector? value) (= 2 (count value))
    :else false))

(defn legacy-config-details
  [config]
  (let [top-level (->> legacy-config-keys
                       (filter #(contains? config %))
                       sort
                       vec)
        legacy-rules? (some legacy-rule? (:component-rules config))]
    {:top-level top-level
     :legacy-rules? (boolean legacy-rules?)}))

(defn validate-config!
  [config]
  (let [{:keys [top-level legacy-rules?]} (legacy-config-details config)]
    (when (or (seq top-level) legacy-rules?)
      (throw (ex-info "Legacy dependency-checker config syntax is no longer supported."
                      {:top-level top-level
                       :legacy-rules? legacy-rules?})))))

(defn source-file?
  [^java.io.File f include-exts]
  (and (.isFile f)
       (some #(str/ends-with? (.getName f) %) include-exts)))

(defn source-files
  [paths include-exts]
  (->> paths
       (map io/file)
       (filter #(.exists ^java.io.File %))
       (mapcat file-seq)
       (filter #(source-file? % include-exts))))

(defn abs-num
  [n]
  (if (neg? n) (- n) n))

(defn normalize-forbidden-rule
  [rule]
  (if (map? rule)
    rule
    (if (and (vector? rule) (= 2 (count rule)))
      (let [[from to] rule]
        {:from from :to to})
      (throw (ex-info "Invalid forbidden dependency rule" {:rule rule})))))

(defn compile-exception
  [ex]
  (let [from-ns-m (when-let [p (:from-ns ex)] #(= p %))
        to-ns-m (when-let [p (:to-ns ex)] #(= p %))]
    (assoc ex
           :from-ns-match? from-ns-m
           :to-ns-match? to-ns-m)))

(defn exception-matches?
  [ex {:keys [from-component to-component from-ns to-ns]}]
  (and (if (contains? ex :from-component) (= (:from-component ex) from-component) true)
       (if (contains? ex :to-component) (= (:to-component ex) to-component) true)
       (if-let [m (:from-ns-match? ex)] (m from-ns) true)
       (if-let [m (:to-ns-match? ex)] (m to-ns) true)))
