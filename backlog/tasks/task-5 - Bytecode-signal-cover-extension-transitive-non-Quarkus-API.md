---
id: TASK-5
title: 'Bytecode signal: cover extension transitive non-Quarkus API'
status: To Do
assignee: []
created_date: '2026-08-01 11:53'
labels: []
dependencies: []
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
docs/DESIGN.md's signal 2 (bytecode) says a reference to any class contained in the extension's runtime artifact, or its non-Quarkus transitive API (e.g. io.smallrye.* for smallrye extensions), marks it used. M2 (plugin/src/main/java/.../bytecode/BytecodeUsage.java, ConfigRootProbe#containedClasses) only checks the extension's own runtime (and deployment) jar; it does not walk the extension's non-Quarkus transitive dependencies to catch references to their classes. This is a documented scope cut, not a bug: closing it needs a per-extension transitive-closure walk (excluding other Quarkus extensions, which already have their own signal) and was deferred to keep M2's bytecode signal scoped to what the spike proved.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
