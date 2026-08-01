---
id: TASK-1
title: 'M1 spike: extension model + config-root matching'
status: To Do
assignee: []
created_date: '2026-08-01 09:51'
labels: []
dependencies: []
priority: high
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Riskiest unknowns first, per docs/DESIGN.md: (1) verify config-root metadata is recoverable per extension at plugin runtime (quarkus-extension.properties, deployment @ConfigRoot via Jandex, or extension yaml; fallback static index from platform BOM metadata); (2) verify quarkus-bootstrap ApplicationModel can be resolved from a plain mojo without full augmentation. Validate on Apicurio Registry app module: enumerate its ~25 extensions and match config roots against its application.properties profiles. Exit criterion: jdbc drivers correctly classified used via config values, not bytecode.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Both risky assumptions have a verified yes/no answer with evidence
- [ ] #2 Registry app extensions enumerated with config-root match results
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
