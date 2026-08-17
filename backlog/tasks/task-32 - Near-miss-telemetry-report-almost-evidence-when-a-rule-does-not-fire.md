---
id: TASK-32
title: 'Near-miss telemetry: report almost-evidence when a rule does not fire'
status: To Do
assignee: []
created_date: '2026-08-17 09:07'
labels: []
dependencies: []
priority: high
type: feature
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design from the Apicurio retrospective (user-approved 2026-08-17): when a rule does NOT fire but near-evidence exists in the index, the report should say so, turning the next shape-blindness bug into a self-reporting event instead of something discovered by re-running a bench.

Pilot scope: the type-mention family (JWT probe). A near-miss is: the index mentions the tracked FQCN ONLY as a type argument of a parameterized declaration the probe does not credit at that position class... concretely for the pilot: a parameterized mention (e.g. Instance<JsonWebToken>) in a position/shape the current probe would already credit is NOT a near-miss; the near-miss is the residual gap after the fix (e.g. nested Provider<Instance<Jwt>>, or the FQCN appearing only inside a wildcard/array the probe does not unwrap at depth). Implementation: a loose probe per family (contains-based or deeper unwrap) run ONLY for prefixes that did not fire; append a 'near-miss (diagnostic)' line to the suspect row's note for the family's target GA when the row is suspect. Do not credit based on near-miss evidence.

Keep it cheap (only non-fired families) and low-noise (exact-FQCN-based loose checks, not substring on user types).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Near-miss computato nel engine quando una regola tipo-mention non fire-a ma una quasi-evidenza esiste nell'index (pilota: JWT come argomento di wrapper tipo Instance<T>)
- [ ] #2 Il diagnostic appare sulla riga suspect interessata (campo note, zero cambi di schema) ed e' visibile in testo e JSON
- [ ] #3 Nessun credito prodotto dal near-miss (solo diagnostica)
- [ ] #4 Test comportamentali: near-miss riportato senza credito; nessun near-miss quando l'uso e' nella forma gia' coperta
- [ ] #5 mvn clean install verde
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
