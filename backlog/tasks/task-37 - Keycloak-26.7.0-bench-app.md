---
id: TASK-37
title: >-
  Keycloak 26.7.0 as a bench app (flagship, 30k stars)
status: To Do
assignee: []
created_date: '2026-08-17 17:20'
updated_date: '2026-08-17 17:20'
labels: []
dependencies: []
modified_files: []
priority: medium
type: task
ordinal: 28000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Bench expansion on the flagship Quarkus distribution. Feasibility verified 2026-08-17: latest stable is 26.7.0 (2026-07-09); org.keycloak artifacts (keycloak-quarkus-server and the parent chain) are published on Central; the local JDK is 25 (Keycloak 26.x targets 21+, so it should build). Module map at that tag: quarkus/runtime is the runtime codebase with direct quarkus-* declarations (primary bench target), quarkus/server generates the server artifacts (fallback), quarkus/deployment is the extension's build-time code.

Plan:
1. Shallow clone --branch 26.7.0 into /private/tmp/keycloak-267 (large repo; background).
2. If any parent poms are missing from Central, mvn -N install at the repo root and quarkus/ (pom-only, fast).
3. Build quarkus/runtime: mvn -f quarkus/runtime/pom.xml package -Dmaven.test.skip=true.
4. Run the mojo on the module and capture the full report (suspects, credits, near-miss telemetry).
5. Triage: Keycloak declares 20+ quarkus extensions directly, so the row set is rich - check suspect plausibility, credit correctness, and any near-miss firing; every anomaly becomes a fix or a task.
6. If stable, promote to scripts/bench-snapshot.sh with an expected file and the pinned commit; otherwise document the obstacles in the work log.

Risks: JDK toolchain pinning in the Keycloak build (local is 25), parent-pom availability on Central, the runtime module being library-shaped rather than app-shaped (fallback to quarkus/server), build time 5-15 minutes.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Clone shallow a 26.7.0 in /private/tmp/keycloak-267
- [ ] #2 Modulo target (quarkus/runtime, fallback quarkus/server) builda con artefatti da Central; ostacoli documentati se bloccanti
- [ ] #3 Mojo eseguito sul modulo; report completo catturato (sospetti, crediti, near-miss)
- [ ] #4 Analisi: plausibilita' dei sospetti, crediti verificati, near-miss triaged (ogni anomalia = fix o task)
- [ ] #5 Se stabile: app aggiunta a bench-snapshot con file expected e commit pinnato; altrimenti ostacoli documentati nel work log
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
