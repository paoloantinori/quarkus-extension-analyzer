---
id: TASK-8
title: 'Fourth signal: DI-produced bean types'
status: To Do
assignee: []
created_date: '2026-08-01 17:03'
updated_date: '2026-08-05 06:01'
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
<!-- SECTION:NOTES:END -->
