---
id: TASK-38
title: >-
  Deployment-consumer rule: credit an extension whose -deployment artifact
  is consumed by another declared extension's deployment tree
status: To Do
assignee: []
created_date: '2026-08-17 19:05'
updated_date: '2026-08-17 19:05'
labels: []
dependencies: []
modified_files: []
priority: low
type: feature
ordinal: 29000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Candidate rule surfaced by the Keycloak 26.7.0 bench (TASK-37, work-log unit 37): six extension suspects (hibernate-validator, rest-jackson, the micrometer/otel family) in quarkus/runtime are NOT used by that module's code or config - they are consumed by Keycloak's OWN extension deployment module (quarkus/deployment depends on quarkus-rest-jackson-deployment and quarkus-hibernate-validator-deployment). The runtime pom's direct declarations are arguably redundant, but the extensions ARE load-bearing for the application as a whole: removing them from the runtime pom would change nothing (they arrive via the server extension's tree), while removing them from the deployment tree breaks the server.

Proposed signal: when a declared-suspect extension X's DEPLOYMENT artifact (-deployment GA) appears in the deployment dependency tree of another declared extension Y (or of an extension that is itself used), credit X with evidence "deployment-consumer: required by Y's deployment tree". Data source: the ApplicationModel already distinguishes deployment artifacts in the resolved tree, so this is a join the engine can do without new scanning.

Design cautions from the session's phantom-name history:
- The join must verify that the -deployment GA actually resolves (no phantom names: confirm against the tree, not against a constructed string).
- Scope gate as usual: credit only directly-declared suspects.
- Decide the interaction with the honest per-module verdict: this rule changes the Keycloak suspects from "redundant declaration" (a finding a user might act on: remove from the pom!) to "used" (nothing to do). That trade-off needs a deliberate decision: arguably BOTH are true and the report could keep the suspect with a richer note ("redundant here, load-bearing via Y's deployment tree") instead of flipping the verdict. Prefer the note-enrichment shape unless bench evidence shows users remove these and break builds.
- Bench impact: the Keycloak expected file records the 6 suspects; any verdict change here must go through a deliberate bench-snapshot --update with the reasoning in the work log.

Validation before shipping: ablate one Keycloak row both ways (remove from runtime pom only: expect NO change in behavior since it arrives transitively; remove from the deployment tree too: expect breakage) to pin the semantics empirically.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Meccanismo deciso e documentato (credito vs note-enrichment) con la trade-off esplicita
- [ ] #2 Join implementato nel engine (deployment tree gia' presente nell'ApplicationModel, nessuno scanning nuovo)
- [ ] #3 Verifica anti-nomi-fantasma: il -deployment GA risolve davvero nell'albero
- [ ] #4 Ablazione bidirezionale su una riga Keycloak a pin delle semantiche (solo-pom: nessun cambiamento; deployment-tree: break)
- [ ] #5 Bench snapshot aggiornato deliberatamente (Keycloak expected: 6 sospetti cambiano solo se il verdict cambia) con motivazione nel work log
- [ ] #6 Test comportamentale o matrice per la nuova forma
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
