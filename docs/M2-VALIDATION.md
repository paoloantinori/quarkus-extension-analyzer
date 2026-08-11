# M2 validation on the Apicurio Registry bench

Date: 2026-08-01. Runs executed and verified by the orchestrator against
`io.apicurio:apicurio-registry-app:3.3.2-SNAPSHOT` (Quarkus 3.33.2.1), the
ground-truth bench defined in [DESIGN.md](DESIGN.md#validation-bench).

## Reproduction

```bash
cd plugin && mvn -q install -DskipTests
cd /path/to/apicurio-registry
mvn -q compile -f app/pom.xml   # bytecode signal needs app/target/classes populated
mvn io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze \
    -f app/pom.xml -Dqea.reportFile=/tmp/qea-registry-app.json
```

Note: run without `-q` if you want the text report; the mojo logs it at INFO
level, which Maven's quiet mode suppresses (the JSON file is written either way).

## Results: M1 (config signal only) vs M2 (three signals)

| | M1 spike | M2 plugin |
|---|---|---|
| used-config | 7 (+4 inherited) | 10 (includes 4 inherited JDBC drivers) |
| used-bytecode | not checked | 2 |
| used-capability | not checked | 5 |
| suspect | 13 | **7** |
| total directly declared extensions | 24 | 24 |

The four JDBC drivers (h2, postgresql, mysql, mssql) remain correctly
classified `used-config` with `configInherited: true` via `quarkus.datasource.`
inherited from `quarkus-agroal`, satisfying the bench exit criterion by config,
not bytecode: per-driver matched keys now include the named-datasource keys
(e.g. `quarkus.datasource.h2.db-kind`).

Six of M1's thirteen suspects were resolved by the new signals:

- `quarkus-jackson`: used-bytecode.
- `quarkus-resteasy-client`, `quarkus-smallrye-context-propagation`,
  `quarkus-smallrye-health`, `quarkus-undertow`, `quarkus-vertx`:
  used-capability, each justified by a conditional/direct extension-dependency
  edge from another used extension in the resolved model (e.g. micrometer
  depends on undertow and resteasy-client for its binders in this app's graph).

## Remaining suspects (7) and what they mean

`apicurio-registry-config-index`, `quarkus-kubernetes-client`,
`quarkus-resteasy-client-jackson`, `quarkus-resteasy-jackson`,
`quarkus-scheduler`, `quarkus-smallrye-fault-tolerance`, `quarkus-smallrye-jwt`.

Suspect means "no signal fired", not "safe to remove". Two instructive cases:

- **`quarkus-kubernetes-client` is a true blind spot, and a known one.** The
  registry's KubernetesOps storage variant genuinely uses the Kubernetes
  client, but application bytecode references the Fabric8 *library* classes
  (which the report correctly marks used-bytecode as a plain jar), while the
  *extension* only contributes the managed client bean and its config wiring.
  No signal can see that today. This is the documented signal-2 gap
  (DESIGN.md, deferred transitive-API walk) plus a DI-production pattern worth
  a fourth signal in the future (injected types whose beans are produced by an
  extension).
- **`apicurio-registry-config-index` stayed suspect, disproving M1's
  expectation.** The M1 evidence document predicted, explicitly flagged as
  unverified, that the bytecode signal would resolve it. It did not: the app
  compiles against no class from that jar. The M1 caveat discipline paid off;
  the prediction was wrong and the tool now says so with evidence.

The remaining five need human triage, which is precisely the tool's purpose:
each row carries the evidence trail of which signals were checked and found
nothing.

## M3 addendum: ignore-list interop (same bench, same day)

With `-Dqea.ignoreFragments=true` the same run writes
`app/target/qea-mdp-ignores.xml` and `app/target/qea-depclean-ignores.xml`,
each containing exactly the 15 used-config + used-capability extensions (the
7 suspects and the 2 used-bytecode rows are excluded by design: bytecode-based
analyzers see the latter on their own, and recommending to ignore an unproven
suspect would defeat the tool's purpose). The JSON report carries the same 15
entries in `ignoreRecommendations` with per-entry reason sentences. DepClean
entries are full-match regexes against `groupId:artifactId:version:scope`
with dots escaped (verified against DepClean's source: `Pattern.matches`,
case-insensitive). A fragment-write failure degrades to a warning and never
suppresses the JSON report or the `failOnSuspect` outcome.

## TASK-5 addendum: transitive-API bytecode signal (same bench)

The signal-2 extension now attributes each declared extension's exclusive
transitive plain jars (BFS from the declared extension's runtime artifact,
traversing through nested non-declared extensions, stopping at other declared
extensions; jars shared between declared extensions or directly declared by the
project are never attributed). Bench outcome: **suspects drop from 7 to 6**.
`quarkus-kubernetes-client` is now `used-bytecode` via
`io.fabric8:kubernetes-client-api`, resolving the blind spot documented above.
Three more extensions gained transitive evidence (`quarkus-agroal` via
`agroal-api`, `quarkus-elasticsearch-java-client` via `elasticsearch-java`,
`quarkus-opentelemetry` via `opentelemetry-sdk-metrics`); all four are
transitive-only by construction (`bytecodeViaTransitiveApi` is only ever set
when the extension's own jar is not directly referenced).

The diagnostic trail is worth recording. The first implementation used every
extension in the resolved model as an attribution root, so any jar under a
nested transitive extension (`quarkus-kubernetes-client-internal`) counted as
"shared" with its declared ancestor and could never be exclusive: the target
case did not flip, and two plausible explanations (test-scope sharing,
`MISSING_FROM_APPLICATION` pruning) were both refuted by a
`-Dqea.debugAttribution=true` trace before the real mechanism was found. The
fix (declared-extension roots, traversal through nested extensions) carries a
dedicated regression test, and the debug flag stays in the plugin because one
grep of its output settled what three rounds of static inference could not.

Remaining suspects (6): `apicurio-registry-config-index`,
`quarkus-resteasy-client-jackson`, `quarkus-resteasy-jackson`,
`quarkus-scheduler`, `quarkus-smallrye-fault-tolerance`, `quarkus-smallrye-jwt`.
These need human triage or future signals (DI-produced bean types remains the
strongest candidate).

## TASK-12 addendum: member-level annotations in the bytecode signal

TASK-12 widens signal 2's Jandex extraction: `referencedTypesViaJandex` now
iterates `ClassInfo.annotations()` (class-, field-, method- and record-component-level
annotations) instead of `declaredAnnotations()` (class-level only). The previous
behavior missed field and record-component annotations, which hid
`jakarta.validation-api` from the signal when `@NotNull` sat on a record component
(the super-heroes rest-fights `FightRequest` case); the field/method TYPE extraction
loops are unchanged.

Bench re-baselining DONE 2026-08-11 (Apicurio `app`, Quarkus 3.38.1; rest-fights,
Quarkus 3.38.1; analyzer resolver still pinned to 3.33.2.1, 3.x skew confirmed a
non-issue again). Measured results, raw JSON saved under
`docs/_bench-runs/`:

- Apicurio registry `app`: extensions used-bytecode = 7, used-config = 7,
  used-capability = 5, **suspect = 5** (24 total). Down from the TASK-5-era 7
  extension suspects. The net change is `quarkus-smallrye-fault-tolerance`
  flipping suspect to used-bytecode (via its exclusive transitive
  `io.smallrye:smallrye-fault-tolerance-api`, now captured because its
  method-level `@Fallback`/`@Retry` annotations reference that jar). The
  remaining 5 suspects (`apicurio-registry-config-index`,
  `quarkus-resteasy-client-jackson`, `quarkus-resteasy-jackson`,
  `quarkus-scheduler`, `quarkus-smallrye-jwt`) are unchanged.
- super-heroes rest-fights: extensions used-bytecode = 7, used-config = 11,
  used-capability = 2, **suspect = 3** (23 total), identical to the TASK-5
  baseline. The predicted `hibernate-validator` flip did NOT happen, for a
  correct reason: the annotation type `jakarta.validation.constraints.NotNull`
  lives in the shared jar `jakarta.validation:jakarta.validation-api`, and
  TASK-5's exclusive-attribution rule deliberately excludes jars reachable from
  2+ declared extensions. TASK-12 widened annotation *capture*, but cannot
  override the shared-jar *membership* exclusion that guards this extension, so
  it stays suspect (with its TASK-11 shared-jar hint intact). This is the tool
  behaving as designed, not a regression.
- Both benches: the TASK-9 first-run reactor-resolution fix holds from a clean
  compile (no first-run crash; the `ChainedMavenWorkspaceReader` resolves the
  never-installed `app`/`rest-fights` modules correctly).

## Verified conclusions

1. The three-signal design measurably improves on config-only classification
   (13 to 7 suspects on a 24-extension application) with zero false "used"
   downgrades of the M1 results.
2. The refactoring rounds (simplify + code review) were behavior-preserving:
   verdict counts identical before and after (10/2/5/7 across two runs of the
   same bench).
3. Runtime DI wiring in the mojo required all three Maven collaborators
   (RepositorySystem, RemoteRepositoryManager, SettingsDecrypter); missing any
   one triggers BootstrapMavenContext's ad hoc container and a
   `SettingsDecrypter` bean error. Found only by running against a real
   project; unit tests cannot exercise this path.
