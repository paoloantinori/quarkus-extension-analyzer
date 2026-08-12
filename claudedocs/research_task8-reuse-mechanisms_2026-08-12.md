# Research Report: existing mechanisms for detecting extension-provided bean types (TASK-8 reuse)

**Date**: 2026-08-12
**Depth**: exhaustive
**Confidence**: HIGH on the mechanism existence; MEDIUM on static-extractability without augmentation (one code path to verify empirically)

## Executive Summary

Quarkus already builds, at build time, the exact "which extension provides which
bean type, and who injects it" map that TASK-8 needs. It is the ArC "unremovable
beans" / removed-beans computation, and its output is even exposed at runtime
(`/q/arc/removed-beans`) and in the Dev UI. The producer declarations are also
statically discoverable in extension deployment bytecode via
`SyntheticBeanBuildItem.configure(Type.class)` and `BeanRegistrar`. No IDE,
Maven, or Gradle plugin has built a dependency-analyzer on top of this; the
semantic leader (autonomousapps) is bytecode/bytecode-annotation based and has no
Quarkus awareness. So the realistic reuse path is: consume ArC's own
producer/consumer data, either by (a) reusing its build item types at build time
(only possible if the analyzer were a Quarkus extension itself, i.e. option B /
M5), or (b) statically harvesting `SyntheticBeanBuildItem.configure(...)` /
`BeanRegistrar` type literals from deployment jars in the current standalone-mojo
form. Option (b) is the reuse path compatible with today's standalone plugin,
with a curated table as the fallback for producers declared dynamically.

## Findings

### 1. Quarkus ArC already computes the authoritative producer/consumer graph (HIGH)

At build time ArC identifies "unremovable" beans that root the dependency tree.
Unremovable beans include, verbatim: "a bean which declares a `@Scheduled`
method, identified by the Scheduler extension" and "a JAX-RS resource class,
identified by the RESTEasy extension" [1]. Extensions add their own unremovable
rules. An "unused" bean is one that is not unremovable, not injectable to any
injection point, and has no producer eligible for injection [1]. The set of
removed beans is exposed via `/q/arc/removed-beans` and Dev UI [2].

This is precisely the data TASK-8 needs: the producer↔consumer wiring, computed
authoritatively by the framework. The hibernate-validator/scheduler false
positives from our triage are exactly the cases ArC resolves.

### 2. Producer declarations are statically extractable from deployment bytecode (HIGH for the pattern, MEDIUM for completeness)

Extensions register the beans they provide through:
- `SyntheticBeanBuildItem.configure(Type.class)` in `@BuildStep` methods [3][4],
  where `Type.class` is a class literal visible in the deployment jar's
  bytecode.
- `BeanRegistrar` implementations (the build-compatible CDI SPI) [5].
- `@Produces`-style and annotation-based producer methods on runtime classes.

The `META-INF/quarkus-extension.yaml` descriptor does NOT list produced bean
types (it carries name/description/capabilities/metadata) [6], so the descriptor
is not a source for this.

Static harvest feasibility: the `SyntheticBeanBuildItem.configure(Foo.class)`
calls are bytecode-visible, so an external Jandex/ASM scan of a deployment jar
can enumerate the produced types without running augmentation, the same way the
analyzer already Jandexes deployment jars for `@ConfigRoot`. Producers declared
via `BeanRegistrar` (dynamic) would be missed by a static scan; those need a
curated fallback. No existing tool does this harvest today (no search hit).

### 3. No IDE/Maven/Gradle dependency-analyzer reuses this (HIGH)

- The IntelliJ Quarkus Tools (redhat-developer/intellij-quarkus) resolve
  `@ConfigProperty` and do Qute/code-completion, but do not do dependency-usage
  analysis or "which extension provides this bean" for unused-dependency
  detection [7].
- autonomousapps/dependency-analysis-gradle-plugin, the semantic leader, is
  bytecode and annotation based (it understands annotation processors, service
  loaders, etc.) but has no Quarkus/CDI-producer awareness [8].
- DepClean and maven-dependency-plugin are bytecode-only with no CDI awareness.

So there is no off-the-shelf dependency analyzer to reuse; the reuse is of
Quarkus's *internal mechanism*, not another tool's *analysis*.

### 4. Implications for TASK-8

Two feasible reuse paths, depending on the plugin's form:

- **Standalone mojo (today's Option A):** statically harvest
  `SyntheticBeanBuildItem.configure(<Type>.class)` and `BeanRegistrar`-declared
  types from each extension's deployment jar (the analyzer already Jandexes
  deployment jars for signal 1). Build `extension -> produced types`. Mark an
  extension used when project bytecode references one of its produced types,
  even via a shared jar. This reuses the *mechanism* (deployment-jar bytecode
  scan) without running augmentation. Limitation: dynamic producers (`BeanRegistrar`
  with runtime-decided types) are invisible statically and need a small curated
  fallback. This is the reuse path compatible with today's plugin.
- **Extension form (Option B / M5):** the analyzer runs inside augmentation and
  can consume ArC's `BeanContainer`/`BeanInfo` build items directly, getting the
  authoritative producer/consumer graph for free, including dynamic producers.
  This is strictly more accurate but requires the M5 re-architecture.

Either way, TASK-8 no longer requires inventing a curated table from scratch;
it can be driven by harvested deployment-jar producer declarations, with a
curated table only as the fallback for the dynamic-producer tail.

## Confidence Assessment

- **HIGH**: ArC computes the authoritative producer/consumer graph at build
  time and exposes removed/unremovable beans [1][2]. Producer declarations use
  `SyntheticBeanBuildItem.configure(Type.class)` [3][4]. No existing
  dependency-analyzer reuses this.
- **MEDIUM**: the static harvest of `SyntheticBeanBuildItem.configure(...)` type
  literals is feasible in principle (bytecode-visible) but I have not confirmed
  what fraction of real Quarkus extensions declare producers statically vs via
  dynamic `BeanRegistrar`; the dynamic tail size determines how much curated
  fallback is still needed. One empirical check: scan a few extension deployment
  jars (e.g. quarkus-narayana-jta, quarkus-hibernate-validator-deployment) for
  `configure(` calls and see the hit rate.
- **LOW**: I could not verify whether Quarkus's devtools/code.quarkus.io
  extract a reusable producer-type list for catalog purposes; the descriptor does
  not carry it.

## Sources

1. Quarkus blog, "Unused Beans and Why We Remove Them" (the authoritative
   mechanism, incl. the `@Scheduled`/Scheduler example that matches our triage
   case): https://quarkus.io/blog/unused-beans/
2. Quarkus CDI reference (ArC, `/q/arc/removed-beans`, unremovable-types):
   https://quarkus.io/guides/cdi-reference
3. Quarkus, "Writing Your Own Extension" (`SyntheticBeanBuildItem.configure`):
   https://quarkus.io/guides/writing-extensions
4. "Exploring Synthetic Beans in Quarkus" (configure/creator patterns):
   https://dev.to/yanev/exploring-synthetic-beans-in-quarkus-a-powerful-extension-mechanism-fbd
5. "Dynamic Beans, Static Speed" (`BeanRegistrar` build-compatible SPI):
   https://www.the-main-thread.com/p/quarkus-vs-spring-dynamic-bean-registration
6. Quarkus, "Extension Metadata" (`quarkus-extension.yaml` fields; no produced
   types): https://quarkus.io/guides/extension-metadata
7. redhat-developer/intellij-quarkus (IDE features, no dependency analysis):
   https://github.com/redhat-developer/intellij-quarkus
8. autonomousapps/dependency-analysis-gradle-plugin architecture (bytecode/
   annotation based, no CDI awareness):
   https://deepwiki.com/autonomousapps/dependency-analysis-gradle-plugin/2-plugin-architecture
