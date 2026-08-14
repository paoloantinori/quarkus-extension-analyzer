---
id: TASK-20
title: >-
  Mojo LinkageError on projects exposing Quarkus bootstrap classes: isolate the
  model-resolution classloader
status: Done
assignee: []
created_date: '2026-08-14'
updated_date: '2026-08-14 20:43'
labels: []
dependencies: []
references:
  - docs/AUTONOMOUS-WORK-LOG.md
  - TASK-9
priority: high
type: bug
ordinal: 20000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
FOUND 2026-08-14 on the camel-quarkus grpc integration test (5th bench, work
unit 9 of the autonomous log). The mojo fails with:

```
java.lang.LinkageError: loader constraint violation: when resolving method
'io.quarkus.paths.PathCollection io.quarkus.maven.dependency.ResolvedDependencyBuilder.getResolvedPaths()'
the class loader ... of the current class, io/quarkus/bootstrap/resolver/BootstrapAppModelResolver,
and the class loader ... for the method's defining class, io/quarkus/maven/dependency/ResolvedDependencyBuilder,
have different Class objects for the type io/quarkus/paths/PathCollection
```

Root cause: our plugin embeds quarkus-bootstrap-maven-resolver 3.33.2.1; the
analyzed project (camel-quarkus ITs, via their build-parent) exposes Quarkus
bootstrap classes 3.39.0 on the project build classpath; the Maven plugin realm
imports the project's classes, so `ResolvedDependencyBuilder` is loaded twice
(two realms) with conflicting `io.quarkus.paths.PathCollection` class objects.

Scope: affects projects that themselves ship bootstrap classes into the plugin
realm (extension-collection build infrastructure like camel-quarkus ITs).
Typical applications using released platform extensions do NOT expose bootstrap
classes and are unaffected (all prior benches validated this).

FIX DIRECTIONS:
1. Classloader isolation: resolve the ApplicationModel in a dedicated
   child-first classloader that loads ONLY the embedded resolver version for
   `io.quarkus.*`, so the project's classes cannot leak into the resolution.
2. Alternative: shade/relocate the embedded bootstrap classes under our own
   namespace (maven-shade-plugin with relocation).

Repro: /tmp/camel-quarkus (10 reactor modules installed locally), run the
analyze goal on integration-tests/grpc. See work unit 9 for the module list.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done

- [x] #1 Reproduce consistently; write the failing case down (the camel IT is
      the repro; keep the installed reactor modules documented)
- [x] #2 Implement the chosen isolation approach; the camel IT analyze goal
      completes with a report
- [x] #3 No regression on the 4 existing benches (mojo + extension)
- [ ] #4 Unit test for the isolation mechanism where feasible

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
FIXED 2026-08-14 via shading. New module 'shaded' (quarkus-extension-analyzer-shaded): bundles core + quarkus-bootstrap-maven-resolver with ALL io.quarkus.* relocated to io.github.paoloantinori.qea.internal.* (and jandex too), ServicesResourceTransformer for META-INF/services. Entry class IsolatedAnalyzerRunner: JDK+Maven-API-only boundary, runs resolution+analysis, returns the report as JSON+text. AnalyzeMojo is now a thin shell (params, report file, failOnSuspect via JSON parsing). VERIFIED: (1) the camel-quarkus IT repro that failed with LinkageError now completes with a full report (5 extensions); (2) zero regressions across all 6 prior benches: rest-fights suspect=3, rest-heroes=5, apicurio=5, grpc-quickstart=0, scheduler-quickstart=0, cache-quickstart=2, all identical to pre-shading baselines; (3) 96/96 reactor tests green; (4) plugin dependency list shows ONLY the shaded artifact (no direct io.quarkus deps); (5) shaded jar verified: 7897 classes, all relocated, zero unrelocated io/quarkus/ entries.
<!-- SECTION:NOTES:END -->
