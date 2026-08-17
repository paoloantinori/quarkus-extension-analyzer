---
id: TASK-29
title: Upgrade quarkus.version (3.33.2.1 -> current) with relocation-coverage recheck
status: To Do
assignee: []
created_date: '2026-08-17 08:13'
labels: []
dependencies: []
priority: high
type: chore
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The root pom pins quarkus.version 3.33.2.1 while the bench apps run 3.38.x (and the API works cross-version today, proven by the benches). On a bump, the seven trailing-dot relocation patterns in shaded/pom.xml are a SNAPSHOT of the io.quarkus content that came with 3.33.2.1's resolver transitives: a new quarkus version could add a new io.quarkus.* package that stays unrelocated, silently reintroducing the TASK-20 LinkageError. ShadedJarRelocationIT catches it (no io/quarkus/ entries may escape), but the pattern list must then be extended deliberately. Also re-check ApplicationModel/bootstrap API compatibility for the isolated runner and the extension-deployment build step, and refresh the recorded bench baselines with post-bump numbers.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 quarkus.version portato all'ultima 3.x stabile (root pom) e reattore verde
- [ ] #2 I sette pattern di relocation riverificati contro il contenuto io.quarkus effettivo del nuovo jar (l'IT ShadedJarRelocationIT deve restare verde e coprire eventuali nuovi package)
- [ ] #3 Entrambe le forme rivaildate su almeno rest-heroes + un quickstart dopo il bump
- [ ] #4 Le classi del bootstrap usate dal runner (LocalProject, MavenArtifactResolver, ApplicationModel API) non hanno breaking change: adattare se serve
- [ ] #5 Baseline banco aggiornate sui docs con i numeri post-bump
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
