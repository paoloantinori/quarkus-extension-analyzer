---
id: TASK-1
title: 'M1 spike: extension model + config-root matching'
status: Done
assignee: []
created_date: '2026-08-01 09:51'
updated_date: '2026-08-01 10:58'
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
- [x] #1 Both risky assumptions have a verified yes/no answer with evidence
- [x] #2 Registry app extensions enumerated with config-root match results
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Both assumptions answered with runnable evidence: A1 PARTIAL YES (union coverage 71%, JDBC via agroal root inheritance, deviations documented), A2 YES (BootstrapAppModelResolver, no Maven session). Deliverables: spike/ + docs/SPIKE-RESULTS.md, commit 7ee6189. DoD: /simplify applied (13 fixes, 1 deliberate skip noted for M2), /code-review high passed (no finding scored >=80; 3 sub-threshold robustness/precision fixes applied anyway).
<!-- SECTION:NOTES:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
