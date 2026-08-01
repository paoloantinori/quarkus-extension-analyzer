---
id: TASK-11
title: Evidence hint for referenced shared jars (hibernate-validator case)
status: In Progress
assignee: []
created_date: '2026-08-01 17:21'
updated_date: '2026-08-01 20:35'
labels: []
dependencies: []
ordinal: 11000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Second bench: quarkus-hibernate-validator is a false suspect because jakarta.validation-api is reachable from two declared extensions, so the exclusivity rule refuses attribution even though project bytecode references it (8 classes use validation annotations). Keep the conservative verdict but add an evidence hint on the suspect row: 'project references shared jar(s) reachable from this extension: <ga> (also reachable from <others>)', so human triage has the signal without the tool overclaiming. Mirror case of the kubernetes-client fix.
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Design (Fable): for suspect extensions only, compute the shared-referenced hint: jars reachable from the extension's subtree that are (a) reached by 2+ declared extensions (hence excluded from attribution) and (b) present in the project's referenced-type set. Reuse TransitiveApiAttribution's reachability data (expose the per-extension reachable set or the sharing map; do not recompute BFS). New nullable ExtensionReport field sharedReferencedJars: list of {ga, alsoReachableFrom: [gas]}, JSON + text rendering ('hint: project references shared jar X, also reachable from Y,Z'). Verdict stays suspect: the hint informs human triage, never upgrades. Tests: synthetic case (suspect with shared referenced jar -> hint present; used extension -> no hint computed; shared but unreferenced -> no hint). Bench expectation to verify by orchestrator: super-heroes rest-fights hibernate-validator row carries the jakarta.validation-api hint.
<!-- SECTION:PLAN:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
