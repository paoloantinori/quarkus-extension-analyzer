# Quarkus extension form: usage

The extension form runs the analyzer inside Quarkus augmentation (at build
time), where it has access to ArC's authoritative bean index. This lets it
resolve "annotation-consumer" false positives the standalone mojo cannot
(e.g. hibernate-validator used via `@NotNull`, scheduler via `@Scheduled`),
because ArC's build-time index knows which annotations the app actually uses.

## When to use the extension vs the mojo

| | Mojo (standalone) | Extension (in-build) |
|---|---|---|
| Use case | CI sweep over any built app, no pom change | Per-app, high-precision, automatic |
| Annotation-consumer FP | Unresolved (reported as suspect with evidence) | **Resolved** (ArC bean index) |
| App pom change needed | No (point and run) | Yes (add the extension as a dependency) |
| Best for | Portfolio/central CI hygiene | Per-app build integration |

Both share the same `core` library, so the three-signal classification is
identical; the extension adds the ArC annotation-consumer post-pass.

## How to use the extension

Add the extension to your Quarkus application module's `pom.xml`:

```xml
<dependency>
  <groupId>io.github.paoloantinori</groupId>
  <artifactId>quarkus-extension-analyzer</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Then build normally (`mvn package`). The analyzer runs during Quarkus
augmentation and prints the classification report to the build log. No separate
goal invocation is needed.

### Configuration

| Key | Default | Effect |
|---|---|---|
| `quarkus.extension-analyzer.fail-on-suspect` | `false` | When `true`, fails the build if any directly-declared dependency is classified `suspect`. |

### What the extension resolves that the mojo cannot

The extension's `AnnotationAttribution` post-pass checks ArC's bean index for
known annotation families and credits the extension that processes each:

| Annotation family | Credited extension |
|---|---|
| `jakarta.validation.constraints.*` (`@NotNull`, `@NotBlank`, etc.) | `quarkus-hibernate-validator` |
| `io.quarkus.scheduler.Scheduled` | `quarkus-scheduler` |
| `org.eclipse.microprofile.jwt.JsonWebToken` | `quarkus-smallrye-jwt` |
| `jakarta.ws.rs.*` (`@Path`, `@GET`, etc.) | `quarkus-resteasy-jackson` |
| `org.eclipse.microprofile.faulttolerance.*` (`@Fallback`, `@Retry`, etc.) | `quarkus-smallrye-fault-tolerance` |
| `io.quarkus.mongodb.panache.*` (Panache entities) | `quarkus-mongodb-panache` |
| `org.eclipse.microprofile.openapi.annotations.*` (`@Operation`, `@Schema`) | `quarkus-smallrye-openapi` |
| `io.smallrye.faulttolerance.api.*` (`@ApplyGuard`, `@ApplyFaultTolerance`) | `quarkus-smallrye-fault-tolerance` |
| `org.eclipse.microprofile.rest.client.inject.RegisterRestClient` | `quarkus-resteasy-client-jackson` (the client serializer; server `@Path` never credits it) |
| `io.quarkus.qute.*` (`@CheckedTemplate`, `TemplateInstance` returns) | `quarkus-rest-qute` |
| `application.yml` / `application.yaml` shipped under the module's `src/main/resources` or `target/classes` | `quarkus-config-yaml` (resolved under the module root being augmented, not the build CWD) |
| REST endpoints returning POJOs (`@Path` + `@GET` returning anything the serializer must convert; `Uni<Pojo>`, `RestResponse<Pojo>` unwrap) | `quarkus-rest-jackson` / `quarkus-resteasy-jackson` |

Each rule fires only when the family's probe evidence is present (annotation
usage in the bean index, a declared type, a shipped file, or endpoint return
types) AND the target extension is a directly-declared suspect (so it never
manufactures a verdict for an undeclared extension).

### Bench results

| Bench | Mojo suspects | Extension suspects | Reduction |
|---|---|---|---|
| Apicurio `app` (~24 extensions) | 5 | **1** | -80% |
| rest-fights (~23 extensions) | 3 | **3** (2 are runtime-only: info, otel) | stable |

## Limitations

- **Runtime-only extensions** (quarkus-info exposes `/info` endpoints, micrometer-
  opentelemetry auto-instruments): these have no compile-time annotation or bean
  reference, so neither the mojo nor the extension can detect their use. They
  remain `suspect` with evidence.
- **The extension itself appears as a suspect** in the report (correct: an analyzer
  extension has no app-side usage signal). Filter it out mentally.
- The annotation-consumer rules are a **curated table** (14 distinct families,
  17 entries; see `AnnotationAttribution.RULES`). Extensions
  not in the table fall through to the three core signals. The table is
  extensible; submit additions validated against real bench data.
- **Bench caveat (2026-08-17):** the old super-heroes workspace under
  /private/tmp/super-heroes is DAMAGED (a copy on Aug 16 gutted .git and
  dropped the openapi spec files; `generate-code` fails with an upstream
  `OpenAPI.getExtensions()` NPE even pristine, at both 3.33.2.1 and 3.38.1).
  The active bench is the fresh clone at /private/tmp/super-heroes-fresh
  (commit a3f2ce1, platform 3.38.1), which builds clean. New baselines with
  the current extension: rest-heroes extensions 7/8/1/3, suspects {info,
  micrometer-otel, the analyzer itself} - down from the damaged-era 5, the
  drop being the config-yaml FILE: credit firing on the real application.yml;
  rest-fights extensions 9/10/2/3, the same three suspects as before (the
  @RegisterRestClient-using app credits rest-client-jackson via the
  transitive-API bytecode signal, so the newly-live rule was not needed
  there and correctly left the non-suspect row untouched).

## Architecture

The extension is a standard Quarkus runtime+deployment pair:

```
extension/                       extension-deployment/
  quarkus-extension-analyzer       quarkus-extension-analyzer-deployment
  (runtime, near-empty)            (@BuildStep: AnalyzerBuildStep +
                                   AnnotationAttribution + AnalyzerReportBuildItem)
```

Both consume the shared `core` library (`quarkus-extension-analyzer-core`), which
holds the `Analyzer`, the three-signal classification, and the report model.
See `docs/REARCH-PLAN.md` for the full multi-module architecture.
