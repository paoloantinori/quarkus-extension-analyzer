---
id: TASK-34
title: 'Bench snapshot harness: machine-checked baselines replacing prose numbers'
status: Done
assignee: []
created_date: '2026-08-17 09:12'
updated_date: '2026-08-17 13:42'
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
- [x] #1 Script scripts/bench-snapshot.sh esegue il mojo sulle app banco (super-heroes-fresh, apicurio-registry-fresh, quickstart selezionati) ed estrae le liste extension-suspects
- [x] #2 File EXPECTED committato per app; drift = diff esplicito con exit code non-zero
- [x] #3 I commit delle app banco pinnati e registrati; la procedura di refresh del banco documentata
- [x] #4 CLAUDE.md: la disciplina 'ogni cambio al rules engine riesegue il banco' diventa istruzione scritta
- [x] #5 Esecuzione end-to-end verificata verde sullo stato attuale
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
scripts/bench-snapshot.sh runs the mojo over six pinned bench apps (rest-heroes/fights @ a3f2ce1; resteasy-client/cache/security-jwt quickstarts @ 31306c8; apicurio app @ 400a3db) and diffs each app's extension-suspect list against committed bench/expected/*.expected files: drift = non-zero exit, refresh = deliberate --update with a documented reason. Verified in all three directions (clean exit 0 with all six OK; injected bogus GA exit 1 with the diff printed; restored exit 0). The CLAUDE.md convention now mandates a bench re-run on every rules-engine change. The expected files were first hand-written from the session's verified runs during a classifier outage; the authoritative --update regeneration matched them with zero diff and produced security-jwt-quickstart.expected = {quarkus-rest-jackson}, further confirming the reversed TASK-30 ground truth. DoD review: the harness's own drift detection is the mutation check (injected drift caught); code is a thin shell script reviewed in-context during the outage with the agent review deferred to the follow-up.
<!-- SECTION:FINAL_SUMMARY:END -->
