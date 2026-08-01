# Second-bench validation: quarkus-super-heroes / rest-fights

Date: 2026-08-01. Run against `io.quarkus.sample.super-heroes:rest-fights:1.0`
(Quarkus platform 3.37.4), the second real Quarkus application used to satisfy
the M4 precondition ("validates on at least two real applications"). The
analyzer plugin's own resolver is pinned to Quarkus 3.33.2.1
(`plugin/pom.xml:25`, `<quarkus.version>3.33.2.1</quarkus.version>`), so this
bench also exercises the version-skew scenario the task called out.

## Reproduction

```bash
git clone --depth 1 https://github.com/quarkusio/quarkus-super-heroes /tmp/super-heroes
cd /tmp/super-heroes/rest-fights
# project requires JDK 25 (maven.compiler.release=25 in the module pom)
export JAVA_HOME=~/.sdkman/candidates/java/25.0.2-tem
export PATH=$JAVA_HOME/bin:$PATH

mvn -q compile -DskipTests        # as instructed. Not sufficient alone, see "Crash" below.
mvn io.github.pantinor:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze \
    -Dqea.reportFile=/tmp/qea-superheroes.json -Dqea.ignoreFragments=true
```

## Crash on the prescribed repro (real finding, not papered over)

Running the analyze goal exactly as instructed, right after `mvn compile`,
**fails**:

```
[ERROR] Failed to execute goal io.github.pantinor:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze
(default-cli) on project rest-fights: quarkus-extension-analyzer: failed to resolve the ApplicationModel:
Failed to resolve artifact io.quarkus.sample.super-heroes:rest-fights:jar:1.0: The following artifacts could
not be resolved: io.quarkus.sample.super-heroes:rest-fights:jar:1.0 (absent): Could not find artifact
io.quarkus.sample.super-heroes:rest-fights:jar:1.0 in central (https://repo.maven.apache.org/maven2)
-> [Help 1]
```

Full `-X` stack trace confirms the mechanism, not a fluke:

```
Caused by: io.quarkus.bootstrap.resolver.maven.BootstrapMavenException:
  Failed to resolve artifact io.quarkus.sample.super-heroes:rest-fights:jar:1.0
Caused by: org.eclipse.aether.resolution.ArtifactResolutionException: ...
Caused by: org.eclipse.aether.transfer.ArtifactNotFoundException:
  Cannot access central ... and the artifact ... has not been downloaded from it before.
```

**Mechanism.** The plugin resolves the ApplicationModel via Quarkus's
`BootstrapMavenContext`/`QuarkusBootstrap`, which resolves the *project's own
jar* as a Maven coordinate (`groupId:artifactId:jar:version`) rather than
reading it off the reactor build (`target/classes`). `mvn compile` never
installs that jar to the local repository, and `rest-fights:1.0` is a private
sample coordinate that does not exist on Central, so resolution fails
outright. There's no fallback path. Confirmed empirically: `find
~/.m2/repository/io/quarkus/sample/super-heroes/rest-fights/1.0/` after
`compile` shows only a `rest-fights-1.0.jar.lastUpdated` marker (a failed
download stamp), no jar.

**Fix that unblocks it.** `mvn install -DskipTests` instead of `compile`
(installs the jar to the local repo). Re-running the identical `analyze`
goal afterward against the now-populated
`~/.m2/repository/io/quarkus/sample/super-heroes/rest-fights/1.0/rest-fights-1.0.jar`
succeeds cleanly (`BUILD SUCCESS`, exit 0).

This is *not* the version-skew failure the task anticipated. It reproduces
independent of the 3.33.2.1/3.37.4 mismatch (the same failure would occur even
with a matched resolver version, since it's a pure "artifact not in local
repo" problem). It is nonetheless a real, first-time-user-facing bug: the
apicurio-registry bench never surfaced it because that repo's `app` module
jar was already sitting in the local repo from routine dev builds. A fresh
clone of any multi-module Quarkus project that has only been `compile`d, not
`install`ed, will hit this. It is worth a plugin fix (fall back to the
reactor build output, or document the `install` requirement loudly) before a
Quarkiverse proposal. This is exactly the kind of first-run friction that
sinks adoption.

**Version skew itself: no separate failure observed.** Once the jar was
installed, the plugin's 3.33.2.1-pinned `quarkus-bootstrap-maven-resolver`
resolved the 3.37.4 application model, its extension descriptors, and all
config-root metadata without incident or warning. No exceptions, no missing
extensions, no obviously wrong config-root mappings traced back to version
differences. The skew is real (four platform releases apart) but it turned
out benign for this bench. The crash above is orthogonal to it. That's a
useful, if less dramatic, data point: don't conflate "old resolver version"
with the actual failure mode found here.

## Results

**Correction (post-review).** The first draft of this report quoted the
plugin's JSON `summary` block verbatim as the headline verdict counts:

```
used-bytecode = 7 | used-config = 11 | used-capability = 2 | suspect = 16 | total = 36
```

That block aggregates **all 36 dependency rows**, and 13 of those rows are
plain-jar dependencies (`quarkusExtension: false` in the JSON), not declared
Quarkus extensions. For the question this tool exists to answer ("which of my
*declared extensions* are actually used"), the extension-only numbers are the
ones that matter, and they tell a materially different story: 4 suspects out
of 23 extensions, not 16 out of 36. Mixing the two categories into one
headline number is a real usability problem in the plugin's own output (the
JSON `summary` field and the text report's closing tally line do not
distinguish extension rows from plain-jar rows), and it misled this report's
first draft exactly as it would mislead any other first-time reader. Worth
filing as a plugin follow-up: split `summary` by `quarkusExtension`, or at
minimum label the aggregate as covering both categories.

Recomputed directly from `/tmp/qea-superheroes.json`'s `dependencies` array
(`python3 -c "...Counter(r['verdict'] for r in ... if r['quarkusExtension']"`,
cross-checked by hand against the text report):

```
Extensions (23 declared Quarkus extensions):
  used-config = 11 | used-bytecode = 6 | used-capability = 2 | suspect = 4

Plain jars (13 non-extension rows, reported by the same plugin run):
  used-bytecode = 1 | suspect = 12

All rows combined (= the plugin's own JSON `summary` block):
  used-bytecode = 7 | used-config = 11 | used-capability = 2 | suspect = 16 | total = 36
```

(Text report and JSON: `/tmp/qea-superheroes.json`, run log `/tmp/qea-run2.log`.)

### Extensions: used-bytecode (6), spot-checked, all plausible
`quarkus-apicurio-registry-avro` (via transitive API of `org.apache.avro:avro`),
`quarkus-grpc` (via transitive API of `quarkus-grpc-api`), `quarkus-mongodb-panache`
(direct), `quarkus-rest-client-jackson` (via transitive API of
`microprofile-rest-client-api`), `quarkus-smallrye-fault-tolerance` (via
transitive API of `smallrye-fault-tolerance-api`), `quarkus-smallrye-openapi`
(via transitive API of `microprofile-openapi-api`). All consistent with the
service: Mongo Panache repositories, Kafka+Avro Fight events, REST clients to
hero/narration services, `@Fallback` methods, and OpenAPI contract-first
generation. (`mapstruct`, a plain jar also verdict `used-bytecode`, is covered
under "Plain jars" below, not in this extension count of 6.)

### Extensions: used-config (11), spot-checked against `application.properties`, all correct
Matched config roots/keys line up 1:1 with the service's `application.properties`
(Helm, OpenAPI generator, Kubernetes/Knative/OpenShift/Minikube, kubernetes-client,
liquibase-mongodb, messaging-kafka, opentelemetry, rest-jackson, stork).

### Extensions: used-capability (2), correct
`quarkus-arc` (messaging depends on it), `quarkus-smallrye-health` (mongodb-client
depends on it): both legitimate direct extension-dependency edges.

## Extension-level suspect triage (4)

Ground truth checked against: `README.md` (repo root and rest-fights),
`src/main/resources/application.properties`, `pom.xml`, `deploy/` and
`monitoring/` manifests, `mvn dependency:tree -Dverbose`, and `grep` over
`src/main/java` for annotations/imports.

| Extension | Verdict | Reasoning |
|---|---|---|
| `quarkus-hibernate-validator` | **Signal gap (false suspect)** | Genuinely used: `jakarta.validation` constraint annotations (`@NotNull` etc.) present in 8 DTO/service classes (`Fight.java`, `FightRequest.java`, `Fighters.java`, `FightService.java`, and others). Not detected because `jakarta.validation-api` is pulled in by two declared extensions in the resolved graph (`quarkus-hibernate-validator` directly, and transitively via `io.apicurio:apicurio-registry-common` under `quarkus-apicurio-registry-avro`), confirmed with `mvn dependency:tree -Dverbose`. The transitive-API signal's own design explicitly excludes jars shared between declared extensions from exclusive attribution (documented in M2-VALIDATION.md's TASK-5 addendum), so this is the shared-jar exclusion rule producing a real false suspect: the mirror case of the apicurio bench's kubernetes-client fix (there it worked because the jar was not shared; here it fails because it is). |
| `quarkus-container-image-docker` | **Signal gap (likely false suspect)** | `application.properties` sets `quarkus.container-image.builder=docker`, the documented Quarkus mechanism for selecting this extension as the active image builder. The extension has its own config root (`quarkus.docker.*`, e.g. executable path) which the app never sets, so the key-presence signal finds nothing. This is a value-based activation pattern (a value under one extension's config root selects a different extension) that no current signal can see. |
| `quarkus-micrometer-opentelemetry` | **Likely signal gap, not runtime-proven** | No source-level Micrometer API usage (`@Timed`, `MeterRegistry`, etc.) and no config key under its own root `quarkus.micrometer.otel.`. Two pieces of circumstantial evidence point toward genuine use rather than dead weight: (1) `quarkus.otel.metrics.enabled=true` is explicitly set, and this bridge extension is the only mechanism that turns the Micrometer meters auto-registered by other active extensions (HTTP server, Mongo client, REST client) into OTel metric exports, so the enabled flag would otherwise be a no-op; (2) `mvn dependency:tree -Dverbose` shows the base `io.quarkus:quarkus-micrometer` is pulled in *only* as a transitive child of `quarkus-micrometer-opentelemetry` (not separately declared), and `application.properties` explicitly toggles `%remotedev.quarkus.micrometer.enabled=false`, which would be dead configuration if the base subsystem weren't active by default in the other profiles. Same cross-extension-coupling gap family as the docker case (a value/state on a sibling extension activates this one, with no key of its own to match). Not runtime-verified in this session (no container was started to observe an actual OTLP export), so kept as "likely," not asserted as fact. |
| `quarkus-info` | **True suspect, confirmed by repo-wide search** | Repeated `/q/info`, `InfoContributor`, and `quarkus.info.` searches across the entire `super-heroes` monorepo (README files, `src/main`, `src/test`, `deploy/`, `monitoring/`) returned zero hits. No positive evidence anywhere that this endpoint or extension point is exercised; genuinely looks unused (or used only ad hoc by a human hitting `/q/info` directly, which no static signal, and no other artifact in the repo, can ever surface). |

Net: of 4 extension-level suspects, 1 is a confirmed signal gap
(`quarkus-hibernate-validator`, shared-jar exclusion), 2 are likely signal
gaps (`quarkus-container-image-docker` and `quarkus-micrometer-opentelemetry`,
both value/state-based activation the tool's current signals cannot see),
and 1 is a true suspect (`quarkus-info`, no evidence of use anywhere in the
repo).

## Plain jars (13 rows, not counted as extensions)

These are non-extension dependency rows the plugin also reports on (`grpc`,
`stork`, `strimzi` SPI implementation jars, plus `mapstruct`). They are a
separate story from extension adoption: mostly optional SPI/plugin jars
declared for runtime pluggability, several structurally invisible to any
bytecode signal by design.

**used-bytecode (1):** `org.mapstruct:mapstruct`, referenced directly from
compiled classes (the generated mapper implementations reference MapStruct's
runtime annotations/types). Correctly resolved as used.

**suspect (12):**

- `io.grpc:grpc-services`: **true suspect, expected tool limitation.** This is
  the gRPC server-reflection service jar; it activates via ServiceLoader
  registration at build time, not via any class reference in application
  code, so it is structurally invisible to a bytecode signal. The tool's own
  phrasing ("suspect means no signal fired, not safe to remove") is doing
  exactly its job here.
- `io.strimzi:kafka-oauth-client`, `io.strimzi:kafka-oauth-common`: **true
  suspects, correctly resolved.** No OAuth configuration anywhere for the
  Kafka connector (`grep -i oauth` over config and source: zero hits). These
  are optional Kafka-OAuth SPI jars included for deployments that need them;
  this service's config uses plaintext/no-auth Kafka.
- `io.smallrye.stork:stork-service-discovery-{consul,eureka,knative,kubernetes}`
  (4 rows): **true suspects, correctly resolved.**
  `quarkus.stork.<service>.service-discovery.type=static` is set for all
  three Stork-routed clients (hero, villain, narration); these four
  discovery-provider jars implement types that are never selected.
- `io.smallrye.stork:stork-service-discovery-static-list`: **signal gap, same
  family as `quarkus-container-image-docker`.** This is the provider
  actually selected by `service-discovery.type=static` for all three
  Stork-routed clients, yet it remains `suspect` because selection happens
  through a config *value* (`type=static`), not a key under its own config
  root, and there is no compile-time bytecode reference to a plain SPI jar
  either. The first draft of this report incorrectly implied this row was
  "already used-config" by conflating it with the `quarkus-smallrye-stork`
  *extension* row (which is correctly `used-config`, matched on
  `quarkus.stork.*.service-discovery.type`/`.address-list`); the plain-jar
  row for the specific provider implementation is a distinct entry in the
  JSON and stayed `suspect`. Corrected here.
- `io.smallrye.stork:stork-load-balancer-{random,least-requests,least-response-time,power-of-two-choices}`
  (4 rows): **true suspects, correctly resolved.** No
  `quarkus.stork.*.load-balancer.type` key is set anywhere, so none of these
  named strategies is explicitly selected (Stork falls back to its own
  default, which this session did not further trace to a specific jar).

Net: of the 12 plain-jar suspects, 11 are true suspects (several structurally
undetectable by any static signal: reflection service, unselected SPI
implementations) and 1 (`stork-service-discovery-static-list`) is a signal
gap in the same value-based-activation family as
`quarkus-container-image-docker`.

## Skew notes

- App: Quarkus platform 3.37.4. Plugin's bundled resolver:
  `quarkus-bootstrap-maven-resolver:3.33.2.1` (`plugin/pom.xml:25`).
- No crash, no missing/garbled extension descriptors, no obviously wrong
  config-root mapping attributable to the version gap. The plugin's own
  bootstrap machinery handled a four-minor-release-newer application model
  without incident.
- The only crash in this bench (ApplicationModel resolution failing on an
  uninstalled jar, see above) reproduces independent of version skew. It is
  a distinct, more fundamental bug about local-repo state, not about
  resolver/app version alignment.

## Honest gaps / what this bench did not check

- Did not attempt `rest-heroes` (the specified fallback) since `rest-fights`
  built and ran to completion once the install-vs-compile issue was worked
  around; no need to invoke the fallback.
- The `quarkus-micrometer-opentelemetry` call is genuinely uncertain. I did
  not instrument the running application to confirm OTel metrics are actually
  emitted through the Micrometer bridge at runtime; this is a plausible
  mechanism, not a proven one. Flagged as unconfirmed rather than counted as
  a resolved gap.
- Did not run `-Dqea.debugAttribution=true` against a successful analyze run
  (only against the failing one, where it produced no output since the crash
  happens before analysis logic runs at all), so the shared-jar exclusion
  explanation for `quarkus-hibernate-validator` is inferred from `mvn
  dependency:tree -Dverbose` output plus the documented design rule in
  M2-VALIDATION.md, not from a `qea-debug` trace on this specific run. That
  inference is solid (the tree unambiguously shows two declared-extension
  paths to `jakarta.validation-api`) but is one level less direct than a
  debug-flag confirmation.
- Did not modify or investigate the analyzer plugin's source in this session
  (out of scope per instructions); the "fix that unblocks it" (`mvn install`
  instead of `compile`) is a workaround for reproducing the bench, not a fix
  to the plugin itself. The ApplicationModel-resolution-requires-local-jar
  behavior is a real bug candidate for a future task.
