---
id: TASK-13
title: >-
  Re-baseline registry + rest-fights benches after TASK-12 bytecode-signal
  widening
status: To Do
assignee: []
created_date: '2026-08-05 05:22'
labels: []
dependencies: []
references:
  - TASK-12
  - docs/M2-VALIDATION.md
  - docs/SECOND-BENCH.md
priority: medium
type: task
ordinal: 13000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
TASK-12 widened BytecodeUsage.referencedTypesViaJandex from ClassInfo.declaredAnnotations() to ClassInfo.annotations(), so field/method/record-component annotations now enter the referenced-type set. This changes verdict counts and MUST be validated by re-running both benches:

1. Apicurio Registry `app` module (docs/M2-VALIDATION.md bench): re-run, update the verdict table. EXPECTED (not measured yet): suspect-count reductions, because member-level annotations now justify some extensions via bytecode that previously fell through to suspect.

2. Quarkus super-heroes `rest-fights` (docs/SECOND-BENCH.md bench): re-run. EXPECTED: hibernate-validator flips to used-bytecode (jakarta.validation constraints on Fighters/FightRequest record components are now captured), which supersedes its TASK-11 shared-referenced-jars hint.

Update docs/M2-VALIDATION.md and docs/SECOND-BENCH.md with the MEASURED numbers, replacing the EXPECTED-not-MEASURED note TASK-12 left in M2-VALIDATION.md.

DEFERRED from TASK-12: the shared machine was running other Java builds and long soak tests, so cloning + building the two external Quarkus apps would have interfered. Do this work when the machine is idle. Neither bench repo is currently checked out locally.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
