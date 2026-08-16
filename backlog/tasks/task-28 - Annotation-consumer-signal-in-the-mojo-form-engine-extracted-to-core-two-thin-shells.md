---
id: TASK-28
title: >-
  Annotation-consumer signal in the mojo form (engine extracted to core, two
  thin shells)
status: To Do
assignee: []
created_date: '2026-08-16 23:13'
labels: []
dependencies: []
priority: high
type: feature
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design (from the TASK-25/27 session architecture study): the mojo form's documented gap (annotation-consumer FPs unresolved, per EXTENSION-USAGE.md) is closable because core's BytecodeUsage already builds a full Jandex Index over the app classesDirs. Move the rules engine from extension-deployment into core as a dependency-free component (jandex + report model only, no quarkus-bootstrap), parameterized by (declaredExtensionGas, dbKindValues, projectRoot) instead of ApplicationModel. The extension's AnnotationAttribution becomes a thin adapter; the mojo's IsolatedAnalyzerRunner runs the same engine post-analyze with the index reused from the bytecode scan. One engine, two shells: no rule duplication (the desync lesson from the three phantom bugs).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Il rules engine (RULES, probe, REST-SERIALIZER scanner, reactive join, flipSuspects) vive in core senza dipendenze quarkus-bootstrap: apply(report, IndexView, Set<String> declaredGas, Set<String> dbKindValues, Path projectRoot)
- [ ] #2 Extension-deployment.AnnotationAttribution e' un adapter sottile (model -> declared GAs); suite comportamentale spostata in core contro il engine, adapter test in deployment
- [ ] #3 Il mojo/shaded runner esegue il pass post-analyze: index riusato da BytecodeUsage, declared GAs dal model, db-kind da AppConfigReader, projectRoot dal MavenProject
- [ ] #4 Bench: il mojo risolve ora le stesse FP annotation-consumer che risolve l'estensione (verifica su restclient quickstart e/o super-heroes-fresh)
- [ ] #5 mvn clean install verde su tutto il reattore
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
