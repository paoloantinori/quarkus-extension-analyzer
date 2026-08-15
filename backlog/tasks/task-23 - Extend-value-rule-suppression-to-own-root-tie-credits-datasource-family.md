---
id: TASK-23
title: Extend value-rule suppression to own-root tie credits (datasource family)
status: To Do
assignee: []
created_date: '2026-08-15 19:10'
labels: []
dependencies: []
priority: low
type: feature
ordinal: 23000
---

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Residual imprecision from TASK-23 (documented, not hacked). When TWO reactive clients are declared AND db-kind=postgresql is present: the join correctly handles the SUSPECT-state path, and value-rules now carry the reactive family, BUT the config signal still credits BOTH clients used-config via an OWN-ROOT TIE on quarkus.datasource. (both claim the root; the key matches; the documented tie behavior credits every tied owner). The TASK-7 suppression mechanism only suppresses INHERITED credit, not own-root ties. FIX DIRECTION: when a value-rules family selector key is present and selects a specific GA, the other family members' own-root claim on that selector key should be suppressed in RootAttributor/classifyExtension — a core priority-chain change that needs its own regression cycle across the 21-app matrix before shipping. Do NOT hack it in at session end.
<!-- SECTION:NOTES:END -->
