---
id: TASK-8
title: 'Fourth signal: DI-produced bean types'
status: Done
assignee: []
created_date: '2026-08-01 17:03'
updated_date: '2026-08-12 11:36'
labels: []
dependencies: []
ordinal: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Candidate signal from M2-VALIDATION: extensions whose value is producing beans of LIBRARY types (e.g. quarkus-kubernetes-client producing io.fabric8 KubernetesClient) are invisible to all three signals when config is absent; the injected type lives in the library jar. Idea: map extension -> produced bean types (from deployment metadata or a curated list) and mark used when project bytecode references those types. Was the kubernetes-client gap before TASK-5 solved it via transitive attribution; other extensions may still need this (resteasy-jackson? smallrye-jwt?).
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Assessment 2026-08-05 (orchestrator, pre-implementation): NOT implementation-ready as written. (1) Mechanism undecided: deployment-metadata extraction vs curated list, with real architectural overlap with TASK-5's transitive attribution. (2) TASK-5 already resolved the task's headline example (quarkus-kubernetes-client) -- see M2-VALIDATION TASK-5 addendum + AnalyzerTest. (3) Residual scope is bench-dependent: a 4th signal would only help some of the 6 post-TASK-5 registry suspects (resteasy-jackson, resteasy-client-jackson, scheduler, smallrye-fault-tolerance, smallrye-jwt, apicurio-registry-config-index) IF those extensions produce bean types the analyzed app references -- which needs TASK-13 bench data to confirm a real residual gap before any signal is built. Recommendation: defer until TASK-13 bench re-baseline (idle machine) confirms the gap, then decide mechanism. No acceptance criteria defined yet.

UPDATE 2026-08-11 (autonomous triage, post-TASK-13, see docs/SUSPECT-TRIAGE.md): the bench data is now in. Per-suspect triage shows the residual suspects are NOT cleanly a "missing bean-producer signal" problem. Of 8 suspects, 4 are confirmed false positives (genuinely used): resteasy-jackson (ubiquitous jackson-databind, filtered by >50% rule), scheduler (@Scheduled, see the ANOMALY in docs/SUSPECT-TRIAGE.md), smallrye-jwt (JsonWebToken in a shared jar), hibernate-validator (jakarta.validation-api shared). TASK-8's original producer-mapping idea would resolve only hibernate-validator and smallrye-jwt, by deliberately overriding the shared-jar exclusion for known producers. It would NOT resolve the ubiquitous-jar case (resteasy-jackson) or the scheduler anomaly (a possible signal-2 bug, filed as its own task).

RE-FRAME: the triage suggests a broader "annotation and API-type attribution" signal (credit an extension when the project uses an annotation/type the extension provides, even via a shared jar) covers more cases (scheduler, validator, jwt). But that is a curated table with maintenance cost and deliberately weakens the shared-jar safety invariant. Both the original and the re-frame are judgment calls the maintainer should make, not autonomous-build decisions.

DECISION: deferred-with-evidence (not wontfix; real residual value exists, just narrower and design-laden than scoped). Do NOT build autonomously. The higher-leverage next step the triage surfaces is the scheduler signal-2 anomaly (possible bug, no design trade-off). Re-open this task only after the maintainer decides to accept the shared-jar-safety weakening that any producer/attribution signal requires.

IMPLEMENTED 2026-08-12 (autonomous), opt-in default-off via -Dqea.vocabularySignal=true. Comprehensive deployment-vocabulary signal: harvests each extension's -deployment-jar referenced types, credits the extension for app-referenced types exclusive to its vocabulary (reuses TASK-5 exclusivity). Noise filter excludes JDK/primitives/ubiquitous-logging. MEASURED bench effect (with flag): rest-fights no credits (hibernate-validator stays suspect: Validator is shared across deployment vocabularies, exclusivity blocks it); Apicurio suspect 5->4 (kubernetes-client via KubernetesClient = redundant with TASK-5; config-index via DynamicConfigPropertyDef = net-new). Default behavior unchanged (benches match pre-TASK-8). STRUCTURAL FINDING: the approach resolves domain-specific producer types but CANNOT resolve the shared-producer-type false positives (the triage headline cases) without weakening the exclusivity invariant -- that remains a maintainer judgment call. Opt-in because net precision gain is marginal. 7 unit tests; /simplify + /code-review recorded in work log. Research report at claudedocs/research_task8-reuse-mechanisms_2026-08-12.md.
<!-- SECTION:NOTES:END -->
