;; mutation-tested: 2026-03-11
(ns dependency-checker.core.infer
  (:require [clojure.pprint]
            [dependency-checker.core.base.config :as cfg]
            [dependency-checker.core.base.dependencies :as deps]
            [dependency-checker.core.base.reader :as reader]
            [dependency-checker.core.base.stats :as stats]))

(defn- first-ns-decl
  [forms]
  (first (filter reader/ns-form? forms)))

(defn- record-requires
  [forms ns-decl]
  (set (map str (deps/extract-dependencies forms ns-decl))))

(defn- record-counts
  [forms]
  (select-keys (stats/var-stats forms) [:public-count :abstract-count]))

(defn- ns-decl->record
  [forms ns-decl]
  (merge {:namespace (str (second ns-decl))
          :requires (record-requires forms ns-decl)}
         (record-counts forms)))

(defn- source-ns-record
  [forms]
  (when-let [ns-decl (first-ns-decl forms)]
    (ns-decl->record forms ns-decl)))

(defn source-ns-records
  [source-path include-exts]
  (->> (cfg/source-files [source-path] include-exts)
       (map (comp source-ns-record reader/read-forms))
       (filter some?)
       vec))

(defn- project-roots
  [records]
  (->> records
       (map :namespace)
       (map cfg/namespace-root)
       set))

(defn- namespace->component-map
  [project-roots records]
  (into {}
        (map (fn [{:keys [namespace]}]
               [namespace (cfg/namespace-component project-roots namespace)]))
        records))

(defn- components
  [project-roots records]
  (->> records
       (map :namespace)
       (map #(cfg/namespace-component project-roots %))
       (filter some?)
       set
       (sort-by str)))

(defn- dependency-component
  [project-roots ns->component dep]
  ((some-fn #(get ns->component %)
            #(cfg/namespace-component project-roots %))
   dep))

(defn- add-component-dependency
  [acc from-component to-component]
  (if (or (nil? to-component)
          (= from-component to-component))
    acc
    (update acc from-component conj to-component)))

(defn- dependency-components
  [project-roots ns->component requires]
  (map #(dependency-component project-roots ns->component %) requires))

(defn- record-component-deps
  [project-roots ns->component acc {:keys [namespace requires]}]
  (if-let [from-component (get ns->component namespace)]
    (reduce (fn [acc to-component]
              (add-component-dependency acc from-component to-component))
            acc
            (dependency-components project-roots ns->component requires))
    acc))

(defn- sorted-dependency-map
  [components deps-by-component]
  (into {}
        (for [component components]
          [component (->> (get deps-by-component component #{})
                          (sort-by str)
                          vec)])))

(defn infer-allowed-deps
  ([records]
   (infer-allowed-deps records #{}))
  ([records ignored-components]
  (let [roots (project-roots records)
        ns->component (namespace->component-map roots records)
        known-components (->> (components roots records)
                              (remove #(contains? ignored-components %))
                              vec)
        initial (zipmap known-components (repeat #{}))
        deps-by-component (reduce (partial record-component-deps roots ns->component)
                                  initial
                                  records)]
    (sorted-dependency-map known-components
                           (into {}
                                 (map (fn [[component deps]]
                                        [component (remove #(contains? ignored-components %) deps)]))
                                 deps-by-component)))))

(defn- starter-config
  [allowed-dependencies ignored-components]
  (cond-> {:allowed-dependencies allowed-dependencies
           :fail-on-cycles true
           :fail-on-violations true}
    (seq ignored-components) (assoc :ignored-components (vec (sort-by str ignored-components)))))

(defn generate-starter-config
  ([]
   (generate-starter-config "src" #{}))
  ([source-path]
   (generate-starter-config source-path #{}))
  ([source-path ignored-components]
   (let [records (source-ns-records source-path (:include-exts cfg/default-config))]
     (starter-config (infer-allowed-deps records ignored-components)
                     ignored-components))))

(defn write-config!
  [path cfg]
  (spit path (str (with-out-str (clojure.pprint/pprint cfg)))))
