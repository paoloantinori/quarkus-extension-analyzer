---
id: TASK-28
title: >-
  Annotation-consumer signal in the mojo form (engine extracted to core, two
  thin shells)
status: Done
assignee: []
created_date: '2026-08-16 23:13'
updated_date: '2026-08-16 23:56'
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
- [x] #1 Il rules engine (RULES, probe, REST-SERIALIZER scanner, reactive join, flipSuspects) vive in core senza dipendenze quarkus-bootstrap: apply(report, IndexView, Set<String> declaredGas, Set<String> dbKindValues, Path projectRoot)
- [x] #2 Extension-deployment.AnnotationAttribution e' un adapter sottile (model -> declared GAs); suite comportamentale spostata in core contro il engine, adapter test in deployment
- [x] #3 Il mojo/shaded runner esegue il pass post-analyze: index riusato da BytecodeUsage, declared GAs dal model, db-kind da AppConfigReader, projectRoot dal MavenProject
- [x] #4 Bench: il mojo risolve ora le stesse FP annotation-consumer che risolve l'estensione (verifica su restclient quickstart e/o super-heroes-fresh)
- [x] #5 mvn clean install verde su tutto il reattore
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Engine extracted to core (AnnotationConsumerRules, no bootstrap deps, declared-GA set instead of ApplicationModel); extension is a thin adapter (pinned); mojo's IsolatedAnalyzerRunner runs the engine post-analyze with a main-classes index (shape-matched to the extension form's bean-index scope), model-derived declared set, config db-kinds, and the MavenProject basedir. Validation exposed a fifth phantom-class bug, the most severe of the session: the shade plugin's bare io.quarkus relocation pattern was prefix-matching the engine's DOMAIN string literals, silently killing every rule in the mojo form (and, per the review, also ConfigRootProbe's @ConfigRoot literal since TASK-20). Fixed with seven trailing-dot package relocations verified against the jar's content, pinned by a failsafe IT that reads the built jar (mutation-verified both directions). Bench: heroes 5->2, fights 2 (hibernate-validator credited), restclient 0 extension suspects. DoD: 3 review agents (correctness w/ disassembly, mutation-minded test quality, docs/API); all findings applied including 4 new pin suites and the docs refresh. 158 tests green, full reactor BUILD SUCCESS.
<!-- SECTION:FINAL_SUMMARY:END -->
