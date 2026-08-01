---
id: TASK-7
title: Per-driver db-kind value discrimination for shared config roots
status: To Do
assignee: []
created_date: '2026-08-01 17:03'
labels: []
dependencies: []
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Deferred from M1 (DESIGN.md 'known hard case'): all quarkus-jdbc-* drivers match quarkus.datasource.* by family; discriminating WHICH driver is used needs the db-kind value (including named datasources and env-var indirection). Design the small curated table approach sanctioned by DESIGN.md; on the registry bench all four drivers are legitimately kept, so validation needs an app where one driver is genuinely dead.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
