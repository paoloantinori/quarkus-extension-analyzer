---
id: TASK-5
title: 'Bytecode signal: cover extension transitive non-Quarkus API'
status: In Progress
assignee: []
created_date: '2026-08-01 11:53'
updated_date: '2026-08-01 12:51'
labels: []
dependencies: []
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
docs/DESIGN.md's signal 2 (bytecode) says a reference to any class contained in the extension's runtime artifact, or its non-Quarkus transitive API (e.g. io.smallrye.* for smallrye extensions), marks it used. M2 (plugin/src/main/java/.../bytecode/BytecodeUsage.java, ConfigRootProbe#containedClasses) only checks the extension's own runtime (and deployment) jar; it does not walk the extension's non-Quarkus transitive dependencies to catch references to their classes. This is a documented scope cut, not a bug: closing it needs a per-extension transitive-closure walk (excluding other Quarkus extensions, which already have their own signal) and was deferred to keep M2's bytecode signal scoped to what the spike proved.
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Design decisions (Fable):
1. Attribution rule: a plain jar is an extension's transitive API only when it is reachable from exactly ONE declared extension's runtime dependency subtree (BFS over ResolvedDependency.getDirectDependencies from the extension's runtime artifact) AND is not directly declared by the project itself. Shared jars (reachable from 2+ extensions) and directly-declared jars are never attributed: ambiguity must not manufacture used verdicts.
2. Signal semantics: if project bytecode references a class contained in an attributed exclusive jar, the owning extension becomes used-bytecode with new nullable evidence field bytecodeViaTransitiveApi (the jar's ga) in ExtensionReport + JSON schema (kebab-case convention) + text report line.
3. Perf: compute subtrees once per extension; only scan candidate exclusive jars (lazily) for contained classes, reusing the existing scanPlainJar isolation; no rescan of jars already scanned.
4. Tests: synthetic model cases: exclusive jar referenced -> attributed used-bytecode; shared jar referenced -> no attribution; exclusive jar also directly declared -> no attribution; exclusive jar not referenced -> stays as-is. Update ReporterTest for the new field.
5. Docs: DESIGN.md signal-2 scope-cut sentence replaced by the implemented rule (including the deliberate exclusivity restriction); expected bench outcome documented honestly either way for quarkus-kubernetes-client (it flips only if fabric8 kubernetes-client is NOT directly declared by the registry app; if it stays suspect, record why: direct declaration explains the bytecode, the extension remains config/DI-only and the fourth-signal idea in M2-VALIDATION stays open).
Execution delegated; Fable reviews and runs the bench.
<!-- SECTION:PLAN:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
