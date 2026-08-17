---
id: TASK-34
title: 'Bench snapshot harness: machine-checked baselines replacing prose numbers'
status: To Do
assignee: []
created_date: '2026-08-17 09:12'
labels: []
dependencies: []
priority: medium
type: task
ordinal: 27000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design from the Apicurio retrospective (user-approved 2026-08-17). Bench baselines live in prose docs today; the Apicurio bug survived because the bench was not systematically re-run. A machine-checked snapshot harness turns every future rules-engine change into a bench diff.

Mechanics: a script runs the mojo per bench app, extracts the extension-suspect GA list from the JSON report, and diffs against a committed expected file per app. Pin the bench apps' git commits (super-heroes-fresh a3f2ce1; capture the apicurio shallow-clone commit) and document the refresh procedure (explicit, so platform drift is a deliberate step, not silent flakiness). The extension form can be a later addition (mojo-only first: cheaper, and both forms share the engine).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Script scripts/bench-snapshot.sh esegue il mojo sulle app banco (super-heroes-fresh, apicurio-registry-fresh, quickstart selezionati) ed estrae le liste extension-suspects
- [ ] #2 File EXPECTED committato per app; drift = diff esplicito con exit code non-zero
- [ ] #3 I commit delle app banco pinnati e registrati; la procedura di refresh del banco documentata
- [ ] #4 CLAUDE.md: la disciplina 'ogni cambio al rules engine riesegue il banco' diventa istruzione scritta
- [ ] #5 Esecuzione end-to-end verificata verde sullo stato attuale
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
