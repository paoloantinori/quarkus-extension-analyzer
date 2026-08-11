---
id: TASK-13
title: >-
  Re-baseline registry + rest-fights benches after TASK-12 bytecode-signal
  widening
status: Done
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
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

<!-- SECTION:FINALSUMMARY:BEGIN -->
DONE 2026-08-11 (machine free). Both benches re-baselined post-TASK-12; measured numbers replace the EXPECTED-not-MEASURED placeholders. Apicurio `app` (Quarkus 3.38.1): extensions suspect 7 -> 5; quarkus-smallrye-fault-tolerance flipped suspect -> used-bytecode via its exclusive transitive smallrye-fault-tolerance-api (member annotations now captured). rest-fights (Quarkus 3.38.1, JDK 25): suspect = 3, UNCHANGED; the predicted hibernate-validator flip did NOT occur for a correct reason: @NotNull's type lives in the SHARED jakarta.validation-api jar, and TASK-5's exclusive-attribution rule excludes shared jars, so TASK-12's wider capture cannot override that membership exclusion (correct-by-design, hint intact). TASK-9 first-run fix holds from clean compile on both (no crash). Raw JSON saved under docs/_bench-runs/. Docs updated: M2-VALIDATION.md (TASK-12 addendum now measured), SECOND-BENCH.md (TASK-13 re-run addendum). DoD #1 n/a (doc/data only, no code changed); #2 deferred (no code diff to review). KEY FINDING: the task's predicted hibernate-validator flip was a hypothesis that did not hold up under measurement; the real TASK-12 win was on the Apicurio bench (fault-tolerance).
<!-- SECTION:FINALSUMMARY:END -->
