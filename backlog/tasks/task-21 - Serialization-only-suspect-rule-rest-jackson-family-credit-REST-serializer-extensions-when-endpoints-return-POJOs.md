---
id: TASK-21
title: >-
  Serialization-only suspect rule (rest-jackson family): credit REST serializer
  extensions when endpoints return POJOs
status: Done
assignee: []
created_date: '2026-08-15 12:46'
updated_date: '2026-08-15 15:03'
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

DONE 2026-08-15. REST-SERIALIZER rule added to AnnotationAttribution: fires when a @Path class has a REST method returning a POJO (non-primitive, non-String/Void, non-HTTP-machinery), crediting quarkus-rest-jackson/quarkus-resteasy-jackson when declared+suspect. Verified both directions: fires on cache-quickstart (POJO endpoints, rest-jackson flipped to used); correctly SILENT on jwt-quickstart (String-only endpoints, stays suspect = genuinely removable). ALSO FIXED a real CWD bug found during regression: the extension build step resolved target/classes and config relative to process CWD, breaking under mvn -f from a foreign dir (used-config collapsed to 0, suspects 19). Now derives the project root from the ApplicationModel app-artifact resolved paths; verified identical results (9/10/2/3) via cd-based and -f-based invocations. 103 tests green.
<!-- SECTION:NOTES:END -->
