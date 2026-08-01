---
id: TASK-11
title: Evidence hint for referenced shared jars (hibernate-validator case)
status: To Do
assignee: []
created_date: '2026-08-01 17:21'
labels: []
dependencies: []
ordinal: 11000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Second bench: quarkus-hibernate-validator is a false suspect because jakarta.validation-api is reachable from two declared extensions, so the exclusivity rule refuses attribution even though project bytecode references it (8 classes use validation annotations). Keep the conservative verdict but add an evidence hint on the suspect row: 'project references shared jar(s) reachable from this extension: <ga> (also reachable from <others>)', so human triage has the signal without the tool overclaiming. Mirror case of the kubernetes-client fix.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
