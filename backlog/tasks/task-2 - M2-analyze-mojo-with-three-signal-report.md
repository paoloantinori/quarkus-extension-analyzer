---
id: TASK-2
title: 'M2: analyze mojo with three-signal report'
status: Done
assignee: []
created_date: '2026-08-01 09:51'
updated_date: '2026-08-01 12:18'
labels: []
dependencies: []
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Maven plugin with analyze goal producing the used-bytecode / used-config / used-capability / suspect report, human + JSON output. Plain jars delegated to maven-dependency-analyzer. Depends on M1 outcome.
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Design decisions locked by M1 evidence (docs/SPIKE-RESULTS.md):
1. Module layout: new plugin/ Maven module (maven-plugin packaging, groupId io.github.paoloantinori, artifactId quarkus-extension-analyzer-maven-plugin), spike/ stays as-is for reference.
2. Model resolution: reuse BootstrapAppModelResolver (proven in M1) but inside the mojo, fed by the session's RepositorySystem/repo session instead of a standalone resolver; the analyzed project is the current MavenProject.
3. Source D upgrade: probe the -deployment jar too (deployment GAV comes from META-INF/quarkus-extension.properties deployment-artifact key), closing the BUILD_TIME-only config-root gap M1 documented.
4. Root attribution: narrowest-claimant-wins across extensions to kill the quarkus-logging-json class of false positives; keep B+C+D union for discovery, drop per-key credit to broader roots claimed more narrowly by another extension.
5. Keep M1's root-inheritance heuristic (non-ubiquitous dependency roots) for driver-style extensions.
6. Signal 2 (bytecode): Jandex over the project's target/classes+test-classes; a reference into an extension's runtime artifact packages marks it used-bytecode; plain jars delegated to maven-dependency-analyzer semantics.
7. Signal 3 (capabilities): join from ApplicationModel (provides/requires + direct extension deps); M1 bench had 0 requires so add a unit test with a synthetic model for this path.
8. Config parsing: hand-rolled properties+profiles per M1 constraint (no SmallRye resolution; unresolved ${...} must stay visible); support application.yaml read-only best effort.
9. Probes run concurrently (M1 deliberately sequential).
10. Mojo surface: goal 'analyze', params: skip, reportFile (JSON), textReport (default true), failOnSuspect (default false), applicationConfig (path to application.properties/.yaml/.yml; when unset, searches the project's configured resource directories in declaration order for one of those three filenames, falling back to the build output directory if none of them have it). No lifecycle binding.
11. Tests: pure-JUnit units for matching/attribution/inheritance/capability-join; validation bench = registry app run documented in docs (expect fewer than M1's 13 suspects thanks to deployment probing + bytecode signal).
Execution delegated; Fable reviews the final diff.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Three-signal mojo delivered and validated on the registry bench: suspects 13 (M1 config-only) -> 7; JDBC drivers used-config via inheritance; 24 unit tests; commit 6ddf9ae. DoD: /simplify (15 fixes + 2 recorded skips), /code-review high (3 verified bugs + 5 contract items, all fixed). Runtime DI lesson recorded in AnalyzeMojo javadoc and M2-VALIDATION.md. Follow-ups: task-5 (transitive-API bytecode signal), M3 interop, plus a candidate fourth signal (DI-produced bean types) noted in M2-VALIDATION.
<!-- SECTION:NOTES:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
