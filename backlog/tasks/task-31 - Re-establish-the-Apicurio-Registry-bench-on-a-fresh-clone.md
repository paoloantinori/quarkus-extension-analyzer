---
id: TASK-31
title: Re-establish the Apicurio Registry bench on a fresh clone
status: To Do
assignee: []
created_date: '2026-08-17 08:15'
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
- [ ] #1 Clone fresco di apicurio-registry (shallow, solo il modulo app serve) in /private/tmp/apicurio-registry-fresh
- [ ] #2 Il modulo app builda pristine (mvn package -DskipTests)
- [ ] #3 Mojo + extension eseguiti sull'app; report confrontato con le vecchie baseline (mojo 5 suspects, extension 1) spiegando le differenze attese (regole ora vive nel mojo, @ConfigRoot fallback rivivo)
- [ ] #4 Baseline aggiornata nei docs (EXTENSION-USAGE e work log)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
