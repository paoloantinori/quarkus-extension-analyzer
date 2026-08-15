---
id: TASK-22
title: >-
  Reactive-driver attribution (reactive-pg-client family): credit reactive
  clients selected by the reactive datasource stack
status: To Do
assignee: []
created_date: '2026-08-15 12:46'
labels: []
dependencies: []
priority: low
type: feature
ordinal: 22000
---

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Filed from the TASK-21 ablation bench. quarkus-reactive-pg-client removal FAILS the build (Hibernate Reactive cannot build its persistence unit) -> load-bearing FALSE POSITIVE, unresolved by current signals. It is selected by the reactive stack, not bytecode/config. SIGNAL IDEA: hibernate-reactive(-panache) declared AND used AND a quarkus-reactive-*-client suspect matching the configured db-kind -> credit. Related to the existing db-kind value-rules family.
<!-- SECTION:NOTES:END -->
