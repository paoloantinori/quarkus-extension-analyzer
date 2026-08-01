---
id: TASK-6
title: 'Repo CI: build and test on push/PR'
status: To Do
assignee: []
created_date: '2026-08-01 17:03'
labels: []
dependencies: []
priority: high
ordinal: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The repo has no GitHub Actions workflow: mvn verify (45 tests) runs only locally. Add a minimal workflow (JDK 17, mvn -q verify on plugin/, cache) so contributions and future changes are gated. Without this, the DoD gates are the only protection.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
