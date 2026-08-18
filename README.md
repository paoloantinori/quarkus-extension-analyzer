# quarkus-extension-analyzer

Dependency-usage analysis that understands Quarkus.

**Status:** M1 (spike), M2 (the `analyze` mojo), M3 (ignore-list interop), and
M4 (Quarkiverse evaluation) are done. M5 (the Quarkus build-time extension form)
ships alongside the mojo: both forms run the same core engine, including the
annotation-consumer rules that resolve Quarkus-specific false positives
(TASK-28). The forms differ only in the index they feed the engine: the
extension passes ArC's bean index from inside augmentation; the mojo builds one
over the module's compiled classes. Validated on the Quarkus super-heroes suite
and the official quickstarts (see the bench section in
[docs/EXTENSION-USAGE.md](docs/EXTENSION-USAGE.md) for the current numbers and
the workspace caveat), plus the historical benches in
[docs/M2-VALIDATION.md](docs/M2-VALIDATION.md) and
[docs/SECOND-BENCH.md](docs/SECOND-BENCH.md). Every work unit's decisions and
evidence are in [docs/AUTONOMOUS-WORK-LOG.md](docs/AUTONOMOUS-WORK-LOG.md);
[docs/DESIGN.md](docs/DESIGN.md) is the original 2026-08-01 design draft.

## The problem

Every Maven dependency-usage analyzer available today is bytecode-based:
`maven-dependency-plugin:analyze`, [DepClean](https://github.com/ASSERT-KTH/depclean),
and friends decide that a dependency is "used" by looking for references to its
classes in your compiled code.

Quarkus breaks that assumption by design. Extensions are wired at build
augmentation time: `quarkus-jdbc-postgresql`, `quarkus-smallrye-health`,
`quarkus-scheduler`, `quarkus-opentelemetry` and many others can be genuinely
load-bearing while having **zero bytecode references** in your application.
Run `dependency:analyze` on any real Quarkus application and its report drowns
in false "unused declared" warnings; the sanctioned workaround is a
hand-maintained ignore-list, which rots.

The reverse problem exists too: a Quarkus extension that really is dead weight
(added for an experiment, config never wired) is indistinguishable, in the
report, from the twenty load-bearing ones around it.

## The idea

Classify each declared Quarkus extension as **used** or **suspect** by combining
three signals that together approximate what augmentation actually does:

1. **Config-root match**: any configuration key belonging to the extension's
   config roots appears in any profile of `application.properties`/`application.yaml`.
   This catches the classic false positives: JDBC drivers selected by
   `%prod.quarkus.datasource.db-kind`, health/metrics/otel toggled by config.
2. **Bytecode reference**: user code references the extension's runtime API
   (Jandex; delegate plain non-extension jars to the existing
   `maven-dependency-analyzer`).
3. **Capability requirement**: another used extension requires a capability
   this extension provides (from the Quarkus bootstrap `ApplicationModel`).

On top of the three signals, a curated **annotation-consumer** rules pass runs
in both forms (TASK-28): a Jandex index over the app's classes says which
annotation families the app actually uses (`@NotNull`, `@Scheduled`,
`@Path` endpoints returning POJOs, a shipped `application.yml`, ...), and the
rules table credits the extension that processes each family. This is what
closes the false positives no bytecode scan can see: the annotation types live
in shared jars, and serializers or build-step-generated implementations leave
no compile-time reference at all. A dependency-join rule also credits the
reactive SQL client that a used Hibernate Reactive setup requires (selected by
`db-kind` when several are declared).

An extension flagged by none of the signals (nor the rules pass) is reported
as *suspect*, with the evidence trail. Output is report-only, plus a generated
ignore-list fragment compatible with `maven-dependency-plugin` and DepClean, so
the tool composes with the existing ecosystem instead of replacing it.

## Why this doesn't exist yet

Researched 2026-08-01 (see [docs/DESIGN.md](docs/DESIGN.md#prior-art) for sources):

- `maven-dependency-plugin` documents manual ignore-lists as the official answer.
- DepClean handles transitive/inherited bloat and has reflection heuristics for
  Spring/Hibernate, but no Quarkus awareness; its own paper lists this class of
  false positive as an open limitation.
- The one analyzer that understands annotation processors and service loaders
  (autonomousapps' dependency-analysis plugin) is Gradle-only.
- Quarkus itself removes unused *beans* at build time, but has no tooling to
  detect unused *extensions*.

## Origin

This project came out of CI research on
[Apicurio Registry](https://github.com/Apicurio/apicurio-registry): a
`dependency:analyze` sweep over a 67-module reactor produced 40 unremovable
false positives on the main Quarkus application module, several artifacts even
appearing in both the "unused declared" and "used undeclared" lists at once
(details in
[apicurio-registry#9135](https://github.com/Apicurio/apicurio-registry/issues/9135),
discussions [#8364](https://github.com/Apicurio/apicurio-registry/discussions/8364)
and [#8365](https://github.com/Apicurio/apicurio-registry/discussions/8365)).
For that project the conclusion was "the tool cannot answer the question".
This repo exists so the next project gets a better answer. If it proves useful,
the natural long-term home is [Quarkiverse](https://github.com/quarkiverse).

## Roadmap

- **M1, spike** (riskiest unknown first) -- done. Given a built Quarkus app,
  enumerate its extensions and their config roots via the bootstrap
  `ApplicationModel`, and match them against parsed application config.
  Validated on a real multi-extension app (Apicurio Registry's `app` module
  is the test bench: ground truth is known for all ~25 of its extensions).
  Evidence: [docs/SPIKE-RESULTS.md](docs/SPIKE-RESULTS.md).
- **M2, mojo** -- done. `analyze` goal producing the three-signal report,
  implemented in [`plugin/`](plugin/) and validated on the same bench.
  Numbers: [docs/M2-VALIDATION.md](docs/M2-VALIDATION.md).
- **M3, interop** -- done. Opt-in `-Dqea.ignoreFragments=true` on the `analyze`
  goal generates `ignoredUnusedDeclaredDependencies` / DepClean `ignoreDependencies`
  fragments from the `used-config`/`used-capability` extensions; the JSON report's
  `ignoreRecommendations` array carries the same data for CI consumers.
- **M4, community** -- done. Evaluated proposing to Quarkiverse; conclusion:
  ship standalone (Maven Central), since Quarkiverse hosts Quarkus extensions,
  not Maven plugins. A build-time-extension form (M5) is a deferred option. See
  [docs/M4-QUARKIVERSE-EVAL.md](docs/M4-QUARKIVERSE-EVAL.md).
- **M5, extension form** -- done. A Quarkus build-time extension that runs the
  same analysis inside augmentation. Since TASK-28 the rules engine lives in
  core and both forms run it: the extension feeds it ArC's bean index, the mojo
  feeds it an index over the compiled classes. On the super-heroes bench the
  mojo form went from 5 to 2 extension suspects on rest-heroes (the two
  remaining are runtime-only extensions no compile-time signal can see), and
  hibernate-validator on rest-fights, the headline case neither form could
  close before the curated rules, is now credited in both. See
  [docs/REARCH-PLAN.md](docs/REARCH-PLAN.md),
  [docs/EXTENSION-USAGE.md](docs/EXTENSION-USAGE.md), and
  [docs/AUTONOMOUS-WORK-LOG.md](docs/AUTONOMOUS-WORK-LOG.md).

## Usage

```bash
mvn io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze
```

Run from the Quarkus application module to analyze, after `mvn compile` (the
bytecode signal needs `target/classes` to exist). Useful flags:

- `-Dqea.reportFile=target/quarkus-extension-analysis.json` -- also write the JSON report
- `-Dqea.failOnSuspect=true` -- fail the build if any directly-declared dependency is `suspect`
- `-Dqea.probe=true` -- after the report, re-resolve the app model without each extension suspect
  (in-memory, no pom mutation) and append the bootstrap's verdict per suspect: the ablation
  methodology as a tool mode, the natural CI gate before acting on a report
- `-Dqea.applicationConfig=/path/to/application.yaml` -- override the auto-discovered config file
- `-Dqea.ignoreFragments=true` -- also write `qea-mdp-ignores.xml` and `qea-depclean-ignores.xml`
  to the build directory: ready-to-paste ignore-list fragments for maven-dependency-plugin's
  `analyze` goal and for DepClean, covering the `used-config`/`used-capability` extensions
  (the JSON report's `ignoreRecommendations` array carries the same data for CI consumers that
  want to build their own format)
- `-Dqea.skip=true` -- skip the goal

See [docs/M2-VALIDATION.md](docs/M2-VALIDATION.md) for a real run against the
Apicurio Registry bench.

### Extension form (inside augmentation)

Add the extension to your Quarkus application module and build normally; the
report is printed to the build log, and `quarkus.extension-analyzer.fail-on-suspect`
can fail the build on suspects:

```xml
<dependency>
  <groupId>io.github.paoloantinori</groupId>
  <artifactId>quarkus-extension-analyzer</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Details, the rules table, and the current bench numbers:
[docs/EXTENSION-USAGE.md](docs/EXTENSION-USAGE.md).

## Development

```bash
mvn clean install          # full reactor: core, shaded, plugin, extension, extension-deployment
```

The test suite includes a failsafe IT on the shaded artifact (it must run
after shade). Repo conventions, the test-suite map, and the hard-won
invariants (phantom-name discipline, shade relocation rules, bench workspace
state) live in [CLAUDE.md](CLAUDE.md).

## License

[Apache 2.0](LICENSE).
