---
id: TASK-4
title: 'M4: evaluate Quarkiverse proposal'
status: Done
assignee: []
created_date: '2026-08-01 09:51'
updated_date: '2026-08-07 06:12'
labels: []
dependencies: []
priority: low
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Once M2 validates on at least two real applications, evaluate proposing the plugin to Quarkiverse (naming, extension descriptor conventions, docs).
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Step 1 (unblocks the precondition): validate the analyzer on a second real Quarkus application. Bench candidate: quarkusio/quarkus-super-heroes (canonical multi-service sample, recent Quarkus, diverse extensions incl. Kafka/Hibernate). Clone to /tmp, build ONE service (rest-fights or rest-heroes), run the installed analyze goal against it, triage the report against ground truth (README/config of the service), and record: verdict counts, per-suspect assessment, any crashes/version-skew issues from analyzing an app on a different Quarkus version than the resolver's 3.33.2.1 (that skew test is half the point). Deliverable: validation report to be added as docs/SECOND-BENCH.md. Step 2 (after step 1 passes): evaluate the Quarkiverse proposal. Execution delegated; Fable reviews and commits.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Step 1 DONE: second bench committed as docs/SECOND-BENCH.md (24c8aa1): precondition (two real applications) formally satisfied; version skew proven a non-issue. Step 2 (the actual Quarkiverse evaluation) should wait for TASK-9: proposing a tool with a known first-run adoption blocker would be a poor introduction. Sequence: TASK-9 fix -> re-run second bench repro from clean compile -> then evaluate the proposal.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Evaluation delivered as docs/M4-QUARKIVERSE-EVAL.md (commit 40810be). Conclusion (HIGH confidence, cross-referenced across 3 official Quarkiverse sources: org README, hub home, NamingConventions): a Maven plugin is a CATEGORY MISMATCH for Quarkiverse, which by its own definition hosts Quarkus *extension* projects only (extension-shaped infra: Ecosystem CI, quarkiverse-parent, quarkus-* naming, extension-catalog listing). No non-extension precedent found (search returned only extensions: githubapp, github-api, asyncapi-scanner). Two real paths documented: (A) stay standalone under io.github.pantinor, publish to Maven Central, promote via Quarkus channels (recommended near term, matches how autonomousapps/DepClean/maven-dependency-plugin are housed); (B) recast as a Quarkus build-time extension (-deployment @BuildStep) and then propose, which is the genuine Quarkiverse fit and would also eliminate the reactor-resolution complexity (TASK-9 machinery) by receiving the ApplicationModel from the augmentation context. Either path requires completing TASK-13 bench re-baseline first. NOTE: DoD #1/#2 are code-oriented (simplify/code-review) and were applied by analogy to this research/prose deliverable as a clarity pass plus a source-accuracy/cross-reference verification pass, not as literal code-skill runs. Did NOT take any outward action (no proposal submitted); user decides next step.
<!-- SECTION:FINAL_SUMMARY:END -->
