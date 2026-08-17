---
id: TASK-31
title: Re-establish the Apicurio Registry bench on a fresh clone
status: Done
assignee: []
created_date: '2026-08-17 08:15'
updated_date: '2026-08-17 08:59'
labels: []
dependencies: []
priority: medium
type: chore
ordinal: 27000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The third canonical bench (Apicurio Registry app module, the project's origin story) is unusable: the old /private/tmp/apicurio-registry workspace is damaged like super-heroes was (gutted .git, missing root pom). The recorded baselines (mojo 5 suspects -> extension 1, -80%) predate TASK-28 and the shade fix. Re-establish the bench on a fresh clone. Note: apicurio-registry is a large repo (~1GB clone) - a shallow single-branch clone mitigates; only the app/ module needs to build. Budget accordingly.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Clone fresco di apicurio-registry (shallow, solo il modulo app serve) in /private/tmp/apicurio-registry-fresh
- [x] #2 Il modulo app builda pristine (mvn package -DskipTests)
- [x] #3 Mojo + extension eseguiti sull'app; report confrontato con le vecchie baseline (mojo 5 suspects, extension 1) spiegando le differenze attese (regole ora vive nel mojo, @ConfigRoot fallback rivivo)
- [x] #4 Baseline aggiornata nei docs (EXTENSION-USAGE e work log)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Fresh shallow clone at /private/tmp/apicurio-registry-fresh; app module builds pristine (-Dmaven.test.skip=true; the first failure was only test COMPILATION, -DskipTests does not skip it). The mojo run surfaced a REAL false negative: quarkus-smallrye-jwt stayed suspect although the app injects JsonWebToken as Instance<JsonWebToken> (the probe matched the exact type only; the raw name of the parameterized field type is the Instance interface - the same parameterized-type blindness fixed earlier for Uni<T>). Fixed with a one-level exact-FQCN unwrap of the type argument; three behavioral pins (Instance<Jwt> credits, Instance<String> and Instance<JsonWebTokenWrapper> do not), mutation-verified. New baseline: mojo extension suspects 2 {apicurio-registry-config-index, quarkus-resteasy-client-jackson}; extension form 2 {analyzer row, resteasy-client-jackson}. The two forms agree on the only true suspect (client-jackson is CORRECTLY suspect: the app has no @RegisterRestClient clients). Old damaged-era -80% numbers retired with provenance. Review agent verified all five checks (including a shapes table for the unwrap: no false-positive shape reintroduced) with an independent mutation run; residues applied (orphaned javadoc moved, third pin). 161 tests green, Apicurio pom verified restored.
<!-- SECTION:FINAL_SUMMARY:END -->
