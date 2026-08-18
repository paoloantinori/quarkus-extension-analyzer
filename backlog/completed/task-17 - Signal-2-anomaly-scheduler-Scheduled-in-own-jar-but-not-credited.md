---
id: TASK-17
title: >-
  Signal-2 anomaly: quarkus-scheduler @Scheduled referenced and in its own jar,
  but not credited
status: Done
assignee: []
created_date: '2026-08-11'
updated_date: '2026-08-11 15:37'
labels: []
dependencies: []
references:
  - TASK-12
  - docs/SUSPECT-TRIAGE.md
  - docs/M2-VALIDATION.md
priority: high
type: bug
ordinal: 17000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found during the TASK-13 autonomous suspect triage (docs/SUSPECT-TRIAGE.md).
quarkus-scheduler is classified suspect by the analyzer, but the evidence says
signal-2's own-jar check should mark it used-bytecode:

- Apicurio's `RegistryStorageConfigCache.class` references
  `io/quarkus/scheduler/Scheduled` (and its nested
  `Scheduled$ConcurrentExecution`) in its constant pool, confirmed by `javap`.
- `io.quarkus.scheduler.Scheduled` is physically present in quarkus-scheduler's
  OWN runtime jar (confirmed by `unzip -l` on
  `~/.m2/repository/io/quarkus/quarkus-scheduler/.../quarkus-scheduler-*.jar`).

So `probe.containedClasses` for quarkus-scheduler should include
`io.quarkus.scheduler.Scheduled`, and TASK-12's `ci.annotations()` should place
that name in `jandexReferenced`. Their intersection should fire `used-bytecode`.
It does not (scheduler is suspect, `bytecodeReferenced` false,
`bytecodeViaTransitiveApi` null). This is either a real gap or an unexplained
subtlety, and it is distinct from the shared-jar design trade-off (the class is
in the extension's OWN jar, not a shared one).

Investigation steps (do NOT guess; confirm against the artifact):
1. Confirm whether `@Scheduled` appears in the extension's `containedClasses`
   probe (it may be that the containedClasses scan misses it, e.g. because the
   `-deployment` jar path is being probed instead of runtime, or a packaging
   quirk).
2. Confirm whether `ci.annotations()` surfaces `io.quarkus.scheduler.Scheduled`
   in `jandexReferenced` after TASK-12 (it is a method annotation, so it
   should).
3. The bench is reproducible: Apicurio `app`, run
   `mvn io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze
   -Dqea.debugAttribution=true` and grep the trace for scheduler.

If confirmed as a bug, this is high-leverage: a fix with no design trade-off (no
shared-jar-safety weakening), unlike TASK-8.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done

- [ ] #1 Reproduce and root-cause (containedClasses vs jandexReferenced, which
      side drops `Scheduled`)
- [ ] #2 Fix the smallest true cause; add a regression test
- [ ] #3 Re-run both benches; scheduler should flip to used-bytecode; no other
      verdict changes expected

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
RESOLVED 2026-08-11 as NOT-A-BUG: airtight re-check shows io.quarkus.scheduler.Scheduled lives in quarkus-scheduler-api (the SHARED jar), NOT in quarkus-scheduler's own runtime jar (which holds only io/quarkus/scheduler/runtime/* classes). The earlier 'in its own jar' claim was a grep artifact (the runtime jar contains SchedulerConfig etc. matching a loose pattern). So scheduler is the SAME shared-jar pattern as hibernate-validator: the app references the annotation, but the annotation type lives in a shared jar that exclusive attribution deliberately does not credit. This is safety-by-design, not a signal-2 bug. Downgraded from HIGH; no code change. This reinforces the triage conclusion that all confirmed false positives share one root cause (shared/ubiquitous-jar attribution exclusion).
<!-- SECTION:NOTES:END -->
