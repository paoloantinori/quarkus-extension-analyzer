---
id: TASK-24
title: >-
  FILE: annotation rule is CWD-relative: thread projectRoot through
  AnnotationAttribution.apply()
status: To Do
assignee: []
created_date: '2026-08-16 12:10'
labels: []
dependencies: []
priority: medium
type: bug
ordinal: 24000
---

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Skeptic finding 4 (2026-08-16 review): the FILE:application.yml rule in AnnotationAttribution.annotationFamilyPresent probes Path.of("src","main","resources",...) relative to the process CWD. In any multi-module reactor build the CWD is the reactor root, so the config-yaml rule probes the root's resources, not the module being augmented. Same root cause as the readAppConfig CWD bug fixed in TASK-21's session. FIX: thread the project root (already derived from the ApplicationModel in AnalyzerBuildStep) through apply() to annotationFamilyPresent for the FILE: branch. The infrastructure exists (AnalyzerBuildStep.firstExistingProjectRoot).
<!-- SECTION:NOTES:END -->
