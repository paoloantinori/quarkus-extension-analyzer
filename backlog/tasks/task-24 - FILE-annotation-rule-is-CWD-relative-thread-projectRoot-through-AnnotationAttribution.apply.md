---
id: TASK-24
title: >-
  FILE: annotation rule is CWD-relative: thread projectRoot through
  AnnotationAttribution.apply()
status: Done
assignee: []
created_date: '2026-08-16 12:10'
updated_date: '2026-08-16 22:38'
labels: []
dependencies: []
modified_files:
  - >-
    extension-deployment/src/main/java/io/github/paoloantinori/qea/deployment/AnnotationAttribution.java
  - >-
    extension-deployment/src/main/java/io/github/paoloantinori/qea/deployment/AnalyzerBuildStep.java
  - >-
    extension-deployment/src/test/java/io/github/paoloantinori/qea/deployment/AnnotationAttributionBehaviorTest.java
priority: medium
type: bug
ordinal: 24000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Skeptic finding 4 (2026-08-16 review): the FILE:application.yml rule in AnnotationAttribution resolved src/main/resources and target/classes relative to the process CWD; in any multi-module reactor the CWD is the reactor root, so the config-yaml rule probed the root's resources, not the module being augmented. Same root cause as the readAppConfig CWD bug.

FIX implemented: apply() gains a 5th parameter (Path projectRoot, Path.of("") preserving the legacy CWD behavior for callers that cannot derive a root); AnalyzerBuildStep passes the root it already derives from the ApplicationModel; the FILE: probe is extracted into package-visible configFilePresent(prefix, projectRoot) dispatched before annotationFamilyPresent (whose contract stays index-only). Out-of-scope review findings filed as TASK-26.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 apply() accetta projectRoot e le regole FILE: risolvono src/main/resources e target/classes sotto il root passato, non il CWD
- [x] #2 AnalyzerBuildStep passa il root derivato dall'ApplicationModel (firstExistingProjectRoot)
- [x] #3 Probe FILE: estratto in configFilePresent package-visible, testato direttamente (semantica root-only, entrambe le location, entrambe le estensioni)
- [x] #4 Test end-to-end FILE: positivi (resources e target/classes) e negativi (root altro modulo, nessun file)
- [x] #5 mvn clean install verde su tutto il reattore
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Skeptic finding 4 (2026-08-16 review): the FILE:application.yml rule in AnnotationAttribution resolved src/main/resources and target/classes relative to the process CWD; in any multi-module reactor the CWD is the reactor root, so the config-yaml rule probed the root's resources, not the module being augmented. Same root cause as the readAppConfig CWD bug.

FIX implemented: apply() gains a 5th parameter (Path projectRoot, Path.of("") preserving the legacy CWD behavior for callers that cannot derive a root); AnalyzerBuildStep passes the root it already derives from the ApplicationModel; the FILE: probe is extracted into package-visible configFilePresent(prefix, projectRoot) dispatched before annotationFamilyPresent (whose contract stays index-only). Out-of-scope review findings filed as TASK-26.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented + DoD-reviewed (3 agents: reuse/altitude, simplification/efficiency, correctness with empirical simulation). Review findings fixed: the self-described CWD-regression pin test was VACUOUS (empirically demonstrated: reverting to CWD probing still passed it; an implementation probing root-OR-CWD would pass the whole suite) so the probe was extracted to package-visible configFilePresent and unit-pinned directly (passed-root-only semantics, both locations, both file spellings), and the end-to-end test reworded to claim only what it pins; redundant call-site comment deleted (javadoc is the single home); the nested-ternary root derivation collapsed into firstExistingProjectRoot (single definition of the Path.of("") fallback contract). Correctness reviewer verified Path.of("").resolve() equivalence, the single-module no-regression walk, and null-safety at all call sites (grep: one production caller). 51 behavioral + 3 structural tests green; full reactor BUILD SUCCESS (95 + 54).
<!-- SECTION:FINAL_SUMMARY:END -->
