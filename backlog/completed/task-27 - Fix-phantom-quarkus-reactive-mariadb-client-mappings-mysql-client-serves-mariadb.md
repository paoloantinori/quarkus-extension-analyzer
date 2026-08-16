---
id: TASK-27
title: >-
  Fix phantom quarkus-reactive-mariadb-client mappings (mysql-client serves
  mariadb)
status: Done
assignee: []
created_date: '2026-08-16 23:06'
updated_date: '2026-08-16 23:06'
labels: []
dependencies: []
modified_files:
  - >-
    extension-deployment/src/main/java/io/github/paoloantinori/qea/deployment/AnnotationAttribution.java
  - >-
    extension-deployment/src/test/java/io/github/paoloantinori/qea/deployment/AnnotationAttributionBehaviorTest.java
  - >-
    core/src/main/resources/io/github/paoloantinori/qea/plugin/configroot/value-rules.txt
  - >-
    core/src/test/java/io/github/paoloantinori/qea/plugin/configroot/ValueRulesTest.java
priority: high
type: bug
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the systematic no-phantom-names sweep (work unit 23): quarkus-reactive-mariadb-client does not exist as an artifact (verified against Quarkus BOMs 3.33.2.1 and 3.37-3.39, plus the Quarkus docs and the still-open dedicated-extension request quarkusio/quarkus#55695). MariaDB reactive apps use quarkus-reactive-mysql-client with db-kind=mariadb, so the phantom mappings made real MariaDB reactive apps either stay SUSPECT (extension join: family mismatch) or get no value-rule credit (core).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Nessun riferimento al GA fantasma quarkus-reactive-mariadb-client in codice o risorse
- [x] #2 value-rules.txt: db-kind=mariadb accredita quarkus-reactive-mysql-client (riga pin-ata da test loadDefault)
- [x] #3 reactiveFamiliesOf: mysql-client serve {mysql, mariadb}, ordine deterministico, commenti cross-pointer ai due moduli
- [x] #4 Evidence tail corretta nel caso multi-datasource (piu' client selezionati)
- [x] #5 mvn clean install verde
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Fixed both execution forms: value-rules.txt maps db-kind=mariadb to quarkus-reactive-mysql-client (loadDefault test pins the line against phantom regression); reactiveFamilyOf became reactiveFamiliesOf returning ordered families with mysql-client serving {mysql, mariadb}. Review (adversarial agent, with empirical pre-fix test failure verification) findings applied: the core test now pins the production resource via loadDefault (the inline-rules test only pins the mechanism), the evidence tail no longer claims dead weight when multiple clients are each selected by their own db-kind (two-pass credits), family order is deterministic (List), cross-pointer comments link the two encodings of the domain fact, and the value-rules header pointer corrected (root pom, and the reactive set verified against BOM 3.33.2.1 specifically). 152 tests green (97 core + 55 deployment).
<!-- SECTION:FINAL_SUMMARY:END -->
