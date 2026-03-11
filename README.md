# Dependency Checker

Analyzes component boundaries and architecture health for this project.

## Run

    clj -M:check-dependencies
    clj -M:check-dependencies dependency-checker.edn
    clj -M:check-dependencies dependency-checker.edn --source-path src
    clj -M:check-dependencies dependency-checker.edn --format edn
    clj -M:check-dependencies --help

Use `--help` to print a usage summary to stdout.

Create or recreate starter config:

    clj -M:check-dependencies dependency-checker.edn --init
    clj -M:check-dependencies dependency-checker.edn --source-path src --force-init

## Working Directory Behavior

The checker resolves paths from the directory where you run `clj` (the current working directory).

- Default config path `dependency-checker.edn` is resolved relative to the current working directory.
- Default source path is `src`, resolved relative to the current working directory unless you use an absolute path.

## Config File

Default config path is `dependency-checker.edn`.

### Component Discovery

Components are not configured manually.

The checker derives the component from the second namespace segment under the chosen source path:

```text
<source-path>/project_ns/component_ns/...
```

Examples:

- `src/sample_app/ui/view.clj` with namespace `sample-app.ui.view` belongs to component `:ui`
- `src/sample_app/game/rules.clj` with namespace `sample-app.game.rules` belongs to component `:game`

All dependencies from descendant namespaces roll up to their component. For example, every dependency from `sample-app.ui.*` contributes to component `:ui`.

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

### Ignored Components

Use `:ignored-components` to exclude components completely from analysis. Ignored components do not appear in metrics, edges, violations, warnings, or cycles.

```clojure
{:ignored-components [:spec-runner]}
```

This is useful for support components that you do not want treated as part of the architectural policy surface.

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

Namespace exceptions are exact namespace names.

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

`--init` and `--force-init` infer `:allowed-dependencies` from observed component-to-component dependencies under the chosen source path.

Generated config includes the inferred `:allowed-dependencies` plus `:fail-on-cycles true` and `:fail-on-violations true`.

If you want to exclude support components such as `:spec-runner`, add `:ignored-components` manually after generation.

If an existing config uses legacy keys such as `:source-paths` or `:component-rules`, the checker exits with an error and recommends regenerating the file with `--force-init`.

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
{:allowed-dependencies {:cli [:core]
                        :core []}
 :ignored-components [:spec-runner]
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

## Mutation Workflow

Run mutation testing one file at a time.

Before running specs, run the Speclj structure check. The `:spec` alias already does this for you.

Structure checker: `github.com/unclebob/speclj-structure-check`

```bash
clj -M:spec
```

Then mutate exactly one source file with `--max-workers 3`:

```bash
clj -M:mutate src/dependency_checker/core.clj --max-workers 3
```

Workflow rules:

- Mutate only one file at a time.
- Before moving to the next file, cover every uncovered mutation in the current file.
- Before moving to the next file, kill every surviving mutation in the current file.
- `clj-mutate` uses LCOV coverage data and regenerates it when stale.

Recommended loop for each file:

1. Run `clj -M:mutate path/to/file.clj --max-workers 3`.
2. If any mutations are uncovered, add or fix specs until they are covered.
3. If any mutations survive, change code or specs until they are killed.
4. Rerun the same single-file mutation command.
5. Only start the next file when the current file has no uncovered mutations and no survivors.
