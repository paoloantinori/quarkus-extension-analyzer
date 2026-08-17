---
id: TASK-29
title: Upgrade quarkus.version (3.33.2.1 -> current) with relocation-coverage recheck
status: Done
assignee: []
created_date: '2026-08-17 08:13'
updated_date: '2026-08-17 08:37'
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
- [x] #1 quarkus.version portato all'ultima 3.x stabile (root pom) e reattore verde
- [x] #2 I sette pattern di relocation riverificati contro il contenuto io.quarkus effettivo del nuovo jar (l'IT ShadedJarRelocationIT deve restare verde e coprire eventuali nuovi package)
- [x] #3 Entrambe le forme rivaildate su almeno rest-heroes + un quickstart dopo il bump
- [x] #4 Le classi del bootstrap usate dal runner (LocalProject, MavenArtifactResolver, ApplicationModel API) non hanno breaking change: adattare se serve
- [x] #5 Baseline banco aggiornate sui docs con i numeri post-bump
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
quarkus.version 3.33.2.1 -> 3.38.2 (latest stable line; 3.39 is CR), user-confirmed after a classifier hold. Full reactor green incl. ShadedJarRelocationIT (the designed upgrade guard). Relocation coverage verified explicitly: 0 unrelocated io/quarkus entries, same seven packages. Value-rules GAs re-verified against the 3.38.2 BOM (all real, mariadb reactive still absent). Bench with aligned versions: mojo identical (2 suspects + 3 credits), extension identical to the recorded baseline (7/8/1/3). Review agent's completeness sweep: dependency tree fully converged at 3.38.2, shaded jar definitively from the new world, no stale pins in poms/README; two residues applied - AnalyzeMojo's stale "embedded 3.33" comment dropped, requires-quarkus-version floor raised [3.33,) -> [3.38,) (post-bump the 3.33 claim is unverified). Version citations refreshed in value-rules.txt/CLAUDE.md/spike; historical docs untouched. 158 tests green, mojo smoke-tested after the residue commit.
<!-- SECTION:FINAL_SUMMARY:END -->
