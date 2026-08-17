---
id: TASK-35
title: 'Credit audit: ablate sample credited rows to verify no over-credit'
status: To Do
assignee: []
created_date: '2026-08-17 09:13'
labels: []
dependencies: []
priority: medium
type: task
ordinal: 28000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design from the Apicurio retrospective (user-approved 2026-08-17). The ablation bench verifies the SUSPECT direction (removal should break things -> it was a false positive). The symmetric direction has never been tested empirically: rows the rules now CREDIT as used should be truly load-bearing. A sample-based credit audit ablates credited rows with the strong oracle (the jwt-qs protocol: mvn verify incl. app tests, not just package) to confirm the new rules do not over-credit.

Candidates: Apicurio's newly credited smallrye-scheduler and smallrye-jwt (Instance<Jwt> fix), resteasy-jackson (REST-SERIALIZER); super-heroes' config-yaml (FILE:) and rest-qute. Note expectations: removing smallrye-jwt should break auth compilation/tests; removing scheduler removes @Scheduled jobs (tests may not cover - then runtime reasoning per the ablation-bench methodology); removing rest-qute breaks the native-method Templates (already ablation-proven for this family); config-yaml removal breaks config reading (already proven). Prioritize the NOT-yet-proven ones: scheduler and jwt on Apicurio.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Campione di righe accreditate selezionate dalle app banco (es. Apicurio: smallrye-scheduler, smallrye-jwt; super-heroes: config-yaml, rest-qute)
- [ ] #2 Per ognuna: ablazione (rimozione dep) con l'oracolo forte del banco (build + test dell'app, non solo mvn package)
- [ ] #3 Esito registrato: accredito confermato load-bearing, o falsa positiva trovata (con fix o rollback della regola)
- [ ] #4 Tabella nel work log e aggiornamento del banco se serve
- [ ] #5 Pom delle app banco ripristinati e verificati
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
