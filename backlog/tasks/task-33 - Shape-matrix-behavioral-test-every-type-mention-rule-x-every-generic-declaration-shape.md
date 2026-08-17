---
id: TASK-33
title: >-
  Shape-matrix behavioral test: every type-mention rule x every generic
  declaration shape
status: To Do
assignee: []
created_date: '2026-08-17 09:11'
labels: []
dependencies: []
priority: high
type: task
ordinal: 26000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design from the Apicurio retrospective (user-approved 2026-08-17). Today the behavioral suite is one-test-per-shape-per-rule, encoding the same incomplete shape model as the code. A matrix test enumerates the generic declaration shapes once and runs EVERY type-mention rule against EVERY shape, so the next rule that misses a wrapper is caught by construction rather than by a bench.

The matrix needs a documented expected-semantics table (which cells credit, which do not and why: one-level unwrap by design; nested stays unflagged as not occurring in real code; wildcards follow Jandex name() delegation). Shapes: bare type, Optional/Uni/Multi/Instance/Supplier/Provider wrappers, arrays, ? extends wildcard, 2-level nesting; positions: field, return, param. Annotation-position shapes are already covered by dedicated suite tests: reference them from the matrix javadoc instead of duplicating.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Un test matrice (o parameterizzato) incrocia le regole tipo-mention con le forme generiche di dichiarazione: tipo nudo, Optional<T>, Uni<T>, Multi<T>, Instance<T>, Supplier<T>, Provider<T>, array T[], wildcard ? extends T, nesting a 2 livelli
- [ ] #2 Per ogni cella la semantica attesa e' documentata (accredita / non accredita) e assert-ita; le celle non-creditate coerenti con la semantica del near-miss di TASK-32
- [ ] #3 Le forme di posizione delle annotazioni (classe/metodo/campo, ereditate, da interfaccia) riferite alle suite esistenti che le coprono senza duplicarle
- [ ] #4 mvn clean install verde
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
