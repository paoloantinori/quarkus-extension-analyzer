---
id: TASK-4
title: 'M4: evaluate Quarkiverse proposal'
status: To Do
assignee: []
created_date: '2026-08-01 09:51'
updated_date: '2026-08-01 19:12'
labels: []
dependencies: []
priority: low
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Once M2 validates on at least two real applications, evaluate proposing the plugin to Quarkiverse (naming, extension descriptor conventions, docs).
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Step 1 (unblocks the precondition): validate the analyzer on a second real Quarkus application. Bench candidate: quarkusio/quarkus-super-heroes (canonical multi-service sample, recent Quarkus, diverse extensions incl. Kafka/Hibernate). Clone to /tmp, build ONE service (rest-fights or rest-heroes), run the installed analyze goal against it, triage the report against ground truth (README/config of the service), and record: verdict counts, per-suspect assessment, any crashes/version-skew issues from analyzing an app on a different Quarkus version than the resolver's 3.33.2.1 (that skew test is half the point). Deliverable: validation report to be added as docs/SECOND-BENCH.md. Step 2 (after step 1 passes): evaluate the Quarkiverse proposal. Execution delegated; Fable reviews and commits.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Step 1 DONE: second bench committed as docs/SECOND-BENCH.md (24c8aa1): precondition (two real applications) formally satisfied; version skew proven a non-issue. Step 2 (the actual Quarkiverse evaluation) should wait for TASK-9: proposing a tool with a known first-run adoption blocker would be a poor introduction. Sequence: TASK-9 fix -> re-run second bench repro from clean compile -> then evaluate the proposal.
<!-- SECTION:NOTES:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
