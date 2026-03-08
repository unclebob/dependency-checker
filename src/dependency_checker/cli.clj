;; mutation-tested: 2026-03-07
(ns dependency-checker.cli
  (:require [clojure.string :as str]))

(defn apply-format-option
  [state more]
  (if-let [format-arg (first more)]
    {:state (assoc state :fmt (keyword format-arg))
     :remaining (rest more)}
    {:error :usage}))

(def ^:private option-handlers
  {"--help" (fn [state more] {:state (assoc state :help? true) :remaining more})
   "--init" (fn [state more] {:state (assoc state :init? true) :remaining more})
   "--force-init" (fn [state more] {:state (assoc state :force-init? true) :remaining more})
   "--no-color" (fn [state more] {:state (assoc state :color? false) :remaining more})
   "--no-edges" (fn [state more] {:state (assoc state :edges? false) :remaining more})
   "--format" apply-format-option})

(defn apply-option
  [state arg more]
  (if-let [handler (get option-handlers arg)]
    (handler state more)
    {:error :usage}))

(defn- starting-state
  [args]
  (let [[config-path remaining] (if (and (seq args) (not (str/starts-with? (first args) "--")))
                                  [(first args) (rest args)]
                                  ["dependency-checker.edn" args])]
    [{:config-path config-path
      :fmt :text
      :help? false
      :init? false
      :force-init? false
      :color? true
      :edges? true}
     remaining]))

(defn- parse-step
  [state remaining]
  (let [[arg & more] remaining
        step (apply-option state arg more)]
    (if (:error step)
      {:error :usage}
      {:state (:state step) :remaining (:remaining step)})))

(defn parse-args
  [args]
  (let [[initial-state initial-remaining] (starting-state args)]
    (loop [state initial-state
           remaining initial-remaining]
      (if (empty? remaining)
        state
        (let [step (parse-step state remaining)]
          (if-let [error (:error step)]
            {:error error}
            (recur (:state step) (:remaining step))))))))
