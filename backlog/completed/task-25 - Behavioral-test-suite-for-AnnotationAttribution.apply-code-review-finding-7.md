---
id: TASK-25
title: Behavioral test suite for AnnotationAttribution.apply (code-review finding 7)
status: Done
assignee: []
created_date: '2026-08-16 21:25'
updated_date: '2026-08-16 21:53'
labels: []
dependencies: []
modified_files:
  - >-
    extension-deployment/src/test/java/io/github/paoloantinori/qea/deployment/AnnotationAttributionBehaviorTest.java
  - >-
    extension-deployment/src/test/java/io/github/paoloantinori/qea/deployment/AnnotationAttributionTest.java
  - >-
    extension-deployment/src/main/java/io/github/paoloantinori/qea/deployment/AnnotationAttribution.java
  - docs/AUTONOMOUS-WORK-LOG.md
priority: high
type: bug
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Fixes /code-review high finding 7: the AnnotationAttribution rules engine shipped with zero behavioral coverage (the adjacent structural test never called apply() and never built an index with @Path or @RegisterRestClient), which is how two regressions from the skeptic fixes shipped undetected.

Approach: ASM-generated .class fixtures carrying the REAL framework FQCNs (jakarta.ws.rs.Path, org.eclipse.microprofile.rest.client.inject.RegisterRestClient, io.smallrye.mutiny.Uni, org.jboss.resteasy.reactive.RestResponse, ...) because annotationFamilyPresent probes exact names; generic returns emitted as descriptor + generic signature (descriptors cannot carry type arguments). Minimal anonymous ApplicationModel (13 abstract methods) + report factory with SUSPECT rows; apply() called end-to-end.

25 tests covering: REST-SERIALIZER rule (Pojo/String/void/Uni<Void>/Uni<Pojo>/Multi<Void>/Optional<Pojo>/RestResponse<Pojo>/RestResponse<String>), method-level @Path interfaces, inherited endpoints, @RegisterRestClient (positive + server-@Path negative), jakarta.ws.rs family, undeclared-extension guard, identity path, @NotNull probe, JsonWebToken exact-FQCN (positive + similarly-named negative), reactive-driver join (single/db-kind/no-db-kind), flipSuspects contract, summary recomputation.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 apply() esercitata end-to-end (index sintetico + ApplicationModel minimale + report con righe SUSPECT)
- [x] #2 Ogni ramo dell'unwrap (Uni/Multi/Optional/RestResponse, Uni<Void> vs Uni<Pojo>) coperto
- [x] #3 @RegisterRestClient, method-level @Path, metodi ereditati, JsonWebToken exact-FQCN coperti
- [x] #4 Join reattivo: singolo dichiarato, db-kind match, ambiguità senza db-kind
- [x] #5 Contratto flipSuspects: sharedReferencedJars azzerati, vocabularyEvidence preservato, note con evidence
- [x] #6 mvn clean install verde su tutto il reattore
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
25 behavioral tests green (plus 3 pre-existing structural). The suite immediately caught a REAL production bug on first run: the @RegisterRestClient probe looked up the phantom name org.eclipse.microprofile.restclient.inject.RegisterRestClient (restclient without the dot) while the real FQCN is org.eclipse.microprofile.rest.client.inject.RegisterRestClient (verified against microprofile-rest-client-api-4.0.jar), leaving the rule dead on arrival for the second time. Fixed the FQCN in the RULES table and the annotationFamilyPresent branch. Also corrected the stale flipSuspects comment that claimed vocabularyEvidence must not survive the flip while the code (correctly) preserves it; the test now pins the actual contract. Full reactor: mvn clean install BUILD SUCCESS, 123 tests (95 core + 28 deployment).

Post-review expansion (mutation-verified): /simplify applied (assertion helpers, static stubs/index, fixture merges, 4 multi-site FQCN constants closing the rule/probe desync class). /code-review-equivalent found 8 more phantom/gap issues; 20 additional tests added (45 behavioral total) pinning the flipSuspects non-suspect guard, the reactive leftover-suspect disambiguation, the Response/Uni<Response>/TemplateInstance exclusions, unwrap depth 2 (Uni<RestResponse<Pojo>>), CompletionStage/List, the classHierarchy interface walk, and 8 more family probes.

THIRD phantom-probe bug found by ground-truth jar verification: io.smallrye.faulttolerance.api.Async and .ApplyProfile exist in no artifact; replaced with the verified real annotations ApplyGuard/ApplyFaultTolerance (smallrye-fault-tolerance-api 6.10.1). Reverse mutation check: all three fixes' pinning tests kill their mutants.

Full reactor: mvn clean install BUILD SUCCESS, 143 tests (95 core + 48 deployment, 45 behavioral). FILE: rules deliberately untested (CWD-relative, TASK-24).
<!-- SECTION:FINAL_SUMMARY:END -->
