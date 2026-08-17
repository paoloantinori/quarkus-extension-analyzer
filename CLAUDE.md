# CLAUDE.md - quarkus-extension-analyzer

Dependency-usage analysis that understands Quarkus: a Maven plugin (`analyze`
goal) and a Quarkus build-time extension, sharing one core engine. Classifies
each declared dependency as used (bytecode / config / capability /
annotation-consumer) or suspect, with an evidence trail.

## Documentation map

- `docs/AUTONOMOUS-WORK-LOG.md` - append-only decision log; every work unit
  with its evidence. Read the tail before changing anything subtle.
- `docs/EXTENSION-USAGE.md` - the extension form, the rules table, current
  bench numbers and the bench-workspace caveat.
- `docs/REARCH-PLAN.md` - the multi-module architecture as planned.
- `docs/DESIGN.md` - original 2026-08-01 design draft (historical, superseded).
- `docs/ABLATION-BENCH.md`, `docs/SUSPECT-TRIAGE.md` - empirical ground truth
  behind the serializer/reactive-driver rules.
- Historical bench reports: `docs/M2-VALIDATION.md`, `docs/SECOND-BENCH.md`.

## Modules and build

`core` (engine + report model, no quarkus-bootstrap deps) <- `shaded`
(self-contained mojo runner; bootstrap relocated) <- `plugin` (thin mojo);
`extension` + `extension-deployment` (the build-time form; deployment is a
thin adapter over the core engine). `spike/` is not a reactor module.

```bash
mvn clean install    # full reactor, runs all suites incl. the shaded IT
```

Java 17; `quarkus.version` (root pom) pins the bootstrap APIs, currently
3.38.2. The suite map: core holds the 52-test behavioral rules suite
(`AnnotationConsumerRulesBehaviorTest`), the config/value-rules suites, and
the analyzer suites; `shaded` holds the runner derivation test and
`ShadedJarRelocationIT` (failsafe, runs AFTER shade); `extension-deployment`
holds the adapter tests.

## Hard invariants (each was violated once; each fix cost hours)

1. **No phantom names.** Every hardcoded FQCN or GA in the rules must be
   verified against a real artifact (jar listing or the Quarkus BOM at the
   pinned version) before shipping. Five phantom-name bugs have been found
   and fixed (RegisterRestClient `restclient` vs `rest.client`; the smallrye
   FT probe pair; `quarkus-reactive-mariadb-client` which exists in no BOM).
   Rule-table entries and their probes share named constants in
   `AnnotationConsumerRules`; the BEHAVIORAL TESTS deliberately keep
   independent string literals (a test reusing a broken production constant
   passes vacuously - that independence caught the first phantom).
2. **Shade relocations: only the seven trailing-dot `io.quarkus.<pkg>.`
   patterns in `shaded/pom.xml`.** A bare `io.quarkus` pattern prefix-matches
   the engine's DOMAIN string literals ("io.quarkus:quarkus-rest-jackson",
   "io.quarkus.scheduler.Scheduled") and silently rewrites them to
   `internal.*` names that match nothing: every rule dies in the mojo form
   with all unit tests green (they run unshaded). `ShadedJarRelocationIT`
   reads the built jar and pins this; it runs via failsafe at verify/install.
   The seven packages must cover the jar's actual `io.quarkus` content
   (bootstrap, commons, fs, maven, paths, sbom, util) - re-check on a
   quarkus.version bump.
3. **One engine, two shells.** The annotation-consumer engine is
   `core .../plugin/annotation/AnnotationConsumerRules`, parameterized by
   (declaredExtensionGas, dbKindValues, projectRoot) instead of
   ApplicationModel (a bootstrap type core must not depend on). The
   declared-GA derivation is therefore duplicated in the two shells
   (`AnnotationAttribution.collectDeclaredExtensionGas` and the runner's
   copy) - both copies are pinned by tests; keep them in sync.
4. **Index scope:** the mojo feeds the engine an index over the module's MAIN
   classes only (matching the extension form's ArC bean-index scope; a
   test-only `@Path` stub must not credit a serializer). The bytecode SIGNAL
   deliberately keeps its wider main+test scope.

## Bench

- Active bench: `/private/tmp/super-heroes-fresh` (fresh clone, platform
  3.38.1) and `/private/tmp/quarkus-quickstarts`. The OLD
  `/private/tmp/super-heroes` workspace is DAMAGED (an Aug-16 copy gutted
  .git and dropped the openapi specs; `generate-code` NPEs even pristine) -
  do not use it.
- When benching the extension form: back up the app pom FIRST, insert the
  dependency anchored AFTER `</dependencyManagement>` (a naive insert after
  the first `<dependencies>` lands inside dependencyManagement and the build
  then passes vacuously without the analyzer), restore and verify 0 analyzer
  refs afterwards.
- Mojo form: `mvn compile io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze`
  (flags: `-Dqea.reportFile=`, `-Dqea.failOnSuspect=`, `-Dqea.applicationConfig=`,
  `-Dqea.ignoreFragments=`, `-Dqea.vocabularySignal=`, `-Dqea.debugAttribution=`, `-Dqea.skip=`).

## Working conventions

- Task tracking via the Backlog MCP (`task_create` / `task_edit` / `task_complete`);
  a task is Done only with its Definition of Done: /simplify (four angles) and
  a /code-review-equivalent pass (the /code-review skill is user-invocation
  only; dispatch review subagents instead), then mutation-verify any
  load-bearing fix (break it on purpose, watch the pin fail, restore).
- `docs/AUTONOMOUS-WORK-LOG.md` is append-only; dated docs are historical
  records and stay as written. No em-dash (U+2014) in authored prose.
- Evidence before assertion: bench claims in docs must cite the runs that
  produced them; old numbers across the shade fix are not comparable (the
  bare pattern had also silently disabled ConfigRootProbe's `@ConfigRoot`
  fallback in every mojo report since TASK-20).
