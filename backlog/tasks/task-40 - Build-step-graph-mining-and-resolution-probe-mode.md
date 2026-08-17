---
id: TASK-40
title: >-
  Build-step graph mining and resolution probe mode (the Quarkus-native
  total signals)
status: To Do
assignee: []
created_date: '2026-08-17 19:40'
updated_date: '2026-08-17 19:40'
labels: []
modified_files: []
priority: low
type: feature
ordinal: 31000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Rungs 3 and 4 of the "total detection" ladder (design discussion 2026-08-17, user-approved): the two signals that read Quarkus's own authority instead of approximating it.

Rung 3 - build-step graph mining (static, total for build-load-bearing):
- Augmentation fails when a required build step is missing. @BuildStep methods declare consumed/produced build items in the bytecode of the -deployment artifacts, which are already in the resolved tree.
- Extract the producer/consumer graph across the deployment tree (Jandex over the -deployment jars: methods with @BuildStep, their parameter types = consumed items, return types + @Produce = produced items), then a declared extension whose steps are transitively consumed by another used extension's steps is load-bearing with the edge as evidence.
- This is total BY CONSTRUCTION for "is X load-bearing for the build": it is the same authority Quarkus uses to fail.
- Effort: significant but principled (type matching on build items, generics erasure on collections of items, transitive closure). Start with direct edges (Keycloak's shape) and extend to transitive only if benches demand it.
- Known prior art in-repo: BuildStepsListGenerator already parses deployment jars' build steps for the extension form's own descriptor - reuse that machinery.

Rung 4 - resolution probe mode (dynamic, opt-in):
- For each suspect: re-resolve the model with that artifact filtered from the tree and observe whether the bootstrap refuses (unsatisfied capability, missing build step, unresolvable extension dependency). This is the bench's ablation methodology shipped as a tool mode (-Dqea.probe=true).
- Slow (N re-resolutions), hence opt-in; authoritative; the natural CI gate for a fail-on-suspect policy before acting on a report.
- Careful design needed on filtering without mutating the user's pom (in-memory resolver filter, mirroring the existing ChainedWorkspaceReader machinery).

Honest totality statement (to keep in the docs): rungs 3+4 together are total for BUILD-load-bearing and rung 2 (TASK-39) is total for static references; pure runtime-only usage (an /info endpoint, a serializer used by a build-step-generated implementation with zero source references) remains invisible to static analysis by construction - the documented residual, where only real ablation (removing and running the app) answers.

Sequencing note: TASK-38 (deployment-tree join) ships the cheap Keycloak coverage first; this task supersedes its mechanism with the precise graph when built - keep TASK-38's empirical validation (bidirectional ablation) as the oracle for the graph's correctness.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Grafo build-step estratto dai deployment jar nell'albero (riuso di BuildStepsListGenerator dove possibile)
- [ ] #2 Crediti con evidenza-lato (X consumato da Y via build item Z); sola chiusura transitiva se il banco la richiede
- [ ] #3 Probe mode -Dqea.probe=true con filtro in-memory (nessuna mutazione del pom utente)
- [ ] #4 Oracolo: le ablazioni bidirezionali di TASK-38 confermano i crediti del grafo
- [ ] #5 Dichiarazione di totalita' onesta nei docs (build + riferimenti statici coperti; residuo runtime-only documentato)
- [ ] #6 Bench aggiornati deliberatamente; suite completa verde
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
