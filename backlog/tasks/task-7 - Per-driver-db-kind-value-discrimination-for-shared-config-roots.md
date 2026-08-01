---
id: TASK-7
title: Per-driver db-kind value discrimination for shared config roots
status: To Do
assignee: []
created_date: '2026-08-01 17:03'
updated_date: '2026-08-01 19:12'
labels: []
dependencies: []
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Generalize beyond db-kind: value-based extension activation. Two confirmed cases: quarkus-jdbc-* selected by quarkus.datasource.db-kind values (M1 known hard case), and quarkus-container-image-* selected by quarkus.container-image.builder values (second bench: container-image-docker false suspect). Design a small curated value-rules table (config key, value pattern -> extension ga) sanctioned by DESIGN.md, applied as part of signal 1. Validation: super-heroes rest-fights (container-image case) + an app with a genuinely dead jdbc driver.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Third confirmed value-activation case from the second bench: io.smallrye.stork:stork-service-discovery-static-list (plain jar) selected by quarkus.stork.*.service-discovery.type=static. The value-rules design must cover plain-jar providers selected by config values, not only quarkus-* extensions.
<!-- SECTION:NOTES:END -->
