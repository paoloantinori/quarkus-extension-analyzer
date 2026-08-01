# Design

Status: draft, 2026-08-01. Nothing below is implemented yet; M1 exists to
falsify the risky assumptions, which are individually marked.

## Goal

A Maven plugin (`analyze` goal, report-only) that classifies each declared
dependency of a Quarkus application module as:

- `used-bytecode`: referenced from compiled classes (same semantics as
  maven-dependency-analyzer);
- `used-config`: a Quarkus extension whose config roots match keys present in
  the application configuration;
- `used-capability`: a Quarkus extension required (via capability or extension
  dependency) by another extension that is itself used;
- `suspect`: none of the above. Candidate for removal, with the evidence trail
  showing which signals were checked.

Non-extension jars keep plain bytecode semantics; the novelty is only where
bytecode analysis is known-blind.

## Architecture

```
mojo (analyze)
 ├── ExtensionModelReader     <- quarkus bootstrap ApplicationModel
 │     extensions, runtime vs deployment artifacts, capabilities
 ├── ConfigRootIndex          <- per-extension config roots
 ├── AppConfigReader          <- application.properties / yaml, all profiles
 ├── BytecodeUsage            <- Jandex over target/classes + test-classes,
 │     delegating plain jars to maven-dependency-analyzer
 ├── Reporter                 <- text + JSON
 └── IgnoreFragments          <- ignore-list XML fragments (maven-dependency-plugin, DepClean)
```

### Signal 1: config roots

Each extension contributes config roots (e.g. `quarkus.datasource.*` for the
JDBC extensions, `quarkus.scheduler.*` for the scheduler). The matcher walks
every profile (`%dev.`, `%prod.`, `%test.`, plus unprefixed) of the merged
application config and marks the extension used when any key falls under one of
its roots.

**Risky assumption (M1 must verify):** config-root metadata is recoverable per
extension at plugin runtime. Candidate sources, in preference order:
`META-INF/quarkus-extension.properties` in the runtime artifact, the
`-deployment` artifact's `@ConfigRoot` annotations via Jandex, or the
`quarkus-extension.yaml` descriptor. If none is reliable, fallback is a shipped
static index generated from the Quarkus platform BOM metadata
(quarkus.io/guides publish exactly this per extension).

Known hard case: shared config roots. All `quarkus-jdbc-*` drivers listen under
`quarkus.datasource.*`; the discriminator is the `db-kind` value. The matcher
needs per-family value rules for these (a small curated table, acceptable).

### Signal 2: bytecode

Jandex index over the module's `target/classes` and `target/test-classes`;
a reference to any class contained in the extension's runtime artifact (or its
non-Quarkus transitive API, e.g. `io.smallrye.*` for smallrye extensions)
marks it used. Plain (non-extension) dependencies are delegated wholesale to
`org.apache.maven.shared:maven-dependency-analyzer` so results stay comparable
with the standard tooling. M2 implements the extension's own runtime artifact
check only; walking the non-Quarkus transitive API is a deferred scope cut
(tracked in the backlog).

### Signal 3: capabilities

From the `ApplicationModel`: extension A used + A requires capability C +
B provides C implies B used. Same for hard extension-to-extension dependencies
(e.g. RESTEasy Jackson pulling RESTEasy).

**Risky assumption (M1 must verify):** the bootstrap resolver
(`quarkus-bootstrap-core` / `bootstrap-maven-resolver`) can be embedded in a
plain mojo outside the quarkus-maven-plugin without dragging in the whole
augmentation phase. If not, plan B is resolving the model through a forked
`quarkus:info`-style invocation, or reading the platform descriptor json.

## Output

- Human report grouped by verdict, one evidence line per extension.
- JSON for CI consumption.
- Generated ignore fragments: `<ignoredUnusedDeclaredDependencies>` for
  maven-dependency-plugin and `<ignoreDependencies>` regexes for DepClean, so
  adopters can keep their existing analyzer and only borrow the Quarkus brain.
  (M3, implemented: opt-in `-Dqea.ignoreFragments=true` on the `analyze` goal;
  covers `used-config`/`used-capability` extensions only.)

## Explicit non-goals

- Removing dependencies automatically (DepClean already owns "rewrite the pom";
  we only feed it better inputs).
- Analyzing non-Quarkus frameworks (Spring's equivalent blindness is real but
  out of scope).
- Runtime/dynamic usage detection beyond config (no agent, no test coverage
  correlation).

## Prior art

Researched 2026-08-01. Sources and detail in the originating research report
(apicurio-registry `claudedocs/research_quarkus-aware-dependency-analysis_20260801.md`,
summarized in the README):

- maven-dependency-plugin: `ignoredUnusedDeclaredDependencies` is the official
  workaround; no framework awareness.
  https://maven.apache.org/plugins/maven-dependency-plugin/examples/exclude-dependencies-from-dependency-analysis.html
- DepClean (KTH): multi-module, transitive/inherited bloat, `ignoreDependencies`
  regex, Spring/Hibernate reflection heuristics, no Quarkus awareness; last
  release 2.1.0 (~1 year old). https://github.com/ASSERT-KTH/depclean and the
  EMSE paper https://link.springer.com/article/10.1007/s10664-020-09914-8
- autonomousapps dependency-analysis: the semantic gold standard (annotation
  processors, service loaders, runtime deps), Gradle-only.
  https://github.com/autonomousapps/dependency-analysis-gradle-plugin
- Quarkus: build-time unused *bean* removal exists
  (https://quarkus.io/blog/unused-beans/), unused *extension* detection does not.

## Validation bench

Apicurio Registry `app` (25+ extensions, 4 config-selected JDBC drivers,
health/metrics/otel config-toggled, multiple storage variants) is the ground
truth test case: every one of its extensions has a known used/unused answer,
and the standard analyzer scores ~40 false positives on it. A correct M2 run
must report near-zero suspects there, with `quarkus-jdbc-mysql`/`mssql`
correctly justified by config values, not bytecode.
