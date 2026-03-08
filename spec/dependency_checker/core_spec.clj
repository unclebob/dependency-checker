(ns dependency-checker.core-spec
  (:require [speclj.core :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dependency-checker.core.base.config :as cfg-base]
            [dependency-checker.core.base.dependencies :as dep-base]
            [dependency-checker.core.base.reader :as reader-base]
            [dependency-checker.core.base.stats :as stats-base]
             [dependency-checker.core.graph :as graph]
             [dependency-checker.core.infer :as infer]
             [dependency-checker.core.report :as report]
             [dependency-checker.core :as tool]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "dependency-tool-spec" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-file!
  [root rel-path content]
  (let [f (io/file root rel-path)]
    (io/make-parents f)
    (spit f content)))

(describe "dependency-tool/analyze-project"
  (it "computes component dependencies, cycles, and boundary violations"
    (let [root (temp-dir)]
      (write-file! root "demo/a.clj"
                   "(ns demo.a (:require [demo.b :as b] [demo.c :as c]))\n(defprotocol APort (x [this]))\n")
      (write-file! root "demo/b.clj"
                   "(ns demo.b (:require [demo.c :as c]))\n(defn b-fn [] :ok)\n")
      (write-file! root "demo/c.clj"
                   "(ns demo.c (:require [demo.a :as a]))\n(def ^:private hidden 1)\n(defn c-fn [] :ok)\n")
      (let [result (tool/analyze-project
                    {:source-paths [(.getPath root)]
                     :component-rules [{:component :alpha :match "demo.a"}
                                       {:component :beta :match "demo.b"}
                                       {:component :gamma :match "demo.c"}]
                     :allowed-dependencies {:alpha [:gamma]
                                              :beta [:gamma]
                                              :gamma [:alpha]}})]
        (should= #{[:alpha :beta] [:alpha :gamma] [:beta :gamma] [:gamma :alpha]}
                 (set (:component-edges result)))
        (should= 1 (count (:violations result)))
        (should= :alpha (:from-component (first (:violations result))))
        (should= :beta (:to-component (first (:violations result))))
        (should (some #(= #{:alpha :beta :gamma} (set %)) (:cycles result))))))

  (it "calculates fan-in, fan-out, instability, abstractness, and distance metrics"
    (let [root (temp-dir)]
      (write-file! root "demo/a.clj"
                   "(ns demo.a (:require [demo.b :as b] [demo.c :as c]))\n(defprotocol APort (x [this]))\n")
      (write-file! root "demo/b.clj"
                   "(ns demo.b (:require [demo.c :as c]))\n(defn b-fn [] :ok)\n")
      (write-file! root "demo/c.clj"
                   "(ns demo.c (:require [demo.a :as a]))\n(defn c-fn [] :ok)\n")
      (let [result (tool/analyze-project
                    {:source-paths [(.getPath root)]
                     :component-rules [{:component :alpha :match "demo.a"}
                                       {:component :beta :match "demo.b"}
                                       {:component :gamma :match "demo.c"}]})
            stats (:component-stats result)]
        (should= 1 (get-in stats [:alpha :fan-in]))
        (should= 2 (get-in stats [:alpha :fan-out]))
        (should= 1 (get-in stats [:alpha :public-vars]))
        (should= 1 (get-in stats [:alpha :abstract-vars]))
        (should (< (Math/abs (- 0.6666666666666666 (get-in stats [:alpha :instability]))) 1.0e-9))
        (should (< (Math/abs (- 1.0 (get-in stats [:alpha :abstractness]))) 1.0e-9))
        (should (< (Math/abs (- 0.6666666666666666 (get-in stats [:alpha :distance]))) 1.0e-9))
        (should (< (Math/abs (- 0.5 (get-in stats [:beta :instability]))) 1.0e-9))
        (should (< (Math/abs (- 0.0 (get-in stats [:beta :abstractness]))) 1.0e-9))
        (should (< (Math/abs (- 0.5 (get-in stats [:beta :distance]))) 1.0e-9))
        (should= :useless (get-in stats [:alpha :zone]))
        (should= :pain (get-in stats [:beta :zone])))))

  (it "uses healthy-threshold from config for zone classification"
    (let [root (temp-dir)]
      (write-file! root "demo/a.clj"
                   "(ns demo.a (:require [demo.b :as b]))\n(defn a-fn [] :ok)\n")
      (write-file! root "demo/b.clj"
                   "(ns demo.b)\n(defn b-fn [] :ok)\n")
      (let [default-result (tool/analyze-project
                            {:source-paths [(.getPath root)]
                             :component-rules [{:component :alpha :match "demo.a"}
                                               {:component :beta :match "demo.b"}]})
            wide-result (tool/analyze-project
                         {:source-paths [(.getPath root)]
                          :component-rules [{:component :alpha :match "demo.a"}
                                            {:component :beta :match "demo.b"}]
                          :healthy-threshold 0.5})]
        (should= :pain (get-in (:component-stats default-result) [:beta :zone]))
        (should= :healthy (get-in (:component-stats wide-result) [:alpha :zone]))
        (should= :pain (get-in (:component-stats wide-result) [:beta :zone])))))

  (it "uses allowed-dependencies to flag edges not in the allowed map"
    (let [root (temp-dir)]
      (write-file! root "demo/a.clj"
                   "(ns demo.a (:require [demo.b :as b] [demo.c :as c]))\n")
      (write-file! root "demo/b.clj"
                   "(ns demo.b (:require [demo.c :as c]))\n")
      (write-file! root "demo/c.clj"
                   "(ns demo.c)\n")
      (let [result (tool/analyze-project
                    {:source-paths [(.getPath root)]
                     :component-rules [{:component :alpha :match "demo.a"}
                                       {:component :beta :match "demo.b"}
                                       {:component :gamma :match "demo.c"}]
                     :allowed-dependencies {:alpha [:gamma]
                                            :beta [:gamma]
                                            :gamma []}})]
        (should= 1 (count (:violations result)))
        (should= :alpha (:from-component (first (:violations result))))
        (should= :beta (:to-component (first (:violations result)))))))

  (it "allows all dependencies when component has :all in allowed-dependencies"
    (let [root (temp-dir)]
      (write-file! root "demo/a.clj"
                   "(ns demo.a (:require [demo.b :as b]))\n")
      (write-file! root "demo/b.clj"
                   "(ns demo.b)\n")
      (let [result (tool/analyze-project
                    {:source-paths [(.getPath root)]
                     :component-rules [{:component :alpha :match "demo.a"}
                                       {:component :beta :match "demo.b"}]
                     :allowed-dependencies {:alpha :all
                                            :beta []}})]
        (should= 0 (count (:violations result))))))

  (it "supports allowed exceptions for disallowed component dependencies"
    (let [root (temp-dir)]
      (write-file! root "demo/a.clj" "(ns demo.a (:require [demo.b :as b]))\n(defn call [] (b/id))\n")
      (write-file! root "demo/b.clj" "(ns demo.b)\n(defn id [] :ok)\n")
      (let [result (tool/analyze-project
                    {:source-paths [(.getPath root)]
                     :component-rules [{:component :left :match "demo.a"}
                                       {:component :right :match "demo.b"}]
                     :allowed-dependencies {:left []
                                            :right []}
                     :allowed-exceptions [{:from-ns "demo.a" :to-ns "demo.b"}]})]
        (should= [] (:violations result)))))

  (it "includes dynamic namespace lookups in dependency metrics and emits warnings"
    (let [root (temp-dir)]
      (write-file! root "demo/a.clj"
                   "(ns demo.a (:import [demo.imported Thing]))\n(require '[demo.b :as b])\n(defn call []\n  (requiring-resolve 'demo.c/run)\n  (resolve 'demo.d/id)\n  (ns-resolve 'demo.e 'id)\n  (find-ns 'demo.f)\n  (the-ns 'demo.g)\n  (b/id))\n")
      (write-file! root "demo/b.clj" "(ns demo.b)\n(defn id [] :ok)\n")
      (write-file! root "demo/c.clj" "(ns demo.c)\n(defn run [] :ok)\n")
      (write-file! root "demo/d.clj" "(ns demo.d)\n(defn id [] :ok)\n")
      (write-file! root "demo/e.clj" "(ns demo.e)\n(defn id [] :ok)\n")
      (write-file! root "demo/f.clj" "(ns demo.f)\n(defn id [] :ok)\n")
      (write-file! root "demo/g.clj" "(ns demo.g)\n(defn id [] :ok)\n")
      (let [result (tool/analyze-project
                    {:source-paths [(.getPath root)]
                     :component-rules [{:component :alpha :match "demo.a"}
                                       {:component :beta :match "demo.b"}
                                       {:component :gamma :match "demo.c"}
                                       {:component :delta :match "demo.d"}
                                       {:component :epsilon :match "demo.e"}
                                       {:component :zeta :match "demo.f"}
                                       {:component :eta :match "demo.g"}
                                       {:component :imports :match "demo.imported"}]})
            stats (:component-stats result)]
        (should= #{[:alpha :beta] [:alpha :gamma] [:alpha :delta] [:alpha :epsilon] [:alpha :zeta] [:alpha :eta] [:alpha :imports]}
                 (set (:component-edges result)))
        (should= 7 (get-in stats [:alpha :fan-out]))
        (should= 5 (count (:warnings result)))
        (should (every? #(= "demo.a" (:namespace %)) (:warnings result))))))

  (it "generates a starter config with inferred component rules"
    (let [root (temp-dir)]
      (write-file! root "empire/application/runtime.cljc" "(ns empire.application.runtime)\n")
      (write-file! root "empire/adapters/state.cljc" "(ns empire.adapters.state)\n")
      (write-file! root "empire/acceptance/parser.cljc" "(ns empire.acceptance.parser)\n")
      (write-file! root "empire/acceptance/generator.cljc" "(ns empire.acceptance.generator)\n")
      (let [cfg (#'tool/generate-starter-config [(.getPath root)])
            by-component (into {} (map (juxt :component identity) (:component-rules cfg)))]
        (should= "empire.application*" (:match (get by-component :application)))
        (should= "empire.adapters*" (:match (get by-component :adapters)))
        (should= "empire.acceptance*" (:match (get by-component :acceptance))))))

  (it "generates starter config allowed-dependencies from observed component edges"
    (let [root (temp-dir)]
      (write-file! root "demo/a/core.clj"
                   "(ns demo.a.core (:require [demo.b.core :as b] [demo.c.core :as c]))\n(defn run [] (b/run) (c/run))\n")
      (write-file! root "demo/b/core.clj"
                   "(ns demo.b.core (:require [demo.c.core :as c]))\n(defn run [] (c/run))\n")
      (write-file! root "demo/c/core.clj"
                   "(ns demo.c.core)\n(defn run [] :ok)\n")
      (let [cfg (#'tool/generate-starter-config [(.getPath root)])]
        (should= {:a [:b :c]
                  :b [:c]
                  :c []}
                 (:allowed-dependencies cfg)))))

  (it "infers abstract and concrete component roots from module abstractness"
    (let [root (temp-dir)]
      (write-file! root "empire/api/protocols.cljc"
                   "(ns empire.api.protocols)\n(defprotocol Port (go [this]))\n")
      (write-file! root "empire/api/events.cljc"
                   "(ns empire.api.events)\n(defmulti handle-event :type)\n")
      (write-file! root "empire/impl/service_a.cljc"
                   "(ns empire.impl.service-a (:require [empire.api.protocols :as p]))\n(defn run [] :ok)\n")
      (write-file! root "empire/impl/service_b.cljc"
                   "(ns empire.impl.service-b (:require [empire.api.events :as e]))\n(defn execute [] :ok)\n")
      (let [cfg (#'tool/generate-starter-config [(.getPath root)])
            by-component (into {} (map (juxt :component identity) (:component-rules cfg)))]
        (should= "empire.api*" (:match (get by-component :api)))
        (should= "empire.impl*" (:match (get by-component :impl)))))))

(describe "dependency-tool helper behavior"
  (it "matches patterns for keyword, exact string, glob, and regex"
    (let [kw-m (#'tool/pattern->matcher :demo.ns)
          exact-m (#'tool/pattern->matcher "demo.ns")
          glob-m (#'tool/pattern->matcher "demo.*")
          rx-m (#'tool/pattern->matcher "^demo\\..*")
          invalid-m (#'tool/pattern->matcher 42)]
      (should (kw-m "demo.ns"))
      (should-not (kw-m "demo.other"))
      (should (exact-m "demo.ns"))
      (should-not (exact-m "demo.ns.x"))
      (should (glob-m "demo.alpha"))
      (should (rx-m "demo.beta"))
      (should-not (invalid-m "anything"))))

  (it "normalizes component rules in map and vector forms"
    (let [map-rule (#'tool/compile-normalized-rule (#'tool/normalize-component-rule {:component :c1 :match "demo.*"}))
          vec-rule (#'tool/compile-normalized-rule (#'tool/normalize-component-rule [:c2 "demo.x"]))
          invalid-rule (fn [] (#'tool/normalize-component-rule :bad))]
      (should= :c1 (:component map-rule))
      (should ((:matches? map-rule) "demo.a"))
      (should= :c2 (:component vec-rule))
      (should ((:matches? vec-rule) "demo.x"))
      (should-not ((:matches? vec-rule) "demo.y"))
      (should-throw clojure.lang.ExceptionInfo (invalid-rule))))

  (it "normalizes match-patterns for nil, scalar, and sequential definitions"
    (should= [] (#'tool/match-patterns {:component :a}))
    (should= ["demo.*"] (#'tool/match-patterns {:component :a :match "demo.*"}))
    (should= ["demo.a" "demo.b"] (#'tool/match-patterns {:component :a :matches ["demo.a" "demo.b"]})))

  (it "reads and writes config and reports usage and help text"
    (let [root (temp-dir)
          cfg-file (io/file root "dep.edn")
          cfg {:source-paths ["src"] :component-rules [{:component :a :match "a.*"}]}
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
      (should (str/includes? (:text usage-output) "Usage: clj -M:check-dependencies"))
      (should= 0 (:code help-output))
      (should (str/includes? (:text help-output) "Usage: clj -M:check-dependencies"))))

  (it "classifies components into zones based on abstractness and instability"
    (should= :pain (graph/classify-zone 0.0 0.0 0.3))
    (should= :pain (graph/classify-zone 0.1 0.2 0.3))
    (should= :useless (graph/classify-zone 1.0 1.0 0.3))
    (should= :useless (graph/classify-zone 0.8 0.8 0.3))
    (should= :healthy (graph/classify-zone 0.0 1.0 0.3))
    (should= :healthy (graph/classify-zone 1.0 0.0 0.3))
    (should= :healthy (graph/classify-zone 0.5 0.5 0.3)))

  (it "classifies zones using a custom healthy-threshold"
    (should= :healthy (graph/classify-zone 0.3 0.3 0.5))
    (should= :pain (graph/classify-zone 0.3 0.3 0.1))
    (should= :healthy (graph/classify-zone 0.8 0.8 0.7))
    (should= :useless (graph/classify-zone 0.8 0.8 0.1)))

  (it "colorizes zone labels with ANSI codes and intensity based on distance"
    (let [deep-pain (report/colorize-zone :pain 1.0 0.3)
          mild-pain (report/colorize-zone :pain 0.35 0.3)
          deep-useless (report/colorize-zone :useless 1.0 0.3)
          mild-useless (report/colorize-zone :useless 0.35 0.3)
          healthy (report/colorize-zone :healthy 0.0 0.3)
          mild-healthy (report/colorize-zone :healthy 0.25 0.3)]
      (should (str/includes? deep-pain "pain"))
      (should (str/includes? mild-pain "pain"))
      (should (str/includes? deep-useless "useless"))
      (should (str/includes? healthy "healthy"))
      (should (str/includes? mild-healthy "healthy"))
      (should (str/includes? deep-pain "\u001b["))
      (should (str/includes? deep-useless "\u001b["))
      (should (str/includes? healthy "\u001b["))))

  (it "omits ANSI codes when color is disabled"
    (let [report (with-out-str
                   (report/report-text
                    {:component-stats {:alpha {:fan-in 0
                                               :fan-out 0
                                               :instability 0.0
                                               :abstractness 0.0
                                               :distance 1.0
                                               :zone :pain}}
                     :component-edges []
                     :warnings []
                     :violations []
                     :cycles []}
                    {:color? false}))]
      (should (str/includes? report "pain"))
      (should-not (str/includes? report "\u001b["))))

  (it "includes Zone column in text report"
    (let [report (with-out-str
                   (#'tool/report-text
                    {:component-stats {:alpha {:fan-in 0
                                               :fan-out 0
                                               :instability 0.0
                                               :abstractness 0.0
                                               :distance 1.0
                                               :zone :pain}}
                     :component-edges []
                     :warnings []
                     :violations []
                     :cycles []}))]
      (should (str/includes? report "Zone"))
      (should (str/includes? report "pain"))))

  (it "formats text report with warnings, violations, and cycles"
    (let [report (with-out-str
                   (#'tool/report-text
                    {:component-stats {:alpha {:fan-in 1
                                               :fan-out 2
                                               :instability 0.66
                                               :abstractness 0.10
                                               :distance 0.24
                                               :zone :pain}}
                     :component-edges [[:alpha :beta]]
                     :warnings [{:namespace "demo.a" :callee "requiring-resolve" :targets ["demo.b"]}]
                     :violations [{:from-component :alpha
                                   :to-component :beta
                                   :from-ns "demo.a"
                                   :to-ns "demo.b"}]
                     :cycles [[:alpha :beta]]}))]
      (should (str/includes? report "Component Dependencies"))
      (should (str/includes? report ":alpha -> :beta"))
      (should (str/includes? report "Warnings: 1"))
      (should (str/includes? report "demo.a uses requiring-resolve -> demo.b"))
      (should (str/includes? report "Boundary Violations"))
      (should (str/includes? report "Cycles"))))

  (it "omits Component Dependencies when edges? is false"
    (let [report (with-out-str
                   (#'tool/report-text
                    {:component-stats {:alpha {:fan-in 1
                                               :fan-out 2
                                               :instability 0.66
                                               :abstractness 0.10
                                               :distance 0.24
                                               :zone :pain}}
                     :component-edges [[:alpha :beta]]
                     :warnings []
                     :violations []
                     :cycles []}
                    {:edges? false}))]
      (should-not (str/includes? report "Component Dependencies"))
      (should-not (str/includes? report ":alpha -> :beta"))
      (should (str/includes? report "Component Metrics")))))

(describe "dependency-tool CLI flow"
  (it "parses args with defaults"
    (let [defaults (#'tool/parse-args [])]
      (should= "dependency-checker.edn" (:config-path defaults))
      (should= :text (:fmt defaults))
      (should= false (:help? defaults))
      (should= false (:init? defaults))
      (should= false (:force-init? defaults))
      (should= true (:color? defaults))
      (should= true (:edges? defaults))))

  (it "parses --no-color flag"
    (let [parsed (#'tool/parse-args ["--no-color"])]
      (should= false (:color? parsed))))

  (it "parses --no-edges flag"
    (let [parsed (#'tool/parse-args ["--no-edges"])]
      (should= false (:edges? parsed))))

  (it "parses explicit config path and options"
    (let [parsed (#'tool/parse-args ["cfg.edn" "--format" "edn" "--init"])]
      (should= "cfg.edn" (:config-path parsed))
      (should= :edn (:fmt parsed))
      (should= true (:init? parsed))
      (should= false (:force-init? parsed))))

  (it "parses --help flag"
    (let [parsed (#'tool/parse-args ["--help"])]
      (should= "dependency-checker.edn" (:config-path parsed))
      (should= true (:help? parsed))))

  (it "returns usage error for unknown option"
    (should= :usage (:error (#'tool/parse-args ["--unknown"])))
    (should= :usage (:error (#'tool/parse-args ["--format"]))))

  (it "run-cli creates config for --init"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))
          result (with-redefs [tool/generate-starter-config (fn [] {:source-paths ["src"] :component-rules []})]
                   (let [code (atom nil)]
                     (with-out-str (reset! code (#'tool/run-cli [cfg-path "--init"])))
                     @code))]
      (should= 0 result)
      (should (.exists (io/file cfg-path)))))

  (it "run-cli --init does not overwrite existing config"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))]
      (with-redefs [tool/generate-starter-config (fn [] {:source-paths ["src"] :component-rules []})]
        (with-out-str (#'tool/run-cli [cfg-path "--init"])))
      (let [before (slurp cfg-path)]
        (with-out-str (#'tool/run-cli [cfg-path "--init"]))
        (should= before (slurp cfg-path)))))

  (it "run-cli overwrites existing config for --force-init"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))]
      (with-redefs [tool/generate-starter-config (fn [] {:source-paths ["src"] :component-rules []})]
        (with-out-str (#'tool/run-cli [cfg-path "--init"])))
      (with-redefs [tool/generate-starter-config (fn [] {:source-paths ["other"] :component-rules []})]
        (with-out-str (#'tool/run-cli [cfg-path "--force-init"])))
      (should (str/includes? (slurp cfg-path) "other"))))

  (it "run-cli returns usage error when --init and --force-init are both set"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))]
      (binding [*err* (java.io.StringWriter.)]
        (should= 2 (#'tool/run-cli [cfg-path "--init" "--force-init"])))))

  (it "run-cli returns 2 for unsupported format"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))]
      (spit cfg-path "{}")
      (with-redefs [tool/analyze-project (fn [_]
                                           {:config {:fail-on-violations true :fail-on-cycles true}
                                            :component-stats {:alpha {:distance 0.0}}
                                            :violations []
                                            :cycles []
                                            :component-edges []
                                            :warnings []})
                    tool/report-text (fn [& _] nil)]
        (binding [*err* (java.io.StringWriter.)]
          (should= 2 (#'tool/run-cli [cfg-path "--format" "xml"]))))))

  (it "run-cli returns 1 when analysis fails"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))]
      (spit cfg-path "{}")
      (with-redefs [tool/analyze-project (fn [_]
                                           {:config {:fail-on-violations true :fail-on-cycles true}
                                            :component-stats {:alpha {:distance 1.2}}
                                            :violations [{:from-component :a}]
                                            :cycles []
                                            :component-edges []
                                            :warnings []})
                    tool/report-text (fn [& _] nil)]
        (should= 1 (#'tool/run-cli [cfg-path])))))

  (it "run-cli returns 0 when analysis passes"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))]
      (spit cfg-path "{}")
      (with-redefs [tool/analyze-project (fn [_]
                                           {:config {:fail-on-violations true :fail-on-cycles true}
                                            :component-stats {:alpha {:distance 0.0}}
                                            :violations []
                                            :cycles []
                                            :component-edges []
                                            :warnings []})
                    tool/report-text (fn [& _] nil)]
        (should= 0 (#'tool/run-cli [cfg-path])))))

  (it "run-cli supports edn output format"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))
          result {:config {:fail-on-violations true :fail-on-cycles true}
                  :component-stats {:alpha {:distance 0.0}}
                  :violations []
                  :cycles []
                  :component-edges []
                  :warnings []}
          out (java.io.StringWriter.)]
      (spit cfg-path "{}")
      (with-redefs [tool/analyze-project (fn [_] result)]
        (binding [*out* out]
          (should= 0 (#'tool/run-cli [cfg-path "--format" "edn"]))))
      (should (str/includes? (str out) ":component-stats"))))

  (it "run-cli returns 0 for --force-init when config does not yet exist"
    (let [root (temp-dir)
          cfg-path (.getPath (io/file root "dep.edn"))
          result (with-redefs [tool/generate-starter-config (fn [] {:source-paths ["src"]})]
                   (let [code (atom nil)]
                     (with-out-str (reset! code (#'tool/run-cli [cfg-path "--force-init"])))
                     @code))]
      (should= 0 result)))

  (it "run-cli returns usage error for unknown top-level argument"
    (binding [*err* (java.io.StringWriter.)]
      (should= 2 (#'tool/run-cli ["--bogus"]))))

  (it "run-cli returns usage error for missing --format value"
    (should= :usage (:error (#'tool/parse-args ["--format"])))
    (binding [*err* (java.io.StringWriter.)]
      (should= 2 (#'tool/run-cli ["--format"]))))

  (it "run-cli prints help and exits 0 for --help"
    (let [out (java.io.StringWriter.)]
      (binding [*out* out]
        (should= 0 (#'tool/run-cli ["--help"])))
      (should (str/includes? (str out) "Usage: clj -M:check-dependencies")))))

(describe "dependency-tool inference helpers"
  (it "infers concrete prefixes and prefix helpers"
    (let [records [{:namespace "empire.api.port" :requires #{} :public-count 1 :abstract-count 1}
                   {:namespace "empire.impl.a" :requires #{"empire.api.port"} :public-count 1 :abstract-count 0}
                   {:namespace "empire.impl.b" :requires #{"empire.api.port"} :public-count 1 :abstract-count 0}]
          abstract-prefixes #{"empire.api"}
          concrete (#'tool/infer-concrete-prefixes records abstract-prefixes)]
      (should (contains? concrete "empire.impl"))
      (should= "empire" (#'tool/parent-prefix "empire.api"))
      (should= nil (#'tool/parent-prefix "empire"))
      (should= "empire.api" (#'tool/best-abstract-prefix abstract-prefixes "empire.api.port")))))

  (it "covers infer helpers for prefixes, abstractness, and component naming"
    (should= ["empire"] (vec (infer/ns-prefixes "empire")))
    (should= ["empire" "empire.api" "empire.api.port"]
             (vec (infer/ns-prefixes "empire.api.port")))
    (should-not (infer/module-abstract? {:public-count 1 :abstract-count 0}))
    (should (infer/module-abstract? {:public-count 2 :abstract-count 2}))
    (should= :api-port (infer/prefix->component "empire" "empire.api.port"))
    (should= :demo-core (infer/prefix->component "empire" "demo.core")))

(describe "dependency-tool decrap targets"
  (it "handles ns-target for symbol, string, quoted symbol, and unsupported types"
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

  (it "covers dependency helper branches for require targets, call detection, and warnings"
    (should= nil (dep-base/require-target [:as :not-a-target]))
    (should (dep-base/called? '(require 'demo.a) "require"))
    (should-not (dep-base/called? '(println :x) "require"))
    (should= [{:kind :dynamic-namespace-lookup
               :callee "requiring-resolve"
               :targets ["demo.alpha"]}]
             (dep-base/extract-dynamic-lookup-warnings
             ['(println :x)
               '(requiring-resolve 'demo.alpha/run)])))

  (it "covers stats helper branch when var name is not a symbol"
    (should= nil (stats-base/var-symbol '(def 42 :x)))
    (should= 'ok (stats-base/var-symbol '(def ok :x))))

  (it "covers reader ns-form predicate true and false cases"
    (should (reader-base/ns-form? '(ns demo.core)))
    (should-not (reader-base/ns-form? '(defn x [] 1))))

  (it "matches dependency exceptions by component and namespace rules"
    (let [ex (#'tool/compile-exception {:from-component :a
                                        :to-component :b
                                        :from-ns "demo.from"
                                        :to-ns "demo.to"})
          edge {:from-component :a :to-component :b :from-ns "demo.from" :to-ns "demo.to"}
          wrong-comp (assoc edge :to-component :c)
          wrong-ns (assoc edge :to-ns "demo.other")]
      (should (#'tool/exception-matches? ex edge))
      (should-not (#'tool/exception-matches? ex wrong-comp))
      (should-not (#'tool/exception-matches? ex wrong-ns))
      (should (#'tool/exception-matches? (#'tool/compile-exception {}) edge))))

  (it "covers config helper branches for matchers, abs-num, forbidden rules, and exceptions"
    (let [kw-matcher (#'tool/pattern->matcher :demo.ns)
          bad-matcher (#'tool/pattern->matcher 1234)]
      (should (kw-matcher "demo.ns"))
      (should-not (kw-matcher "demo.other"))
      (should-not (bad-matcher "anything")))
    (should= 3 (cfg-base/abs-num -3))
    (should= 3 (cfg-base/abs-num 3))
    (should= {:from :a :to :b} (cfg-base/normalize-forbidden-rule [:a :b]))
    (should-throw clojure.lang.ExceptionInfo (cfg-base/normalize-forbidden-rule [:a :b :c]))
    (should (#'tool/exception-matches?
             (#'tool/compile-exception {:from-component :a})
             {:from-component :a :to-component :z :from-ns "x" :to-ns "y"})))

  (it "does not add self dependencies when inferring allowed dependencies"
    (let [records [{:namespace "demo.app.alpha.core"
                    :requires #{"demo.app.alpha.util"}}
                   {:namespace "demo.app.alpha.util"
                    :requires #{}}
                   {:namespace "demo.app.beta.core"
                    :requires #{"demo.app.alpha.core"}}]
          rules [{:component :alpha :match "demo.app.alpha*"}
                 {:component :beta :match "demo.app.beta*"}]
          inferred (infer/infer-allowed-deps records rules)]
      (should= [] (get inferred :alpha))
      (should= [:alpha] (get inferred :beta))))

  (it "infers abstract prefixes from fully abstract namespace trees"
    (let [records [{:namespace "demo.api.port" :public-count 1 :abstract-count 1}
                   {:namespace "demo.api.events" :public-count 1 :abstract-count 1}
                   {:namespace "demo.impl.core" :public-count 1 :abstract-count 0}]
          inferred (#'tool/infer-abstract-prefixes records)]
      (should (contains? inferred "demo.api"))
      (should-not (contains? inferred "demo.impl"))))

  (it "generates a starter config for empty source roots"
    (let [root (temp-dir)
          cfg (#'tool/generate-starter-config [(.getPath root)])]
      (should= [(.getPath root)] (:source-paths cfg))
      (should (vector? (:component-rules cfg)))
      (should (map? (:allowed-dependencies cfg)))
      (should= true (:fail-on-cycles cfg))
      (should= true (:fail-on-violations cfg))))

  (it "generates starter config with fallback namespace partitioning"
    (let [root (temp-dir)]
      (write-file! root "demo/a/core.clj" "(ns demo.a.core)\n")
      (write-file! root "demo/b/core.clj" "(ns demo.b.core)\n")
      (let [cfg (#'tool/generate-starter-config [(.getPath root)])
            components (set (map :component (:component-rules cfg)))]
        (should (contains? components :a))
        (should (contains? components :b)))))

  (it "prefers the first matching prefix when component names collide"
    (let [root (temp-dir)]
      (write-file! root "demo/ab/c.clj" "(ns demo.ab.c)\n(defprotocol P (x [this]))\n")
      (write-file! root "demo/ab/d.clj" "(ns demo.ab.d)\n(defn d [] :ok)\n")
      (write-file! root "demo/ab_c/core.clj" "(ns demo.ab-c.core)\n(defn c [] :ok)\n")
      (let [cfg (#'tool/generate-starter-config [(.getPath root)])
            by-component (into {} (map (juxt :component identity) (:component-rules cfg)))]
        (should= "demo.ab-c*" (:match (get by-component :ab-c))))))

  (it "computes strongly connected components for cyclic and acyclic graphs"
    (let [nodes #{:a :b :c :d}
          edges [[:a :b] [:b :a] [:b :c]]
          sccs (#'tool/strongly-connected-components nodes edges)
          sets (set (map set sccs))]
      (should (contains? sets #{:a :b}))
      (should (contains? sets #{:c}))
      (should (contains? sets #{:d}))))

  (it "keeps self-loop nodes as single-node components in SCC output"
    (let [nodes #{:x :y}
          edges [[:x :x]]
          sccs (#'tool/strongly-connected-components nodes edges)
          sets (set (map set sccs))]
      (should (contains? sets #{:x}))
      (should (contains? sets #{:y}))))

  (it "handles edges to already-visited nodes that are no longer on stack"
    (let [nodes #{:a :b :c}
          edges [[:a :b] [:b :c] [:c :b] [:a :c]]
          sccs (#'tool/strongly-connected-components nodes edges)
          sets (set (map set sccs))]
      (should (contains? sets #{:a}))
      (should (contains? sets #{:b :c}))))

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
      (should= :db (:to-component (first violations)))
      (should= {:from :ui :to :db}
               (select-keys (:rule (first violations)) [:from :to])))))
