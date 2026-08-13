---
id: TASK-19
title: >-
  Quarkus build-time EXTENSION form (M5): in-build analyzer that reads ArC bean
  index to close annotation-consumer FP
status: In Progress
assignee: []
created_date: '2026-08-12 14:29'
updated_date: '2026-08-13 12:12'
labels: []
dependencies: []
priority: low
type: feature
ordinal: 19000
---

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
DECISION 2026-08-12 (user): build BOTH architectures, as independent projects. This task = the extension form (Option B / M5). Coexists with the mojo (TASK-15, Option A) and shares the core (TASK-18). ARCHITECTURE (3 artifacts): (1) quarkus-extension-analyzer-core = the Analyzer (3-signal classification), report model, ignore fragments. Pure Java + Quarkus bootstrap API (stable). (2) quarkus-extension-analyzer-maven-plugin = the mojo shell (Option A, exists): resolves ApplicationModel itself (ChainedMavenWorkspaceReader/TASK-9), for CI sweep on any built app without touching its pom. (3) quarkus-extension-analyzer (Quarkus extension, THIS task) = a -deployment @BuildStep shell that gets ApplicationModel + ArC BeanContainer/BuildItem from augmentation, calls core. WHY: closes the annotation-consumer FP (hibernate-validator via @NotNull, scheduler via @Scheduled) the mojo cannot resolve without a curated table, because ArC already knows which extension processes which annotation. Also eliminates the TASK-9 reactor-resolution complexity. SCOPE: the -deployment @BuildStep wiring + ArC integration; reuses core. OUT OF SCOPE: core extraction (TASK-18), mojo (TASK-15). NOT STARTED: substantial re-architecture; needs core extracted first (TASK-18) and a design review. References: TASK-14 (superseded), TASK-18, docs/M4-QUARKIVERSE-EVAL.md (Option B).

UPDATE 2026-08-13: BUILD STEP WORKS END-TO-END. The extension form is now functional: the @BuildStep fires during Quarkus augmentation on rest-fights (Quarkus 3.38.1), receives ApplicationModel directly from the build context (no ChainedMavenWorkspaceReader), and produces the full classification report via the shared core Analyzer. Verified counts match the mojo. Packaging fixes: extension-descriptor goal on runtime module; deployment depends on runtime; runtime depends on quarkus-core+arc; @Produce(ArtifactResultBuildItem.class) for terminal observation; BootstrapConfig no-op; ExecutorService. WORKAROUND: quarkus-build-steps.list manually injected (extension-descriptor goal doesn't generate it without a Quarkus parent POM; fix: adopt quarkus-build-parent or a build-helper in a follow-up). REMAINING: (1) the build-steps.list generation automation, (2) the ArC bean-index fourth signal (annotation-consumer resolution), (3) unit tests for the extension shell.
<!-- SECTION:NOTES:END -->
