---
id: TASK-26
title: >-
  Consolidate the duplicated project-root and Maven-layout path derivations in
  the extension shell
status: To Do
assignee: []
created_date: '2026-08-16 22:37'
labels: []
dependencies: []
priority: low
type: chore
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Out-of-scope findings from the TASK-24 DoD review (reuse angle R1+R2), to execute after TASK-24:

R1: AnalyzerBuildStep derives the project root in two pre-existing places: firstExistingProjectRoot(model) (model resolved paths -> root) and readAppConfig's inner loop (classesDirs -> root, a literal round-trip of the projectRoot -> classesDirs computation 10 lines earlier). Two encodings of the strip-/target/classes convention that can drift. Consolidation is not a pure refactor (when classesDirs is empty, readAppConfig falls back to CWD while the outer projectRoot may be non-empty), so it needs its own deliberate justification.

R2: the src/main/resources / target/classes layout idiom is duplicated across AnnotationAttribution.configFilePresent, AnalyzerBuildStep.readAppConfig (two spellings: resolve chains and flat string), and the behavioral test (8 repetitions). A tiny shared helper (e.g. resourcesFile(root, name) / classesFile(root, name)) plus a test-local helper would give the Maven-layout convention one definition, matching the codebase's own single-constant discipline for multi-site FQCNs.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Un unico punto definisce i path convenzionali src/main/resources e target/classes (helper condiviso o costanti)
- [ ] #2 AnnotationAttribution.configFilePresent e AnalyzerBuildStep.readAppConfig usano entrambi quell'helper
- [ ] #3 readAppConfig non rideriva il root invertendo classesDirs quando il root e' gia' disponibile al chiamante (o la divergenza e' documentata come intenzionale)
- [ ] #4 Suite completa verde (mvn clean install)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
