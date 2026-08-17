---
id: TASK-35
title: 'Credit audit: ablate sample credited rows to verify no over-credit'
status: Done
assignee: []
created_date: '2026-08-17 09:13'
updated_date: '2026-08-17 13:52'
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
- [x] #1 Campione di righe accreditate selezionate dalle app banco (es. Apicurio: smallrye-scheduler, smallrye-jwt; super-heroes: config-yaml, rest-qute)
- [x] #2 Per ognuna: ablazione (rimozione dep) con l'oracolo forte del banco (build + test dell'app, non solo mvn package)
- [x] #3 Esito registrato: accredito confermato load-bearing, o falsa positiva trovata (con fix o rollback della regola)
- [x] #4 Tabella nel work log e aggiornamento del banco se serve
- [x] #5 Pom delle app banco ripristinati e verificati
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Ablated credited rows to verify the rules do not over-credit (the symmetric direction never tested before). Apicurio smallrye-jwt: ablation fails at augmentation (UnsatisfiedResolutionException DefaultJWTParser in AppAuthenticationMechanism). Apicurio quarkus-scheduler: ablation fails at compilation (io.quarkus.scheduler package missing; @Scheduled imported by GitOpsRegistryStorage and GitOpsValidationTaskManager). cache-quickstart rest-jackson (REST-SERIALIZER): the strong oracle both directions - ablated verify FAILS ('Response body doesn't match expectation', POJO endpoint without serializer), restored verify GREEN. Already-proven families (rest-qute, config-yaml, quarkus-rest) cited rather than repeated. Verdict: ZERO over-credits in the audited sample. Caveat recorded: Apicurio's own tests are not runnable on the shallow clone (test-compile fails on missing utils-tests artifacts), so its two ablations used augmentation/compilation failure as the oracle (conclusive for both). All bench poms restored byte-identical. DoD: no production code touched; the audit IS the empirical verification, with predictions stated before each run and both directions proven for the quickstart sample.
<!-- SECTION:FINAL_SUMMARY:END -->
