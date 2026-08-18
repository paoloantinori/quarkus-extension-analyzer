---
id: TASK-40
title: >-
  Build-step graph mining and resolution probe mode (the Quarkus-native
  total signals)
status: Done
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

RUNG 3 DONE 2026-08-17 (commit 952266e): BuildStepGraph live in both forms over the same deployment artifacts the join resolves; engine input generalized to a full evidence map. Direct edges only (return-type producers, plain-param consumers, exact FQCN, non-self); Item-suffix prefilter + Multi/Produce shapes documented as v1 boundaries. 3 compile-in-memory pins. Bench: all seven apps at baseline with the graph live - on every bench app the join already subsumes the graph's edges (expected: cross-extension build-item consumption usually implies a -deployment POM edge too). The graph's marginal space: item consumption WITHOUT a descriptor-forced runtime declaration.

RUNG 4 REMAINING. Design sketch validated against the existing machinery: the runner's resolveModel builds MavenArtifactResolver over the project; a probe re-resolution per suspect = resolve the app model with a workspace/remote filter that drops the suspect's artifacts, then inspect the failure (or the model's extension list). Implementation entry: a new -Dqea.probe flag on the mojo (default false) that, after the report, loops the suspects and emits one PROBE line per suspect with the bootstrap's verdict. Do NOT mutate the pom: mirror ChainedWorkspaceReader with an excluding reader. Budget: a day of careful work; do it as the next work unit, not a tail-of-session rush.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Grafo build-step estratto dai deployment jar nell'albero (riuso di BuildStepsListGenerator dove possibile)
- [x] #2 Crediti con evidenza-lato (X consumato da Y via build item Z); sola chiusura transitiva se il banco la richiede
- [x] #3 Probe mode -Dqea.probe=true con filtro in-memory (nessuna mutazione del pom utente)
- [x] #4 Oracolo: le ablazioni bidirezionali di TASK-38 confermano i crediti del grafo
- [x] #5 Dichiarazione di totalita' onesta nei docs (build + riferimenti statici coperti; residuo runtime-only documentato)
- [x] #6 Bench aggiornati deliberatamente; suite completa verde
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Both rungs shipped. RUNG 3 (commit 952266e): BuildStepGraph mines @BuildStep return/param types across the resolved deployment artifacts (direct, required, non-self, exact-FQCN; Item-suffix prefilter and Multi/Produce shapes documented as v1 boundaries); the engine's sixth parameter generalized to a full authority-agnostic evidence map; three compile-in-memory pins with the REAL @BuildStep FQCN; all seven bench apps at baseline with the graph live (the join subsumes it on every bench app; its marginal space is item consumption without a descriptor-forced runtime declaration). RUNG 4 (commit 088fcb5): -Dqea.probe re-resolves the app model per suspect from the same direct deps minus the suspect (resolveUserDependencies in-memory hook; buildResolver extracted and shared; no pom mutation); ground-truth verified on jwt-qs (rest-jackson, ablation-proven removable, resolves without it) and apicurio (both suspects resolvable); bench all-green (opt-in). The four-rung total-detection ladder is complete: deployment-tree join, sibling scan, build-step graph, resolution probe. 181 tests green.
<!-- SECTION:FINAL_SUMMARY:END -->
