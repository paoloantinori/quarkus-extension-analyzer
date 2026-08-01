---
id: TASK-8
title: 'Fourth signal: DI-produced bean types'
status: To Do
assignee: []
created_date: '2026-08-01 17:03'
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
