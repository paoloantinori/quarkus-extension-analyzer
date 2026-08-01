---
id: TASK-7
title: Per-driver db-kind value discrimination for shared config roots
status: In Progress
assignee: []
created_date: '2026-08-01 17:03'
updated_date: '2026-08-01 21:20'
labels: []
dependencies: []
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Generalize beyond db-kind: value-based extension activation. Two confirmed cases: quarkus-jdbc-* selected by quarkus.datasource.db-kind values (M1 known hard case), and quarkus-container-image-* selected by quarkus.container-image.builder values (second bench: container-image-docker false suspect). Design a small curated value-rules table (config key, value pattern -> extension ga) sanctioned by DESIGN.md, applied as part of signal 1. Validation: super-heroes rest-fights (container-image case) + an app with a genuinely dead jdbc driver.
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Design (Fable), value-based activation rules (three confirmed cases):
1. Curated rules resource in the plugin jar (e.g. src/main/resources/qea-value-rules.properties or yaml): entries of the form key-pattern + value -> artifact ga. Initial table: quarkus.datasource[.<name>].db-kind values (h2,postgresql,mysql,mariadb,mssql,oracle,db2,derby) -> io.quarkus:quarkus-jdbc-<kind>; quarkus.container-image.builder values (docker,podman,jib,buildpack,openshift) -> io.quarkus:quarkus-container-image-<value> (podman maps to quarkus-container-image-docker? verify against real Quarkus docs/artifacts before writing the table: every mapped artifact must exist in the BOM); quarkus.stork.<svc>.service-discovery.type=static -> io.smallrye.stork:stork-service-discovery-static-list (explicit alias entry; static-list is the artifact for type static, verify). Keys may appear under any %profile prefix.
2. Semantics: a matched rule marks the target artifact (extension OR plain jar, if present among the resolved dependencies) used-config with evidence 'selected by <key>=<value>'. Stronger than family inheritance.
3. Discrimination: when an artifact is covered by the rules table for a family whose selector key(s) exist in the config, and NO value selects it, the family-inheritance evidence for that artifact is SUPPRESSED: verdict falls back to whatever other signals say, with note 'family keys present but no selecting value matches (selector <key> = <values seen>)'. Artifacts with no rules-table coverage keep current behavior. This is the db-kind discrimination from DESIGN.md's known-hard-case, generalized.
4. Expected bench outcomes (orchestrator verifies): registry app UNCHANGED (all four JDBC drivers have named-datasource db-kind values selecting them); rest-fights: quarkus-container-image-docker flips suspect -> used-config IF its config actually sets builder=docker (verify what the app sets; if the builder value only comes from CI flags, the honest outcome is suspect stays and the case study notes value-selection can live outside application config: document whichever is true); stork static-list plain jar expected to flip.
5. Tests: rule parsing, profile-prefixed keys, positive selection, suppression case, alias mapping, no-rule-coverage unchanged. Text/JSON evidence rendering.
Execution delegated; Fable verifies both benches.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Third confirmed value-activation case from the second bench: io.smallrye.stork:stork-service-discovery-static-list (plain jar) selected by quarkus.stork.*.service-discovery.type=static. The value-rules design must cover plain-jar providers selected by config values, not only quarkus-* extensions.
<!-- SECTION:NOTES:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
