# Dependency Checker

Analyzes component boundaries and architecture health for Clojure projects.

## Usage in Your Project

### Clojure CLI

Add an alias to your `deps.edn`:

```clojure
{:aliases
 {:check-dependencies
  {:extra-deps {io.github.unclebob/dependency-checker {:git/tag "..." :git/sha "..."}}
   :main-opts ["-m" "dependency-checker.core"]}}}
```

### Babashka

Add a task to your `bb.edn`:

```clojure
{:tasks {check-dependencies {:doc "Run dependency checker"
                             :extra-deps {io.github.unclebob/dependency-checker {:git/tag "..." :git/sha "..."}}
                             :requires ([dependency-checker.core :as dc])
                             :task (apply dc/-main *command-line-args*)}}}
```

Then run with `bb check-dependencies` (same arguments as below).

### Running

    clj -M:check-dependencies
    clj -M:check-dependencies dependency-checker.edn
    clj -M:check-dependencies dependency-checker.edn --format edn
    clj -M:check-dependencies --help

Use `--help` to print a usage summary to stdout.

Create or recreate starter config:

    clj -M:check-dependencies dependency-checker.edn --init
    clj -M:check-dependencies dependency-checker.edn --force-init

## Working Directory Behavior

The checker resolves paths from the current working directory.

- Default config path `dependency-checker.edn` is resolved relative to the current working directory.
- `:source-paths` entries are also resolved relative to the current working directory unless you use absolute paths.

## Config File

Default config path is `dependency-checker.edn`.

### Component Rules

Each rule maps a component name to a namespace regex pattern:

```clojure
{:component-rules
 [{:component :ui
   :match "^sample\\.ui(\\..*)?$"}
  {:component :game
   :match "^sample\\.game(\\..*)?$"}]}
```

Rules are matched in order — the first matching rule wins. A catch-all rule (e.g., `"sample.*"`) can be placed last.

### Allowed Dependencies

The `:allowed-dependencies` map declares which components each component may depend on. Any dependency not listed is a violation.

```clojure
{:allowed-dependencies
 {:ui [:game :player :state :config]
  :game [:player :state :config]
  :player [:state :config]
  :state [:config]
  :config []
  :test-infra :all}}
```

Use `:all` to allow a component to depend on anything (useful for test infrastructure).

Self-dependencies (a component depending on itself) are always allowed.

### Forbidden Dependencies

Use `:forbidden-dependencies` to list component edges that are not allowed.

```clojure
{:forbidden-dependencies [[:ui :state]
                          [:game :config]]}
```

You can also use map form:

```clojure
{:forbidden-dependencies [{:from :ui :to :state}
                          {:from :game :to :config}]}
```

### Allowed Exceptions

Specific namespace-level edges can be exempted from violation reporting:

```clojure
{:allowed-exceptions [{:from-ns "sample.player.production"
                       :to-ns "sample.computer.production"}]}
```

### Failure Flags

```clojure
{:fail-on-violations true   ; exit 1 if boundary violations found
 :fail-on-cycles true}      ; exit 1 if component cycles found
```

## Metrics

Reports per-component: fan-in, fan-out, instability, abstractness, and distance from the main sequence.

Fan-in/fan-out edges are derived from:
- `ns :require`, `ns :use`, `ns :import`
- Direct `(require ...)`
- Dynamic namespace lookup forms: `requiring-resolve`, `resolve`, `ns-resolve`, `find-ns`, `the-ns`

Dynamic lookup usage is emitted as warnings.

## Abstractness

A symbol counts as abstract only when it represents real indirection (`defprotocol`, `defmulti`). Config-only marking does not count.

## Starter Config

`--init` infers components from namespace structure:
- Abstract components from subtrees containing only `defprotocol`/`defmulti` modules.
- Concrete components from implementing subtrees.
- Seeds `:allowed-dependencies` from observed component-to-component dependencies.

## Example Project

```text
sample-app/
  deps.edn
  dependency-checker.edn
  src/
    sample_app/cli.clj
    sample_app/core/service.clj
    sample_app/core/repo.clj
```

```clojure
;; sample-app/dependency-checker.edn
{:source-paths ["src"]
 :component-rules [{:component :core :match "sample-app.core*"}
                   {:component :cli :match "sample-app.cli*"}]
 :allowed-dependencies {:core []
                        :cli [:core]}
 :fail-on-cycles true
 :fail-on-violations true}
```

Run from `sample-app/`:

    clj -M:check-dependencies

## Example Output

```text
Dependency Analysis
===================

Components: 2
Component edges: 1
Warnings: 0
Violations: 0
Cycles: 0

Component Metrics
-----------------
Component           FanIn  FanOut Instability    Abstract  Distance
:cli                    0       1       1.000       0.000     0.000
:core                   1       0       0.000       0.000     1.000

Component Dependencies
----------------------
:cli -> :core
```
