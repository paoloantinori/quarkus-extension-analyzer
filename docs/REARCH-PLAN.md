# Re-architecture plan: both forms (mojo + extension), shared core

**Status:** design, 2026-08-12. For review before execution. Not yet built.

**Decision (user, 2026-08-12):** build BOTH the standalone mojo (Option A) and
the Quarkus extension (Option B / M5), as independent projects sharing a common
core. This follows the producer-extraction experiments (docs/AUTONOMOUS-WORK-LOG.md,
TASK-8 phase C) which proved the standalone mojo cannot resolve the
annotation-consumer false positives (hibernate-validator via `@NotNull`, scheduler
via `@Scheduled`) without a curated table that weakens the exclusivity invariant;
the extension form reads ArC's authoritative bean index and resolves them for
free, without invariant-weakening.

## Why both, and what each is for

| | mojo (Option A, exists) | extension (Option B, TASK-19) |
|---|---|---|
| **Use case** | CI sweep over any built Quarkus app, without modifying its pom | In-build analysis of the app that declares the extension |
| **Model resolution** | self (ChainedMavenWorkspaceReader, TASK-9) | provided by augmentation |
| **annotation-consumer FP** | UNRESOLVED (needs curated table) | RESOLVED (reads ArC bean index) |
| **Adoption** | point and run; no app change | app must declare the extension |
| **Best for** | portfolio/central CI hygiene | per-app, high-precision |

The two are products for different funnels, not duplicates. Both consume the same
classification logic so a fix in the core propagates to both.

## The 3-artifact structure

```
quarkus-extension-analyzer-core  (TASK-18)        <-- shared, pure Java + Quarkus bootstrap API
  ^                                  ^
  |                                  |
quarkus-extension-analyzer-        quarkus-extension-analyzer
maven-plugin (TASK-15, exists)     (Quarkus extension, TASK-19)
  mojo shell: resolves model       -deployment @BuildStep shell:
  itself (TASK-9 machinery),       gets model + ArC BuildItems from
  reads target/classes             augmentation, calls core
```

**Core (TASK-18):** `Analyzer` (3-signal classification), `ExtensionReport` /
`AnalysisReport`, the signal packages (configroot, bytecode, capability,
deploymentvocab), ignore fragments. Depends only on the Quarkus bootstrap API
(`ApplicationModel`, `ResolvedDependency`, stable across 3.x) + Jandex + ASM.
This is NOT a new analysis engine; it is the current `Analyzer` extracted into
its own artifact, unchanged in logic.

**mojo (TASK-15, exists):** the thin shell that owns model resolution
(`ChainedMavenWorkspaceReader`, the deployment-jar lookup, the reactor workspace)
and the `analyze` goal. Becomes a consumer of core.

**extension (TASK-19, new):** a `-deployment` module with a `@BuildStep` that:
1. receives `ApplicationModel` (no resolution needed) and `BeanContainer` /
   `BuildItem` (ArC's bean index) from augmentation,
2. maps the app's `@Inject`/annotation usage to extension producers via ArC's
   data (closing the annotation-consumer FP),
3. calls core's `Analyzer.analyze(...)` with the model + a fourth signal derived
   from ArC,
4. emits the report and optionally fails the build (`qea.failOnSuspect`).

## What is shared vs what diverges

**Shared (core):** the classification cascade, the three signals, the report
schema, the ignore-fragment generation, the value-rules curated table
(TASK-7), the experimental vocabulary signal (TASK-8 opt-in).

**Diverges (shells):** model resolution (mojo: self-resolve via
`ChainedMavenWorkspaceReader`; extension: from augmentation), the
annotation-consumer fourth signal (mojo: unavailable or curated-table;
extension: ArC bean index), and the build integration (mojo goal vs `@BuildStep`).

## Build order

1. **TASK-18 (extract core):** split the current single `plugin/` module into
   `core/` + `plugin/` (mojo). The core's `Analyzer` and signal packages move to
   `core/`; the mojo keeps only resolution + goal wiring. Verify the mojo still
   builds and the 90 tests pass unchanged (they move with core). This is a pure
   refactor, no logic change.
2. **TASK-19 (extension):** new `extension/` module (runtime + deployment), a
   `@BuildStep` shell, the ArC-derived fourth signal. Validate on the two benches
   (rest-fights, Apicurio): the annotation-consumer suspects should now resolve.

## Open decisions for the maintainer

- **One repo or split repos?** One repo (multi-module Maven) is simpler to keep
  core+shells in sync; split repos match the "independent projects" framing and
  suit the extension going to Quarkiverse later. Recommendation: one multi-module
  repo now, split when the extension matures toward Quarkiverse.
- **Does the extension ship the TASK-8 vocabulary signal?** No need: ArC's bean
  index supersedes it for the extension form. The vocabulary signal stays
  core/opt-in for the mojo only.
- **Module naming under the new groupId** `io.github.paoloantinori`:
  `quarkus-extension-analyzer-core`, `-maven-plugin`, and for the extension
  `quarkus-extension-analyzer` (runtime) + `quarkus-extension-analyzer-deployment`
  (per Quarkus convention).

## Why this is a design doc, not an execution

A multi-module split + a new Quarkus extension is the kind of change where a
small misstep (parent-POM version, a circular core<->shell dependency, a wrong
`-deployment` packaging) compiles clean and surfaces silently. It needs review
checkpoints. This doc is the checkpoint; execution (TASK-18 then TASK-19) starts
on explicit go-ahead.
