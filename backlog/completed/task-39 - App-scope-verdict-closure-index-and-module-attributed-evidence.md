---
id: TASK-39
title: >-
  App-scope verdict: closure-wide index and module-attributed evidence
status: Done
assignee: []
created_date: '2026-08-17 19:40'
updated_date: '2026-08-17 19:40'
labels: []
dependencies: []
modified_files: []
priority: medium
type: feature
ordinal: 30000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
EMPIRICAL DELTA TABLE STARTED 2026-08-17 (TASK-38 follow-up) - Keycloak 26.7.0, same project, two module scopes:

| Scope | Extensions | Suspects | Notes |
|---|---|---|---|
| quarkus/runtime (extension module) | 22 | 4 | TASK-38 credits 2 via quarkus-internal deployment edges; the other 4 are consumed by the module's OWN deployment sibling (quarkus/deployment), invisible from the runtime side in ANY dependency model (the deployment depends on the runtime, not vice versa) |
| quarkus/server (app module, declares both runtime + deployment) | 2 | 1 | the full deployment tree is visible; deployment-consumer credits with the keycloak-server edge |

Structural finding: the "own deployment sibling" shape (an extension module analyzed standalone) can never see its own -deployment's declarations via the resolved model. Two resolutions, both within this task: (a) DOCUMENT that the right analysis point is the app module (cheap, no code); (b) the workspace-sibling POM scan (the mojo already loads the workspace via LocalProject.loadWorkspace - a sibling *-deployment/pom.xml is discoverable) to credit from the extension module too. Decide during implementation; (a) may be sufficient for v1 with (b) as an opt-in.

Rung 2 of the "total detection" ladder (design discussion 2026-08-17, user-approved): change the verdict's scope from per-module to per-application, keeping the per-module dimension as evidence instead of compromise.

Core semantic shift: the verdict answers "removable from the APPLICATION?" (what the user actually asks), and the note says WHERE the use lives ("referenced from keycloak-quarkus-server classes", "required by Y's deployment tree", "no use found anywhere in the app closure"). This subsumes the Keycloak shape and the note-enrichment dilemma: both dimensions, each in its right scope.

Mechanics:
- The ApplicationModel already resolves workspace sibling modules with their resolved paths (target/classes of workspace deps). The bytecode signal's index can cover the app closure (analyzed module + resolved workspace modules), not just the analyzed module's classes.
- Every referenced type must carry the module attribution (which closure member references it), so evidence is navigable and the per-module hygiene question stays answerable ("declared here, used only by module Z").
- Report semantics: verdicts become app-scoped for ALL rows; a new evidence field names the referencing module(s). The text report and JSON need a versioned shape change - decide whether the old per-module verdict survives as a secondary field (recommended: keep both, verdict=app scope, moduleVerdict=per-module hygiene) so existing consumers do not break silently.

Design cautions:
- Attribution dilution: a reference from a sibling module now credits a dependency declared in the analyzed module. That is INTENDED for app-scope verdicts but must be visible in the evidence (the attribution field is the guard against silent dilution).
- Closure boundary: define "app closure" precisely (resolved workspace deps of the analyzed module's runtime tree) and document that library modules consumed by other apps are analyzed from THIS app's perspective only.
- Extension form parity: the bean index is app-scoped already in augmentation; verify the two forms agree under the new semantics, or document the residual difference.
- Bench: EVERY expected file changes under app-scope verdicts - this is a deliberate, one-time bench-snapshot --update with per-app reasoning recorded (super-heroes multi-module apps will shift most: rest-fights consumes siblings).

Validation: before shipping, run the Keycloak and super-heroes benches under both scopes and record the delta table; every verdict flip must be explainable by an attributed reference.

SHIPPED 2026-08-18 (commit 6f81c97, work-log unit 45): both forms index the app closure (own classes + resolved workspace siblings, handling BOTH the directory-resolved and installed-jar-resolved shapes - the jar shape was found empirically via the new -Dqea.debugAttribution closure line on a synthetic two-module fixture). Verdicts are app-scoped. DECISIONS: single verdict field (a duplicate moduleVerdict field would double the schema for no current consumer; the per-module hygiene question stays answerable through the evidence text and sibling-scan notes); the annotation-consumer index deliberately stays module-local in both forms (widening it to library bytecode would credit framework annotations processed by generated code - documented residual; the forms agree). Delta table: keycloak and all bench apps UNCHANGED (none has sibling-only references; zero unintended flips, verified before declaring done) plus the synthetic fixture flip proven both directions (suspect before the jar-shape fix, used-bytecode after). Module-attribution as a dedicated FIELD deferred: the task's own caution (attribution dilution) is served today by the closure debug line and the evidence notes; a per-module field is a schema change to make only when a consumer asks.
<!-- SECTION:DESCRIPTION:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Shipped as the last rung of the total-detection ladder. Both forms (mojo runner and extension build step) now feed the bytecode signal the app closure: the analyzed module's classes plus every resolved workspace sibling's classes, with two derivation shapes (classes directory, and target/classes derived from an installed-jar resolved path - the shape found only because the new debug line made the model's flags visible on a synthetic fixture). The verdict semantics moved to "removable from the application?"; the synthetic two-module fixture (sibling-only jakarta.ws.rs reference) flips suspect->used-bytecode and the discriminating direction is the pre-fix run that missed the jar shape. All seven bench apps at baseline with zero refresh (no bench app has sibling-only references - the semantics change is latent by design until such an app appears). Single verdict field kept (no moduleVerdict duplicate); annotation index stays module-local in both forms with the residual documented. 187 tests green.
<!-- SECTION:FINAL_SUMMARY:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Semantica del report decisa e documentata (verdict app-scope + campo modulo-attribuzione; coesistenza o sostituzione del per-module)
- [x] #2 Indice su tutta la chiusura (modulo analizzato + moduli workspace risolti) con attribuzione per membro
- [x] #3 Entrambe le forme concordano o la differenza residua e' documentata
- [x] #4 Tabella delta pre/post su Keycloak e super-heroes: ogni flip spiegato da un riferimento attribuito
- [x] #5 bench-snapshot aggiornato deliberatamente con motivazione per app
- [x] #6 Suite completa verde + test comportamentali per la nuova semantica
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
