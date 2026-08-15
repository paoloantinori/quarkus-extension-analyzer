---
id: TASK-21
title: >-
  Serialization-only suspect rule (rest-jackson family): credit REST serializer
  extensions when endpoints return POJOs
status: To Do
assignee: []
created_date: '2026-08-15 12:46'
labels: []
dependencies: []
priority: medium
type: feature
ordinal: 21000
---

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Filed from the TASK-21 ablation bench (docs/ABLATION-BENCH.md). quarkus-rest-jackson (and siblings) are load-bearing FALSE POSITIVES: removing them passes mvn package but leaves the fast-jar with ZERO jackson artifacts (verified empirically), so every POJO-returning endpoint fails at runtime. SIGNAL IDEA: quarkus-rest used (jakarta.ws.rs rule or bytecode) AND a declared REST-serializer extension is suspect AND app resources have non-String/non-Response return types (bean-index endpoint return-type analysis) -> credit the serializer. Covers rest-jackson and resteasy-jackson families.
<!-- SECTION:NOTES:END -->
