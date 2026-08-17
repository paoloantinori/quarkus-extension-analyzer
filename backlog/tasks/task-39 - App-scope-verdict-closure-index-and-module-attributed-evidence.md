---
id: TASK-39
title: >-
  App-scope verdict: closure-wide index and module-attributed evidence
status: To Do
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
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Semantica del report decisa e documentata (verdict app-scope + campo modulo-attribuzione; coesistenza o sostituzione del per-module)
- [ ] #2 Indice su tutta la chiusura (modulo analizzato + moduli workspace risolti) con attribuzione per membro
- [ ] #3 Entrambe le forme concordano o la differenza residua e' documentata
- [ ] #4 Tabella delta pre/post su Keycloak e super-heroes: ogni flip spiegato da un riferimento attribuito
- [ ] #5 bench-snapshot aggiornato deliberatamente con motivazione per app
- [ ] #6 Suite completa verde + test comportamentali per la nuova semantica
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
