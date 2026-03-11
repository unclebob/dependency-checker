(ns dependency-checker.core-spec
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dependency-checker.core :as tool]
            [dependency-checker.core.base.config :as cfg-base]
            [dependency-checker.core.base.dependencies :as dep-base]
            [dependency-checker.core.base.reader :as reader-base]
            [dependency-checker.core.base.stats :as stats-base]
            [dependency-checker.core.graph :as graph]
            [dependency-checker.core.infer :as infer]
            [speclj.core :refer :all]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "dependency-tool-spec" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-file!
  [root rel-path content]
  (let [f (io/file root rel-path)]
    (io/make-parents f)
    (spit f content)))

(describe "dependency-tool/analyze-project"
  (it "derives components from the second namespace segment"
    (let [root (temp-dir)]
      (write-file! root "demo/alpha/core.clj"
                   "(ns demo.alpha.core (:require [demo.beta.util :as b] [demo.gamma.api :as g]))\n(defprotocol APort (x [this]))\n")
      (write-file! root "demo/beta/util.clj"
                   "(ns demo.beta.util (:require [demo.gamma.api :as g]))\n(defn b-fn [] :ok)\n")
      (write-file! root "demo/gamma/api.clj"
                   "(ns demo.gamma.api (:require [demo.alpha.core :as a]))\n(defn c-fn [] :ok)\n")
      (let [result (tool/analyze-project {:allowed-dependencies {:alpha [:gamma]
                                                                 :beta [:gamma]
                                                                 :gamma [:alpha]}}
                                         (.getPath root))]
        (should= #{[:alpha :beta] [:alpha :gamma] [:beta :gamma] [:gamma :alpha]}
                 (set (:component-edges result)))
        (should= 1 (count (:violations result)))
        (should= :alpha (:from-component (first (:violations result))))
        (should= :beta (:to-component (first (:violations result))))
        (should (some #(= #{:alpha :beta :gamma} (set %)) (:cycles result))))))

  (it "calculates fan-in, fan-out, instability, abstractness, and distance metrics"
    (let [root (temp-dir)]
      (write-file! root "demo/alpha/core.clj"
                   "(ns demo.alpha.core (:require [demo.beta.util :as b] [demo.gamma.api :as c]))\n(defprotocol APort (x [this]))\n")
      (write-file! root "demo/beta/util.clj"
                   "(ns demo.beta.util (:require [demo.gamma.api :as c]))\n(defn b-fn [] :ok)\n")
      (write-file! root "demo/gamma/api.clj"
                   "(ns demo.gamma.api (:require [demo.alpha.core :as a]))\n(defn c-fn [] :ok)\n")
      (let [result (tool/analyze-project {} (.getPath root))
            stats (:component-stats result)]
        (should= 1 (get-in stats [:alpha :fan-in]))
        (should= 2 (get-in stats [:alpha :fan-out]))
        (should= 1 (get-in stats [:alpha :public-vars]))
        (should= 1 (get-in stats [:alpha :abstract-vars]))
        (should (< (Math/abs (- 0.6666666666666666 (get-in stats [:alpha :instability]))) 1.0e-9))
        (should (< (Math/abs (- 1.0 (get-in stats [:alpha :abstractness]))) 1.0e-9))
        (should (< (Math/abs (- 0.6666666666666666 (get-in stats [:alpha :distance]))) 1.0e-9)))))

  (it "supports allowed exceptions for disallowed component dependencies"
    (let [root (temp-dir)]
      (write-file! root "demo/left/core.clj" "(ns demo.left.core (:require [demo.right.api :as b]))\n(defn call [] (b/id))\n")
      (write-file! root "demo/right/api.clj" "(ns demo.right.api)\n(defn id [] :ok)\n")
      (let [result (tool/analyze-project {:allowed-dependencies {:left []
                                                                 :right []}
                                          :allowed-exceptions [{:from-ns "demo.left.core"
                                                                :to-ns "demo.right.api"}]}
                                         (.getPath root))]
        (should= [] (:violations result)))))

  (it "includes dynamic namespace lookups in dependency metrics and emits warnings"
    (let [root (temp-dir)]
      (write-file! root "demo/alpha/core.clj"
                   "(ns demo.alpha.core (:import [demo.imports Thing]))\n(require '[demo.beta.api :as b])\n(defn call []\n  (requiring-resolve 'demo.gamma/run)\n  (resolve 'demo.delta/id)\n  (ns-resolve 'demo.epsilon 'id)\n  (find-ns 'demo.zeta)\n  (the-ns 'demo.eta)\n  (b/id))\n")
      (write-file! root "demo/beta/api.clj" "(ns demo.beta.api)\n(defn id [] :ok)\n")
      (write-file! root "demo/gamma/run.clj" "(ns demo.gamma.run)\n(defn run [] :ok)\n")
      (write-file! root "demo/delta/id.clj" "(ns demo.delta.id)\n(defn id [] :ok)\n")
      (write-file! root "demo/epsilon/id.clj" "(ns demo.epsilon.id)\n(defn id [] :ok)\n")
      (write-file! root "demo/zeta/core.clj" "(ns demo.zeta.core)\n(defn id [] :ok)\n")
      (write-file! root "demo/eta/core.clj" "(ns demo.eta.core)\n(defn id [] :ok)\n")
      (let [result (tool/analyze-project {} (.getPath root))
            stats (:component-stats result)]
        (should= #{[:alpha :beta] [:alpha :delta] [:alpha :epsilon] [:alpha :eta] [:alpha :gamma] [:alpha :imports] [:alpha :zeta]}
                 (set (:component-edges result)))
        (should= 7 (get-in stats [:alpha :fan-out]))
        (should= 5 (count (:warnings result)))
        (should (every? #(= "demo.alpha.core" (:namespace %)) (:warnings result))))))

  (it "rejects legacy config syntax and recommends regeneration"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dependency-checker.edn"))
          err (java.io.StringWriter.)]
      (write-file! root "demo/alpha/core.clj" "(ns demo.alpha.core)\n")
      (spit cfg-path "{:source-paths [\"src\"] :component-rules [{:component :alpha :match \"demo.alpha*\"}]}")
      (binding [*err* err]
        (should= 2 (#'tool/run-cli [cfg-path "--source-path" (.getPath root)])))
      (should (str/includes? (str err) "Legacy dependency-checker config syntax is no longer supported."))
      (should (str/includes? (str err) "--force-init"))))

  (it "ignores configured components completely during analysis"
    (let [root (temp-dir)]
      (write-file! root "demo/core/main.clj"
                   "(ns demo.core.main (:require [demo.cli.app :as cli] [demo.spec-runner.main :as sr]))\n(defn run [] [cli sr])\n")
      (write-file! root "demo/cli/app.clj"
                   "(ns demo.cli.app)\n(defn run [] :ok)\n")
      (write-file! root "demo/spec_runner/main.clj"
                   "(ns demo.spec-runner.main (:require [demo.cli.app :as cli]))\n(defn run [] (cli/run))\n")
      (let [result (tool/analyze-project {:allowed-dependencies {:core []
                                                                 :cli []
                                                                 :spec-runner :all}
                                          :ignored-components [:spec-runner]}
                                         (.getPath root))]
        (should= #{:core :cli} (set (keys (:component-stats result))))
        (should= #{[:core :cli]} (set (:component-edges result)))
        (should= [] (:warnings result))
        (should= 1 (count (:violations result)))
        (should= :core (:from-component (first (:violations result))))
        (should= :cli (:to-component (first (:violations result)))))))
  )

(describe "dependency-tool config and reporting"
  (it "reads and writes config and reports usage and help text"
    (let [root (temp-dir)
          cfg-file (io/file root "dep.edn")
          cfg {:allowed-dependencies {:alpha [:beta]}}
          usage-output (binding [*err* (java.io.StringWriter.)]
                         (let [code (#'tool/usage!)]
                           {:code code :text (str *err*)}))
          help-output (binding [*out* (java.io.StringWriter.)]
                        (let [code (#'tool/help!)]
                          {:code code :text (str *out*)}))]
      (#'tool/write-config! (.getPath cfg-file) cfg)
      (should= cfg (edn/read-string (slurp cfg-file)))
      (should= cfg (#'tool/load-config (.getPath cfg-file)))
      (should= {} (#'tool/load-config (.getPath (io/file root "missing.edn"))))
      (should= 2 (:code usage-output))
      (should (str/includes? (:text usage-output) "[config.edn] [--source-path path]"))
      (should= 0 (:code help-output))
      (should (str/includes? (:text help-output) "[config.edn] [--source-path path]"))))

  (it "formats text report with warnings, violations, and cycles"
    (let [report (with-out-str
                   (#'tool/report-text
                    {:component-stats {:alpha {:fan-in 1
                                               :fan-out 2
                                               :instability 0.66
                                               :abstractness 0.10
                                               :distance 0.24}}
                     :component-edges [[:alpha :beta]]
                     :warnings [{:namespace "demo.a" :callee "requiring-resolve" :targets ["demo.b"]}]
                     :violations [{:from-component :alpha
                                   :to-component :beta
                                   :from-ns "demo.a"
                                   :to-ns "demo.b"}]
                     :cycles [[:alpha :beta]]}))]
      (should (str/includes? report "Warnings: 1"))
      (should (str/includes? report "demo.a uses requiring-resolve -> demo.b"))
      (should (str/includes? report "Boundary Violations"))
      (should (str/includes? report "Cycles"))))

  (it "computes failure output status from configured gates"
    (should (#'tool/failure? {:config {:fail-on-violations true
                                       :fail-on-cycles true}
                              :violations [{:from-component :a}]
                              :cycles []}))
    (should (#'tool/failure? {:config {:fail-on-violations false
                                       :fail-on-cycles true}
                              :violations []
                              :cycles [[:a :b]]}))
    (should-not (#'tool/failure? {:config {:fail-on-violations false
                                           :fail-on-cycles false}
                                  :violations [{:from-component :a}]
                                  :cycles [[:a :b]]})))

  (it "returns status codes for text and edn output"
    (let [ok-result {:config {:fail-on-violations true
                              :fail-on-cycles true}
                     :violations []
                     :cycles []}
          fail-result {:config {:fail-on-violations true
                                :fail-on-cycles true}
                       :violations [{:from-component :a}]
                       :cycles []}
          out (java.io.StringWriter.)]
      (with-redefs [tool/report-text (fn [_] (println "report"))]
        (binding [*out* out]
          (should= 0 (#'tool/text-output ok-result))
          (should= 1 (#'tool/text-output fail-result))))
      (binding [*out* out]
        (should= 0 (#'tool/edn-output ok-result))
        (should= 1 (#'tool/edn-output fail-result)))))

  (it "formats the legacy config error message"
    (should= "Legacy dependency-checker config syntax is no longer supported."
             (#'tool/legacy-config-message {:top-level []
                                            :legacy-rules? false}))
    (should (str/includes? (#'tool/legacy-config-message {:top-level [:component-rules]
                                                          :legacy-rules? true})
                           "Found legacy keys: [:component-rules].")))

  (it "identifies legacy config exceptions by message"
    (should (tool/legacy-config-error-pred (ex-info "Legacy dependency-checker config syntax is no longer supported."
                                                    {})))
    (should-not (tool/legacy-config-error-pred (ex-info "Different failure" {}))))

  (it "handles analysis exceptions for legacy and non-legacy errors"
    (let [legacy-ex (ex-info "Legacy dependency-checker config syntax is no longer supported."
                             {:top-level [:source-paths] :legacy-rules? false})
          other-ex (ex-info "Different failure" {:kind :other})
          err-out (java.io.StringWriter.)]
      (binding [*err* err-out]
        (should= 2 (tool/handle-analysis-exception* "dep.edn" "src" legacy-ex)))
      (should (str/includes? (str err-out) "Regenerate the config with:"))
      (should-throw clojure.lang.ExceptionInfo
                    (tool/handle-analysis-exception* "dep.edn" "src" other-ex))))

  (it "run-analysis dispatches text edn and legacy config errors"
    (let [cfg-path "dep.edn"
          text-out (java.io.StringWriter.)
          edn-out (java.io.StringWriter.)
          err-out (java.io.StringWriter.)
          ok-result {:config {:fail-on-violations true
                              :fail-on-cycles true}
                     :component-stats {}
                     :violations []
                     :cycles []
                     :component-edges []
                     :warnings []}]
      (with-redefs [tool/load-config (fn [_] {})
                    tool/analyze-project (fn [_ _] ok-result)
                    tool/report-text (fn [_] (println "text-report"))]
        (binding [*out* text-out]
          (should= 0 (#'tool/run-analysis! {:config-path cfg-path
                                            :source-path "src"
                                            :fmt :text})))
        (binding [*out* edn-out]
          (should= 0 (#'tool/run-analysis! {:config-path cfg-path
                                            :source-path "src"
                                            :fmt :edn}))))
      (with-redefs [tool/load-config (fn [_] {})
                    tool/analyze-project (fn [_ _]
                                           (throw (ex-info "Legacy dependency-checker config syntax is no longer supported."
                                                           {:top-level [:source-paths]
                                                            :legacy-rules? false})))]
        (binding [*err* err-out]
          (should= 2 (#'tool/run-analysis! {:config-path cfg-path
                                            :source-path "src"
                                            :fmt :text}))))
      (should (str/includes? (str text-out) "text-report"))
      (should (str/includes? (str edn-out) ":component-stats"))
      (should (str/includes? (str err-out) "Regenerate the config with:"))))

  (it "run-analysis rethrows non-legacy ExceptionInfo"
    (with-redefs [tool/load-config (fn [_] {})
                  tool/analyze-project (fn [_ _]
                                         (throw (ex-info "Different failure"
                                                         {:kind :other})))]
      (should-throw clojure.lang.ExceptionInfo
                    (#'tool/run-analysis! {:config-path "dep.edn"
                                           :source-path "src"
                                           :fmt :text}))))
  )

(describe "dependency-tool CLI flow"
  (it "parses args with defaults"
    (let [defaults (#'tool/parse-args [])]
      (should= "dependency-checker.edn" (:config-path defaults))
      (should= "src" (:source-path defaults))
      (should= :text (:fmt defaults))
      (should= false (:help? defaults))
      (should= false (:init? defaults))
      (should= false (:force-init? defaults))))

  (it "parses explicit config path source path and options"
    (let [parsed (#'tool/parse-args ["cfg.edn" "--source-path" "lib" "--format" "edn" "--init"])]
      (should= "cfg.edn" (:config-path parsed))
      (should= "lib" (:source-path parsed))
      (should= :edn (:fmt parsed))
      (should= true (:init? parsed))
      (should= false (:force-init? parsed))))

  (it "returns usage error for too many positional args or unknown options"
    (should= :usage (:error (#'tool/parse-args ["a" "b"])))
    (should= :usage (:error (#'tool/parse-args ["--unknown"])))
    (should= :usage (:error (#'tool/parse-args ["--format"])))
    (should= :usage (:error (#'tool/parse-args ["--source-path"]))))

  (it "run-cli passes source path into config generation"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))
          seen (atom nil)]
      (with-redefs [tool/generate-starter-config (fn [source-path]
                                                   (reset! seen source-path)
                                                   {:allowed-dependencies {:alpha []}})]
        (should= 0 (#'tool/run-cli [cfg-path "--source-path" "lib" "--init"])))
      (should= "lib" @seen)
      (should (.exists (io/file cfg-path)))))

  (it "apply-config-action handles create noop and analyze actions"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))
          out (java.io.StringWriter.)]
      (with-redefs [tool/generate-starter-config (fn [_] {:allowed-dependencies {}})]
        (binding [*out* out]
          (should= 0 (#'tool/apply-config-action! :create cfg-path "src"))
          (should= 0 (#'tool/apply-config-action! :recreate cfg-path "src"))
          (should= 0 (#'tool/apply-config-action! :noop-init cfg-path "src"))
          (should= nil (#'tool/apply-config-action! :analyze cfg-path "src"))))
      (should (str/includes? (str out) "Created starter dependency config"))
      (should (str/includes? (str out) "Recreated starter dependency config"))
      (should (str/includes? (str out) "not overwritten"))))

  (it "run-cli returns 2 for unsupported format"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))]
      (spit cfg-path "{}")
      (with-redefs [tool/analyze-project (fn [_ _]
                                           {:config {:fail-on-violations true :fail-on-cycles true}
                                            :component-stats {:alpha {:distance 0.0}}
                                            :violations []
                                            :cycles []
                                            :component-edges []
                                            :warnings []})
                    tool/report-text (fn [& _] nil)]
        (should= 2 (#'tool/run-cli [cfg-path "--format" "xml"])))))

  (it "run-cli returns 0 for --help"
    (let [out (java.io.StringWriter.)]
      (binding [*out* out]
        (should= 0 (#'tool/run-cli ["--help"])))
      (should (str/includes? (str out) "Usage: clj -M:check-dependencies")))))

(describe "dependency-tool helpers"
  (it "extracts namespace components from the second segment"
    (should= :alpha (#'tool/namespace-component 'demo.alpha.core))
    (should= :alpha (#'tool/namespace-component "demo.alpha.core"))
    (should= nil (#'tool/namespace-component 'demo)))

  (it "handles ns-target for symbol string quoted symbol and unsupported types"
    (should= 'demo.ns (#'tool/ns-target 'demo.ns))
    (should= 'demo.ns (#'tool/ns-target "demo.ns"))
    (should= 'demo.ns (#'tool/ns-target '(quote demo.ns)))
    (should= 'demo.ns (#'tool/ns-target '(quote "demo.ns")))
    (should= nil (#'tool/ns-target 42)))

  (it "extracts dynamic-lookup targets for all supported lookup forms"
    (should= ['demo.alpha] (#'tool/dynamic-lookup-targets '(requiring-resolve 'demo.alpha/run)))
    (should= ['demo.beta] (#'tool/dynamic-lookup-targets '(resolve 'demo.beta/run)))
    (should= ['demo.gamma 'demo.delta] (#'tool/dynamic-lookup-targets '(ns-resolve 'demo.gamma 'demo.delta/run)))
    (should= ['demo.epsilon] (#'tool/dynamic-lookup-targets '(find-ns 'demo.epsilon)))
    (should= ['demo.zeta] (#'tool/dynamic-lookup-targets '(the-ns 'demo.zeta)))
    (should= [] (#'tool/dynamic-lookup-targets '(println "x"))))

  (it "covers dependency helper branches and warnings"
    (should= nil (dep-base/require-target [:as :not-a-target]))
    (should (dep-base/called? '(require 'demo.a) "require"))
    (should-not (dep-base/called? '(println :x) "require"))
    (should= [{:kind :dynamic-namespace-lookup
               :callee "requiring-resolve"
               :targets ["demo.alpha"]}]
             (dep-base/extract-dynamic-lookup-warnings
              ['(println :x)
               '(requiring-resolve 'demo.alpha/run)])))

  (it "covers stats and reader helpers"
    (should= nil (stats-base/var-symbol '(def 42 :x)))
    (should= 'ok (stats-base/var-symbol '(def ok :x)))
    (should (reader-base/ns-form? '(ns demo.core)))
    (should-not (reader-base/ns-form? '(defn x [] 1))))

  (it "matches dependency exceptions and validates legacy config syntax"
    (let [ex (#'tool/compile-exception {:from-component :a
                                        :to-component :b
                                        :from-ns "demo.from"
                                        :to-ns "demo.to"})
          edge {:from-component :a :to-component :b :from-ns "demo.from" :to-ns "demo.to"}]
      (should (#'tool/exception-matches? ex edge))
      (should= #{:a :b} (cfg-base/ignored-components-set {:ignored-components [:a :b]}))
      (should-throw clojure.lang.ExceptionInfo
                    (#'tool/validate-config! {:source-paths ["src"]}))
      (should-throw clojure.lang.ExceptionInfo
                    (#'tool/validate-config! {:component-rules [{:component :a :match "demo.a*"}]}))))

  (it "covers forbidden-rule normalization and legacy rule detection branches"
    (should= {:from :a :to :b} (cfg-base/normalize-forbidden-rule {:from :a :to :b}))
    (should= {:from :a :to :b} (cfg-base/normalize-forbidden-rule [:a :b]))
    (should-throw clojure.lang.ExceptionInfo (cfg-base/normalize-forbidden-rule [:a :b :c]))
    (should (#'cfg-base/legacy-rule? {:match "demo.a*"}))
    (should (#'cfg-base/legacy-rule? [:a "demo.a*"]))
    (should-not (#'cfg-base/legacy-rule? :nope))
    (should= {:top-level [:component-rules :source-paths]
              :legacy-rules? true}
             (cfg-base/legacy-config-details {:source-paths ["src"]
                                              :component-rules [{:component :a :match "demo.a*"}]})))

  (it "does not add self dependencies when inferring allowed dependencies"
    (let [records [{:namespace "demo.alpha.core"
                    :requires #{"demo.alpha.util"}}
                   {:namespace "demo.alpha.util"
                    :requires #{}}
                   {:namespace "demo.beta.core"
                    :requires #{"demo.alpha.core"}}]
          inferred (infer/infer-allowed-deps records)]
      (should= [] (get inferred :alpha))
      (should= [:alpha] (get inferred :beta))))

  (it "covers infer helper functions for source records and component aggregation"
    (let [root (temp-dir)
          forms '((ns demo.alpha.core (:require [demo.beta.api :as b]))
                  (defprotocol Port (go [this])))
          records [{:namespace "demo.alpha.core" :requires #{"demo.beta.api" "demo.alpha.util"}}
                   {:namespace "demo.alpha.util" :requires #{}}
                   {:namespace "demo.beta.api" :requires #{}}]
          roots (#'infer/project-roots records)
          ns->component (#'infer/namespace->component-map roots records)
          components (#'infer/components roots records)]
      (write-file! root "demo/alpha/core.clj"
                   "(ns demo.alpha.core (:require [demo.beta.api :as b]))\n(defprotocol Port (go [this]))\n")
      (should= {:namespace "demo.alpha.core"
                :requires #{"demo.beta.api"}
                :public-count 1
                :abstract-count 1}
               (#'infer/source-ns-record forms))
      (should= #{"demo"} roots)
      (should= {"demo.alpha.core" :alpha
                "demo.alpha.util" :alpha
                "demo.beta.api" :beta}
               ns->component)
      (should= [:alpha :beta] components)
      (should= :beta (#'infer/dependency-component roots ns->component "demo.beta.api"))
      (should= nil (#'infer/dependency-component roots ns->component "clojure.string"))
      (should= {:alpha #{:beta} :beta #{}}
               (#'infer/add-component-dependency {:alpha #{} :beta #{}} :alpha :beta))
      (should= {:alpha #{} :beta #{}}
               (#'infer/add-component-dependency {:alpha #{} :beta #{}} :alpha :alpha))
      (should= {:alpha #{:beta} :beta #{}}
               (#'infer/record-component-deps roots
                                              ns->component
                                              {:alpha #{} :beta #{}}
                                              {:namespace "demo.alpha.core"
                                               :requires #{"demo.beta.api" "demo.alpha.util"}}))
      (should= {:alpha [:beta] :beta []}
               (#'infer/sorted-dependency-map components {:alpha #{:beta} :beta #{}}))
      (should= [{:namespace "demo.alpha.core"
                 :requires #{"demo.beta.api"}
                 :public-count 1
                 :abstract-count 1}]
               (infer/source-ns-records (.getPath root) #{".clj"}))))

  (it "generates starter config from multiple namespace shapes through the public path"
    (let [root (temp-dir)]
      (write-file! root "demo/alpha/core.clj"
                   "(ns demo.alpha.core (:require [demo.beta.api :as b] [demo.alpha.util :as u]))\n(defprotocol Port (go [this]))\n")
      (write-file! root "demo/alpha/util.clj"
                   "(ns demo.alpha.util)\n(defn helper [] :ok)\n")
      (write-file! root "demo/beta/api.clj"
                   "(ns demo.beta.api (:require [demo.gamma.core :as g]))\n(defn run [] (g/id))\n")
      (write-file! root "demo/gamma/core.clj"
                   "(ns demo.gamma.core)\n(defn id [] :ok)\n")
      (write-file! root "demo/misc/no_ns.clj"
                   "(defn x [] :ignored)\n")
      (should= {:allowed-dependencies {:alpha [:beta]
                                       :beta [:gamma]
                                       :gamma []}
                :fail-on-cycles true
                :fail-on-violations true}
               (infer/generate-starter-config (.getPath root)))))

  (it "includes fail gates in starter config on both arities"
    (with-redefs [infer/source-ns-records (fn [_ _] [])
                  infer/infer-allowed-deps (fn
                                             ([_] {})
                                             ([_ _] {}))]
      (should= {:allowed-dependencies {}
                :fail-on-cycles true
                :fail-on-violations true}
               (infer/generate-starter-config "src"))
      (should= {:allowed-dependencies {}
                :fail-on-cycles true
                :fail-on-violations true}
               (infer/generate-starter-config))))

  (it "includes ignored components in starter config only when configured"
    (with-redefs [infer/source-ns-records (fn [_ _] [])
                  infer/infer-allowed-deps (fn
                                             ([_] {})
                                             ([_ _] {}))]
      (should= {:allowed-dependencies {}
                :fail-on-cycles true
                :fail-on-violations true}
               (infer/generate-starter-config "src"))
      (should= {:allowed-dependencies {}
                :ignored-components [:spec-runner]
                :fail-on-cycles true
                :fail-on-violations true}
               (infer/generate-starter-config "src" #{:spec-runner}))))

  (it "generates a starter config from inferred component dependencies"
    (let [root (temp-dir)]
      (write-file! root "demo/alpha/core.clj" "(ns demo.alpha.core (:require [demo.beta.api :as b]))\n")
      (write-file! root "demo/beta/api.clj" "(ns demo.beta.api)\n")
      (should= {:allowed-dependencies {:alpha [:beta]
                                       :beta []}
                :fail-on-cycles true
                :fail-on-violations true}
               (#'tool/generate-starter-config (.getPath root)))))

  (it "infers dependencies while omitting ignored components"
    (let [records [{:namespace "demo.alpha.core"
                    :requires #{"demo.beta.api" "demo.spec-runner.main"}}
                   {:namespace "demo.beta.api"
                    :requires #{}}
                   {:namespace "demo.spec-runner.main"
                    :requires #{"demo.beta.api"}}]]
      (should= {:alpha [:beta]
                :beta []}
               (infer/infer-allowed-deps records #{:spec-runner}))))

  (it "computes strongly connected components for cyclic and acyclic graphs"
    (let [nodes #{:a :b :c :d}
          edges [[:a :b] [:b :a] [:b :c]]
          sccs (#'tool/strongly-connected-components nodes edges)
          sets (set (map set sccs))]
      (should (contains? sets #{:a :b}))
      (should (contains? sets #{:c}))
      (should (contains? sets #{:d}))))

  (it "finds forbidden violations only for matching component pairs"
    (let [ns-edges [{:from-component :ui
                     :to-component :db
                     :from-ns "demo.ui.core"
                     :to-ns "demo.db.core"}
                    {:from-component :ui
                     :to-component :svc
                     :from-ns "demo.ui.core"
                     :to-ns "demo.svc.core"}]
          forbidden [{:from :ui :to :db}
                     {:from :svc :to :db}]
          violations (#'graph/find-forbidden-violations ns-edges forbidden [])]
      (should= 1 (count violations))
      (should= :ui (:from-component (first violations)))
      (should= :db (:to-component (first violations)))))

  (it "ignores forbidden rules whose component pair does not match the edge"
    (let [ns-edges [{:from-component :ui
                     :to-component :svc
                     :from-ns "demo.ui.core"
                     :to-ns "demo.svc.core"}]
          forbidden [{:from :ui :to :db}
                     {:from :worker :to :svc}]]
      (should= [] (#'graph/find-forbidden-violations ns-edges forbidden []))))

  (it "ignores non-matching forbidden rules through analyze-project"
    (let [root (temp-dir)]
      (write-file! root "demo/ui/core.clj"
                   "(ns demo.ui.core (:require [demo.svc.core :as svc]))\n(defn run [] (svc/run))\n")
      (write-file! root "demo/svc/core.clj"
                   "(ns demo.svc.core)\n(defn run [] :ok)\n")
      (let [result (tool/analyze-project {:forbidden-dependencies [{:from :ui :to :db}
                                                                   {:from :worker :to :svc}]}
                                         (.getPath root))]
        (should= [] (:violations result))))))
