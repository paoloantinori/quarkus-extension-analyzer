# M2 validation on the Apicurio Registry bench

Date: 2026-08-01. Runs executed and verified by the orchestrator against
`io.apicurio:apicurio-registry-app:3.3.2-SNAPSHOT` (Quarkus 3.33.2.1), the
ground-truth bench defined in [DESIGN.md](DESIGN.md#validation-bench).

## Reproduction

```bash
cd plugin && mvn -q install -DskipTests
cd /path/to/apicurio-registry
mvn -q compile -f app/pom.xml   # bytecode signal needs app/target/classes populated
mvn io.github.pantinor:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze \
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
