---
id: TASK-12
title: Bytecode signal misses member-level annotations (Jandex declaredAnnotations)
status: To Do
assignee: []
created_date: '2026-08-01 21:10'
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
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
