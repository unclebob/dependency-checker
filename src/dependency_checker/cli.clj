;; mutation-tested: 2026-03-11
(ns dependency-checker.cli
  (:require [clojure.string :as str]))

(defn apply-format-option
  [state more]
  (if-let [format-arg (first more)]
    {:state (assoc state :fmt (keyword format-arg))
     :remaining (rest more)}
    {:error :usage}))

(defn apply-source-path-option
  [state more]
  (if-let [source-path (first more)]
    {:state (assoc state :source-path source-path)
     :remaining (rest more)}
    {:error :usage}))

(def ^:private option-handlers
  {"--help" (fn [state more] {:state (assoc state :help? true) :remaining more})
   "--init" (fn [state more] {:state (assoc state :init? true) :remaining more})
   "--force-init" (fn [state more] {:state (assoc state :force-init? true) :remaining more})
   "--format" apply-format-option
   "--source-path" apply-source-path-option})

(defn apply-option
  [state arg more]
  (if-let [handler (get option-handlers arg)]
    (handler state more)
    {:error :usage}))

(defn- positional-args
  [args]
  (split-with #(not (str/starts-with? % "--")) args))

(defn- starting-state
  [args]
  (let [[positionals remaining] (positional-args args)]
    (if (> (count positionals) 1)
      {:error :usage}
      (let [[config-path] positionals]
        {:state {:config-path (or config-path "dependency-checker.edn")
                 :source-path "src"
                 :fmt :text
                 :help? false
                 :init? false
                 :force-init? false}
         :remaining remaining}))))

(defn- parse-step
  [state remaining]
  (let [[arg & more] remaining
        step (apply-option state arg more)]
    (if (:error step)
      {:error :usage}
      {:state (:state step) :remaining (:remaining step)})))

(defn parse-args
  [args]
  (let [{:keys [error state remaining]} (starting-state args)]
    (if error
      {:error error}
      (loop [state state
             remaining remaining]
        (if (empty? remaining)
          state
          (let [step (parse-step state remaining)]
            (if-let [step-error (:error step)]
              {:error step-error}
              (recur (:state step) (:remaining step)))))))))
