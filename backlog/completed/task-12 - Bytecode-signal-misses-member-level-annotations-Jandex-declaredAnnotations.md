---
id: TASK-12
title: Bytecode signal misses member-level annotations (Jandex declaredAnnotations)
status: Done
assignee: []
created_date: '2026-08-01 21:10'
updated_date: '2026-08-05 05:57'
labels: []
dependencies: []
priority: high
ordinal: 12000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found during TASK-11 bench verification: BytecodeUsage.referencedTypesViaJandex uses ClassInfo.declaredAnnotations(), which per Jandex 3.5.3's own javadoc excludes annotations on class members. Field-level constraints on records (@NotNull/@Valid on record components, super-heroes rest-fights Fighters/FightRequest) never enter jandexReferenced, so jakarta.validation-api is invisible to signal 2 (verified: standalone probe against jandex 3.5.3 shows declaredAnnotations()=1 vs annotations()=28 on the same class). Fix: use ci.annotations() or walk fields/methods; NOTE this widens the bytecode signal globally and WILL change verdict counts: re-baseline both benches (registry: expect possible suspect reductions; rest-fights: hibernate-validator may flip used-bytecode, superseding its TASK-11 hint) and update the validation docs in the same change.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Switched BytecodeUsage.referencedTypesViaJandex from ClassInfo.declaredAnnotations() (class-level only) to ClassInfo.annotations() so field/method/record-component annotations enter the referenced-type set; jakarta.validation-api is no longer invisible to signal 2 (the rest-fights FightRequest record-component case). Verified against Jandex 3.5.3 source + javap. New BytecodeUsageTest compiles an in-memory source set with javac and asserts field-, record-component- and parameter-position capture (fails with declaredAnnotations(), passes with annotations()). M2-VALIDATION.md gains a TASK-12 addendum marking the bench re-baseline EXPECTED-not-MEASURED. Full plugin suite 83/83 green. /simplify clean (one finding skipped: distinct marker names intentionally guard per-position capture); high-effort /code-review correct, no findings (one non-blocking nit resolved: added parameter-position assertion). Bench re-baseline deferred to TASK-13 because the shared machine is running other Java builds and soak tests.
<!-- SECTION:FINAL_SUMMARY:END -->
