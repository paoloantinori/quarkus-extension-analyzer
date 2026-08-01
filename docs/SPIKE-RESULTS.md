# M1 Spike Results

Status: complete, 2026-08-01. Evidence for TASK-1 (M1 spike), answering the two risky
assumptions from [DESIGN.md](DESIGN.md). Code lives in [`../spike`](../spike); it is
throwaway (no mojo, no plugin packaging) but it actually runs against the validation
bench.

## How to reproduce

```bash
cd spike
mvn -q compile exec:java
# or against a different app / config file:
mvn -q compile exec:java -Dexec.args="<groupId:artifactId:version> <application.properties>"
```

Default arguments point at the validation bench: `io.apicurio:apicurio-registry-app:3.3.2-SNAPSHOT`
(already installed in the local `~/.m2` repository) and its
`app/src/main/resources/application.properties`. Full raw output of the run this
document is based on is reproduced in full at the bottom (["Full run output"](#full-run-output)).

## Assumption 2: can the ApplicationModel be resolved from plain Java?

**Verdict: YES.**

`io.quarkus:quarkus-bootstrap-maven-resolver:3.33.2.1` resolves a full Quarkus
`ApplicationModel` for an arbitrary artifact coordinate from a plain `main()`, with no
Maven session, no mojo, and no augmentation phase. Confirmed by decompiling the actual
3.33.2.1 jar (`javap -classpath ... BootstrapAppModelResolver`), not by guessing from
older docs/blog posts.

Working entry points (exact signatures, `io.quarkus.bootstrap.resolver` package):

```java
MavenArtifactResolver mvn = MavenArtifactResolver.builder()
        .setWorkspaceDiscovery(false)   // read from the local repo; don't try to interpret
        .build();                       // the spike's own project as the application
BootstrapAppModelResolver resolver = new BootstrapAppModelResolver(mvn);
ApplicationModel model = resolver.resolveModel(ArtifactCoords.jar(groupId, artifactId, version));
```

- `MavenArtifactResolver.Builder#setWorkspaceDiscovery(boolean)` was the one non-obvious
  flag needed. Without it, the resolver tries to treat the spike's own `pom.xml` as a
  reactor workspace and the resolution fails or behaves unpredictably. Setting it
  `false` forces resolution purely from the local/remote Maven repository, which is
  what a standalone tool with no reactor context needs.
- `BootstrapAppModelResolver.resolveModel(ArtifactCoords)` is the single call that does
  everything: it returns the fully resolved `ApplicationModel` including all transitive
  dependencies, `-deployment` artifact coordinates, extension capabilities, and platform
  BOM imports.

Measured on the bench: resolution completed in **5883 ms** (Maven local-repo reads; no
network was needed since the app was already installed; see the ["Full run
output"](#full-run-output) appendix below for the run this figure is taken from), and
produced:

| Metric | Value |
|---|---|
| Total resolved dependencies | 614 |
| Runtime dependencies | 462 |
| Extensions (`ResolvedDependency.isRuntimeExtensionArtifact()`) | 51 |
| ... of which directly declared by the app | 24 |
| Extension capabilities entries | 21 (27 provided, 0 required by this app) |
| Imported platform BOMs | 1 |

`ResolvedDependency.isRuntimeExtensionArtifact()` and `Dependency.isDirect()` are the
two predicates used to go from "614 resolved artifacts" down to "24 extensions this app
actually declares"; no manual GAV-prefix filtering (`startsWith("quarkus-")`) was
needed. Notably, the model-based predicate also correctly picked up the app's own
custom Quarkus extension (`io.apicurio:apicurio-registry-config-index`, verified against
`~/.m2/repository/io/apicurio/apicurio-registry-config-index-deployment`; the app
declares both the runtime and `-deployment` artifacts in its `pom.xml`). No counterfactual
run with a `groupId == io.quarkus` filter was actually performed on this bench, so the
claim that such a filter would have missed this extension is reasoning about the
filter's mechanics (the extension's groupId is `io.apicurio`, not `io.quarkus`), not a
measured comparison. The model-based predicate is still the right one to use going
forward regardless, since it needs no groupId assumption at all.

Signal 3 (capabilities) data is present and usable straight off the model: the loop
above reports 21 extensions with capability metadata and 27 provided capabilities. This
app happens to declare zero `requires` capabilities, so the "A requires C, B provides
C" chain could not be exercised end-to-end on this bench, but the data structure
(`ExtensionCapabilities.getProvidesCapabilities()` / `getRequiresCapabilities()`) is
populated and shaped as DESIGN.md expects. M2 will need a bench with at least one
`requires` edge to validate the join logic (e.g. `quarkus-resteasy-jackson` and
`quarkus-jackson`, which are documented as coupled extensions in Quarkus itself). This
run never actually computed or inspected their `ResolvedDependency.getDirectDependencies()`
edge, so that pairing is cited here as known Quarkus coupling, not as something measured
on this bench.

### Dead ends on the way to the working call

- First attempt used `MavenArtifactResolver.builder().build()` with default workspace
  discovery. This is the "guessed defaults" failure mode the task instructions warn
  about; `setWorkspaceDiscovery(false)` was found by reading
  `io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver` on the classpath directly
  (javap / decompiled source), not by trial and error against the API, so it did not
  cost two failed compiles. Worth recording anyway since it is the one flag a naive
  read of `resolveModel(ArtifactCoords)` alone would not surface.

## Assumption 1: is config-root metadata recoverable per extension at runtime?

**Verdict: PARTIAL YES.** With an important qualification: it fully explains the
JDBC-driver hard case (the spike's stated exit criterion), but it leaves real coverage
gaps that a shipped tool cannot ignore.

Four candidate sources were probed independently against every resolved extension's
runtime jar (`spike/src/main/java/.../ConfigRoots.java`), so the spike can report
per-source coverage rather than only "the union worked":

| Source | What it reads | Coverage (of 51 resolved extensions) |
|---|---|---|
| A: `META-INF/quarkus-extension.properties` | Properties file | **0 / 51 (0%)** |
| B: `META-INF/quarkus-extension.yaml`, `metadata.config` | YAML descriptor | 31 / 51 (61%) |
| C: `META-INF/quarkus-config-doc/quarkus-config-model.json` | Generated config-doc model | 26 / 51 (51%) |
| D: `@ConfigMapping(prefix=)` / `@ConfigRoot(name=)` | Jandex over the jar's own classes | 26 / 51 (51%) |
| **Union of B+C+D** (what the classifier uses) | | **36 / 51 (71%)** |

**Deviation from DESIGN.md worth flagging up front:** source D
(`@ConfigMapping`/`@ConfigRoot` via Jandex) was probed only against each extension's
**runtime** jar (`ResolvedDependency.getResolvedPaths()`), not the `-deployment` artifact
that DESIGN.md specifies as the home of `BUILD_TIME`-phase config roots. This spike never
resolves `-deployment` artifacts at all: `ApplicationModel.getDependencies()` returns
runtime coordinates for extensions, and the corresponding `-deployment` jars, while
present in the model's build-dependency graph, were not walked or Jandexed here.
Consequently, any config root that lives *only* in a `-deployment` module (a
`BUILD_TIME`-only `@ConfigRoot` with no runtime-visible counterpart) is invisible to
source D and is not reflected anywhere in the reported 71% union-coverage figure. Probing
`-deployment` jars for source D is explicitly M2 work, not covered here.

Source A is a genuine dead end, not an implementation bug: `quarkus-extension.properties`
carries deployment-artifact coordinates and capability metadata but never config
information, across all 51 extensions checked. It is kept in the code (and in this
table) specifically to document that negative rather than silently drop the source.

Another dead end, this time in the source D implementation: `JarIndexer.createJarIndex`
was evaluated as a replacement for the manual `ZipFile`-entry walk and rejected, since
every overload writes a filesystem artifact by design (a modified jar, a new jar, or a
`<name>-jar.idx` file written next to the jar in the local repo when both are `false`)
and prints one line per indexed class in verbose mode; in-memory indexing needs the
manual entry walk instead.

No single source dominates the other three:
- B (`quarkus-extension.yaml`) is hand-maintained and can be outright wrong. For
  example, `quarkus-opentelemetry` declares `quarkus.opentelemetry.` in its yaml while
  its real, working prefix is `quarkus.otel.` (source C/D both correctly report
  `quarkus.otel.*`; the run below shows the classifier picking up `quarkus.otel.*` keys
  for exactly this extension via sources C+D, with the wrong B-declared root ignored
  because it never matches a real key).
- C and D are derived straight from the extension's own annotated source and are
  authoritative where present, but only cover classes actually annotated with
  `@ConfigMapping`/`@ConfigRoot`, so spec-defined MicroProfile prefixes (`mp.health.`,
  `mp.jwt.`, `mp.context.`) only ever show up via B.
- Taking the union (rather than a preference order that stops at the first hit) is the
  safer default for a "used" signal: a spurious extra root can only produce a false
  positive if it happens to collide with a key really owned by someone else, whereas
  dropping a correct root produces an immediate false "suspect".

**False positive observed, not just theorized.** The code comment on
`probeConfigModelJson` predicts that an over-broad `@ConfigMapping`-declared prefix can
cause a false "used" classification, and the bench run confirms it happens in practice
for `quarkus-logging-json`. Probing that extension in isolation shows source B (yaml)
correctly declares only the narrow `quarkus.log.console.json.` root, while sources C
(config-doc json) and D (Jandex annotations) both declare the broader `quarkus.log.`
root, since the declaring `@ConfigMapping` class covers the whole `quarkus.log`
namespace even though the extension only really owns the `console.json` subtree. Because
the union includes the broad root, `quarkus-logging-json` classifies `used-config` on
the strength of keys such as `quarkus.log.category."io.apicurio".level`, which are owned
by Quarkus core logging, not by this extension at all; the app's `application.properties`
never actually sets a `quarkus.log.console.json.*` key. Concrete M2 risk mitigation to
evaluate: when sources disagree on root granularity for the same class, prefer the
narrowest declared root, or require internal consistency across sources (only credit a
root if at least two of B/C/D agree on the same prefix) before using it as a "used"
signal.

### The JDBC hard case: exit criterion met

None of the four JDBC drivers (`quarkus-jdbc-h2`, `-mysql`, `-postgresql`, `-mssql`)
own a config root from any of the four sources. This is expected, since they are pure
driver implementations with no `@ConfigMapping` of their own. The spike adds one
derived signal beyond the four raw sources: **root inheritance**. An extension with no
config root of its own inherits the roots of the extensions it directly depends on (via
`ResolvedDependency.getDirectDependencies()`, falling back to the yaml descriptor's
`metadata.extension-dependencies` list when the model doesn't carry direct deps),
excluding roots owned by "ubiquitous" extensions (more than half of all resolved
extensions depend on them; on this bench that's `quarkus-core`, `quarkus-arc`,
`quarkus-smallrye-context-propagation`). Without that exclusion every extension would
trivially inherit `quarkus.log.*` from core and the signal would be worthless.

Result on the bench: all four JDBC drivers inherit `quarkus.datasource.` from
`quarkus-agroal` (the connection-pool extension all four depend on) and are correctly
classified **`used-config (inherited)`**, matching all 30 `quarkus.datasource.*` keys
in the app's `application.properties` (`quarkus.datasource.db-kind`,
`quarkus.datasource.h2.jdbc.*`, etc.), via config, not bytecode, exactly as the exit
criterion in `backlog/tasks/task-1...md` requires. Per-driver discrimination by
`db-kind` value (h2 vs. postgresql vs. mysql vs. mssql) is explicitly out of scope for
M1 per DESIGN.md and is not attempted; all four drivers currently classify identically
via the shared `quarkus.datasource.` root.

### Coverage gaps that are real, not artifacts

13 of the 24 directly-declared extensions land as `suspect` in this spike's table (see
full table below) under the config signal alone. Six of them are individually argued
here to be load-bearing despite the zero config-root match: `quarkus-scheduler`,
`quarkus-smallrye-health`, `quarkus-undertow`, `quarkus-vertx` and
`quarkus-resteasy-client` are wired by *presence* (they provide the
scheduling/health-endpoint/servlet/reactive/REST-client infrastructure the app runs on)
rather than by any explicit config key the app sets, and `quarkus-smallrye-context-propagation`
is one of the three extensions this spike's own root-inheritance step already treats as
"ubiquitous" because more than half of the 51 resolved extensions depend on it. This is
not a bug in the spike; it is signal 1 operating exactly as scoped. The other seven
suspects (`quarkus-jackson`, `quarkus-kubernetes-client`, `quarkus-resteasy-jackson`,
`quarkus-smallrye-fault-tolerance`, `quarkus-smallrye-jwt`,
`io.apicurio:apicurio-registry-config-index` and `quarkus-resteasy-client-jackson`) are
plausibly legitimate suspects too, but are not individually argued in this doc. All 13,
argued or not, are exactly the residual DESIGN.md's signal 2 (bytecode) and signal 3
(capabilities) exist to shrink; M1 explicitly scopes bytecode out ("NOT CHECKED" on
every row).

Two of the 13, not one, have no config-root metadata of their own in any of the four
sources: `io.apicurio:apicurio-registry-config-index` and
`quarkus-resteasy-client-jackson`. The former is the real coverage gap worth flagging
for M2: the app's own first-party custom extension does not ship a
`quarkus-extension.yaml` `metadata.config` list, a config-doc json, or Jandex-visible
`@ConfigMapping` classes in that particular jar. Signal 2 (the bytecode/Jandex scan of
the app's own compiled classes) was **NOT CHECKED** by this spike, so whether it would
close this particular gap is an unverified expectation for M2, not evidence: it is
plausible only because the app's own code presumably references classes from this jar,
but that reference was never actually inspected. The latter,
`quarkus-resteasy-client-jackson`, is a stock Quarkus companion extension (Jackson
support wired into the RESTEasy Reactive client) with no config of its own to declare in
any source; its lack of metadata is expected, not a gap, and needs no M2 follow-up.

### Why the M2 config reader still cannot delegate to SmallRye Config

`AppConfig` (`spike/src/main/java/.../AppConfig.java`) parses `application.properties`
itself instead of resolving it through SmallRye Config, even though key extraction is now
delegated to `java.util.Properties#load(Reader)` (comment skipping, backslash
continuation and separator parsing all come for free from the JDK that way). The reason
predates that change and is unaffected by it: a real SmallRye Config resolution eagerly
resolves `${...}` property expressions and environment-variable references, and fails
(or silently drops the property) when one is unresolvable outside a running application
context. The config-root signal only needs the *key* space, and `application.properties`
files routinely contain keys whose values reference environment variables or other
properties that do not exist at analysis time. `AppConfigReader` in the eventual M2 mojo
inherits this same constraint: it must keep parsing properties and `%profile.` prefixes
itself (now via `Properties.load` plus the profile-prefix split already in this spike)
rather than delegating to SmallRye Config resolution.

### Recommendation for M2

Ship the source-B/C/D union plus root-inheritance as the config signal (no fallback to
a static platform-BOM index needed for M1's exit criterion; union coverage was
sufficient without it). Do **not** rely on source A. Keep signal 1 explicitly partial:
13 of the 24 directly-declared extensions land as `suspect` under signal 1 alone, before
bytecode/capability signals are added; only 6 of those 13 are individually argued above
as likely false suspects (load-bearing extensions with a legitimately zero config
footprint), and the remaining 7 are unconfirmed either way, not shown to be false
suspects. That is enough on its own to confirm DESIGN.md's three-signal design is
required, not optional. Config alone cannot single-handedly clear a real app's extension
list.

One more implementation note for the mojo: the per-extension probes in
`ConfigRoots.probe()` (sources A-D) are independent of each other across extensions,
with no shared mutable state. The spike runs them sequentially, one extension at a time,
deliberately, to keep the throwaway code simple to read and debug. The M2 mojo should
run them concurrently instead: probing dozens of jars sequentially (Jandexing every
class in each one) is exactly the kind of per-artifact I/O-and-parsing work that
parallelizes cleanly.

## Full run output

Regenerated after the M1 code-fix pass, including source D's revert back to the manual
`ZipFile`-entry walk (see the dead-ends note above: `JarIndexer.createJarIndex` was
evaluated and rejected because every overload writes a filesystem artifact). This run
therefore has no `Indexed <class> (<n> annotations)` verbose lines and writes no
`-jar.idx` files, matching the original spike's output shape exactly. The classification
summary line is unchanged from every prior run. One content difference from the very
first run remains: `quarkus-logging-json`'s `SOURCE` column reads `CD`, not `BCD`. That
is unrelated to the source-D revert; it is a consequence of the `AppConfig.match`
root-grouping fix (also part of this pass) now crediting every one of its matched keys to
the single broadest matching root (`quarkus.log.`) instead of to every root that happened
to match at least one key. See the "false positive observed" note above for why
`quarkus.log.` over-matching is itself a pre-existing, not new, concern.

```
========================================================================================================================
quarkus-extension-analyzer :: M1 spike
  application : io.apicurio:apicurio-registry-app:3.3.2-SNAPSHOT
  config      : /home/pantinor/data/repo/work/apicurio-registry/app/src/main/resources/application.properties
========================================================================================================================

[A2] ApplicationModel resolved in 5883 ms
[A2]   app artifact          : io.apicurio:apicurio-registry-app:3.3.2-SNAPSHOT
[A2]   dependencies          : 614
[A2]   runtime dependencies  : 462
[A2]   extension capabilities: 21
[A2]   platform imports      : 1
[A2]   quarkus extensions    : 51 (24 directly declared)

[A1] config-root source coverage over 51 resolved extensions
[A1]   A META-INF/quarkus-extension.properties :  0 / 51 (0%)
[A1]   B META-INF/quarkus-extension.yaml       : 31 / 51 (61%)
[A1]   C quarkus-config-doc/config-model.json  : 26 / 51 (51%)
[A1]   D @ConfigMapping/@ConfigRoot via Jandex : 26 / 51 (51%)
[A1]   union of B+C+D (what the spike uses)    : 36 / 51 (71%)

[CFG] application.properties: 308 lines, 185 distinct keys, 76 under quarkus.*, 1 profiles [<none>]
[A1]   ubiquitous extensions excluded from root inheritance: [io.quarkus:quarkus-arc, io.quarkus:quarkus-core, io.quarkus:quarkus-smallrye-context-propagation]

============================================================================================================================================
CLASSIFICATION of directly declared Quarkus extensions (bytecode signal OUT OF SCOPE for this spike)
SOURCE: B=quarkus-extension.yaml  C=quarkus-config-model.json  D=@ConfigMapping via Jandex  inherit=root of a non-ubiquitous extension dependency
============================================================================================================================================
EXTENSION                                      SOURCE  CONFIG ROOTS                       #KEYS  VERDICT
--------------------------------------------------------------------------------------------------------------------------------------------
io.apicurio:apicurio-registry-config-index     -       (none)                             0      suspect (no config metadata)
quarkus-agroal                                 BCD     quarkus.datasource.                30     used-config
                                                         keys : quarkus.datasource.h2.db-kind, quarkus.datasource.h2.jdbc.initial-size, quark...
quarkus-elasticsearch-java-client              B       quarkus.elasticsearch.             2      used-config
                                                         keys : quarkus.elasticsearch.devservices.enabled, quarkus.elasticsearch.health.enabled
quarkus-elytron-security-properties-file       BCD     quarkus.security.users., quarku... 1      used-config
                                                         roots: quarkus.security.users., quarkus.security.users.embedded.
                                                         keys : quarkus.security.users.embedded.enabled
quarkus-jackson                                BCD     quarkus.jackson.                   0      suspect (roots known, no key)
quarkus-jdbc-h2                                inherit quarkus.datasource.  <-quarkus-... 30     used-config (inherited)
                                                         roots: quarkus.datasource.  <-quarkus-agroal
quarkus-jdbc-mssql                             inherit quarkus.datasource.  <-quarkus-... 30     used-config (inherited)
                                                         roots: quarkus.datasource.  <-quarkus-agroal, quarkus.datasource.  <-quarkus-datasource, quarkus.devservices.  <-quarkus-devservices, quarkus.transaction-manager.  <-quarkus-narayana-jta
quarkus-jdbc-mysql                             inherit quarkus.datasource.  <-quarkus-... 30     used-config (inherited)
                                                         roots: quarkus.datasource.  <-quarkus-agroal, quarkus.datasource.  <-quarkus-datasource, quarkus.devservices.  <-quarkus-devservices, quarkus.transaction-manager.  <-quarkus-narayana-jta
quarkus-jdbc-postgresql                        inherit quarkus.datasource.  <-quarkus-... 30     used-config (inherited)
                                                         roots: quarkus.datasource.  <-quarkus-agroal, quarkus.datasource.  <-quarkus-datasource, quarkus.devservices.  <-quarkus-devservices, quarkus.transaction-manager.  <-quarkus-narayana-jta
quarkus-kubernetes-client                      B       quarkus.kubernetes-client.         0      suspect (roots known, no key)
quarkus-logging-json                           CD      quarkus.log., quarkus.log.conso... 9      used-config
                                                         roots: quarkus.log., quarkus.log.console.json.
                                                         keys : quarkus.log.category."io.apicurio".level, quarkus.log.category."io.quarkus.ht...
quarkus-micrometer-registry-prometheus         B       quarkus.micrometer.                4      used-config
                                                         keys : quarkus.micrometer.binder.http-client.enabled, quarkus.micrometer.binder.http...
quarkus-oidc                                   BCD     quarkus.keycloak.devservices., ... 3      used-config
                                                         roots: quarkus.keycloak.devservices., quarkus.oidc.
                                                         keys : quarkus.oidc.client-id, quarkus.oidc.enabled, quarkus.oidc.tenant-enabled
quarkus-opentelemetry                          CD      quarkus.opentelemetry., quarkus... 12     used-config
                                                         roots: quarkus.opentelemetry., quarkus.otel., quarkus.otel.exporter.otlp.
                                                         keys : quarkus.otel.enabled, quarkus.otel.exporter.otlp.endpoint, quarkus.otel.expor...
quarkus-resteasy-client-jackson                -       (none)                             0      suspect (no config metadata)
quarkus-resteasy-client                        B       quarkus.rest-client.               0      suspect (roots known, no key)
quarkus-resteasy-jackson                       B       quarkus.jackson., quarkus.reste... 0      suspect (roots known, no key)
                                                         roots: quarkus.jackson., quarkus.resteasy.
quarkus-scheduler                              BCD     quarkus.scheduler.                 0      suspect (roots known, no key)
quarkus-smallrye-context-propagation           B       mp.context.                        0      suspect (roots known, no key)
quarkus-smallrye-fault-tolerance               BCD     mp.fault.tolerance., quarkus.fa... 0      suspect (roots known, no key)
                                                         roots: mp.fault.tolerance., quarkus.fault-tolerance., smallrye.faulttolerance.
quarkus-smallrye-health                        BCD     mp.health., quarkus.health., qu... 0      suspect (roots known, no key)
                                                         roots: mp.health., quarkus.health., quarkus.smallrye-health.
quarkus-smallrye-jwt                           BCD     mp.jwt., quarkus.smallrye-jwt.,... 0      suspect (roots known, no key)
                                                         roots: mp.jwt., quarkus.smallrye-jwt., smallrye.jwt.
quarkus-undertow                               BCD     quarkus.servlet.                   0      suspect (roots known, no key)
quarkus-vertx                                  BCD     quarkus.vertx.                     0      suspect (roots known, no key)
--------------------------------------------------------------------------------------------------------------------------------------------
used-config = 7 | used-config (inherited) = 4 | suspect = 13 | total directly declared extensions = 24
Every row is additionally 'used-bytecode: NOT CHECKED' (signal 2 is out of scope for M1).

[A2] capability graph available for signal 3: 21 extensions, 27 provided capabilities, 0 required capabilities
```

## Acceptance criteria check

- [x] Both risky assumptions have a verified yes/no answer with evidence. Assumption 2:
      **YES**. Assumption 1: **PARTIAL YES** (union coverage 71%, JDBC hard case fully
      resolved via inheritance, 13 real coverage gaps documented above and left to
      signals 2/3).
- [x] Registry app extensions enumerated with config-root match results: 51 resolved
      extensions, 24 directly declared, full per-extension table above.

## What was not attempted (out of scope for M1, noted per DESIGN.md)

- Signal 2 (bytecode/Jandex over the app's own `target/classes`): every row above is
  explicitly `used-bytecode: NOT CHECKED`.
- Signal 3 (capability requires/provides join): the data is confirmed present and
  correctly shaped (see Assumption 2 section) but not exercised end-to-end because this
  bench declares zero `requires` capabilities; needs a different bench app or a
  synthetic case for M2.
- Per-driver `db-kind` discrimination among the four JDBC extensions: explicitly
  deferred to future work by DESIGN.md; all four currently share one verdict.
- The static platform-BOM fallback index mentioned in DESIGN.md as a fallback for
  assumption 1: not needed, since union coverage (B+C+D) plus inheritance was
  sufficient to meet the exit criterion. Worth revisiting only if M2 finds coverage
  gaps that matter in practice (e.g. the `apicurio-registry-config-index` case above,
  though that one is a first-party in-house extension without any public Quarkus
  platform BOM entry to fall back to anyway).
